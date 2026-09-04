package com.turn.fieldtest.core

import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PdrTest {
    @Test
    fun detectorIsPrimaryAndCounterOnlyValidatesTotals() {
        val processor = StepProcessor(
            availableSources = setOf(
                StepSource.STEP_DETECTOR,
                StepSource.STEP_COUNTER,
                StepSource.ACCELEROMETER_FALLBACK,
            ),
        )

        val baseline = processor.process(StepSignal(0L, StepSource.STEP_COUNTER, 100L))
        val counter = processor.process(StepSignal(1L, StepSource.STEP_COUNTER, 105L))
        val detector = processor.process(StepSignal(2L, StepSource.STEP_DETECTOR))
        val fallback = processor.process(StepSignal(1_000_000_000L, StepSource.ACCELEROMETER_FALLBACK))

        assertFalse(baseline.acceptedForMovement)
        assertEquals(StepRejectionReason.COUNTER_VALIDATION_ONLY, counter.reason)
        assertEquals(5L, counter.validatedCounterSteps)
        assertTrue(detector.acceptedForMovement)
        assertEquals(StepRejectionReason.NON_PRIMARY_SOURCE, fallback.reason)
    }

    @Test
    fun refractoryWindowPreventsDuplicateSteps() {
        val processor = StepProcessor(
            availableSources = setOf(StepSource.STEP_DETECTOR),
            refractoryPeriodNanos = 250_000_000L,
        )

        assertTrue(processor.process(StepSignal(1_000_000_000L, StepSource.STEP_DETECTOR)).acceptedForMovement)
        val duplicate = processor.process(StepSignal(1_200_000_000L, StepSource.STEP_DETECTOR))
        val next = processor.process(StepSignal(1_300_000_000L, StepSource.STEP_DETECTOR))

        assertFalse(duplicate.acceptedForMovement)
        assertEquals(StepRejectionReason.DUPLICATE_WITHIN_REFRACTORY_PERIOD, duplicate.reason)
        assertTrue(next.acceptedForMovement)
    }

    @Test
    fun strideCanBeInitializedFromHeightAndCalibratedByKnownDistance() {
        val heightBased = StrideModel.fromHeight(1.80)
        val calibrated = heightBased.calibrated(knownDistanceMetres = 15.0, acceptedSteps = 20)

        assertEquals(0.7434, heightBased.baseStrideMetres, 1e-12)
        assertEquals(0.75, calibrated.currentStrideMetres, 1e-12)
    }

    @Test
    fun calibrationScaleIsKeptWithinSafeSessionBounds() {
        val base = StrideModel(baseStrideMetres = 0.75)

        assertEquals(StrideModel.MIN_SCALE, base.calibrated(1.0, 10).sessionScale)
        assertEquals(StrideModel.MAX_SCALE, base.calibrated(20.0, 10).sessionScale)
    }

    @Test
    fun acceptedStepsFollowMathematicalHeadingConvention() {
        val tracker = PdrTracker(
            initialPosition = MetricPoint(0.0, 0.0),
            initialHeadingRadians = 0.0,
            stepProcessor = StepProcessor(
                setOf(StepSource.STEP_DETECTOR, StepSource.ACCELEROMETER_FALLBACK),
                refractoryPeriodNanos = 0L,
            ),
            strideModel = StrideModel(baseStrideMetres = 1.0),
        )

        tracker.processStep(StepSignal(1L, StepSource.STEP_DETECTOR))
        tracker.applyRelativeHeadingChange(PI / 2.0)
        tracker.processStep(StepSignal(2L, StepSource.ACCELEROMETER_FALLBACK))
        tracker.processStep(StepSignal(3L, StepSource.STEP_DETECTOR))

        // The fallback event is ignored because the detector is the sole primary source.
        assertEquals(2, tracker.state.acceptedStepCount)
        assertEquals(1.0, tracker.state.position.x, 1e-12)
        assertEquals(1.0, tracker.state.position.y, 1e-12)
        assertEquals(2.0, tracker.state.estimatedDistanceMetres, 1e-12)
    }

    @Test
    fun angleNormalizationIsStableAcrossMultipleTurns() {
        assertEquals(PI / 2.0, normalizeRadians(PI * 4.0 + PI / 2.0), 1e-12)
        assertEquals(-PI / 2.0, normalizeRadians(-PI * 4.0 - PI / 2.0), 1e-12)
    }
}
