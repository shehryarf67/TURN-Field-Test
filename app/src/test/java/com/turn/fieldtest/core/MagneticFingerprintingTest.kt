package com.turn.fieldtest.core

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.*

class MagneticFingerprintingTest {
    private val corridor = MapFloor("F", listOf(MetricPolygon(listOf(
        MetricPoint(0.0, 0.0), MetricPoint(40.0, 0.0), MetricPoint(40.0, 3.0), MetricPoint(0.0, 3.0)))))
    private val map = MetricMap(mapOf("F" to corridor))

    /** Synthetic indoor anomaly along the corridor: Earth's field bent by periodic steel columns. */
    private fun field(x: Double) = MagneticFeature(45.0 + 9.0 * sin(x / 1.6) + 4.0 * sin(x / 0.7 + 1.0), 30.0 + 6.0 * cos(x / 2.1))

    private fun surveyed(): MagneticFingerprintMap {
        val walk = (0 until 54).map { i -> MagneticObservation(field(1.0 + 38.0 * (i + 0.5) / 54), 4, true) }
        val result = MagneticPathSurvey.build(corridor, MetricPoint(1.0, 1.5), MetricPoint(39.0, 1.5), walk)
        val accepted = assertIs<MagneticWalkResult.Accepted>(result)
        return MagneticFingerprintMap("F").apply { accepted.samples.forEach { (p, f) -> add(p, f) } }
    }

    @Test fun featuresDoNotDependOnHowThePhoneIsHeld() {
        val worldField = doubleArrayOf(0.0, 22.0, -41.0); val worldGravity = doubleArrayOf(0.0, 0.0, -9.81)
        fun rotate(v: DoubleArray, a: Double, b: Double): DoubleArray { // yaw a, then pitch b
            val x1 = v[0] * cos(a) - v[1] * sin(a); val y1 = v[0] * sin(a) + v[1] * cos(a)
            return doubleArrayOf(x1, y1 * cos(b) - v[2] * sin(b), y1 * sin(b) + v[2] * cos(b))
        }
        val flat = assertNotNull(MagneticFeature.from(worldField, worldGravity))
        listOf(0.4 to 0.0, 2.1 to 0.9, -1.3 to 1.4).forEach { (a, b) ->
            val tilted = assertNotNull(MagneticFeature.from(rotate(worldField, a, b), rotate(worldGravity, a, b)))
            assertEquals(flat.magnitude, tilted.magnitude, 1e-9)
            assertEquals(flat.vertical, tilted.vertical, 1e-9)
        }
        assertEquals(sqrt(22.0 * 22.0 + 41.0 * 41.0), flat.magnitude, 1e-9)
        assertNull(MagneticFeature.from(worldField, doubleArrayOf(0.0, 0.0, 0.5)), "no gravity estimate, no feature")
    }

    @Test fun delayedStepsGetTheReadingsTakenBeforeThemAndCalibrationJumpsBreakContinuity() {
        val window = MagneticStepWindow()
        (1..10).forEach { window.record(it * 100_000_000L, MagneticFeature(40.0 + it, 30.0)) }
        val first = assertNotNull(window.takeForStep(500_000_000L))
        assertEquals(43.0, first.feature.magnitude, 1e-9) // mean of 41..45
        assertFalse(first.continuous, "the first step has nothing to be continuous with")
        val second = assertNotNull(window.takeForStep(1_000_000_000L))
        assertEquals(48.0, second.feature.magnitude, 1e-9)
        assertTrue(second.continuous)
        window.markDiscontinuity(1_050_000_000L)
        window.record(1_100_000_000L, MagneticFeature(60.0, 30.0))
        assertFalse(assertNotNull(window.takeForStep(1_200_000_000L)).continuous)
        assertNull(window.takeForStep(1_300_000_000L), "no readings since the last step")
    }

    @Test fun mapInterpolatesNearbyCellsWithExtraUncertaintyAndHasNothingFarAway() {
        val m = MagneticFingerprintMap("F")
        m.add(MetricPoint(1.0, 1.0), MagneticFeature(40.0, 20.0))
        val own = assertNotNull(m.expectationAt(MetricPoint(1.1, 1.1)))
        val near = assertNotNull(m.expectationAt(MetricPoint(1.7, 1.1)))
        assertEquals(40.0, near.feature.magnitude, 1e-9)
        assertTrue(near.varianceMagnitude > own.varianceMagnitude)
        assertNull(m.expectationAt(MetricPoint(5.0, 1.0)))
    }

    @Test fun implausibleWalksAreRejectedWithAReason() {
        val obs = MagneticObservation(MagneticFeature(45.0, 30.0), 3, true)
        val a = MetricPoint(1.0, 1.5); val b = MetricPoint(9.0, 1.5)
        assertIs<MagneticWalkResult.Rejected>(MagneticPathSurvey.build(corridor, a, b, List(2) { obs }))
        assertTrue(assertIs<MagneticWalkResult.Rejected>(MagneticPathSurvey.build(corridor, a, b, List(40) { obs })).reason.contains("per step"))
        assertTrue(assertIs<MagneticWalkResult.Rejected>(MagneticPathSurvey.build(corridor, a, b, List(11) { null })).reason.contains("missing"))
        val outside = MetricPoint(9.0, 6.0)
        assertTrue(assertIs<MagneticWalkResult.Rejected>(MagneticPathSurvey.build(corridor, a, outside, List(11) { obs })).reason.contains("walkable"))
        val spiked = List(11) { if (it < 5) MagneticObservation(MagneticFeature(150.0, 30.0), 3, true) else obs }
        assertTrue(assertIs<MagneticWalkResult.Rejected>(MagneticPathSurvey.build(corridor, a, b, spiked)).reason.contains("implausible"))
    }

    /** Walks from x=6 to x=26 with an uncertain start; returns the final along-corridor error. */
    private fun walk(magnetic: Boolean, phoneOffset: Double = 0.0, spikes: Set<Int> = emptySet()): Double {
        val mags = mapOf("F" to surveyed())
        val filter = ParticleFilter(map, ParticleFilterConfig(particleCount = 500), seed = 3L)
        // Wi-Fi-grade start: right corridor, but 2.5 m standard deviation along it.
        filter.initialize(AbsoluteFix(MetricPoint(8.0, 1.5), "F", AbsoluteFixSource.WIFI), positionStdMetres = 2.5)
        var truth = 6.0; var previous: MagneticObservation? = null
        repeat(28) { step ->
            truth += 0.7
            assertTrue(filter.predictStep(0.7, 0.0).accepted)
            val f = field(truth)
            val reading = if (step in spikes) MagneticFeature(f.magnitude + 35.0, f.vertical) else MagneticFeature(f.magnitude + phoneOffset, f.vertical + phoneOffset)
            val obs = MagneticObservation(reading, 4, continuous = step > 0)
            if (magnetic) filter.correctWithMagnetic(mags, obs, previous)
            previous = obs
        }
        return kotlin.math.abs(assertNotNull(filter.summary()).position.x - truth)
    }

    @Test fun magneticEvidenceResolvesAlongCorridorUncertaintyThatPdrCannot() {
        val pdrOnly = walk(magnetic = false)
        val withMagnetic = walk(magnetic = true)
        assertTrue(pdrOnly > 1.5, "PDR keeps the start error, was %.2f m".format(pdrOnly))
        assertTrue(withMagnetic < 1.0, "magnetic matching should localise along the corridor, was %.2f m".format(withMagnetic))
    }

    @Test fun anotherPhonesConstantOffsetStillLocalises() {
        val error = walk(magnetic = true, phoneOffset = 5.0)
        assertTrue(error < 1.5, "step-to-step changes cancel a constant device offset, error %.2f m".format(error))
    }

    @Test fun briefInterferenceDoesNotDragTheEstimate() {
        val error = walk(magnetic = true, spikes = setOf(20, 21, 22))
        assertTrue(error < 1.5, "a passing disturbance must be absorbed by the outlier model, error %.2f m".format(error))
    }

    @Test fun unmappedAreasAndDisturbedReadingsAreSkippedExplicitly() {
        val filter = ParticleFilter(map, ParticleFilterConfig(particleCount = 200), seed = 1L)
        filter.initialize(AbsoluteFix(MetricPoint(20.0, 1.5), "F", AbsoluteFixSource.MANUAL), positionStdMetres = 0.3)
        val ok = MagneticObservation(MagneticFeature(45.0, 30.0), 3, true)
        assertEquals(MagneticCorrectionKind.IGNORED_NO_MAP, filter.correctWithMagnetic(emptyMap(), ok, null).kind)
        val partial = MagneticFingerprintMap("F").apply { add(MetricPoint(2.0, 1.5), MagneticFeature(45.0, 30.0)) }
        assertEquals(MagneticCorrectionKind.IGNORED_UNMAPPED_AREA, filter.correctWithMagnetic(mapOf("F" to partial), ok, null).kind)
        val spike = MagneticObservation(MagneticFeature(160.0, 30.0), 3, true)
        assertEquals(MagneticCorrectionKind.IGNORED_DISTURBED, filter.correctWithMagnetic(mapOf("F" to surveyed()), spike, null).kind)
    }
}
