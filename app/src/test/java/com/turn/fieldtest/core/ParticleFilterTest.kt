package com.turn.fieldtest.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ParticleFilterTest {
    @Test
    fun seededInitializationIsDeterministic() {
        val config = quietConfig(initialPositionStdMetres = 1.0)
        val first = ParticleFilter(wideMap(), config, seed = 42L)
        val second = ParticleFilter(wideMap(), config, seed = 42L)

        first.initialize(AbsoluteFix(MetricPoint(10.0, 10.0), "F1", AbsoluteFixSource.MANUAL))
        second.initialize(AbsoluteFix(MetricPoint(10.0, 10.0), "F1", AbsoluteFixSource.MANUAL))

        assertEquals(first.particles, second.particles)
        assertEquals(first.summary(), second.summary())
    }

    @Test
    fun pdrPredictionPropagatesEveryParticle() {
        val filter = ParticleFilter(wideMap(), quietConfig(), seed = 1L)
        filter.initialize(AbsoluteFix(MetricPoint(1.0, 1.0), "F1", AbsoluteFixSource.MANUAL))

        val prediction = filter.predictStep(strideMetres = 1.0, measuredHeadingRadians = 0.0)

        assertTrue(prediction.accepted)
        assertEquals(0, prediction.rejectedParticleCount)
        assertEquals(2.0, prediction.summary!!.position.x, 1e-12)
        assertEquals(1.0, prediction.summary.position.y, 1e-12)
        assertEquals(FilterEventType.PDR_PREDICTION, filter.events.last().type)
    }

    @Test
    fun wallCrossingRejectsAllParticlesAndReportsFilterLost() {
        val floor = MapFloor(
            "F1",
            listOf(rectangle(0.0, 0.0, 10.0, 10.0)),
            listOf(WallSegment("wall", MetricPoint(2.0, 0.0), MetricPoint(2.0, 10.0))),
        )
        val filter = ParticleFilter(MetricMap(mapOf("F1" to floor)), quietConfig(), seed = 2L)
        filter.initialize(AbsoluteFix(MetricPoint(1.0, 5.0), "F1", AbsoluteFixSource.MANUAL))

        val result = filter.predictStep(2.0, 0.0)

        assertFalse(result.accepted)
        assertEquals(quietConfig().particleCount, result.rejectedParticleCount)
        assertEquals(
            quietConfig().particleCount,
            result.constraints[MovementConstraint.CROSSES_WALL],
        )
        assertTrue(filter.isLost)
        assertEquals(FilterEventType.FILTER_LOST, filter.events.last().type)
    }

    @Test
    fun outsideWalkableSpaceRejectsPrediction() {
        val map = MetricMap(mapOf("F1" to MapFloor("F1", listOf(rectangle(0.0, 0.0, 10.0, 10.0)))))
        val filter = ParticleFilter(map, quietConfig(), seed = 3L)
        filter.initialize(AbsoluteFix(MetricPoint(9.5, 5.0), "F1", AbsoluteFixSource.MANUAL))

        val result = filter.predictStep(1.0, 0.0)

        assertFalse(result.accepted)
        assertEquals(
            quietConfig().particleCount,
            result.constraints[MovementConstraint.END_OUTSIDE_WALKABLE_SPACE],
        )
    }

    @Test
    fun floorTransitionIsForbiddenAwayFromConfiguredTopology() {
        val filter = ParticleFilter(twoFloorMap(), quietConfig(), seed = 4L)
        filter.initialize(AbsoluteFix(MetricPoint(1.0, 1.0), "F1", AbsoluteFixSource.MANUAL))

        val result = filter.requestFloorTransition("F2")

        assertFalse(result.accepted)
        assertEquals("F1", filter.summary()!!.floorId)
        assertEquals(FilterEventType.FLOOR_TRANSITION_REJECTED, filter.events.last().type)
    }

    @Test
    fun floorTransitionSucceedsInsideConfiguredArea() {
        val filter = ParticleFilter(twoFloorMap(), quietConfig(), seed = 5L)
        filter.initialize(AbsoluteFix(MetricPoint(10.0, 10.0), "F1", AbsoluteFixSource.MANUAL))

        val result = filter.requestFloorTransition("F2")

        assertTrue(result.accepted)
        assertEquals("F2", result.summary!!.floorId)
        assertEquals(10.0, result.summary.position.x, 1e-12)
        assertEquals(10.0, result.summary.position.y, 1e-12)
        assertEquals(FilterEventType.FLOOR_TRANSITION, filter.events.last().type)
    }

    @Test
    fun wifiLikelihoodPullsCloudTowardFingerprint() {
        val config = quietConfig(
            particleCount = 800,
            initialPositionStdMetres = 3.0,
            correctionSpatialSigmaMetres = 1.0,
            resampleEffectiveSizeRatio = 0.99,
        )
        val filter = ParticleFilter(wideMap(), config, seed = 6L)
        filter.initialize(AbsoluteFix(MetricPoint(5.0, 5.0), "F1", AbsoluteFixSource.MANUAL))
        val match = exactWifiMatch(MetricPoint(10.0, 5.0), "F1")
        val distanceBefore = filter.summary()!!.position.distanceTo(match.estimatedPosition!!)

        val correction = filter.correctWithWifi(match, allowGlobalRelocalization = false)
        val distanceAfter = correction.summary!!.position.distanceTo(match.estimatedPosition)

        assertEquals(WifiCorrectionKind.APPLIED, correction.kind)
        assertTrue(distanceAfter < distanceBefore)
        assertTrue(correction.resampled)
        assertEquals(config.particleCount, filter.particles.size)
        assertTrue(filter.particles.all { kotlin.math.abs(it.weight - 1.0 / config.particleCount) < 1e-12 })
    }

    @Test
    fun cachedWifiEvidenceCannotCorrectOrRelocalizeFilter() {
        val filter = ParticleFilter(wideMap(), quietConfig(), seed = 27L)
        val before = filter.initialize(
            AbsoluteFix(MetricPoint(2.0, 2.0), "F1", AbsoluteFixSource.MANUAL),
        )
        val cachedMatch = exactWifiMatch(MetricPoint(16.0, 16.0), "F1").copy(
            freshness = WifiScanFreshness.CACHED,
        )

        val result = filter.correctWithWifi(cachedMatch)

        assertEquals(WifiCorrectionKind.IGNORED_NOT_FRESH, result.kind)
        assertEquals(before.position, result.summary?.position)
        assertFalse(result.resampled)
    }

    @Test
    fun strongDistantWifiMatchTriggersExplicitGlobalRelocalization() {
        val config = quietConfig(
            globalRelocalizationDistanceMetres = 5.0,
            minimumRelocalizationStdMetres = 0.0,
            maximumRelocalizationStdMetres = 0.0,
        )
        val filter = ParticleFilter(wideMap(), config, seed = 7L)
        filter.initialize(AbsoluteFix(MetricPoint(1.0, 1.0), "F1", AbsoluteFixSource.MANUAL))

        val correction = filter.correctWithWifi(exactWifiMatch(MetricPoint(15.0, 15.0), "F1"))

        assertEquals(WifiCorrectionKind.GLOBAL_RELOCALIZATION, correction.kind)
        assertEquals(15.0, correction.summary!!.position.x, 1e-12)
        assertEquals(15.0, correction.summary.position.y, 1e-12)
        assertEquals(FilterEventType.GLOBAL_RELOCALIZED, filter.events.last().type)
    }

    @Test
    fun qrCorrectionCollapsesCloudAndRecoversLostFilter() {
        val floor = MapFloor(
            "F1",
            listOf(rectangle(0.0, 0.0, 10.0, 10.0)),
            listOf(WallSegment("wall", MetricPoint(2.0, 0.0), MetricPoint(2.0, 10.0))),
        )
        val filter = ParticleFilter(
            MetricMap(mapOf("F1" to floor)),
            quietConfig(qrPositionStdMetres = 0.0),
            seed = 8L,
        )
        filter.initialize(AbsoluteFix(MetricPoint(1.0, 5.0), "F1", AbsoluteFixSource.MANUAL))
        filter.predictStep(2.0, 0.0)
        assertTrue(filter.isLost)

        val summary = filter.correctWithQr("F1", MetricPoint(7.0, 7.0), initialHeadingOffsetRadians = 0.3)

        assertFalse(filter.isLost)
        assertEquals(7.0, summary.position.x, 1e-12)
        assertEquals(7.0, summary.position.y, 1e-12)
        assertTrue(filter.particles.all { it.headingOffsetRadians == 0.3 })
        assertEquals(FilterEventType.QR_CORRECTION, filter.events.last().type)
    }

    @Test
    fun resamplingPreservesCountAndNormalizesWeights() {
        val filter = ParticleFilter(
            wideMap(),
            quietConfig(particleCount = 75, initialPositionStdMetres = 1.0),
            seed = 9L,
        )
        filter.initialize(AbsoluteFix(MetricPoint(10.0, 10.0), "F1", AbsoluteFixSource.MANUAL))

        val summary = assertNotNull(filter.forceResample())

        assertEquals(75, filter.particles.size)
        assertEquals(1.0, filter.particles.sumOf { it.weight }, 1e-12)
        assertTrue(summary.confidence in 0.0..1.0)
        assertTrue(summary.floorConfidence in 0.0..1.0)
    }

    private fun exactWifiMatch(position: MetricPoint, floorId: String): WifiMatchResult =
        WeightedKnnWifiMatcher(WifiMatcherConfig(k = 1)).match(
            liveRssiByBssid = mapOf("aa" to -50.0),
            fingerprints = listOf(WifiFingerprint("wifi-fix", floorId, position, mapOf("aa" to -50.0))),
        )

    private fun quietConfig(
        particleCount: Int = 100,
        initialPositionStdMetres: Double = 0.0,
        qrPositionStdMetres: Double = 0.0,
        correctionSpatialSigmaMetres: Double = 3.0,
        resampleEffectiveSizeRatio: Double = 0.55,
        globalRelocalizationDistanceMetres: Double = 8.0,
        minimumRelocalizationStdMetres: Double = 0.0,
        maximumRelocalizationStdMetres: Double = 4.0,
    ) = ParticleFilterConfig(
        particleCount = particleCount,
        initialPositionStdMetres = initialPositionStdMetres,
        qrPositionStdMetres = qrPositionStdMetres,
        initialHeadingOffsetStdRadians = 0.0,
        initialStrideScaleStd = 0.0,
        movementStdMetres = 0.0,
        headingNoiseStdRadians = 0.0,
        headingOffsetRandomWalkStdRadians = 0.0,
        strideScaleRandomWalkStd = 0.0,
        correctionSpatialSigmaMetres = correctionSpatialSigmaMetres,
        resampleEffectiveSizeRatio = resampleEffectiveSizeRatio,
        globalRelocalizationDistanceMetres = globalRelocalizationDistanceMetres,
        minimumRelocalizationStdMetres = minimumRelocalizationStdMetres,
        maximumRelocalizationStdMetres = maximumRelocalizationStdMetres,
    )

    private fun wideMap(): MetricMap = MetricMap(
        mapOf("F1" to MapFloor("F1", listOf(rectangle(0.0, 0.0, 20.0, 20.0)))),
    )

    private fun twoFloorMap(): MetricMap {
        val transitionArea = rectangle(9.0, 9.0, 11.0, 11.0)
        return MetricMap(
            floors = mapOf(
                "F1" to MapFloor("F1", listOf(rectangle(0.0, 0.0, 20.0, 20.0))),
                "F2" to MapFloor("F2", listOf(rectangle(0.0, 0.0, 20.0, 20.0))),
            ),
            verticalTransitions = listOf(
                VerticalTransition("stairs", "F1", "F2", transitionArea, transitionArea),
            ),
        )
    }

    private fun rectangle(left: Double, bottom: Double, right: Double, top: Double): MetricPolygon =
        MetricPolygon(
            listOf(
                MetricPoint(left, bottom),
                MetricPoint(right, bottom),
                MetricPoint(right, top),
                MetricPoint(left, top),
            ),
        )
}
