package com.turn.fieldtest.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NavigationTest {
    private val floor = MapFloor("f", listOf(MetricPolygon(listOf(MetricPoint(0.0, 0.0), MetricPoint(30.0, 0.0), MetricPoint(30.0, 20.0), MetricPoint(0.0, 20.0)))))
    private fun node(id: String, x: Double, y: Double) = NavigationNode(id, "f", MetricPoint(x, y))
    private fun edge(a: NavigationNode, b: NavigationNode, twoWay: Boolean = true, accessible: Boolean = true) =
        NavigationEdge(a.id + b.id, a.id, b.id, a.point.distanceTo(b.point), bidirectional = twoWay, accessible = accessible)

    @Test fun routesThroughTurnsAndReportsMetricLength() {
        val a = node("a", 2.0, 2.0); val b = node("b", 12.0, 2.0); val c = node("c", 12.0, 12.0)
        val route = NavigationGraph(floor, listOf(a, b, c), listOf(edge(a, b), edge(b, c))).route(a.point, c.id)!!
        assertEquals(listOf(a.point, b.point, c.point), route.points)
        assertEquals(20.0, route.distanceMetres, 1e-8)
    }
    @Test fun startAttachesToInteriorOfLongCorridorAndDistanceProgresses() {
        val a = node("a", 2.0, 2.0); val b = node("b", 22.0, 2.0)
        val graph = NavigationGraph(floor, listOf(a, b), listOf(edge(a, b)))
        val first = graph.route(MetricPoint(10.0, 3.0), b.id)!!
        assertEquals(13.0, first.distanceMetres, 1e-8)
        assertEquals(MetricPoint(10.0, 3.0), first.points.first())
        assertEquals(7.0, graph.route(MetricPoint(15.0, 2.0), b.id)!!.distanceMetres, 1e-8)
    }
    @Test fun disconnectedDestinationDoesNotInventAConnection() {
        val a = node("a", 2.0, 2.0); val b = node("b", 10.0, 2.0); val c = node("c", 20.0, 12.0)
        assertNull(NavigationGraph(floor, listOf(a, b, c), listOf(edge(a, b))).route(a.point, c.id))
    }
    @Test fun rejectsWallCrossingEvenWhenEndpointsAreWalkable() {
        val a = node("a", 2.0, 2.0); val b = node("b", 12.0, 2.0)
        val wall = WallSegment("wall", MetricPoint(7.0, 0.0), MetricPoint(7.0, 8.0))
        val graph = NavigationGraph(floor.copy(walls = listOf(wall)), listOf(a, b), listOf(edge(a, b)))
        assertTrue(graph.validationErrors().isNotEmpty())
        assertNull(graph.route(a.point, b.id))
    }
    @Test fun startCannotAttachAcrossAWall() {
        val a = node("a", 2.0, 2.0); val b = node("b", 22.0, 2.0)
        val graph = NavigationGraph(floor.copy(walls = listOf(WallSegment("wall", MetricPoint(0.0, 3.0), MetricPoint(25.0, 3.0)))), listOf(a, b), listOf(edge(a, b)))
        assertNull(graph.route(MetricPoint(10.0, 4.0), b.id))
    }
    @Test fun aStarChoosesShorterOfTwoMappedPaths() {
        val a = node("a", 2.0, 2.0); val b = node("b", 10.0, 2.0)
        val c = node("c", 10.0, 10.0); val d = node("d", 2.0, 18.0)
        val graph = NavigationGraph(floor, listOf(a, b, c, d), listOf(edge(a, d), edge(d, c), edge(a, b), edge(b, c)))
        assertEquals(16.0, graph.route(a.point, c.id)!!.distanceMetres, 1e-8)
    }
    @Test fun honorsOneWayAndAccessibilityAttributes() {
        val a = node("a", 2.0, 2.0); val b = node("b", 12.0, 2.0)
        val graph = NavigationGraph(floor, listOf(a, b), listOf(edge(a, b, twoWay = false, accessible = false)))
        assertNull(graph.route(b.point, a.id))
        assertNull(graph.route(a.point, b.id, accessibleOnly = true))
        assertNotNull(graph.route(a.point, b.id))
    }
    @Test fun unknownDestinationAndFarOffPathReturnNoRoute() {
        val a = node("a", 2.0, 2.0); val b = node("b", 12.0, 2.0)
        val graph = NavigationGraph(floor, listOf(a, b), listOf(edge(a, b)))
        assertNull(graph.route(a.point, "unknown"))
        assertNull(graph.route(MetricPoint(5.0, 18.0), b.id))
    }
    @Test fun rejectsCrossFloorNodesAndForgedDistances() {
        val a = node("a", 2.0, 2.0); val b = node("b", 12.0, 2.0)
        assertTrue(NavigationGraph(floor, listOf(a, b.copy(floorId = "other")), listOf(edge(a, b))).validationErrors().isNotEmpty())
        assertTrue(NavigationGraph(floor, listOf(a, b), listOf(edge(a, b).copy(distanceMetres = 1.0))).validationErrors().isNotEmpty())
    }
}
