package com.turn.fieldtest.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FloorTrackEvaluatorTest {
    private fun s(seconds: Int) = seconds * 1_000_000_000L
    private fun on(seconds: Int, floor: String) = FloorTruth(s(seconds), floor)
    private fun transit(seconds: Int, kind: ConnectorKind = ConnectorKind.STAIRS) = FloorTruth(s(seconds), null, kind, true)
    private fun at(seconds: Int, floor: String) = ConfirmedFloor(s(seconds), floor)
    private val stairsUp = listOf(on(0, "F1"), transit(100), on(115, "F2"))

    @Test
    fun followedChangeIsAccurateWithLatencyFromArrival() {
        val m = FloorTrackEvaluator.evaluate(stairsUp, listOf(at(0, "F1"), at(30, "F1"), at(118, "F2"), at(200, "F2")), s(3600))
        assertEquals(FloorTrackMetrics(1.0, listOf(3.0), 0.0, 0, falseChanges = 0, missedChanges = 0), m)
    }

    @Test
    fun lateConfirmationCostsAccuracyOutsideTheGraceWindow() {
        val m = FloorTrackEvaluator.evaluate(stairsUp, listOf(at(0, "F1"), at(130, "F2")), s(3600))
        assertEquals(listOf(15.0), m.latenciesSeconds)
        // Evaluated: 0-95 s and 120-3600 s; wrong 120-130 s.
        assertEquals(3565.0 / 3575.0, m.accuracy, 1e-12)
        assertEquals(0, m.falseChanges)
    }

    @Test
    fun confirmationBeforeArrivalGivesNegativeLatencyAndTransitEndsAreNotFalse() {
        assertEquals(listOf(-5.0), FloorTrackEvaluator.evaluate(stairsUp, listOf(at(0, "F1"), at(110, "F2")), s(600)).latenciesSeconds)
        val m = FloorTrackEvaluator.evaluate(stairsUp, listOf(at(0, "F1"), at(108, "F3"), at(116, "F2")), s(3600))
        assertEquals(1, m.falseChanges)
        assertEquals(1.0, m.falseChangesPerHour, 1e-12)
        assertEquals(listOf(1.0), m.latenciesSeconds)
        assertEquals(0, m.reverts)
    }

    @Test
    fun changeUndoneWithinThirtySecondsIsARevert() {
        val truth = listOf(on(0, "F1"))
        val quick = FloorTrackEvaluator.evaluate(truth, listOf(at(0, "F1"), at(600, "F2"), at(620, "F1")), s(3600))
        assertEquals(1, quick.reverts)
        assertEquals(1, quick.falseChanges) // returning to the true floor is not a false change
        assertEquals(1.0, quick.falseChangesPerHour, 1e-12)
        assertEquals(3580.0 / 3600.0, quick.accuracy, 1e-12)
        assertEquals(0, FloorTrackEvaluator.evaluate(truth, listOf(at(0, "F1"), at(600, "F2"), at(640, "F1")), s(3600)).reverts)
        assertEquals(1, FloorTrackEvaluator.evaluate(truth, listOf(at(0, "F1"), at(600, "F2"), at(640, "F1")), s(3600), revertSeconds = 60.0).reverts)
    }

    @Test
    fun unconfirmedChangeIsMissed() {
        val m = FloorTrackEvaluator.evaluate(listOf(on(0, "F1"), on(100, "F2")), listOf(at(0, "F1")), s(1000))
        assertEquals(1, m.missedChanges)
        assertEquals(emptyList(), m.latenciesSeconds)
        assertEquals(95.0 / 990.0, m.accuracy, 1e-12) // a direct jump gets grace on both sides of the arrival
    }

    @Test
    fun abortedTransitIsNotAChangeButWanderingIsFalse() {
        val m = FloorTrackEvaluator.evaluate(listOf(on(0, "F1"), transit(100), on(120, "F1")),
            listOf(at(0, "F1"), at(105, "F2"), at(110, "F1")), s(600))
        assertEquals(emptyList(), m.latenciesSeconds)
        assertEquals(0, m.missedChanges)
        assertEquals(1, m.falseChanges)
        assertEquals(1, m.reverts)
        assertEquals(1.0, m.accuracy)
    }

    @Test
    fun correctingAWrongStartFloorIsNotAFalseChange() {
        val m = FloorTrackEvaluator.evaluate(listOf(on(0, "F2")), listOf(at(0, "F1"), at(60, "F2")), s(600))
        assertEquals(0, m.falseChanges)
        assertEquals(0, m.reverts)
        assertEquals(0.9, m.accuracy, 1e-12)
    }

    @Test
    fun noDecisionYetCountsAsWrongAndInputsAreChecked() {
        assertEquals(0.5, FloorTrackEvaluator.evaluate(listOf(on(0, "F1")), listOf(at(50, "F1")), s(100)).accuracy, 1e-12)
        assertTrue(FloorTrackEvaluator.evaluate(listOf(transit(0, ConnectorKind.LIFT)), emptyList(), s(10)).accuracy.isNaN())
        assertEquals(0.0, FloorTrackEvaluator.evaluate(listOf(on(10, "F1")), listOf(at(10, "F1")), s(10)).falseChangesPerHour)
        assertFailsWith<IllegalArgumentException> { FloorTrackEvaluator.evaluate(emptyList(), emptyList(), s(10)) }
        assertFailsWith<IllegalArgumentException> { FloorTrackEvaluator.evaluate(listOf(on(10, "F1")), emptyList(), s(5)) }
        assertFailsWith<IllegalArgumentException> { FloorTrackEvaluator.evaluate(listOf(on(0, "F1")), emptyList(), s(5), transitGraceSeconds = -1.0) }
        assertFailsWith<IllegalArgumentException> { FloorTruth(0, " ") }
    }
}
