package com.turn.fieldtest.core

import kotlin.math.PI
import kotlin.test.*

class StepHeadingHistoryTest {
    @Test fun delayedStepUsesItsMeasuredHeadingInsteadOfLatestTurn() {
        val history = StepHeadingHistory().apply {
            reset(1_000_000_000)
            record(1_500_000_000, 0.0)
            record(2_300_000_000, PI / 2)
        }
        assertFalse(MapHeading.hasFreshHeading(1_600_000_000, 2_300_000_000, 1_000_000_000))
        val heading = assertNotNull(history.headingForStep(1_600_000_000))
        val pdr = PdrTracker(MetricPoint(5.0, 5.0), 0.0,
            StepProcessor(setOf(StepSource.STEP_DETECTOR)), StrideModel(0.7))
        val floor = MapFloor("f", listOf(MetricPolygon(listOf(MetricPoint(0.0, 0.0),
            MetricPoint(20.0, 0.0), MetricPoint(20.0, 20.0), MetricPoint(0.0, 20.0)))))
        val filter = ParticleFilter(MetricMap(mapOf("f" to floor)), ParticleFilterConfig(
            particleCount = 100, initialHeadingOffsetStdRadians = 0.0, initialStrideScaleStd = 0.0,
            headingNoiseStdRadians = 0.0, movementStdMetres = 0.0,
            headingOffsetRandomWalkStdRadians = 0.0, strideScaleRandomWalkStd = 0.0))
        filter.initialize(AbsoluteFix(pdr.state.position, "f", AbsoluteFixSource.MANUAL), 0.0)
        val step = pdr.processStep(StepSignal(1_600_000_000, StepSource.STEP_DETECTOR), heading)
        val result = filter.predictStep(step.strideMetres, heading)
        assertTrue(step.decision.acceptedForMovement)
        assertEquals(5.7, result.summary!!.position.x, 1e-9)
        assertEquals(5.0, result.summary.position.y, 1e-9)
        assertEquals(PI / 2, history.headingForStep(2_400_000_000))
    }

    @Test fun missingStaleAndPreAlignmentEvidenceCannotCreateMotion() {
        val history = StepHeadingHistory().apply { reset(1_000_000_000) }
        assertNull(history.headingForStep(1_100_000_000))
        history.record(1_200_000_000, 0.0)
        assertNull(history.headingForStep(900_000_000))
        assertNull(history.headingForStep(3_300_000_000))
        assertNull(history.headingForStep(900_000_001))
    }

    @Test fun nearSimultaneousCallbackOrderHasBoundedTolerance() {
        val history = StepHeadingHistory().apply { record(1_100_000_000, 0.2) }
        assertEquals(0.2, history.headingForStep(1_000_000_000))
        assertNull(history.headingForStep(800_000_000))
    }

    @Test fun realignmentClearsPriorHeadingsAndOutOfOrderFramesCannotReplaceNewOnes() {
        val history = StepHeadingHistory().apply { record(1_000_000_000, 0.0); reset(2_000_000_000) }
        history.record(1_500_000_000, 0.0)
        assertNull(history.headingForStep(2_000_000_000))
        history.record(2_100_000_000, PI)
        history.record(2_050_000_000, 0.0)
        assertEquals(PI, history.headingForStep(2_150_000_000))
    }

    @Test fun longStreamHasBoundedHistoryAndKeepsRecentDelayedSteps() {
        val history = StepHeadingHistory()
        repeat(10_000) { history.record(it * 20_000_000L, 0.1) }
        assertEquals(0.1, history.headingForStep(199_000_000_000))
        assertNull(history.headingForStep(1_000_000_000))
    }
}
