package com.turn.fieldtest.core

import kotlin.math.sqrt

sealed class ParsedBleBeacon {
    abstract val stableId: String
    abstract val calibratedTxPowerDbm: Int
}

data class IBeacon(
    val uuid: String,
    val major: Int,
    val minor: Int,
    override val calibratedTxPowerDbm: Int,
) : ParsedBleBeacon() {
    override val stableId: String = "ibeacon:${uuid.lowercase()}:$major:$minor"
}

data class EddystoneUid(
    val namespaceHex: String,
    val instanceHex: String,
    override val calibratedTxPowerDbm: Int,
) : ParsedBleBeacon() {
    override val stableId: String =
        "eddystone-uid:${namespaceHex.lowercase()}:${instanceHex.lowercase()}"
}

/** Parses raw BLE scan-record bytes; Android ScanRecord conversion stays in the platform layer. */
object BleAdvertisementParser {
    private val iBeaconPrefix = byteArrayOf(0x4c, 0x00, 0x02, 0x15)
    private val eddystoneUidPrefix = byteArrayOf(0xaa.toByte(), 0xfe.toByte(), 0x00)

    fun parse(scanRecord: ByteArray): ParsedBleBeacon? =
        parseIBeacon(scanRecord) ?: parseEddystoneUid(scanRecord)

    fun parseIBeacon(scanRecord: ByteArray): IBeacon? {
        val prefixIndex = scanRecord.indexOfSequence(iBeaconPrefix)
        if (prefixIndex < 0 || prefixIndex + 25 > scanRecord.size) return null
        val uuidBytes = scanRecord.copyOfRange(prefixIndex + 4, prefixIndex + 20)
        val hex = uuidBytes.toHex()
        val uuid = buildString(36) {
            append(hex.substring(0, 8)).append('-')
            append(hex.substring(8, 12)).append('-')
            append(hex.substring(12, 16)).append('-')
            append(hex.substring(16, 20)).append('-')
            append(hex.substring(20, 32))
        }
        val major = scanRecord.readUnsignedShortBigEndian(prefixIndex + 20)
        val minor = scanRecord.readUnsignedShortBigEndian(prefixIndex + 22)
        val txPower = scanRecord[prefixIndex + 24].toInt()
        return IBeacon(uuid, major, minor, txPower)
    }

    fun parseEddystoneUid(scanRecord: ByteArray): EddystoneUid? {
        val prefixIndex = scanRecord.indexOfSequence(eddystoneUidPrefix)
        // UUID + frame type + Tx + 10-byte namespace + 6-byte instance.
        if (prefixIndex < 0 || prefixIndex + 20 > scanRecord.size) return null
        val txPower = scanRecord[prefixIndex + 3].toInt()
        val namespace = scanRecord.copyOfRange(prefixIndex + 4, prefixIndex + 14).toHex()
        val instance = scanRecord.copyOfRange(prefixIndex + 14, prefixIndex + 20).toHex()
        return EddystoneUid(namespace, instance, txPower)
    }

    private fun ByteArray.indexOfSequence(sequence: ByteArray): Int {
        if (sequence.isEmpty() || size < sequence.size) return -1
        for (start in 0..size - sequence.size) {
            var matches = true
            sequence.indices.forEach { offset ->
                if (this[start + offset] != sequence[offset]) matches = false
            }
            if (matches) return start
        }
        return -1
    }

    private fun ByteArray.readUnsignedShortBigEndian(index: Int): Int =
        ((this[index].toInt() and 0xff) shl 8) or (this[index + 1].toInt() and 0xff)

    private fun ByteArray.toHex(): String = joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
}

data class BleObservation(
    val stableBeaconId: String,
    val rssiDbm: Int,
    val timestampMillis: Long,
    val simulated: Boolean,
)

data class AggregatedBleFingerprint(
    val stableBeaconId: String,
    val medianRssiDbm: Double,
    val meanRssiDbm: Double,
    val standardDeviationDb: Double,
    val observationCount: Int,
    val detectionRate: Double,
)

object BleFingerprintDistance {
    fun calculate(
        liveByStableId: Map<String, Double>,
        storedByStableId: Map<String, Double>,
        missingRssiDbm: Double = -100.0,
    ): Double {
        val live = liveByStableId.mapKeys { it.key.trim().lowercase() }
        val stored = storedByStableId.mapKeys { it.key.trim().lowercase() }
        val union = live.keys + stored.keys
        if (union.isEmpty()) return Double.POSITIVE_INFINITY
        return sqrt(
            union.sumOf { id ->
                ((live[id] ?: missingRssiDbm) - (stored[id] ?: missingRssiDbm)).squared()
            } / union.size,
        )
    }
}

enum class RadioDataMode {
    DEMO,
    REAL_DEVICE,
}

data class BleFeatureConfig(
    val enabled: Boolean = false,
    val registeredBeaconIds: Set<String> = emptySet(),
)

sealed class BleScanStartResult {
    data class Started(val simulated: Boolean) : BleScanStartResult()
    data class NotStarted(val reason: String) : BleScanStartResult()
}

/** Port implemented by BluetoothLeScanner on a phone and by [FakeBleScanner] in demo/tests. */
interface BluetoothLeScannerPort {
    val isSimulated: Boolean
    fun startScan(onObservation: (BleObservation) -> Unit): BleScanStartResult
    fun stopScan()
}

class DisabledBleScanner : BluetoothLeScannerPort {
    override val isSimulated: Boolean = false
    override fun startScan(onObservation: (BleObservation) -> Unit): BleScanStartResult =
        BleScanStartResult.NotStarted(BLE_NOT_CONFIGURED_MESSAGE)

    override fun stopScan() = Unit
}

class FakeBleScanner(private val observations: List<BleObservation>) : BluetoothLeScannerPort {
    override val isSimulated: Boolean = true
    private var running = false

    override fun startScan(onObservation: (BleObservation) -> Unit): BleScanStartResult {
        running = true
        observations.forEach { observation ->
            if (running) onObservation(observation.copy(simulated = true))
        }
        return BleScanStartResult.Started(simulated = true)
    }

    override fun stopScan() {
        running = false
    }
}

/** Ensures BLE is opt-in, registered, and never simulated in real-device mode. */
class BleScanController(
    private val mode: RadioDataMode,
    private val config: BleFeatureConfig,
    private val scanner: BluetoothLeScannerPort,
) {
    fun start(onObservation: (BleObservation) -> Unit): BleScanStartResult {
        if (!config.enabled || config.registeredBeaconIds.isEmpty()) {
            return BleScanStartResult.NotStarted(BLE_NOT_CONFIGURED_MESSAGE)
        }
        if (mode == RadioDataMode.REAL_DEVICE && scanner.isSimulated) {
            return BleScanStartResult.NotStarted("Simulated BLE is forbidden in real-device mode")
        }
        return scanner.startScan { observation ->
            val id = observation.stableBeaconId.trim().lowercase()
            if (id in config.registeredBeaconIds.map { it.trim().lowercase() }.toSet()) {
                onObservation(observation)
            }
        }
    }

    fun stop() = scanner.stopScan()
}

const val BLE_NOT_CONFIGURED_MESSAGE: String = "BLE not configured — Wi-Fi + PDR active"
