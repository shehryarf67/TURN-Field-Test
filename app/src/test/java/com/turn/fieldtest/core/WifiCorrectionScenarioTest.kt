package com.turn.fieldtest.core

import kotlin.math.log10
import kotlin.math.max
import kotlin.test.*

/**
 * End-to-end check of the correction loop on a synthetic 30 m corridor: planner-suggested survey points,
 * fingerprints from a log-distance radio model, a PDR stride that under-reads, and fresh Wi-Fi corrections.
 */
class WifiCorrectionScenarioTest {
    private val corridor = MapFloor("F", listOf(MetricPolygon(listOf(
        MetricPoint(0.0, 0.0), MetricPoint(30.0, 0.0), MetricPoint(30.0, 3.0), MetricPoint(0.0, 3.0)))))
    private val map = MetricMap(mapOf("F" to corridor))
    private val accessPoints = mapOf("ap:01" to MetricPoint(0.0, 0.0), "ap:02" to MetricPoint(10.0, 3.0),
        "ap:03" to MetricPoint(20.0, 0.0), "ap:04" to MetricPoint(30.0, 3.0), "ap:05" to MetricPoint(15.0, 9.0))

    private fun rssi(at: MetricPoint, ap: MetricPoint) = (-38.0 - 28.0 * log10(max(at.distanceTo(ap), 1.0))).coerceAtLeast(-99.0)
    private fun scan(at: MetricPoint, wobble: Double = 0.0) = accessPoints.mapValues { (_, ap) -> rssi(at, ap) + wobble }

    private fun surveyPoints(): List<MetricPoint> {
        val nodes = listOf(NavigationNode("a", "F", MetricPoint(0.5, 1.5)), NavigationNode("b", "F", MetricPoint(29.5, 1.5)))
        val edges = listOf(NavigationEdge("ab", "a", "b", 29.0))
        return SurveyPointPlanner.suggest(corridor, nodes, edges, emptyList(), spacingMetres = 3.0).map { it.position }
    }

    private fun fingerprints() = surveyPoints().mapIndexed { i, p -> WifiFingerprint("RP-$i", "F", p, scan(p)) }

    @Test fun plannerCoversTheCorridorEvenlyInsideWalkableSpace() {
        val points = surveyPoints()
        assertTrue(points.size in 10..11, "expected about one point every 3 m, got ${points.size}")
        assertTrue(points.all { corridor.isWalkable(it) })
        val xs = points.map { it.x }.sorted()
        assertTrue(xs.zipWithNext().all { (a, b) -> b - a in 2.0..3.5 }, "gaps: ${xs.zipWithNext().map { it.second - it.first }}")
    }

    @Test fun unknownHotspotsNoLongerMakeAGoodScanLookUnlikeTheDatabase() {
        val truth = MetricPoint(12.0, 1.5)
        // Six strong APs that were never surveyed (phones, a neighbour's router) plus the real ones.
        val live = scan(truth, wobble = 1.5) + (1..6).associate { "hotspot:$it" to -42.0 }
        val strict = WeightedKnnWifiMatcher(WifiMatcherConfig(minimumSharedBssids = 3))
            .match(live, fingerprints())
        val filtered = WeightedKnnWifiMatcher(WifiMatcherConfig(minimumSharedBssids = 3, ignoreUnknownLiveBssids = true))
            .match(live, fingerprints())
        assertTrue(strict.unlikeDatabase, "unknown APs alone push the distance to %.1f dB".format(strict.weightedMatchDistance))
        assertFalse(filtered.unlikeDatabase)
        assertFalse(filtered.spatiallyAmbiguous)
        assertTrue(filtered.confidence > strict.confidence)
        assertTrue(assertNotNull(filtered.estimatedPosition).distanceTo(truth) < 2.0)
    }

    @Test fun onlyUnknownApsGiveNoEstimateInsteadOfAWrongOne() {
        val matcher = WeightedKnnWifiMatcher(WifiMatcherConfig(minimumSharedBssids = 3, ignoreUnknownLiveBssids = true))
        val result = matcher.match(mapOf("hotspot:1" to -40.0, "hotspot:2" to -50.0), fingerprints())
        assertNull(result.estimatedPosition)
    }

    /** Walks 32 steps along the corridor; returns the final error and the error right after each Wi-Fi fix. */
    private fun walk(measuredStride: Double, correct: Boolean): Pair<Double, List<Double>> {
        val matcher = WeightedKnnWifiMatcher(WifiMatcherConfig(minimumSharedBssids = 3, ignoreUnknownLiveBssids = true))
        val db = fingerprints()
        val trueStride = 0.7
        val afterFix = mutableListOf<Double>()
        run {
            val filter = ParticleFilter(map, ParticleFilterConfig(particleCount = 400), seed = 7L)
            filter.initialize(AbsoluteFix(MetricPoint(3.0, 1.5), "F", AbsoluteFixSource.MANUAL), positionStdMetres = 0.5)
            var truth = MetricPoint(3.0, 1.5)
            repeat(32) { step ->
                truth = MetricPoint(truth.x + trueStride, 1.5)
                assertTrue(filter.predictStep(measuredStride, 0.0).accepted)
                if (correct && step % 6 == 5) {
                    val match = matcher.match(scan(truth, wobble = if (step % 12 == 5) 2.0 else -2.0) + mapOf("hotspot:1" to -45.0), db)
                    assertEquals(WifiCorrectionKind.APPLIED, filter.correctWithWifi(match, allowGlobalRelocalization = false).kind)
                    afterFix += assertNotNull(filter.summary()).position.distanceTo(truth)
                }
            }
            return assertNotNull(filter.summary()).position.distanceTo(truth) to afterFix
        }
    }

    @Test fun freshWifiCorrectionsKeepARealisticStrideErrorNearTheTruePosition() {
        // A 10 % short stride after calibration: drift is modest but grows; fixes must keep it under 2 m.
        val (drifted, _) = walk(measuredStride = 0.63, correct = false)
        val (corrected, afterFix) = walk(measuredStride = 0.63, correct = true)
        assertTrue(afterFix.all { it < 2.0 }, "error after each fix: ${afterFix.map { "%.2f".format(it) }}")
        assertTrue(corrected < drifted, "corrected %.2f m vs uncorrected %.2f m".format(corrected, drifted))
    }

    @Test fun freshWifiCorrectionsBoundEvenASevereStrideError() {
        // A 30 % short stride drifts without limit; fixes must hold the error to about one survey spacing.
        val (drifted, _) = walk(measuredStride = 0.5, correct = false)
        val (corrected, afterFix) = walk(measuredStride = 0.5, correct = true)
        assertTrue(drifted > 5.0, "uncorrected PDR should drift, was %.2f m".format(drifted))
        assertTrue(afterFix.all { it < 3.5 }, "error after each fix: ${afterFix.map { "%.2f".format(it) }}")
        assertTrue(corrected < drifted / 2, "corrected %.2f m vs uncorrected %.2f m".format(corrected, drifted))
    }

    @Test fun oneMisleadingFixCannotDragAWellLocalisedEstimate() {
        val matcher = WeightedKnnWifiMatcher(WifiMatcherConfig(minimumSharedBssids = 3, ignoreUnknownLiveBssids = true))
        val filter = ParticleFilter(map, ParticleFilterConfig(particleCount = 400), seed = 11L)
        filter.initialize(AbsoluteFix(MetricPoint(10.0, 1.5), "F", AbsoluteFixSource.MANUAL), positionStdMetres = 0.4)
        // A reflection makes the scan look like it was taken 5 m away (below the 8 m relocalisation distance).
        val misleading = matcher.match(scan(MetricPoint(15.0, 1.5)), fingerprints())
        filter.correctWithWifi(misleading, allowGlobalRelocalization = false)
        val moved = assertNotNull(filter.summary()).position.distanceTo(MetricPoint(10.0, 1.5))
        assertTrue(moved < 1.5, "a single fix moved the estimate %.2f m".format(moved))
    }
}
