package com.turn.fieldtest.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Asymmetric frames: F1 covers (0,0)-(10,10) and F2 covers (8,4)-(28,14), so the stairs at (2,2) on F1 and
 * (15,8) on F2 are each outside the other floor. Mixing up the two frames anywhere excludes or misplaces the link.
 */
class FloorTopologyTest {
    private fun rectangle(left: Double, bottom: Double, right: Double, top: Double) = MetricPolygon(listOf(
        MetricPoint(left, bottom), MetricPoint(right, bottom), MetricPoint(right, top), MetricPoint(left, top)))

    private val f1 = MapFloor("F1", listOf(rectangle(0.0, 0.0, 10.0, 10.0)),
        walls = listOf(WallSegment("w1", MetricPoint(3.0, 0.0), MetricPoint(3.0, 4.0))))
    private val f2 = MapFloor("F2", listOf(rectangle(8.0, 4.0, 28.0, 14.0)))
    private val f3 = MapFloor("F3", listOf(rectangle(0.0, 0.0, 20.0, 20.0)))
    private val f4 = MapFloor("F4", listOf(rectangle(0.0, 0.0, 20.0, 20.0)))
    private val floors = listOf(f1, f2, f3, f4).associateBy { it.id }
    private val levels = mapOf("F1" to 0, "F2" to 1, "F3" to 2, "F4" to 3)

    private fun link(id: String = "stairs", kind: ConnectorKind = ConnectorKind.STAIRS, from: String = "F1",
        fromPoint: MetricPoint = MetricPoint(2.0, 2.0), to: String = "F2", toPoint: MetricPoint = MetricPoint(15.0, 8.0),
        radius: Double = 2.0, bidirectional: Boolean = true, enabled: Boolean = true, verified: Boolean = true,
        seconds: Double = 15.0, label: String = "Stairs 1") =
        FloorLinkInput(id, kind, from, fromPoint, to, toPoint, radius, bidirectional, enabled, verified, seconds, label)

    private val escalator = link("escalator", ConnectorKind.ESCALATOR, "F2", MetricPoint(20.0, 10.0), "F3",
        MetricPoint(5.0, 5.0), radius = 1.5, bidirectional = false, seconds = 12.0, label = "Escalator up")

    private fun build(links: List<FloorLinkInput>, start: String = "F1", unverified: Boolean = false) =
        FloorTopologyBuilder.build(start, floors, levels, links, unverified)

    @Test
    fun bidirectionalLinkGivesTwoPortalsAndOneWayGivesOne() {
        val build = build(listOf(link(), escalator))
        val portals = build.topology.portals
        assertEquals(3, portals.size)
        assertEquals(listOf(0, 1, 2), portals.map { it.index })
        with(portals[0]) {
            assertEquals("stairs", linkId); assertEquals("F1", fromFloorId); assertEquals("F2", toFloorId)
            assertEquals(MetricPoint(2.0, 2.0), fromCentre); assertEquals(MetricPoint(15.0, 8.0), toCentre); assertEquals(1, levelDelta)
        }
        with(portals[1]) {
            assertEquals("stairs", linkId); assertEquals("F2", fromFloorId); assertEquals("F1", toFloorId)
            assertEquals(MetricPoint(15.0, 8.0), fromCentre); assertEquals(MetricPoint(2.0, 2.0), toCentre); assertEquals(-1, levelDelta)
        }
        with(portals[2]) {
            assertEquals("escalator", linkId); assertEquals(ConnectorKind.ESCALATOR, kind); assertEquals("F2", fromFloorId)
            assertEquals("F3", toFloorId); assertEquals(1, levelDelta); assertEquals(12.0, travelTimeSeconds); assertEquals(1.5, radiusMetres)
        }
        assertEquals(emptyList(), build.topology.portalsFrom("F3")) // one-way: no way back down
        assertEquals(listOf("escalator", "stairs"), build.topology.portalsFrom("F2").map { it.linkId }.sorted())
        assertTrue(build.topology.adjacent("F1", "F2") && build.topology.adjacent("F2", "F1"))
        assertTrue(build.topology.adjacent("F3", "F2"))
        assertFalse(build.topology.adjacent("F1", "F3"))
        assertFalse(build.topology.adjacent("F1", "F1"))
        assertEquals(emptyList(), build.excluded)
    }

    @Test
    fun reportsEachExclusionReason() {
        val build = build(listOf(
            link(),
            link("d", enabled = false, verified = false, label = "Disabled"),
            link("u", verified = false, label = "Unchecked"),
            link("m", to = "F9", label = "Missing"),
            link("e", toPoint = MetricPoint(2.0, 2.0), label = "Wrong frame"), // (2,2) is outside F2
            link("r", seconds = 0.5, label = "Too quick"),
            link("same", to = "F1", toPoint = MetricPoint(5.0, 5.0), label = "Same floor"),
        ))
        assertEquals(listOf(
            ExcludedLink("d", "Disabled", ExclusionReason.DISABLED),
            ExcludedLink("u", "Unchecked", ExclusionReason.UNVERIFIED),
            ExcludedLink("m", "Missing", ExclusionReason.FLOOR_MISSING),
            ExcludedLink("e", "Wrong frame", ExclusionReason.ENDPOINT_NOT_WALKABLE),
            ExcludedLink("r", "Too quick", ExclusionReason.RULES),
            ExcludedLink("same", "Same floor", ExclusionReason.RULES),
        ), build.excluded)
        assertEquals(listOf("stairs", "stairs"), build.topology.portals.map { it.linkId })
        // A floor with a map but no level is missing too.
        assertEquals(ExclusionReason.FLOOR_MISSING,
            FloorTopologyBuilder.build("F1", floors, levels - "F2", listOf(link()), false).excluded.single().reason)
    }

    @Test
    fun unverifiedLinksNeedTheFlag() {
        val off = build(listOf(link(verified = false)))
        assertSame(FloorTopology.NONE, off.topology)
        assertEquals(listOf(ExcludedLink("stairs", "Stairs 1", ExclusionReason.UNVERIFIED)), off.excluded)
        val on = build(listOf(link(verified = false)), unverified = true)
        assertEquals(2, on.topology.portals.size)
        assertTrue(on.topology.portals.none { it.verifiedOnSite })
        assertEquals(emptyList(), on.excluded)
    }

    @Test
    fun breadthFirstSearchGivesTheSessionFloorsAndLeavesUnlinkedFloorsOut() {
        val fromBottom = build(listOf(link(), escalator))
        assertEquals(setOf("F1", "F2", "F3"), fromBottom.sessionFloorIds)
        assertEquals(mapOf("F1" to 0, "F2" to 1, "F3" to 2), fromBottom.topology.levels)
        // The search is undirected: the top of a one-way escalator still reaches the floors below.
        assertEquals(setOf("F1", "F2", "F3"), build(listOf(link(), escalator), start = "F3").sessionFloorIds)
        // A usable link between two floors the start cannot reach adds no portals.
        val island = link("island", from = "F3", fromPoint = MetricPoint(1.0, 1.0), to = "F4", toPoint = MetricPoint(1.0, 1.0), label = "Island")
        val withIsland = build(listOf(link(), island))
        assertEquals(setOf("F1", "F2"), withIsland.sessionFloorIds)
        assertTrue(withIsland.topology.portals.none { it.linkId == "island" })
        assertEquals(emptyList(), withIsland.excluded)
    }

    @Test
    fun unusableStartFloorGivesNone() {
        val unlinked = build(listOf(link(), escalator), start = "F4")
        assertSame(FloorTopology.NONE, unlinked.topology)
        assertEquals(setOf("F4"), unlinked.sessionFloorIds)
        val disabled = build(listOf(link(enabled = false)))
        assertSame(FloorTopology.NONE, disabled.topology)
        assertEquals(setOf("F1"), disabled.sessionFloorIds)
        assertEquals(ExclusionReason.DISABLED, disabled.excluded.single().reason)
        assertTrue(FloorTopology.NONE.isEmpty)
        assertEquals(emptyList(), FloorTopology.NONE.portalsFrom("F1"))
        assertFalse(FloorTopology.NONE.adjacent("F1", "F2"))
        assertSame(FloorTopology.NONE, build(emptyList()).topology)
    }

    @Test
    fun captureIsDeterministicAndRefusesPointsBehindWallsOrOutsideWalkableSpace() {
        val topology = build(listOf(
            link("b-stairs"),
            link("a-lift", ConnectorKind.LIFT, label = "Lift"),
            link("0-near", fromPoint = MetricPoint(2.4, 2.0), toPoint = MetricPoint(16.0, 8.0), label = "Near"),
        )).topology
        val point = MetricPoint(2.5, 2.0)
        val captured = topology.capture(f1, point, 1.0)
        assertEquals(listOf("0-near", "a-lift", "b-stairs"), captured.map { it.linkId }) // distance, then link id
        assertTrue(captured.all { it.fromFloorId == "F1" })
        assertEquals(captured, topology.capture(f1, point, 1.0))
        // 1.5 m from the centre, inside the radius, but behind the wall at x = 3.
        assertEquals(emptyList(), topology.capture(f1, MetricPoint(3.5, 2.0), 1.0))
        // Inside radius + slack but outside walkable space.
        assertEquals(emptyList(), topology.capture(f1, MetricPoint(-0.5, 2.0), 1.0))
        // 2.5 m (and 2.53 m from the near link) away on open floor: only the slack lets it in, nearest first.
        assertEquals(listOf("a-lift", "b-stairs", "0-near"), topology.capture(f1, MetricPoint(2.0, 4.5), 1.0).map { it.linkId })
        assertEquals(emptyList(), topology.capture(f1, MetricPoint(2.0, 4.5), 0.0))
        assertFailsWith<IllegalArgumentException> { topology.capture(f1, point, -1.0) }
    }

    @Test
    fun captureUsesEachFloorsOwnFrame() {
        val topology = build(listOf(link())).topology
        // On F2 the stairs sit at (15,8); F1's (2,2) means nothing there.
        assertEquals(listOf("F2"), topology.capture(f2, MetricPoint(15.5, 8.0), 0.0).map { it.fromFloorId })
        assertEquals(emptyList(), topology.capture(f2, MetricPoint(2.0, 2.0), 1.0))
        assertEquals(emptyList(), topology.capture(f1, MetricPoint(9.5, 8.0), 1.0))
        assertEquals(MetricPoint(2.0, 2.0), topology.capture(f2, MetricPoint(15.0, 8.0), 0.0).single().toCentre)
    }
}
