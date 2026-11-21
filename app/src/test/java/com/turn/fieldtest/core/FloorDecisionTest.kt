package com.turn.fieldtest.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class FloorDecisionTest {
    private fun rectangle() = MetricPolygon(listOf(MetricPoint(0.0, 0.0), MetricPoint(10.0, 0.0), MetricPoint(10.0, 10.0), MetricPoint(0.0, 10.0)))
    private val floors = listOf("F1", "F2", "F3").associateWith { MapFloor(it, listOf(rectangle())) }
    private val levels = mapOf("F1" to 0, "F2" to 1, "F3" to 2)

    // F1-F2 stairs and F2-F3 stairs: F1 and F3 are not adjacent.
    private val topology = FloorTopologyBuilder.build("F1", floors, levels, listOf(
        FloorLinkInput("s12", ConnectorKind.STAIRS, "F1", MetricPoint(2.0, 2.0), "F2", MetricPoint(8.0, 8.0), 2.0, true, true, true, 15.0, "s12"),
        FloorLinkInput("s23", ConnectorKind.STAIRS, "F2", MetricPoint(2.0, 2.0), "F3", MetricPoint(8.0, 8.0), 2.0, true, true, true, 15.0, "s23"),
    ), useUnverifiedLinks = false).topology

    private fun decision() = FloorDecision(topology, DecisionConfig(), "F1")
    private fun s(seconds: Double) = (seconds * 1e9).toLong()

    @Test
    fun nearFiftyFiftyNeverSwitches() {
        val d = decision()
        repeat(40) { i ->
            val votes = if (i % 2 == 0) mapOf("F1" to 0.45, "F2" to 0.55) else mapOf("F1" to 0.55, "F2" to 0.45)
            val r = d.update(votes, s(i * 0.5), null)
            assertEquals("F1", r.floorId)
            assertEquals(FloorState.UNCERTAIN, r.state)
        }
        // An exact tie is not broken by id either.
        assertEquals("F1", d.update(mapOf("F1" to 0.5, "F2" to 0.5), s(30.0), null).floorId)
    }

    @Test
    fun switchesOnlyAfterThreeUpdatesAndTwoSeconds() {
        val votes = mapOf("F1" to 0.2, "F2" to 0.8)
        val slow = decision()
        listOf(0.0, 1.0, 1.9).forEach { assertEquals("F1", slow.update(votes, s(it), null).floorId) }
        val r = slow.update(votes, s(2.0), null)
        assertEquals("F2", r.floorId)
        assertEquals("F1", r.changedFrom)
        assertNull(slow.update(votes, s(2.5), null).changedFrom)

        val quick = decision() // six updates within 1.5 s: never 2 s
        (0..5).forEach { assertEquals("F1", quick.update(votes, s(it * 0.3), null).floorId) }
    }

    @Test
    fun nonAdjacentFloorNeedsAMatchingEvent() {
        val votes = mapOf("F1" to 0.1, "F3" to 0.9)
        val d = decision()
        (0..10).forEach { assertEquals("F1", d.update(votes, s(it.toDouble()), null).floorId) }
        val e = decision()
        val switched = (0..10).map { e.update(votes, s(it.toDouble()), setOf(2)) }.first { it.changedFrom != null }
        assertEquals("F3", switched.floorId)
    }

    @Test
    fun hardSwitchBelowTwoPercentAndResetIsImmediate() {
        val d = decision()
        val r = d.update(mapOf("F1" to 0.01, "F2" to 0.6, "F3" to 0.39), s(0.0), null)
        assertEquals("F2", r.floorId)
        assertEquals("F1", r.changedFrom)
        d.reset("F3", s(1.0))
        assertEquals("F3", d.floorId)
        assertEquals(FloorState.CONFIRMED, d.update(mapOf("F3" to 1.0), s(1.1), null).state)
    }

    @Test
    fun marksDriveTheState() {
        val d = decision()
        d.markChanging(s(10.0))
        assertEquals(FloorState.CHANGING, d.update(mapOf("F1" to 1.0), s(5.0), null).state)
        assertEquals(FloorState.CONFIRMED, d.update(mapOf("F1" to 1.0), s(10.0), null).state)
        assertEquals(FloorState.CHANGING, d.update(mapOf("F1" to 1.0), s(11.0), null, moving = true).state)
        d.markUncertain("height changed away from a floor link", s(20.0))
        val u = d.update(mapOf("F1" to 1.0), s(12.0), null)
        assertEquals(FloorState.UNCERTAIN, u.state)
        assertEquals("height changed away from a floor link", u.uncertainReason)
        assertEquals(FloorState.CONFIRMED, d.update(mapOf("F1" to 1.0), s(20.0), null).state)
        assertFailsWith<IllegalArgumentException> { FloorTrackingCoordinator(FloorTopology.NONE, barometerPresent = true, startFloorId = "F1") }
        assertNull(FloorTrackingCoordinator.createOrNull(FloorTopology.NONE, FloorDetectionConfig(), true, "F1"))
    }
}
