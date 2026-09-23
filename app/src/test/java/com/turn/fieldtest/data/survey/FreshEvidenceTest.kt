package com.turn.fieldtest.data.survey

import com.turn.fieldtest.data.local.SurveySessionEntity
import com.turn.fieldtest.platform.wifi.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FreshEvidenceTest {
    private val session = SurveySessionEntity("s", "v", "f", "rp", 1.0, 1.0, "test", "test", "35", "1", dataMode = "REAL_DEVICE", startedAtEpochMillis = 10000)
    private val fresh = WifiAccessPoint("aa", null, -50, 2412, 1, 999000, 100)
    private fun batch(aps: List<WifiAccessPoint>) = WifiScanBatch(12000, 10000, true, true, WifiFreshness.FRESH, aps, 100, true)
    @Test fun mixedFreshAndCachedApsRetainAllRawRowsButOnlyFreshEvidence() {
        val result = WifiSurveyCaptureFactory().create(session, 0, batch(listOf(fresh, fresh.copy(bssid = "bb", ageMillisAtReceipt = 20000))))
        assertTrue(result.snapshot.isFresh)
        assertEquals(2, result.observations.size)
        assertEquals(listOf("aa"), result.observations.filter { it.isFresh }.map { it.bssid })
    }
    @Test fun repeatedIndividualTimestampCannotCountAgain() {
        val result = WifiSurveyCaptureFactory().create(session, 1, batch(listOf(fresh)), previousTimestamps = mapOf("aa" to fresh.scanTimestampMicros))
        assertFalse(result.snapshot.isFresh)
        assertFalse(result.observations.single().isFresh)
    }
    @Test fun evidenceFromBeforeStandingAtThePointDoesNotCount() {
        val result = WifiSurveyCaptureFactory().create(session, 0, batch(listOf(fresh.copy(ageMillisAtReceipt = 3000))))
        assertFalse(result.snapshot.isFresh)
    }
    @Test fun emptyFreshBroadcastDoesNotCompleteSurveyProgress() {
        assertFalse(WifiSurveyCaptureFactory().create(session, 0, batch(emptyList())).snapshot.isFresh)
    }
    @Test fun duplicateBssidPrefersNewMeasurementOverStrongerCachedMeasurement() {
        val old = fresh.copy(rssiDbm = -30, scanTimestampMicros = 100, ageMillisAtReceipt = 3000)
        val result = WifiSurveyCaptureFactory().create(session, 0, batch(listOf(old, fresh)))
        assertTrue(result.snapshot.isFresh)
        assertTrue(result.observations.single().isFresh)
        assertEquals(fresh.rssiDbm, result.observations.single().rssiDbm)
    }
}
