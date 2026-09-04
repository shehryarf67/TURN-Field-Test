package com.turn.fieldtest.core

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BleSupportTest {
    @Test
    fun parsesIBeaconAndBuildsStableIdentifier() {
        val record = hex(
            "0201061aff4c000215" +
                "00112233445566778899aabbccddeeff" +
                "00010002c5",
        )

        val beacon = assertIs<IBeacon>(BleAdvertisementParser.parse(record))

        assertEquals("00112233-4455-6677-8899-aabbccddeeff", beacon.uuid)
        assertEquals(1, beacon.major)
        assertEquals(2, beacon.minor)
        assertEquals(-59, beacon.calibratedTxPowerDbm)
        assertEquals(
            "ibeacon:00112233-4455-6677-8899-aabbccddeeff:1:2",
            beacon.stableId,
        )
    }

    @Test
    fun parsesEddystoneUidAndBuildsStableIdentifier() {
        val record = hex(
            "0201061716aafe00e5" +
                "0102030405060708090a" +
                "0b0c0d0e0f10" +
                "0000",
        )

        val beacon = assertIs<EddystoneUid>(BleAdvertisementParser.parse(record))

        assertEquals("0102030405060708090a", beacon.namespaceHex)
        assertEquals("0b0c0d0e0f10", beacon.instanceHex)
        assertEquals(-27, beacon.calibratedTxPowerDbm)
        assertEquals(
            "eddystone-uid:0102030405060708090a:0b0c0d0e0f10",
            beacon.stableId,
        )
    }

    @Test
    fun futureBleDistanceUsesStableIdsAndMissingPenalty() {
        val distance = BleFingerprintDistance.calculate(
            liveByStableId = mapOf("Beacon-A" to -50.0),
            storedByStableId = mapOf("beacon-a" to -50.0, "beacon-b" to -60.0),
        )

        assertEquals(sqrt(800.0), distance, 1e-12)
    }

    @Test
    fun disabledFeatureNeverStartsFakeScannerOrEmitsData() {
        var emitted = 0
        val controller = BleScanController(
            mode = RadioDataMode.DEMO,
            config = BleFeatureConfig(),
            scanner = FakeBleScanner(listOf(BleObservation("b1", -60, 0L, simulated = true))),
        )

        val result = controller.start { emitted++ }

        assertIs<BleScanStartResult.NotStarted>(result)
        assertEquals(BLE_NOT_CONFIGURED_MESSAGE, result.reason)
        assertEquals(0, emitted)
    }

    @Test
    fun simulatedScannerIsRejectedInRealDeviceMode() {
        val controller = BleScanController(
            mode = RadioDataMode.REAL_DEVICE,
            config = BleFeatureConfig(enabled = true, registeredBeaconIds = setOf("b1")),
            scanner = FakeBleScanner(emptyList()),
        )

        val result = controller.start { error("must not emit") }

        assertIs<BleScanStartResult.NotStarted>(result)
        assertTrue(result.reason.contains("forbidden"))
    }

    @Test
    fun demoScannerEmitsOnlyRegisteredSimulatedBeacons() {
        val received = mutableListOf<BleObservation>()
        val controller = BleScanController(
            mode = RadioDataMode.DEMO,
            config = BleFeatureConfig(enabled = true, registeredBeaconIds = setOf("b1")),
            scanner = FakeBleScanner(
                listOf(
                    BleObservation("b1", -60, 1L, simulated = false),
                    BleObservation("b2", -70, 2L, simulated = false),
                ),
            ),
        )

        assertIs<BleScanStartResult.Started>(controller.start(received::add))
        assertEquals(1, received.size)
        assertEquals("b1", received.single().stableBeaconId)
        assertTrue(received.single().simulated)
    }

    private fun hex(value: String): ByteArray {
        require(value.length % 2 == 0)
        return ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }
}
