package com.turn.fieldtest.data.survey

import com.turn.fieldtest.data.local.SurveySessionEntity
import com.turn.fieldtest.platform.wifi.WifiAccessPoint
import com.turn.fieldtest.platform.wifi.WifiFreshness
import com.turn.fieldtest.platform.wifi.WifiScanBatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WifiSurveyCaptureTest {
    @Test
    fun cachedBatchIsStoredButCannotBecomeIndependentFingerprintEvidence() {
        var next = 0
        val factory = WifiSurveyCaptureFactory(EntityIdGenerator { "id-${next++}" })
        val capture = factory.create(
            session(),
            scanSequence = 1,
            batch = batch(WifiFreshness.CACHED_OR_STALE, resultsUpdated = false, -42),
        )

        assertTrue(capture.snapshot.isCached)
        assertFalse(capture.snapshot.isFresh)
        assertTrue(capture.observations.all { !it.isFresh })
    }

    @Test
    fun aggregationUsesOneReadingPerFreshSnapshotAndCorrectDetectionRate() {
        var next = 0
        val factory = WifiSurveyCaptureFactory(EntityIdGenerator { "id-${next++}" })
        val session = session()
        val captures = listOf(
            factory.create(session, 0, batch(WifiFreshness.FRESH, true, -50)),
            factory.create(session, 1, batch(WifiFreshness.FRESH, true, -70)),
            factory.create(session, 2, batch(WifiFreshness.FRESH, true, null)),
            factory.create(session, 3, batch(WifiFreshness.CACHED_OR_STALE, false, -10)),
        )
        val aggregate = WifiEntityFingerprintAggregator.aggregate(
            session = session,
            snapshots = captures.map { it.snapshot },
            observations = captures.flatMap { it.observations },
            calculatedAtEpochMillis = 5_000,
        ).single()

        assertEquals(-60.0, aggregate.medianRssiDbm)
        assertEquals(-60.0, aggregate.meanRssiDbm)
        assertEquals(2, aggregate.observationCount)
        assertEquals(3, aggregate.totalFreshSnapshotCount)
        assertEquals(2.0 / 3.0, aggregate.detectionRate, 1e-9)
    }

    private fun session() = SurveySessionEntity(
        id = "survey",
        venueId = "venue",
        floorId = "floor",
        referencePointId = "rp",
        knownXMetres = 1.0,
        knownYMetres = 2.0,
        deviceManufacturer = "test",
        deviceModel = "test",
        androidVersion = "test",
        appVersion = "test",
        dataMode = "DEMO",
        startedAtEpochMillis = 1,
    )

    private fun batch(
        freshness: WifiFreshness,
        resultsUpdated: Boolean,
        rssi: Int?,
    ) = WifiScanBatch(
        receivedAtEpochMillis = 2_000,
        receivedAtElapsedMillis = 1_000,
        requestAccepted = true,
        resultsUpdated = resultsUpdated,
        freshness = freshness,
        accessPoints = rssi?.let {
            listOf(WifiAccessPoint("AA:BB", "lab", it, 2_412, 1, 900_000, 100))
        }.orEmpty(),
        newestResultAgeMillis = 100,
        scanThrottlingEnabled = true,
        simulated = true,
    )
}
