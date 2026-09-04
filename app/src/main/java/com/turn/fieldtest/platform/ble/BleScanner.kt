package com.turn.fieldtest.platform.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.turn.fieldtest.platform.PlatformClock
import com.turn.fieldtest.platform.SystemPlatformClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.sqrt

enum class BleProtocol { IBEACON, EDDYSTONE_UID }

data class ParsedBeacon(
    val protocol: BleProtocol,
    val stableIdentifier: String,
    val txPowerDbm: Int?,
    val rawPayloadHex: String,
)

data class BleObservation(
    val beacon: ParsedBeacon,
    val rssiDbm: Int,
    val observedAtEpochMillis: Long,
    val simulated: Boolean,
)

data class BleScanConfiguration(
    val featureEnabled: Boolean = false,
    val registeredStableIdentifiers: Set<String> = emptySet(),
    val demoMode: Boolean = false,
)

enum class BleScannerStatus {
    DISABLED,
    NOT_CONFIGURED,
    PERMISSION_REQUIRED,
    BLUETOOTH_DISABLED,
    READY,
    SCANNING,
    SCAN_FAILED,
    STOPPED,
}

data class BleScannerState(
    val status: BleScannerStatus = BleScannerStatus.DISABLED,
    val message: String = BLE_NOT_CONFIGURED_MESSAGE,
    val lastFailureCode: Int? = null,
) {
    companion object {
        const val BLE_NOT_CONFIGURED_MESSAGE = "BLE not configured — Wi-Fi + PDR active"
    }
}

sealed interface BleStartResult {
    data object Started : BleStartResult
    data class NotStarted(val state: BleScannerState) : BleStartResult
}

interface BleScanner {
    val observations: SharedFlow<BleObservation>
    val state: StateFlow<BleScannerState>
    fun start(configuration: BleScanConfiguration): BleStartResult
    fun stop()
}

/**
 * Optional physical BLE adapter. It never requests permissions and never touches the platform
 * scanner until both the feature flag and at least one registered beacon are present.
 */
class AndroidBleScanner(
    context: Context,
    private val bluetoothManager: BluetoothManager? = context.applicationContext.getSystemService(BluetoothManager::class.java),
    private val clock: PlatformClock = SystemPlatformClock,
) : BleScanner {
    private val appContext = context.applicationContext
    private val mutableObservations = MutableSharedFlow<BleObservation>(extraBufferCapacity = 64)
    private val mutableState = MutableStateFlow(BleScannerState())
    private var activeIdentifiers: Set<String> = emptySet()
    private var scanning = false

    override val observations: SharedFlow<BleObservation> = mutableObservations.asSharedFlow()
    override val state: StateFlow<BleScannerState> = mutableState.asStateFlow()

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = handleResult(result)

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach(::handleResult)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            mutableState.value = BleScannerState(
                status = BleScannerStatus.SCAN_FAILED,
                message = "BLE scan failed with Android error $errorCode",
                lastFailureCode = errorCode,
            )
        }
    }

    @SuppressLint("MissingPermission")
    override fun start(configuration: BleScanConfiguration): BleStartResult {
        stop()
        if (!configuration.featureEnabled) return notStarted(
            BleScannerStatus.DISABLED,
            BleScannerState.BLE_NOT_CONFIGURED_MESSAGE,
        )
        if (configuration.registeredStableIdentifiers.isEmpty()) return notStarted(
            BleScannerStatus.NOT_CONFIGURED,
            BleScannerState.BLE_NOT_CONFIGURED_MESSAGE,
        )
        if (!hasScanPermission()) return notStarted(
            BleScannerStatus.PERMISSION_REQUIRED,
            "BLE permission required only because BLE has been enabled and configured",
        )
        val adapter = bluetoothManager?.adapter
        if (adapter == null || !adapter.isEnabled) return notStarted(
            BleScannerStatus.BLUETOOTH_DISABLED,
            "Bluetooth is disabled or unavailable",
        )
        val scanner = adapter.bluetoothLeScanner ?: return notStarted(
            BleScannerStatus.SCAN_FAILED,
            "Bluetooth LE scanner is unavailable",
        )

        activeIdentifiers = configuration.registeredStableIdentifiers.map { it.lowercase() }.toSet()
        return try {
            scanner.startScan(
                null,
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                callback,
            )
            scanning = true
            mutableState.value = BleScannerState(BleScannerStatus.SCANNING, "BLE correction experiment active")
            BleStartResult.Started
        } catch (_: SecurityException) {
            notStarted(BleScannerStatus.PERMISSION_REQUIRED, "Bluetooth scan permission was denied")
        } catch (_: RuntimeException) {
            notStarted(BleScannerStatus.SCAN_FAILED, "Bluetooth LE scanner failed to start")
        }
    }

    @SuppressLint("MissingPermission")
    override fun stop() {
        if (scanning && hasScanPermission()) {
            runCatching { bluetoothManager?.adapter?.bluetoothLeScanner?.stopScan(callback) }
        }
        scanning = false
        activeIdentifiers = emptySet()
        if (mutableState.value.status !in setOf(BleScannerStatus.DISABLED, BleScannerStatus.NOT_CONFIGURED)) {
            mutableState.value = BleScannerState(BleScannerStatus.STOPPED, "BLE scanner stopped")
        }
    }

    private fun notStarted(status: BleScannerStatus, message: String): BleStartResult.NotStarted {
        val newState = BleScannerState(status, message)
        mutableState.value = newState
        return BleStartResult.NotStarted(newState)
    }

    private fun hasScanPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_SCAN
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        return ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun handleResult(result: ScanResult) {
        val bytes = result.scanRecord?.bytes ?: return
        val beacon = BleAdvertisementParser.parse(bytes) ?: return
        if (beacon.stableIdentifier.lowercase() !in activeIdentifiers) return
        mutableObservations.tryEmit(
            BleObservation(
                beacon = beacon,
                rssiDbm = result.rssi,
                observedAtEpochMillis = clock.epochMillis(),
                simulated = false,
            ),
        )
    }
}

/** Demo scanner refuses to emit unless the caller explicitly declares DEMO mode. */
class FakeBleScanner(
    private val scope: CoroutineScope,
    private val trace: List<FakeBleObservation>,
    private val clock: PlatformClock = SystemPlatformClock,
) : BleScanner {
    private val mutableObservations = MutableSharedFlow<BleObservation>(extraBufferCapacity = 64)
    private val mutableState = MutableStateFlow(BleScannerState())
    private var job: Job? = null

    override val observations: SharedFlow<BleObservation> = mutableObservations.asSharedFlow()
    override val state: StateFlow<BleScannerState> = mutableState.asStateFlow()

    override fun start(configuration: BleScanConfiguration): BleStartResult {
        stop()
        if (!configuration.featureEnabled || configuration.registeredStableIdentifiers.isEmpty()) {
            val value = BleScannerState(BleScannerStatus.NOT_CONFIGURED, BleScannerState.BLE_NOT_CONFIGURED_MESSAGE)
            mutableState.value = value
            return BleStartResult.NotStarted(value)
        }
        if (!configuration.demoMode) {
            val value = BleScannerState(BleScannerStatus.DISABLED, "Fake BLE scanner is allowed only in DEMO / SIMULATED mode")
            mutableState.value = value
            return BleStartResult.NotStarted(value)
        }

        val allowed = configuration.registeredStableIdentifiers.map { it.lowercase() }.toSet()
        mutableState.value = BleScannerState(BleScannerStatus.SCANNING, "SIMULATED DATA — fake BLE trace")
        job = scope.launch {
            var priorOffset = 0L
            trace.sortedBy(FakeBleObservation::offsetMillis).forEach { item ->
                delay((item.offsetMillis - priorOffset).coerceAtLeast(0L))
                priorOffset = item.offsetMillis
                val parsed = BleAdvertisementParser.parse(item.advertisementBytes) ?: return@forEach
                if (parsed.stableIdentifier.lowercase() in allowed) {
                    mutableObservations.emit(BleObservation(parsed, item.rssiDbm, clock.epochMillis(), simulated = true))
                }
            }
        }
        return BleStartResult.Started
    }

    override fun stop() {
        job?.cancel()
        job = null
        if (mutableState.value.status == BleScannerStatus.SCANNING) {
            mutableState.value = BleScannerState(BleScannerStatus.STOPPED, "SIMULATED BLE trace stopped")
        }
    }
}

data class FakeBleObservation(
    val offsetMillis: Long,
    val advertisementBytes: ByteArray,
    val rssiDbm: Int,
)

object BleAdvertisementParser {
    fun parse(bytes: ByteArray): ParsedBeacon? = parseIBeacon(bytes) ?: parseEddystoneUid(bytes)

    private fun parseIBeacon(bytes: ByteArray): ParsedBeacon? {
        // Locate Apple manufacturer id (little endian), followed by iBeacon type and length.
        val start = findSequence(bytes, byteArrayOf(0x4c, 0x00, 0x02, 0x15)) ?: return null
        val uuidStart = start + 4
        if (uuidStart + 20 >= bytes.size) return null
        val uuidHex = bytes.copyOfRange(uuidStart, uuidStart + 16).toHex()
        val uuid = listOf(8, 4, 4, 4, 12).fold(Pair(0, mutableListOf<String>())) { (offset, parts), size ->
            parts += uuidHex.substring(offset, offset + size)
            offset + size to parts
        }.second.joinToString("-")
        val major = unsignedShort(bytes, uuidStart + 16)
        val minor = unsignedShort(bytes, uuidStart + 18)
        val txPower = bytes[uuidStart + 20].toInt()
        return ParsedBeacon(
            protocol = BleProtocol.IBEACON,
            stableIdentifier = "ibeacon:${uuid.lowercase()}:$major:$minor",
            txPowerDbm = txPower,
            rawPayloadHex = bytes.toHex(),
        )
    }

    private fun parseEddystoneUid(bytes: ByteArray): ParsedBeacon? {
        var index = 0
        while (index < bytes.size) {
            val length = bytes[index].toInt() and 0xff
            if (length == 0) break
            val endExclusive = index + length + 1
            if (endExclusive > bytes.size || index + 1 >= bytes.size) return null
            val type = bytes[index + 1].toInt() and 0xff
            if (type == 0x16 && index + 22 <= endExclusive) {
                val data = index + 2
                val isEddystone = (bytes[data].toInt() and 0xff) == 0xaa &&
                    (bytes[data + 1].toInt() and 0xff) == 0xfe
                val isUidFrame = (bytes[data + 2].toInt() and 0xff) == 0x00
                if (isEddystone && isUidFrame && data + 20 <= endExclusive) {
                    val txPower = bytes[data + 3].toInt()
                    val namespace = bytes.copyOfRange(data + 4, data + 14).toHex()
                    val instance = bytes.copyOfRange(data + 14, data + 20).toHex()
                    return ParsedBeacon(
                        protocol = BleProtocol.EDDYSTONE_UID,
                        stableIdentifier = "eddystone-uid:$namespace:$instance",
                        txPowerDbm = txPower,
                        rawPayloadHex = bytes.toHex(),
                    )
                }
            }
            index = endExclusive
        }
        return null
    }

    private fun findSequence(source: ByteArray, target: ByteArray): Int? {
        if (target.isEmpty() || source.size < target.size) return null
        for (start in 0..source.size - target.size) {
            if (target.indices.all { offset -> source[start + offset] == target[offset] }) return start
        }
        return null
    }

    private fun unsignedShort(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

data class BleFingerprintVector(val valuesByStableIdentifier: Map<String, Double>)

interface BleFingerprintDistance {
    fun distance(live: BleFingerprintVector, stored: BleFingerprintVector, missingRssiDbm: Double = -100.0): Double
}

object EuclideanBleFingerprintDistance : BleFingerprintDistance {
    override fun distance(live: BleFingerprintVector, stored: BleFingerprintVector, missingRssiDbm: Double): Double {
        val identifiers = live.valuesByStableIdentifier.keys + stored.valuesByStableIdentifier.keys
        if (identifiers.isEmpty()) return Double.POSITIVE_INFINITY
        val squared = identifiers.sumOf { id ->
            val delta = (live.valuesByStableIdentifier[id] ?: missingRssiDbm) -
                (stored.valuesByStableIdentifier[id] ?: missingRssiDbm)
            delta * delta
        }
        return sqrt(squared / identifiers.size)
    }
}

data class RadioPositionEvidence(
    val xMetres: Double,
    val yMetres: Double,
    val floorId: String,
    val confidence: Double,
    val source: String,
)

interface BleOnlyPositioning {
    fun estimate(vector: BleFingerprintVector): RadioPositionEvidence?
}

interface CombinedRadioFusion {
    fun combine(wifiEvidence: RadioPositionEvidence?, bleEvidence: RadioPositionEvidence?): RadioPositionEvidence?
}
