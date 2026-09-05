package com.turn.fieldtest.core

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WifiPositioningTest {
    @Test
    fun unrelatedWeakAccessPointsCannotInitializeLocation() {
        val result = WeightedKnnWifiMatcher().match(
            mapOf("unrelated-ap" to -95.0),
            listOf(WifiFingerprint("rp", "G", MetricPoint(1.0, 1.0), mapOf("survey-ap" to -95.0))),
        )
        assertTrue(result.unlikeDatabase)
        assertEquals(null, result.estimatedPosition)
        assertTrue(result.neighbours.isEmpty())
    }

    @Test
    fun aggregationUsesOnlyDistinctFreshSnapshots() {
        val snapshots = listOf(
            snapshot(
                "fresh-1",
                WifiScanFreshness.FRESH,
                WifiObservation("AA:BB", -50, 1L),
                WifiObservation("aa:bb", -55, 1L),
            ),
            snapshot("cached", WifiScanFreshness.CACHED, WifiObservation("aa:bb", -10, 2L)),
            snapshot("fresh-2", WifiScanFreshness.FRESH, WifiObservation("AA:BB", -70, 3L)),
            snapshot("fresh-empty", WifiScanFreshness.FRESH),
        )

        val aggregate = WifiFingerprintAggregator.aggregate(snapshots).single()

        assertEquals("aa:bb", aggregate.bssid)
        assertEquals(-60.0, aggregate.medianRssiDbm, 1e-12)
        assertEquals(-60.0, aggregate.meanRssiDbm, 1e-12)
        assertEquals(10.0, aggregate.standardDeviationDb, 1e-12)
        assertEquals(-70, aggregate.minimumRssiDbm)
        assertEquals(-50, aggregate.maximumRssiDbm)
        assertEquals(2, aggregate.observationCount)
        assertEquals(2.0 / 3.0, aggregate.detectionRate, 1e-12)
    }

    @Test
    fun unionDistancePenalizesMissingBssid() {
        val result = WifiVectorDistance.calculate(
            liveRssiByBssid = mapOf("a" to -50.0),
            storedRssiByBssid = mapOf("a" to -50.0, "b" to -60.0),
            missingRssiDbm = -100.0,
        )

        assertEquals(sqrt(800.0), result.distance, 1e-12)
        assertEquals(2, result.comparedBssidCount)
    }

    @Test
    fun deviceOffsetNormalizationRemovesUniformPhoneBias() {
        val raw = WifiVectorDistance.calculate(
            liveRssiByBssid = mapOf("a" to -60.0, "b" to -70.0),
            storedRssiByBssid = mapOf("a" to -50.0, "b" to -60.0),
        )
        val normalized = WifiVectorDistance.calculate(
            liveRssiByBssid = mapOf("a" to -60.0, "b" to -70.0),
            storedRssiByBssid = mapOf("a" to -50.0, "b" to -60.0),
            normalization = WifiNormalization.DEVICE_OFFSET,
        )

        assertEquals(10.0, raw.distance, 1e-12)
        assertEquals(0.0, normalized.distance, 1e-12)
        assertEquals(10.0, normalized.appliedDeviceOffsetDb, 1e-12)
    }

    @Test
    fun exactMatchAvoidsDivisionByZeroAndExcludesNonExactNeighbours() {
        val matcher = WeightedKnnWifiMatcher(WifiMatcherConfig(k = 4))
        val result = matcher.match(
            liveRssiByBssid = mapOf("a" to -50.0),
            fingerprints = listOf(
                fingerprint("exact", "F1", 2.0, mapOf("a" to -50.0)),
                fingerprint("other", "F1", 8.0, mapOf("a" to -60.0)),
            ),
        )

        assertTrue(result.isExactMatch)
        assertEquals(1, result.neighbours.size)
        assertEquals(1.0, result.neighbours.single().weight, 1e-12)
        assertEquals(MetricPoint(2.0, 0.0), result.estimatedPosition)
        assertTrue(result.confidence.isFinite())
    }

    @Test
    fun weightedFloorVoteAndCoordinatesUseInverseDistance() {
        val matcher = WeightedKnnWifiMatcher(WifiMatcherConfig(k = 3))
        val result = matcher.match(
            liveRssiByBssid = mapOf("a" to -50.0),
            fingerprints = listOf(
                fingerprint("f1-near", "F1", 0.0, mapOf("a" to -49.0)),
                fingerprint("f1-far", "F1", 10.0, mapOf("a" to -52.0)),
                fingerprint("f2", "F2", 30.0, mapOf("a" to -51.0)),
            ),
        )

        assertEquals("F1", result.estimatedFloorId)
        assertEquals(1.5 / 2.5, result.floorAgreement, 1e-12)
        assertEquals(10.0 / 3.0, result.estimatedPosition!!.x, 1e-12)
        assertEquals(1.0, result.neighbours.sumOf { it.weight }, 1e-12)
    }

    @Test
    fun scanFreshnessDegradesConfidenceWithoutChangingEstimate() {
        val matcher = WeightedKnnWifiMatcher()
        val fingerprints = listOf(fingerprint("p", "F1", 1.0, mapOf("a" to -50.0)))

        val fresh = matcher.match(mapOf("a" to -50.0), fingerprints, WifiScanFreshness.FRESH)
        val cached = matcher.match(mapOf("a" to -50.0), fingerprints, WifiScanFreshness.CACHED)
        val stale = matcher.match(mapOf("a" to -50.0), fingerprints, WifiScanFreshness.STALE)

        assertEquals(fresh.estimatedPosition, cached.estimatedPosition)
        assertTrue(fresh.confidence > cached.confidence)
        assertTrue(cached.confidence > stale.confidence)
        assertTrue(fresh.uncertaintyRadiusMetres < stale.uncertaintyRadiusMetres)
    }

    @Test
    fun unlikeDatabaseIsReportedFromNearestMatchDistance() {
        val matcher = WeightedKnnWifiMatcher(
            WifiMatcherConfig(k = 1, unlikeDatabaseDistanceThreshold = 20.0),
        )
        val result = matcher.match(
            mapOf("a" to -20.0),
            listOf(fingerprint("p", "F1", 0.0, mapOf("a" to -90.0))),
        )

        assertTrue(result.unlikeDatabase)
        assertFalse(result.confidence == 1.0)
    }

    private fun snapshot(
        id: String,
        freshness: WifiScanFreshness,
        vararg observations: WifiObservation,
    ) = WifiScanSnapshot(id, 0L, freshness, observations.toList())

    private fun fingerprint(
        id: String,
        floor: String,
        x: Double,
        vector: Map<String, Double>,
    ) = WifiFingerprint(id, floor, MetricPoint(x, 0.0), vector)
}
