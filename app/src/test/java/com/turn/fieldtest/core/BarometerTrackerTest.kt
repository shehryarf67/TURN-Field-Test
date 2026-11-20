package com.turn.fieldtest.core

import com.turn.fieldtest.core.replay.SyntheticPressure
import com.turn.fieldtest.core.replay.nanos
import com.turn.fieldtest.core.replay.replayPressure
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BarometerTrackerTest {
    private val config = BarometerConfig()
    private fun tracker() = BarometerTracker(config, sensorPresent = true)

    @Test
    fun rejectsNonFiniteOutOfRangeAndLateSamples() {
        val tracker = tracker()
        assertNull(tracker.add(nanos(1.0), Double.NaN))
        assertNull(tracker.add(nanos(1.0), Double.POSITIVE_INFINITY))
        assertNull(tracker.add(nanos(1.0), 250.0))
        assertNull(tracker.add(nanos(1.0), 1200.0))
        assertNull(tracker.latestHpa)
        assertNotNull(tracker.add(nanos(1.0), 945.0))
        assertNull(tracker.add(nanos(0.9), 945.0), "more than 50 ms late is dropped")
        assertNotNull(tracker.add(nanos(0.97), 945.1), "up to 50 ms late is re-sorted")
        assertEquals(945.0, tracker.latestHpa)
        assertNotNull(tracker.add(nanos(1.2), 945.0))
    }

    @Test
    fun aGapReseedsTheSmoothingButKeepsTheAnchor() {
        val tracker = tracker()
        var t = 0.0
        repeat(50) { tracker.add(nanos(t), 945.0); t += 0.2 }
        val afterGap = assertNotNull(tracker.add(nanos(t + 6.0), 944.6))
        assertEquals(944.6, afterGap.smoothedHpa, 1e-9)
        assertEquals(0.4 * 8434.0 / 944.6, afterGap.heightSinceAnchorMetres, 1e-6)

        val noGap = tracker()
        t = 0.0
        repeat(50) { noGap.add(nanos(t), 945.0); t += 0.2 }
        val shortPause = assertNotNull(noGap.add(nanos(t + 4.0), 944.6))
        assertNotEquals(944.6, shortPause.smoothedHpa)
    }

    @Test
    fun oneFloorOfStairsReadsAboutThreeAndAHalfMetres() {
        for (seed in 1L..5L) {
            val trace = SyntheticPressure(seed).walk(10.0).climb(0.39 / 945.0 * 8434.0, 12.0).walk(10.0).build()
            val last = replayPressure(trace).states.last()
            assertEquals(3.5, last.heightSinceAnchorMetres, 0.3, "seed $seed")
        }
    }

    @Test
    fun anchorZeroesTheHeight() {
        val tracker = tracker()
        SyntheticPressure(3).walk(5.0).stairs(1).walk(8.0).build().samples.forEach { tracker.add(it.timestampNanos, it.hpa) }
        assertTrue(tracker.heightSinceAnchorMetres > 3.0)
        tracker.anchor(nanos(30.0))
        assertEquals(0.0, tracker.heightSinceAnchorMetres, 1e-12)
    }

    @Test
    fun aDoorStepIsADiscontinuityNotHeight() {
        for (seed in 1L..20L) {
            for (step in listOf(0.3, -0.3)) {
                val trace = SyntheticPressure(seed).walk(10.0).pressureStep(step).walk(15.0).build()
                val states = replayPressure(trace).states
                assertEquals(1, states.count { it.discontinuity }, "seed $seed, step $step")
                assertTrue(states.all { abs(it.heightSinceAnchorMetres) < 0.7 }, "seed $seed: the $step hPa step alone would be 2.7 m")
            }
        }
    }

    @Test
    fun aFastLiftRideIsHeightNotADiscontinuity() {
        for (seed in 1L..40L) {
            for (floors in listOf(2, -2)) {
                val trace = SyntheticPressure(seed).walk(8.0).stand(5.0).lift(floors, speedMps = 2.5).stand(8.0).build()
                val states = replayPressure(trace).states
                assertTrue(states.none { it.discontinuity }, "seed $seed, $floors floors: the start of the ride was absorbed")
                assertEquals(floors * 3.5, states.last().heightSinceAnchorMetres, 0.4, "seed $seed, $floors floors")
            }
        }
    }

    @Test
    fun weatherDriftStaysBelowSixtyCentimetresOverTenMinutes() {
        for ((seed, hpaPerHour) in listOf(1L to 1.5, 2L to -1.5, 3L to 1.0)) {
            val trace = SyntheticPressure(seed).drift(hpaPerHour).walk(600.0).build()
            val worst = replayPressure(trace).states.maxOf { abs(it.heightSinceAnchorMetres) }
            assertTrue(worst < 0.6, "seed $seed drift $hpaPerHour: $worst m")
        }
    }

    @Test
    fun heldDriftCompensationKeepsAnUnexplainedHeight() {
        val tracker = tracker()
        tracker.driftCompensationHeld = true
        SyntheticPressure(4).walk(5.0).stairs(1).walk(120.0).build().samples.forEach { tracker.add(it.timestampNanos, it.hpa) }
        assertEquals(3.5, tracker.heightSinceAnchorMetres, 0.3)
    }

    @Test
    fun healthIsStaleAfterThreeSecondsAndUnavailableWithoutASensor() {
        val tracker = tracker()
        assertEquals(BarometerHealth.STALE, tracker.health(0L), "no reading yet")
        tracker.add(nanos(1.0), 945.0)
        assertEquals(BarometerHealth.OK, tracker.health(nanos(3.9)))
        assertEquals(BarometerHealth.STALE, tracker.health(nanos(4.1)))

        val absent = BarometerTracker(config, sensorPresent = false)
        assertNull(absent.add(nanos(1.0), 945.0))
        assertEquals(BarometerHealth.UNAVAILABLE, absent.health(nanos(1.0)))
        assertEquals(0.0, absent.heightSinceAnchorMetres)
    }
}
