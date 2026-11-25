package com.turn.fieldtest.core

import com.turn.fieldtest.core.replay.SyntheticPressure
import com.turn.fieldtest.core.replay.replayPressure
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Per-floor elevations (schema 8): floor counts judged against the real storey, with parity when unset. */
class FloorElevationsTest {
    private val vertical = VerticalMotionConfig()
    private val houseLevels = mapOf("G" to 0, "F1" to 1)
    private fun house(storey: Double) = requireNotNull(FloorElevations.of(houseLevels, mapOf("G" to 0.0, "F1" to storey)))
    private fun threeStorey(storey: Double) = requireNotNull(FloorElevations.of(mapOf("G" to 0, "F1" to 1, "F2" to 2),
        mapOf("G" to 0.0, "F1" to storey, "F2" to 2 * storey)))

    @Test
    fun floorCountRuleIsTheDetectorsOriginalRule() {
        assertNull(floorCountCandidates(0.59, vertical))
        assertEquals(setOf(1), floorCountCandidates(0.6, vertical))
        assertEquals(setOf(1), floorCountCandidates(1.34, vertical))
        assertEquals(setOf(1, 2), floorCountCandidates(1.5, vertical))
        assertEquals(setOf(2), floorCountCandidates(-2.2, vertical))
        assertNull(floorCountCandidates(Double.NaN, vertical))
    }

    @Test
    fun elevationsAreUsedOnlyWhenCompleteAndPlausible() {
        assertNull(FloorElevations.of(houseLevels, mapOf("G" to 0.0, "F1" to null)), "a floor without a height keeps the nominal storey")
        assertNull(FloorElevations.of(houseLevels, mapOf("G" to 0.0)), "a missing floor keeps the nominal storey")
        assertNull(FloorElevations.of(houseLevels, mapOf("G" to 0.0, "F1" to 1.2)), "1.2 m is not a storey")
        assertNull(FloorElevations.of(houseLevels, mapOf("G" to 2.7, "F1" to 0.0)), "elevations must rise with level")
        assertNull(FloorElevations.of(mapOf("G" to 0), mapOf("G" to 0.0)), "one floor needs no elevations")
        assertNull(FloorElevations.of(houseLevels, mapOf("G" to 0.0, "F1" to Double.NaN)))
        assertNotNull(FloorElevations.of(houseLevels, mapOf("G" to 0.0, "F1" to 2.7)))
        val message = FloorElevations.validationError(houseLevels, mapOf("G" to 0.0, "F1" to 1.0), mapOf("F1" to "Upstairs", "G" to "Ground"))
        assertTrue(message!!.contains("Upstairs is 1.00 m above Ground"), message)
        assertTrue(FloorElevations.validationError(houseLevels, mapOf("G" to 0.0, "F1" to null))!!.startsWith("Set a height for every floor"))
        // A level gap (no floor 1 saved) is judged per level: 5.4 m over two levels is two 2.7 m storeys.
        assertNull(FloorElevations.validationError(mapOf("G" to 0, "F2" to 2), mapOf("G" to 0.0, "F2" to 5.4)))
    }

    @Test
    fun uniformStoreysMatchTheNominalDivision() {
        val e = threeStorey(3.5)
        listOf(0.5, 2.2, 3.5, 5.0, 7.0, -3.5, -6.0).forEach { dh ->
            assertEquals(dh / 3.5, e.floorsFor("G", dh).let { if (dh < 0) it else it }, 1e-9, "from G, $dh m")
            assertEquals(dh / 3.5, e.floorsFor("F1", dh), 1e-9, "from F1, $dh m")
            assertEquals(floorCountCandidates(abs(dh) / 3.5, vertical), e.candidates(listOf("F1"), dh, vertical), "candidates for $dh m")
        }
    }

    @Test
    fun houseStoreysAreOneFloorWhereTheNominalStoreyIsUnsure() {
        listOf(2.6, 2.7, 2.8).forEach { storey ->
            val e = house(storey)
            assertEquals(setOf(1), e.candidates(listOf("G"), storey, vertical), "up a $storey m storey")
            assertEquals(setOf(1), e.candidates(listOf("F1"), -storey, vertical), "down a $storey m storey")
            // A barometer that under-reads a quarter of the climb still sees one floor; the 3.5 m nominal sees none.
            assertEquals(setOf(1), e.candidates(listOf("G"), 0.75 * storey, vertical), "$storey m read as ${0.75 * storey}")
            assertNull(floorCountCandidates(0.75 * storey / 3.5, vertical))
            // Half a storey is not a floor.
            assertNull(e.candidates(listOf("G"), 0.5 * storey, vertical))
            // Two storeys in a three-storey house are two floors, where the nominal storey says one or two.
            assertEquals(setOf(2), threeStorey(storey).candidates(listOf("G"), 2 * storey, vertical))
            assertEquals(setOf(1, 2), floorCountCandidates(2 * storey / 3.5, vertical))
        }
        assertEquals(2.6, house(2.6).smallestStoreyMetres, 1e-12)
    }

    @Test
    fun mixedStoreysJudgeEachStepByItsOwnHeight() {
        // A tall ground floor (4.2 m) under 2.7 m upper storeys.
        val e = requireNotNull(FloorElevations.of(mapOf("G" to 0, "F1" to 1, "F2" to 2), mapOf("G" to 0.0, "F1" to 4.2, "F2" to 6.9)))
        assertEquals(setOf(1), e.candidates(listOf("G"), 4.2, vertical))
        assertEquals(setOf(1), e.candidates(listOf("F1"), 2.7, vertical))
        assertEquals(setOf(2), e.candidates(listOf("G"), 6.9, vertical))
        assertEquals(setOf(1), e.candidates(listOf("F2"), -2.7, vertical))
        assertEquals(2.7, e.smallestStoreyMetres, 1e-12)
        assertEquals(3.45, e.storeyMetres("G", "F2"), 1e-12)
        assertEquals(-6.9, e.expectedHeight("F2", "G"), 1e-12)
        // Unknown start floors are ignored; no known start floor means no floor change.
        assertNull(e.candidates(listOf("X"), 4.2, vertical))
    }

    @Test
    fun fillFromStoreyHeightPutsTheGroundFloorAtZero() {
        assertEquals(mapOf("B1" to -2.7, "G" to 0.0, "F1" to 2.7), FloorElevations.fromStoreyHeight(mapOf("B1" to -1, "G" to 0, "F1" to 1), 2.7))
        assertTrue(runCatching { FloorElevations.fromStoreyHeight(houseLevels, 1.0) }.isFailure)
    }

    @Test
    fun syntheticStairsAndLiftsAtHouseStoreysGiveExactlyOneFloor() {
        listOf(2.6, 2.7, 2.8).forEach { storey ->
            listOf(3L, 7L).forEach { seed ->
                val e = house(storey)
                val judge = FloorCountJudge { dh -> e.candidates(listOf(if (dh > 0) "G" else "F1"), dh, vertical) }
                val stairs = SyntheticPressure(seed).walk(10.0).stairs(1, floorHeightMetres = storey).walk(10.0).stand(8.0)
                    .stairs(-1, floorHeightMetres = storey).stand(8.0).build()
                val events = replayPressure(stairs, judge = judge).events
                assertEquals(listOf(VerticalDirection.UP to setOf(1), VerticalDirection.DOWN to setOf(1)),
                    events.map { it.direction to it.floorCandidates }, "stairs at $storey m, seed $seed")
                assertTrue(events.all { it.kind == VerticalKindHint.STAIRS })
                assertTrue(abs(abs(events.first().heightDeltaMetres) - storey) < 0.4, "${events.first().heightDeltaMetres}")

                val lift = SyntheticPressure(seed).stand(6.0).lift(1, floorHeightMetres = storey).stand(10.0).build()
                val liftEvents = replayPressure(lift, judge = judge).events
                assertEquals(listOf(setOf(1)), liftEvents.map { it.floorCandidates }, "lift at $storey m, seed $seed")

                val tall = threeStorey(storey)
                val two = SyntheticPressure(seed).stand(6.0).lift(2, speedMps = 1.0, floorHeightMetres = storey).stand(10.0).build()
                val twoEvents = replayPressure(two, judge = { dh -> tall.candidates(listOf("G"), dh, vertical) }).events
                assertEquals(listOf(setOf(2)), twoEvents.map { it.floorCandidates }, "two-floor lift at $storey m, seed $seed")
                assertEquals(listOf(setOf(1, 2)), replayPressure(two).events.map { it.floorCandidates },
                    "the nominal 3.5 m storey cannot tell one floor from two here")
            }
        }
    }

    @Test
    fun underReadStairsAreSeenOnlyWithElevations() {
        // A 2.6 m house storey whose climb the barometer reads as 1.9 m (e.g. stairwell pressure lag).
        val e = house(2.6)
        val trace = SyntheticPressure(5L).walk(10.0).stairs(1, floorHeightMetres = 1.9).walk(10.0).build()
        assertTrue(replayPressure(trace).events.isEmpty(), "0.57 of a 3.5 m storey is below the minimum floor fraction")
        assertEquals(listOf(setOf(1)), replayPressure(trace, judge = { dh -> e.candidates(listOf("G"), dh, vertical) }).events.map { it.floorCandidates })
    }

    @Test
    fun uniformElevationsReproduceTheNominalDetectorExactly() {
        val e = threeStorey(3.5)
        listOf(11L, 12L, 13L).forEach { seed ->
            val trace = SyntheticPressure(seed).walk(8.0).stairs(1).walk(6.0).stand(5.0).lift(2).stand(8.0).stairs(-2).stand(8.0)
                .pulse(0.3, 0.8).stand(6.0).climb(5.25, 20.0).stand(8.0).build()
            val nominal = replayPressure(trace).events
            val judged = replayPressure(trace, judge = { dh -> e.candidates(listOf("F1"), dh, vertical) }).events
            assertTrue(nominal.isNotEmpty())
            assertEquals(nominal, judged, "seed $seed")
        }
    }
}
