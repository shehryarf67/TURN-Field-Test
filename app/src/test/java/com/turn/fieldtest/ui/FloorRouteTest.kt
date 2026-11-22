package com.turn.fieldtest.ui

import com.turn.fieldtest.core.ConnectorKind
import com.turn.fieldtest.core.FloorLinkInput
import com.turn.fieldtest.core.FloorTopologyBuilder
import com.turn.fieldtest.core.MapFloor
import com.turn.fieldtest.core.MetricPoint
import com.turn.fieldtest.core.MetricPolygon
import com.turn.fieldtest.core.MultiFloorRouteResult
import com.turn.fieldtest.core.MultiFloorRouter
import com.turn.fieldtest.core.NavigationEdge
import com.turn.fieldtest.core.NavigationGraph
import com.turn.fieldtest.core.NavigationNode
import com.turn.fieldtest.core.RouteFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Phase 3 screen logic: grouped search, the leg of the confirmed floor, the connector banner and failure text. */
class FloorRouteTest {
    private fun floor(id: String) = MapFloor(id, listOf(MetricPolygon(listOf(
        MetricPoint(0.0, 0.0), MetricPoint(30.0, 0.0), MetricPoint(30.0, 20.0), MetricPoint(0.0, 20.0)))))
    private val floors = listOf("GF", "F1", "F2").associateWith { floor(it) }
    private val levels = mapOf("GF" to 0, "F1" to 1, "F2" to 2)
    private val names = mapOf("GF" to "Ground floor", "F1" to "First floor", "F2" to "Second floor")

    private fun corridor(floorId: String): NavigationGraph {
        val west = NavigationNode("$floorId-west", floorId, MetricPoint(2.0, 2.0))
        val east = NavigationNode("$floorId-room", floorId, MetricPoint(28.0, 2.0), poiId = "POI-$floorId")
        return NavigationGraph(floors.getValue(floorId), listOf(west, east),
            listOf(NavigationEdge("$floorId-corridor", west.id, east.id, west.point.distanceTo(east.point))))
    }
    private val graphs = floors.keys.associateWith { corridor(it) }

    private fun stairs(id: String, from: String, to: String) = FloorLinkInput(id, ConnectorKind.STAIRS, from, MetricPoint(25.0, 2.0),
        to, MetricPoint(25.0, 2.0), 1.5, true, true, true, 15.0, "Stairs 1")

    private val router = MultiFloorRouter(graphs,
        FloorTopologyBuilder.build("GF", floors, levels, listOf(stairs("s0", "GF", "F1"), stairs("s1", "F1", "F2")), false).topology,
        floors = floors)

    /** From the west end of GF to the room on F2: walk to Stairs 1, ride up 2 floors, walk to the room. */
    private fun routeToF2(from: MetricPoint = MetricPoint(3.0, 2.0), fromFloor: String = "GF"): FloorRouteUi {
        val result = routeToPlace(router, floors.keys, graphs, fromFloor, from, "POI-F2", "F2", stepFree = false)
        return FloorRouteUi(assertIs<MultiFloorRouteResult.Found>(result).route, "POI-F2", "F2", "Lab 2", names)
    }

    @Test fun liveRoutesNeverBorrowAnotherFloorsGraph() {
        val start = graphs.getValue("GF")
        val partial = mapOf("GF" to start, "F2" to graphs.getValue("F2"))
        // Multi-floor: only the tracked floor's own graph; a floor without one gets the "no saved paths" status.
        assertEquals(graphs.getValue("F2"), liveRouteGraph(true, "F2", partial, start, null))
        assertNull(liveRouteGraph(true, "F1", partial, start, start))
        assertNull(liveRouteGraph(true, null, partial, start, start))
        assertTrue(NO_FLOOR_PATHS_TEXT.contains("no saved paths"))
        // Single-floor sessions keep the session graph, else the saved one, exactly as before.
        assertEquals(start, liveRouteGraph(false, "F1", partial, start, null))
        assertEquals(graphs.getValue("F1"), liveRouteGraph(false, null, emptyMap(), null, graphs.getValue("F1")))
    }

    @Test fun otherFloorsAreGroupedHighestFirstWithoutTheOpenFloor() {
        val places = listOf(
            BuildingPlace("a", "Lab 1", "GF", "Ground floor", 0, true),
            BuildingPlace("b", "Washroom", "F2", "Second floor", 2, true),
            BuildingPlace("c", "Lab 2", "F2", "Second floor", 2, false),
            BuildingPlace("d", "Lab 3", "F1", "First floor", 1, true),
            BuildingPlace("e", "Office", "F1", "First floor", 1, true),
        )
        val groups = otherFloorPlaceGroups("GF", places) { true }
        assertEquals(listOf("F2", "F1"), groups.map { it.floorId })
        assertEquals(listOf("Lab 2", "Washroom"), groups.first().places.map { it.name })
        // A search drops floors without a match; the open floor is never a group.
        assertEquals(listOf("F2", "F1"), otherFloorPlaceGroups("GF", places) { it.name.startsWith("Lab") }.map { it.floorId })
        assertEquals(listOf("F1"), otherFloorPlaceGroups("GF", places) { it.name == "Office" }.map { it.floorId })
        // A one-floor building lists nothing extra, so its search is unchanged.
        assertTrue(otherFloorPlaceGroups("GF", places.filter { it.floorId == "GF" }) { true }.isEmpty())
    }

    @Test fun legFollowsTheConfirmedFloor() {
        val route = routeToF2()
        val ground = assertNotNull(floorLeg(route, "GF"))
        assertEquals("Stairs 1", ground.target)
        assertEquals("Go up 2 floors to Second floor by Stairs 1", ground.instruction)
        assertEquals(ConnectorKind.STAIRS, ground.connector?.kind)
        assertEquals(22.0, ground.walkMetres, 0.5)
        assertTrue(ground.points.size >= 2)
        // F1 is only passed through on the merged ride: nothing to show there.
        assertNull(floorLeg(route, "F1"))
        val top = assertNotNull(floorLeg(route, "F2"))
        assertNull(top.connector)
        assertEquals("Lab 2", top.target)
        assertTrue(top.instruction.startsWith("Walk ") && top.instruction.endsWith("to Lab 2"))
        assertNull(floorLeg(route, null))
    }

    @Test fun legRowsMarkTheShownFloor() {
        val route = routeToF2()
        val onGround = floorLegRows(route, "GF")
        assertEquals(3, onGround.size)
        assertEquals(listOf(true, false, false), onGround.map { it.current })
        assertEquals("Go up 2 floors to Second floor by Stairs 1", onGround[1].text)
        assertEquals(listOf(false, false, true), floorLegRows(route, "F2").map { it.current })
        assertEquals(listOf(false, false, false), floorLegRows(route, "F1").map { it.current })
        // Re-planned from F2 after the confirmed floor changed: a single walk leg is current.
        val replanned = FloorRouteUi(assertIs<MultiFloorRouteResult.Found>(
            router.route("F2", MetricPoint(25.0, 2.0), "F2", "F2-room")).route, "POI-F2", "F2", "Lab 2", names)
        assertEquals(listOf(true), floorLegRows(replanned, "F2").map { it.current })
    }

    @Test fun bannerAppearsNearTheLinkOnly() {
        val ground = floorLeg(routeToF2(), "GF")
        assertNull(connectorBannerText(ground, MetricPoint(3.0, 2.0)))
        assertEquals("Go up 2 floors to Second floor by Stairs 1", connectorBannerText(ground, MetricPoint(21.0, 2.0)))
        // Close by the remaining walk too, e.g. when re-planned from a point near the stairs.
        val near = floorLeg(routeToF2(MetricPoint(22.0, 2.0)), "GF")
        assertNotNull(connectorBannerText(near, null))
        // The destination floor has no link to announce.
        assertNull(connectorBannerText(floorLeg(routeToF2(), "F2"), MetricPoint(27.0, 2.0)))
        assertNull(connectorBannerText(null, MetricPoint(25.0, 2.0)))
    }

    @Test fun routeToPlaceReportsWhyItCannotRoute() {
        fun reason(r: MultiFloorRouteResult) = assertIs<MultiFloorRouteResult.Unreachable>(r).reason
        val start = MetricPoint(3.0, 2.0)
        assertEquals(RouteFailure.NO_FLOOR_LINK, reason(routeToPlace(null, floors.keys, graphs, "GF", start, "POI-F2", "F2", false)))
        assertEquals(RouteFailure.NO_FLOOR_LINK, reason(routeToPlace(router, setOf("GF", "F1"), graphs, "GF", start, "POI-F2", "F2", false)))
        assertEquals(RouteFailure.NO_PATH_GRAPH, reason(routeToPlace(router, floors.keys, graphs - "F2", "GF", start, "POI-F2", "F2", false)))
        assertEquals(RouteFailure.DESTINATION_UNKNOWN, reason(routeToPlace(router, floors.keys, graphs, "GF", start, "nowhere", "F2", false)))
        // Stairs only: step-free has no way up.
        assertEquals(RouteFailure.NO_ROUTE, reason(routeToPlace(router, floors.keys, graphs, "GF", start, "POI-F2", "F2", true)))
    }

    @Test fun failureAndStatusText() {
        assertEquals("No step-free way to Second floor", routeFailureText(RouteFailure.NO_ROUTE, "Lab 2", "Second floor", true, false))
        assertEquals("No route to Second floor · check its floor links and paths",
            routeFailureText(RouteFailure.NO_ROUTE, "Lab 2", "Second floor", false, false))
        assertEquals("No checked floor link reaches Second floor · allow unchecked links in Map options",
            routeFailureText(RouteFailure.NO_FLOOR_LINK, "Lab 2", "Second floor", false, false))
        assertEquals("No floor link reaches Second floor · add one in Floor links",
            routeFailureText(RouteFailure.NO_FLOOR_LINK, "Lab 2", "Second floor", false, true))
        RouteFailure.entries.forEach { assertTrue(routeFailureText(it, "Lab 2", "Second floor", false, false).isNotBlank()) }
        val status = crossFloorRouteStatus(routeToF2())
        assertTrue(status.startsWith("Walk 22 m to Stairs 1 · About "), status)
        assertTrue(status.endsWith("m walking"), status)
    }
}
