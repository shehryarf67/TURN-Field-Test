package com.turn.fieldtest.core

import com.turn.fieldtest.core.replay.PressureTrace
import com.turn.fieldtest.core.replay.SyntheticPressure
import com.turn.fieldtest.core.replay.nanos
import com.turn.fieldtest.core.replay.replayPressure
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VerticalMotionDetectorTest {
    private val config = FloorDetectionConfig()
    private val seeds = 1L..20L

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

    /** SENSOR_DELAY_NORMAL is only a hint: devices deliver anything from 5 to 25 Hz. */
    @Test
    fun theStartOfALiftRideIsNeverAbsorbedAsADoorStep() {
        for (sampleHz in listOf(5.0, 10.0, 25.0)) for (speed in listOf(1.0, 1.6, 2.5)) {
            for (seed in 1L..40L) {
                for (floors in listOf(1, -1, 2, -2)) {
                    val trace = SyntheticPressure(seed, sampleHz).walk(8.0).stand(5.0).lift(floors, speed).stand(6.0).walk(10.0).build()
                    val replay = replayPressure(trace, config)
                    val label = "$floors floors at $speed m/s, $sampleHz Hz, seed $seed"
                    assertTrue(replay.states.none { it.discontinuity }, "$label: absorbed as a discontinuity")
                    val event = replay.events.single()
                    assertEquals(if (floors > 0) VerticalDirection.UP else VerticalDirection.DOWN, event.direction, label)
                    assertEquals(setOf(abs(floors)), event.floorCandidates, label)
                }
            }
        }
    }

    /**
     * A door opening and closing is two sharp steps that cancel; at any sample rate neither may be handed back to the
     * height on its own (each judging window ends at the other step), so no floor event and no floor-sized height.
     */
    @Test
    fun shortDoorPulsesGiveNoEventAtAnySampleRate() {
        val limit = config.vertical.minimumFloorFraction * config.vertical.floorHeightMetres
        val failures = mutableListOf<String>()
        for (sampleHz in listOf(5.0, 8.0, 10.0, 15.0, 25.0)) for (hpa in listOf(0.2, -0.2, 0.3, -0.3)) {
            for (seconds in listOf(0.4, 0.6, 0.8, 1.2)) for (seed in 1L..40L) {
                val trace = SyntheticPressure(seed, sampleHz).walk(10.0).pulse(hpa, seconds).walk(20.0).build()
                val replay = replayPressure(trace, config)
                val worst = replay.states.maxOf { abs(it.heightSinceAnchorMetres) }
                if (replay.events.isNotEmpty() || worst >= limit) {
                    failures += "$hpa hPa for $seconds s at $sampleHz Hz, seed $seed: " +
                        "${replay.events.map { it.floorCandidates }}, height up to ${"%.2f".format(worst)} m"
                }
            }
        }
        assertEquals(emptyList(), failures, "${failures.size} of 3200 pulses")
    }

    /**
     * A door or HVAC step just before a lift ride in the same direction is still a step: it is judged by the jump
     * itself, not by the trend that follows, so only the ride counts as height.
     */
    @Test
    fun aDoorStepRightBeforeALiftRideIsNotAddedToTheRide() {
        val failures = mutableListOf<String>()
        for (sampleHz in listOf(5.0, 10.0, 25.0)) for (gapSeconds in listOf(0.5, 1.0, 1.5, 3.0)) for (direction in listOf(1, -1)) {
            for (seed in 100L..139L) {
                // A pressure rise (looks like going down) followed by a ride down, or a drop followed by a ride up.
                val trace = SyntheticPressure(seed, sampleHz).walk(8.0).stand(3.0).pressureStep(0.3 * direction).stand(gapSeconds)
                    .lift(-2 * direction, 1.6).stand(6.0).walk(10.0).build()
                val events = replayPressure(trace, config).events
                val expected = if (direction > 0) VerticalDirection.DOWN else VerticalDirection.UP
                if (events.size != 1 || events[0].floorCandidates != setOf(2) || events[0].direction != expected) {
                    failures += "gap $gapSeconds s, ride ${-2 * direction} floors, $sampleHz Hz, seed $seed: " +
                        events.map { "${it.direction} ${it.floorCandidates} ${"%.2f".format(it.heightDeltaMetres)} m" }
                }
            }
        }
        assertEquals(emptyList(), failures, "${failures.size} of 960 step-then-ride traces")
    }

    @Test
    fun aSecondFlightRightAfterAnEventIsFoundAfterTheBarometerIsReAnchored() {
        for (seed in 1L..10L) {
            val firstEvent = events(SyntheticPressure(seed).walk(10.0).stairs(1).mark("top").walk(20.0).build()).single()
            for (pauseSeconds in listOf(0.0, 1.0)) {
                // The same seed draws the same noise, so the first event lands at the same instant in the longer trace.
                val probe = SyntheticPressure(seed).walk(10.0).stairs(1).mark("top").build()
                val untilEvent = (firstEvent.endNanos - probe.mark("top")) / 1e9 + pauseSeconds
                val trace = SyntheticPressure(seed).walk(10.0).stairs(1).walk(untilEvent).stairs(1).walk(20.0).build()
                val tracker = BarometerTracker(config.barometer, sensorPresent = true)
                val detector = VerticalMotionDetector(config.vertical)
                val found = mutableListOf<VerticalEvent>()
                for (sample in trace.samples) {
                    val state = tracker.add(sample.timestampNanos, sample.hpa) ?: continue
                    val event = detector.onBarometer(state, trace.stepsInWindow(state.timestampNanos, config.vertical.rateWindowSeconds))
                    if (event != null) {
                        found += event
                        tracker.anchor(state.timestampNanos) // what Step 5 does on an APPLIED event; no detector reset
                    }
                }
                val label = "seed $seed, pause $pauseSeconds s"
                assertEquals(firstEvent.endNanos, found.first().endNanos, "$label: the first event moved")
                assertEquals(listOf(setOf(1), setOf(1)), found.map { it.floorCandidates }, label)
                assertTrue(found.all { it.direction == VerticalDirection.UP }, label)
            }
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
