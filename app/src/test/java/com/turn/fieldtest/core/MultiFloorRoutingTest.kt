package com.turn.fieldtest.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Three stacked 30 × 20 m floors (GF, F1, F2), each with one corridor from (2,2) to (28,2) and a room node at
 * its east end. Stairs sit at x = 5 and a lift at x = 25 on every floor unless a test says otherwise.
 */
class MultiFloorRoutingTest {
    private fun floor(id: String) = MapFloor(id, listOf(MetricPolygon(listOf(
        MetricPoint(0.0, 0.0), MetricPoint(30.0, 0.0), MetricPoint(30.0, 20.0), MetricPoint(0.0, 20.0)))))
    private val floors = listOf("GF", "F1", "F2").associateWith { floor(it) }
    private val levels = mapOf("GF" to 0, "F1" to 1, "F2" to 2)
    private val names = mapOf("GF" to "Ground floor", "F1" to "First floor", "F2" to "Second floor")

    private fun corridor(floorId: String, oneWayEastbound: Boolean = false, stepFree: Boolean = true): NavigationGraph {
        val west = NavigationNode("$floorId-west", floorId, MetricPoint(2.0, 2.0))
        val east = NavigationNode("$floorId-room", floorId, MetricPoint(28.0, 2.0), poiId = "POI-$floorId")
        val edge = NavigationEdge("$floorId-corridor", west.id, east.id, west.point.distanceTo(east.point),
            bidirectional = !oneWayEastbound, accessible = stepFree)
        return NavigationGraph(floors.getValue(floorId), listOf(west, east), listOf(edge))
    }
    private val graphs = floors.keys.associateWith { corridor(it) }

    private fun link(id: String, kind: ConnectorKind, from: String, to: String, x: Double, seconds: Double,
        label: String, bidirectional: Boolean = true, verified: Boolean = true, enabled: Boolean = true,
        accessible: Boolean = kind.stepFree) =
        FloorLinkInput(id, kind, from, MetricPoint(x, 2.0), to, MetricPoint(x, 2.0), 1.5, bidirectional, enabled,
            verified, seconds, label, accessible)

    private val stairsGf = link("s-gf", ConnectorKind.STAIRS, "GF", "F1", 5.0, 15.0, "Stairs 1")
    private val stairsF1 = link("s-f1", ConnectorKind.STAIRS, "F1", "F2", 5.0, 15.0, "Stairs 1")
    private fun lift(seconds: Double = 25.0, from: String = "GF", to: String = "F1") =
        link("lift-$from", ConnectorKind.LIFT, from, to, 25.0, seconds, "Lift A")

    private fun topology(links: List<FloorLinkInput>, start: String = "GF", unchecked: Boolean = false) =
        FloorTopologyBuilder.build(start, floors, levels, links, unchecked).topology

    private fun router(links: List<FloorLinkInput>, g: Map<String, NavigationGraph> = graphs, unchecked: Boolean = false, start: String = "GF") =
        MultiFloorRouter(g, topology(links, start, unchecked), floors = floors)

    private fun found(result: MultiFloorRouteResult) = assertIs<MultiFloorRouteResult.Found>(result).route
    private fun failure(result: MultiFloorRouteResult) = assertIs<MultiFloorRouteResult.Unreachable>(result).reason
    private fun rides(route: MultiFloorRoute) = route.legs.filterIsInstance<ConnectorLeg>()
    private val start = MetricPoint(3.0, 2.0)

    @Test
    fun sameFloorDelegatesToTheExistingRouterUnchanged() {
        val r = router(listOf(stairsGf, lift()))
        listOf(false, true).forEach { stepFree ->
            val expected = graphs.getValue("GF").route(MetricPoint(10.0, 3.0), "GF-room", stepFree)!!
            val route = found(r.route("GF", MetricPoint(10.0, 3.0), "GF", "GF-room", stepFree))
            assertEquals(expected, route.singleFloor)
            assertEquals(1, route.legs.size)
            assertEquals(expected.points, (route.legs.single() as WalkLeg).points)
            assertEquals(expected.distanceMetres, route.walkMetres)
            assertFalse(route.crossesFloors)
        }
        // A same-floor failure stays a failure even though another floor might offer a way round.
        val blocked = mapOf("GF" to corridor("GF", stepFree = false), "F1" to corridor("F1"))
        assertEquals(RouteFailure.NO_ROUTE, failure(router(listOf(stairsGf), blocked).route("GF", start, "GF", "GF-room", true)))
    }

    @Test
    fun picksStairsOrLiftByTotalTime() {
        // Stairs: 2 m + 15 s + 23 m = 35 s. Lift at 25 s: 22 m + 25 s + 3 m = 45 s.
        val viaStairs = found(router(listOf(stairsGf, lift())).route("GF", start, "F1", "F1-room"))
        assertEquals("Stairs 1", rides(viaStairs).single().label)
        assertEquals(35.0, viaStairs.seconds, 1e-6)
        assertEquals(25.0, viaStairs.walkMetres, 1e-6)
        // A 5 s lift: 22 m + 5 s + 3 m = 25 s beats the stairs.
        val viaLift = found(router(listOf(stairsGf, lift(5.0))).route("GF", start, "F1", "F1-room"))
        assertEquals(ConnectorKind.LIFT, rides(viaLift).single().kind)
        assertEquals(25.0, viaLift.seconds, 1e-6)
    }

    @Test
    fun stepFreeUsesOnlyLiftsAndRampsAndStepFreePaths() {
        val escalator = link("esc", ConnectorKind.ESCALATOR, "GF", "F1", 10.0, 12.0, "Escalator", accessible = true)
        val route = found(router(listOf(stairsGf, escalator, lift())).route("GF", start, "F1", "F1-room", stepFreeOnly = true))
        assertEquals(ConnectorKind.LIFT, rides(route).single().kind)
        // Stairs wrongly flagged step-free are still not step-free.
        val flagged = stairsGf.copy(accessible = true)
        assertEquals(RouteFailure.NO_ROUTE, failure(router(listOf(flagged, escalator)).route("GF", start, "F1", "F1-room", true)))
        // A lift whose flag was cleared on site is left out too.
        assertEquals(RouteFailure.NO_ROUTE, failure(router(listOf(lift().copy(accessible = false))).route("GF", start, "F1", "F1-room", true)))
        // Step-free also avoids paths that are not step-free on the floors walked.
        val stepped = graphs + ("F1" to corridor("F1", stepFree = false))
        assertEquals(RouteFailure.NO_ROUTE, failure(router(listOf(lift()), stepped).route("GF", start, "F1", "F1-west", true)))
        assertNotNull(found(router(listOf(lift()), stepped).route("GF", start, "F1", "F1-west")))
    }

    @Test
    fun oneWayEscalatorOnlyCarriesItsDirection() {
        val up = link("esc-up", ConnectorKind.ESCALATOR, "GF", "F1", 10.0, 12.0, "Escalator up", bidirectional = false)
        val upRoute = found(router(listOf(up)).route("GF", start, "F1", "F1-room"))
        assertEquals("Escalator up", rides(upRoute).single().label)
        assertEquals(1, rides(upRoute).single().levelDelta)
        val down = router(listOf(up), start = "F1")
        assertEquals(RouteFailure.NO_ROUTE, failure(down.route("F1", MetricPoint(27.0, 2.0), "GF", "GF-room")))
        val withStairs = found(router(listOf(up, stairsGf), start = "F1").route("F1", MetricPoint(27.0, 2.0), "GF", "GF-room"))
        assertEquals(-1, rides(withStairs).single().levelDelta)
        assertEquals("Stairs 1", rides(withStairs).single().label)
    }

    @Test
    fun oneWayPathsAreRespectedOnEveryFloor() {
        // The F1 corridor runs east only: arriving by the lift at x = 25 the room is still reachable.
        val eastOnly = graphs + ("F1" to corridor("F1", oneWayEastbound = true))
        val r = router(listOf(lift(5.0)), eastOnly)
        val route = found(r.route("GF", start, "F1", "F1-room"))
        assertEquals(ConnectorKind.LIFT, rides(route).single().kind)
        // Back down: from the F1 room the lift lies west against the one-way corridor.
        val back = router(listOf(lift(5.0)), eastOnly, start = "F1")
        assertEquals(RouteFailure.NO_ROUTE, failure(back.route("F1", MetricPoint(27.0, 2.0), "GF", "GF-room")))
    }

    @Test
    fun chainedFloorsMergeOneStaircaseIntoOneRide() {
        val route = found(router(listOf(stairsGf, stairsF1)).route("GF", start, "F2", "F2-room"))
        val ride = rides(route).single()
        assertEquals("GF", ride.fromFloorId); assertEquals("F2", ride.toFloorId); assertEquals(2, ride.levelDelta)
        assertEquals(listOf("s-gf", "s-f1"), ride.portals.map { it.linkId })
        assertEquals(30.0, ride.seconds, 1e-6)
        assertEquals(listOf("Walk 2 m to Stairs 1", "Go up 2 floors to Second floor by Stairs 1", "Walk 23 m to Room 201"),
            RouteLegText.instructions(route, names, "Room 201"))
        // The landing on F1 needs no path graph there.
        val noF1 = found(router(listOf(stairsGf, stairsF1), graphs - "F1").route("GF", start, "F2", "F2-room"))
        assertEquals(2, rides(noF1).single().levelDelta)
    }

    @Test
    fun chainedFloorsByDifferentLinksSplitIntoLegs() {
        val stairs2 = link("s2", ConnectorKind.STAIRS, "F1", "F2", 25.0, 15.0, "Stairs 2")
        val route = found(router(listOf(stairsGf, stairs2)).route("GF", start, "F2", "F2-room"))
        assertEquals(listOf("WalkLeg", "ConnectorLeg", "WalkLeg", "ConnectorLeg", "WalkLeg"), route.legs.map { it::class.simpleName })
        val walks = route.legs.filterIsInstance<WalkLeg>()
        assertEquals(listOf("GF", "F1", "F2"), walks.map { it.floorId })
        assertEquals(listOf(2.0, 20.0, 3.0), walks.map { it.distanceMetres })
        assertEquals(listOf("Stairs 1", "Stairs 2", null), walks.map { it.toConnector })
        assertEquals(25.0 / MultiFloorRouter.WALKING_SPEED_MPS + 30.0, route.seconds, 1e-6)
        assertEquals(route.seconds, route.legs.sumOf { it.seconds }, 1e-6)
        assertEquals(listOf(MetricPoint(5.0, 2.0), MetricPoint(25.0, 2.0)), route.pointsOn("F1").single())
        assertEquals("Stairs 2", route.connectorFrom("F1")?.label)
        assertNull(route.connectorFrom("F2"))
        assertEquals(20.0, route.walkMetresOn("F1"), 1e-6)
        assertEquals(listOf("Walk 2 m to Stairs 1", "Go up 1 floor to First floor by Stairs 1", "Walk 20 m to Stairs 2",
            "Go up 1 floor to Second floor by Stairs 2", "Walk 3 m to Room 201"), RouteLegText.instructions(route, names, "Room 201"))
    }

    @Test
    fun unreachableDestinationsSayWhy() {
        val r = router(listOf(stairsGf))
        assertEquals(RouteFailure.NO_FLOOR_LINK, failure(r.route("GF", start, "F2", "F2-room")))
        assertEquals(RouteFailure.DESTINATION_UNKNOWN, failure(r.route("GF", start, "F1", "nowhere")))
        assertEquals(RouteFailure.START_OFF_MAP, failure(r.route("GF", MetricPoint(40.0, 2.0), "F1", "F1-room")))
        assertEquals(RouteFailure.NO_PATH_GRAPH, failure(router(listOf(stairsGf), graphs - "F1").route("GF", start, "F1", "F1-room")))
        // The F1 room is in a part of F1 the stairs do not reach.
        val island = NavigationGraph(floors.getValue("F1"), listOf(
            NavigationNode("a", "F1", MetricPoint(20.0, 15.0)), NavigationNode("F1-room", "F1", MetricPoint(28.0, 15.0))),
            listOf(NavigationEdge("ab", "a", "F1-room", 8.0)))
        assertEquals(RouteFailure.NO_ROUTE, failure(router(listOf(stairsGf), graphs + ("F1" to island)).route("GF", start, "F1", "F1-room")))
    }

    @Test
    fun disabledAndUncheckedLinksFollowTheOption() {
        val unchecked = stairsGf.copy(verifiedOnSite = false)
        assertEquals(RouteFailure.NO_FLOOR_LINK, failure(router(listOf(unchecked)).route("GF", start, "F1", "F1-room")))
        assertNotNull(found(router(listOf(unchecked), unchecked = true).route("GF", start, "F1", "F1-room")))
        val disabled = stairsGf.copy(enabled = false)
        assertEquals(RouteFailure.NO_FLOOR_LINK, failure(router(listOf(disabled), unchecked = true).route("GF", start, "F1", "F1-room")))
        // The faster lift is unchecked, so the checked stairs are used until unchecked links are allowed.
        val fastLift = lift(1.0).copy(verifiedOnSite = false)
        assertEquals(ConnectorKind.STAIRS, rides(found(router(listOf(stairsGf, fastLift)).route("GF", MetricPoint(20.0, 2.0), "F1", "F1-room"))).single().kind)
        assertEquals(ConnectorKind.LIFT, rides(found(router(listOf(stairsGf, fastLift), unchecked = true).route("GF", MetricPoint(20.0, 2.0), "F1", "F1-room"))).single().kind)
    }

    @Test
    fun legTextAndSummary() {
        val route = found(router(listOf(lift().copy(label = "Main"))).route("GF", start, "F1", "F1-room"))
        val ride = rides(route).single()
        assertEquals("Main (lift)", RouteLegText.connectorName(ride))
        assertEquals("Go up 1 floor to First floor by Main (lift)", RouteLegText.instruction(ride, names, "Room"))
        assertEquals("Go down 1 floor to GF by Main (lift)", RouteLegText.instruction(ride.copy(levelDelta = -1, toFloorId = "GF"), emptyMap(), "Room"))
        assertEquals("Arrive at Room", RouteLegText.instruction(WalkLeg("F1", listOf(start), 0.0, 0.0, null), names, "Room"))
        assertEquals("Go to Lift A", RouteLegText.instruction(WalkLeg("F1", listOf(start), 0.4, 0.3, "Lift A"), names, "Room"))
        // 22 m + 25 s + 3 m = 45 s.
        assertEquals("About 1 min · 25 m walking", RouteLegText.summary(route))
        // A start right at the link drops the empty walk from the instructions.
        val atStairs = found(router(listOf(stairsGf)).route("GF", MetricPoint(5.0, 2.0), "F1", "F1-room"))
        assertEquals("Go up 1 floor to First floor by Stairs 1", RouteLegText.instructions(atStairs, names, "Room").first())
        assertSame(null, atStairs.singleFloor)
        assertTrue(atStairs.crossesFloors)
    }
}
