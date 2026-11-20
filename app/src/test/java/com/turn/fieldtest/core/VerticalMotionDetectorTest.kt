package com.turn.fieldtest.core

import com.turn.fieldtest.core.replay.PressureTrace
import com.turn.fieldtest.core.replay.SyntheticPressure
import com.turn.fieldtest.core.replay.nanos
import com.turn.fieldtest.core.replay.replayPressure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VerticalMotionDetectorTest {
    private val config = FloorDetectionConfig()
    private val seeds = 1L..5L

    /** Latest acceptable emission after the climb: the rate window empties, settling holds, plus 1 s, plus 2 s of EMA lag and rate noise. */
    private val settleBudget = nanos(config.vertical.rateWindowSeconds + config.vertical.settleSeconds + 3.0)

    private fun events(trace: PressureTrace) = replayPressure(trace, config).events

    @Test
    fun oneFloorOfStairsUpGivesExactlyOneStairsEventSoonAfterSettling() {
        for (seed in seeds) {
            val trace = SyntheticPressure(seed).walk(10.0).stairs(1).mark("top").walk(20.0).build()
            val event = events(trace).single()
            assertEquals(VerticalDirection.UP, event.direction, "seed $seed")
            assertEquals(setOf(1), event.floorCandidates, "seed $seed")
            assertEquals(VerticalKindHint.STAIRS, event.kind, "seed $seed")
            assertEquals(3.5, event.heightDeltaMetres, 0.4, "seed $seed")
            assertTrue(event.endNanos > trace.mark("top") && event.endNanos <= trace.mark("top") + settleBudget,
                "seed $seed: emitted ${(event.endNanos - trace.mark("top")) / 1e9} s after the top")
        }
    }

    @Test
    fun theDetectorReportsAStairsSegmentWhileMoving() {
        val trace = SyntheticPressure(7).walk(10.0).stairs(1).mark("top").walk(20.0).build()
        val tracker = BarometerTracker(config.barometer, sensorPresent = true)
        val detector = VerticalMotionDetector(config.vertical)
        for (sample in trace.samples) {
            val state = tracker.add(sample.timestampNanos, sample.hpa) ?: continue
            detector.onBarometer(state, trace.stepsInWindow(state.timestampNanos, config.vertical.rateWindowSeconds))
            if (sample.timestampNanos == trace.mark("top")) {
                assertTrue(detector.moving)
                assertEquals(VerticalKindHint.STAIRS, detector.movingKind)
            }
        }
        assertFalse(detector.moving)
        assertNull(detector.movingKind)
    }

    @Test
    fun twoFloorsOfStairsGiveTwo() {
        for (seed in seeds) {
            val trace = SyntheticPressure(seed).walk(10.0).stairs(2).walk(20.0).build()
            val event = events(trace).single()
            assertEquals(VerticalDirection.UP, event.direction)
            assertEquals(setOf(2), event.floorCandidates, "seed $seed")
            assertEquals(VerticalKindHint.STAIRS, event.kind)
        }
    }

    @Test
    fun aStepFreeLiftDownTwoFloorsIsALift() {
        for (seed in seeds) {
            val trace = SyntheticPressure(seed).walk(8.0).stand(5.0).lift(-2).stand(6.0).walk(10.0).build()
            val event = events(trace).single()
            assertEquals(VerticalDirection.DOWN, event.direction, "seed $seed")
            assertEquals(setOf(2), event.floorCandidates, "seed $seed")
            assertEquals(VerticalKindHint.LIFT, event.kind, "seed $seed")
            assertTrue(event.heightDeltaMetres < -6.0)
        }
    }

    @Test
    fun noEventForDoorsHvacMezzaninesDriftOrReversals() {
        for (seed in seeds) {
            val scenarios = mapOf(
                "door pulse" to SyntheticPressure(seed).walk(10.0).pulse(0.3, 3.0).walk(20.0),
                "short door pulse" to SyntheticPressure(seed).walk(10.0).pulse(-0.3, 0.6).walk(20.0),
                "HVAC step" to SyntheticPressure(seed).walk(10.0).pressureStep(0.15).walk(60.0),
                "large HVAC step" to SyntheticPressure(seed).walk(10.0).pressureStep(-0.35).walk(60.0),
                "mezzanine" to SyntheticPressure(seed).walk(10.0).climb(1.75, 7.0).walk(30.0),
                "half ramp back" to SyntheticPressure(seed).walk(10.0).climb(1.75, 6.0).climb(-1.75, 6.0).walk(20.0),
                "full floor back" to SyntheticPressure(seed).walk(10.0).stairs(1).stairs(-1).walk(20.0),
            )
            for ((name, builder) in scenarios) assertEquals(emptyList(), events(builder.build()), "$name, seed $seed")
        }
    }

    @Test
    fun noEventInHalfAnHourOfNoiseAndDrift() {
        for ((seed, hpaPerHour) in listOf(1L to 1.5, 2L to -1.5)) {
            val trace = SyntheticPressure(seed).drift(hpaPerHour).walk(1800.0).build()
            assertEquals(emptyList(), events(trace), "seed $seed")
        }
    }

    @Test
    fun oneAndAHalfFloorsIsAmbiguous() {
        for (seed in seeds) {
            val trace = SyntheticPressure(seed).walk(10.0).climb(5.25, 18.0).walk(20.0).build()
            assertEquals(setOf(1, 2), events(trace).single().floorCandidates, "seed $seed")
        }
    }

    @Test
    fun aSegmentLongerThanTheMaximumEmitsNothing() {
        val trace = SyntheticPressure(5).walk(10.0).climb(-30.0, 200.0, stepHz = 0.0).walk(20.0).build()
        assertEquals(emptyList(), events(trace))
    }
}
