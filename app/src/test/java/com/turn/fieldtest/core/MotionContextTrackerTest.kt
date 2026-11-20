package com.turn.fieldtest.core

import com.turn.fieldtest.core.replay.nanos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MotionContextTrackerTest {
    private val stairs = 0
    private val lift = 1
    private val ramp = 2
    private val portals = mapOf(
        stairs to CuePortal(ConnectorKind.STAIRS, 12.0),
        lift to CuePortal(ConnectorKind.LIFT, 25.0),
        ramp to CuePortal(ConnectorKind.RAMP, 20.0),
    )

    private fun tracker() = MotionContextTracker(CueConfig(), portals)

    /** Steps at [hz] from [fromSeconds] for [seconds]; returns every cue emitted. */
    private fun MotionContextTracker.walk(fromSeconds: Double, seconds: Double, hz: Double, weight: Map<Int, Double>): List<DwellCue> {
        val count = (seconds * hz).toInt() + 1
        return (0 until count).mapNotNull { onStep(nanos(fromSeconds + it / hz), weight) }
    }

    @Test
    fun sixStepsOverFiveSecondsInStairsGiveOneCueThenTheCooldownHolds() {
        val tracker = tracker()
        val cues = tracker.walk(0.0, 5.0, 1.0, mapOf(stairs to 0.7))
        assertEquals(1, cues.size)
        assertEquals(DwellCue(VerticalKindHint.STAIRS, setOf(stairs), nanos(5.0)), cues.single())
        assertEquals(emptyList(), tracker.walk(5.5, 14.0, 1.5, mapOf(stairs to 0.9)), "cooldown")
        assertEquals(1, tracker.walk(21.0, 6.0, 1.5, mapOf(stairs to 0.9)).size, "after the cooldown")
    }

    @Test
    fun theDwellAlsoCoversPartOfTheTravelTime() {
        val tracker = MotionContextTracker(CueConfig(), mapOf(stairs to CuePortal(ConnectorKind.ESCALATOR, 20.0)))
        val cues = tracker.walk(0.0, 10.0, 1.5, mapOf(stairs to 0.6))
        assertEquals(nanos(8.0), cues.single().atNanos, "0.4 x 20 s")
    }

    @Test
    fun weightSplitAcrossStairsAndRampPortalsCounts() {
        val cues = tracker().walk(0.0, 6.0, 1.5, mapOf(stairs to 0.3, ramp to 0.25, lift to 0.4))
        assertEquals(setOf(stairs, ramp), cues.single().portalIndices)
    }

    @Test
    fun walkingThroughInThreeSecondsGivesNothing() {
        val tracker = tracker()
        assertEquals(emptyList(), tracker.walk(0.0, 3.0, 1.8, mapOf(stairs to 0.9)))
        assertEquals(emptyList(), tracker.walk(3.6, 10.0, 1.8, mapOf(stairs to 0.1)))
    }

    @Test
    fun anInterruptedRunStartsOver() {
        val tracker = tracker()
        assertEquals(emptyList(), tracker.walk(0.0, 4.0, 1.5, mapOf(stairs to 0.9)))
        assertNull(tracker.onStep(nanos(4.5), mapOf(stairs to 0.2)))
        assertEquals(emptyList(), tracker.walk(5.0, 4.0, 1.5, mapOf(stairs to 0.9)))
    }

    @Test
    fun anEightSecondLiftDwellIsReportedOnTheFirstStepOut() {
        val tracker = tracker()
        assertEquals(emptyList(), tracker.walk(0.0, 2.0, 1.8, mapOf(lift to 0.8)))
        for (second in 3..11) tracker.onTick(nanos(second.toDouble()), mapOf(lift to 0.8))
        val cue = tracker.onStep(nanos(12.0), mapOf(lift to 0.6))
        assertEquals(DwellCue(VerticalKindHint.LIFT, setOf(lift), nanos(12.0)), cue)
        assertNull(tracker.onStep(nanos(12.5), mapOf(lift to 0.6)))
    }

    @Test
    fun aShortOrBusyLiftDwellGivesNothing() {
        val short = tracker()
        for (second in 0..6) short.onTick(nanos(second.toDouble()), mapOf(lift to 0.8))
        assertNull(short.onStep(nanos(7.0), mapOf(lift to 0.8)))

        val busy = tracker()
        busy.onTick(nanos(0.0), mapOf(lift to 0.8))
        assertEquals(emptyList(), busy.walk(2.0, 1.0, 2.0, mapOf(lift to 0.8)))
        for (second in 4..9) busy.onTick(nanos(second.toDouble()), mapOf(lift to 0.8))
        assertNull(busy.onStep(nanos(10.0), mapOf(lift to 0.8)), "three steps in the dwell")

        val left = tracker()
        for (second in 0..5) left.onTick(nanos(second.toDouble()), mapOf(lift to 0.8))
        for (second in 6..9) left.onTick(nanos(second.toDouble()), mapOf(lift to 0.2))
        assertNull(left.onStep(nanos(10.0), mapOf(lift to 0.2)))
    }

    @Test
    fun stepsOutsidePortalsGiveNothing() {
        val tracker = tracker()
        assertEquals(emptyList(), tracker.walk(0.0, 60.0, 1.8, emptyMap()))
        assertEquals(emptyList(), tracker.walk(61.0, 30.0, 1.8, mapOf(stairs to 0.45, 9 to 0.5)))
        for (second in 92..110) tracker.onTick(nanos(second.toDouble()), mapOf(7 to 1.0))
        assertNull(tracker.onStep(nanos(111.0), mapOf(7 to 1.0)))
    }

    @Test
    fun resetForgetsTheCooldown() {
        val tracker = tracker()
        assertEquals(1, tracker.walk(0.0, 5.0, 1.0, mapOf(stairs to 0.7)).size)
        tracker.reset()
        assertEquals(1, tracker.walk(6.0, 5.0, 1.0, mapOf(stairs to 0.7)).size)
    }
}
