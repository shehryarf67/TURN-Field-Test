package com.turn.fieldtest.platform.wifi

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LiveWifiEvidenceGateTest {
    private fun ap(id: String, timestamp: Long, rssi: Int = -60) =
        WifiAccessPoint(id, null, rssi, 2412, 1, timestamp, 1)
    private fun batch(vararg aps: WifiAccessPoint) = WifiScanBatch(
        500_000, 20_000, true, true, WifiFreshness.FRESH, aps.toList(), 1, true)

    @Test fun refreshRejectsRecentMeasurementsFromBeforeTheButtonPress() {
        val gate = LiveWifiEvidenceGate()
        gate.reset(1_000_000)
        gate.requireMeasurementsAfter(15_000_000)
        val accepted = gate.consume(batch(ap("old-hallway", 14_000_000), ap("current-end", 16_000_000)))
        assertEquals(listOf("current-end"), accepted.map { it.bssid })
        assertTrue(gate.consume(batch(ap("current-end", 16_000_000))).isEmpty())
    }

    @Test fun rejectedBroadcastsDoNotConsumeNewEvidenceAndDuplicatesUseNewestRow() {
        val gate = LiveWifiEvidenceGate()
        val scan = batch(ap("AA", 18_000_000, -40), ap("aa", 19_000_000, -65))
        assertTrue(gate.consume(scan.copy(resultsUpdated = false)).isEmpty())
        assertTrue(gate.consume(scan.copy(simulated = true)).isEmpty())
        assertEquals(-65, gate.consume(scan).single().rssiDbm)
    }

    @Test fun sessionStartStaleAndFutureRowsCannotCorrect() {
        val gate = LiveWifiEvidenceGate()
        gate.reset(12_000_000)
        assertTrue(gate.consume(batch(ap("prior-session", 11_000_000), ap("future", 21_000_000),
            ap("stale", 13_000_000).copy(ageMillisAtReceipt = 16_000))).isEmpty())
    }
}
