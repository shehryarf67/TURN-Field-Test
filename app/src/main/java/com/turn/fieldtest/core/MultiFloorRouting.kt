package com.turn.fieldtest.core

import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/** One part of a route: a walk on one floor, or a ride between floors. */
sealed interface RouteLeg {
    val seconds: Double
}

/**
 * A walk on [floorId] along [points] (in that floor's frame). [toConnector] names the link it walks to, or is null
 * for the last leg, which ends at the destination.
 */
data class WalkLeg(
    val floorId: String, val points: List<MetricPoint>, val distanceMetres: Double, override val seconds: Double,
    val toConnector: String?,
) : RouteLeg

/**
 * A ride from [fromFloorId] to [toFloorId] by one or more chained links of the same name and kind (e.g. Stairs 1
 * GF→F1→F2). [entry] is on the first floor and [exit] on the last, each in its own frame.
 */
data class ConnectorLeg(
    val portals: List<FloorPortal>, val kind: ConnectorKind, val label: String,
    val fromFloorId: String, val toFloorId: String, val levelDelta: Int,
    val entry: MetricPoint, val exit: MetricPoint, override val seconds: Double,
) : RouteLeg

/**
 * A route as floor legs. [singleFloor] is the existing router's own result when start and destination share a
 * floor (then [legs] holds one [WalkLeg] with the same points); it is null for a cross-floor route.
 */
data class MultiFloorRoute(
    val legs: List<RouteLeg>, val walkMetres: Double, val seconds: Double, val singleFloor: NavigationRoute? = null,
) {
    val crossesFloors: Boolean get() = legs.any { it is ConnectorLeg }

    /** The route line to draw on [floorId]: every walk leg on that floor, in order. */
    fun pointsOn(floorId: String): List<List<MetricPoint>> =
        legs.filterIsInstance<WalkLeg>().filter { it.floorId == floorId && it.points.size >= 2 }.map { it.points }

    /** The ride that starts on [floorId], i.e. the link to highlight and announce there, if any. */
    fun connectorFrom(floorId: String): ConnectorLeg? =
        legs.filterIsInstance<ConnectorLeg>().firstOrNull { it.fromFloorId == floorId }

    /** Walking metres left on [floorId] before its connector or the destination. */
    fun walkMetresOn(floorId: String): Double =
        legs.filterIsInstance<WalkLeg>().filter { it.floorId == floorId }.sumOf { it.distanceMetres }
}

enum class RouteFailure { START_OFF_MAP, DESTINATION_UNKNOWN, NO_PATH_GRAPH, NO_FLOOR_LINK, NO_ROUTE }

sealed interface MultiFloorRouteResult {
    data class Found(val route: MultiFloorRoute) : MultiFloorRouteResult
    data class Unreachable(val reason: RouteFailure) : MultiFloorRouteResult
}

/**
 * Routes across floors: each floor's path graph plus the session's directed floor links ([FloorTopology], which
 * already applies "enabled" and "Use unchecked floor links"). Costs are seconds: walking metres at
 * [WALKING_SPEED_MPS], links at their saved travel time (already for the whole level span of the link).
 *
 * When start and destination share a floor the call is delegated to [NavigationGraph.route] unchanged, so
 * single-floor routes stay exactly as before.
 */
class MultiFloorRouter(
    private val graphs: Map<String, NavigationGraph>,
    private val topology: FloorTopology,
    private val maxAttachmentMetres: Double = 3.0,
    /** Every floor's walkable map, so a floor without a path graph can still pass people between two links. */
    private val floors: Map<String, MapFloor> = graphs.mapValues { it.value.floor },
) {
    fun route(
        startFloorId: String, start: MetricPoint, destinationFloorId: String, destinationNodeId: String,
        stepFreeOnly: Boolean = false,
    ): MultiFloorRouteResult {
        if (startFloorId == destinationFloorId) {
            val graph = graphs[startFloorId] ?: return MultiFloorRouteResult.Unreachable(RouteFailure.NO_PATH_GRAPH)
            val route = graph.route(start, destinationNodeId, stepFreeOnly, maxAttachmentMetres)
                ?: return MultiFloorRouteResult.Unreachable(RouteFailure.NO_ROUTE)
            val seconds = route.distanceMetres / WALKING_SPEED_MPS
            return MultiFloorRouteResult.Found(MultiFloorRoute(
                listOf(WalkLeg(startFloorId, route.points, route.distanceMetres, seconds, null)),
                route.distanceMetres, seconds, route))
        }
        return CrossFloorSearch(start, startFloorId, destinationFloorId, destinationNodeId, stepFreeOnly).run()
    }

    private fun usableGraph(floorId: String): NavigationGraph? =
        graphs[floorId]?.takeIf { it.floor.id == floorId && it.validationErrors().isEmpty() }

    /** A walk step: the polyline it adds and its length. */
    private class Step(val parent: String, val points: List<MetricPoint>, val floorId: String?, val portal: FloorPortal?) {
        val metres: Double = points.zipWithNext().sumOf { (a, b) -> a.distanceTo(b) }
    }

    /** Where a free point joins a floor's path graph: onto [edge] at [point], or onto [nodeId] itself. */
    private class Attachment(val point: MetricPoint, val edge: NavigationEdge?, val nodeId: String?, val offsetMetres: Double)

    private inner class CrossFloorSearch(
        val start: MetricPoint, val startFloorId: String, val destinationFloorId: String,
        val destinationNodeId: String, val stepFreeOnly: Boolean,
    ) {
        private val adjacency = mutableMapOf<String, MutableList<Pair<String, Step>>>()
        private fun link(from: String, to: String, step: Step, cost: Double) {
            adjacency.getOrPut(from) { mutableListOf() } += to to step
            costs[from to to] = cost
        }
        private val costs = mutableMapOf<Pair<String, String>, Double>()

        fun run(): MultiFloorRouteResult {
            val startGraph = graphs[startFloorId]
            val startFloor = startGraph?.floor
            if (startFloor == null) return MultiFloorRouteResult.Unreachable(RouteFailure.NO_PATH_GRAPH)
            if (!startFloor.isWalkable(start)) return MultiFloorRouteResult.Unreachable(RouteFailure.START_OFF_MAP)
            val destinationGraph = usableGraph(destinationFloorId)
                ?: return MultiFloorRouteResult.Unreachable(RouteFailure.NO_PATH_GRAPH)
            val destination = destinationGraph.nodes.firstOrNull { it.id == destinationNodeId }
                ?: return MultiFloorRouteResult.Unreachable(RouteFailure.DESTINATION_UNKNOWN)
            if (startFloorId !in topology.levels || destinationFloorId !in topology.levels)
                return MultiFloorRouteResult.Unreachable(RouteFailure.NO_FLOOR_LINK)
            val portals = topology.portals.filter { !stepFreeOnly || (it.accessible && it.kind.stepFree) }
            build(portals)
            return search(nodeKey(destinationFloorId, destination.id))
                ?.let { MultiFloorRouteResult.Found(it) } ?: MultiFloorRouteResult.Unreachable(RouteFailure.NO_ROUTE)
        }

        private fun build(portals: List<FloorPortal>) {
            portals.forEach { p -> link(entryKey(p), exitKey(p), Step(entryKey(p), listOf(p.fromCentre), null, p), p.travelTimeSeconds) }
            val floorIds = (portals.flatMap { listOf(it.fromFloorId, it.toFloorId) } + startFloorId + destinationFloorId).toSet()
            floorIds.forEach { floorId ->
                val graph = usableGraph(floorId)
                val floor = floors[floorId] ?: graphs[floorId]?.floor
                // Sources leave a point (the start, a link's arrival); sinks reach one (a link's entry).
                val sources = buildList {
                    if (floorId == startFloorId) add(START to start)
                    portals.filter { it.toFloorId == floorId }.forEach { add(exitKey(it) to it.toCentre) }
                }
                val sinks = portals.filter { it.fromFloorId == floorId }.map { entryKey(it) to it.fromCentre }
                val attachments = if (graph == null) emptyMap() else (sources + sinks).mapNotNull { (key, point) ->
                    attach(graph, point)?.let { key to it }
                }.toMap()
                if (graph != null) addGraphEdges(floorId, graph, sources, sinks, attachments)
                if (floor != null) sources.forEach { (s, sp) ->
                    sinks.forEach { (t, tp) -> directTransfer(floorId, floor, s, sp, attachments[s], t, tp, attachments[t]) }
                }
            }
        }

        private fun addGraphEdges(
            floorId: String, graph: NavigationGraph, sources: List<Pair<String, MetricPoint>>,
            sinks: List<Pair<String, MetricPoint>>, attachments: Map<String, Attachment>,
        ) {
            val byId = graph.nodes.associateBy { it.id }
            usable(graph).forEach { e ->
                val a = byId.getValue(e.from).point; val b = byId.getValue(e.to).point
                walk(nodeKey(floorId, e.from), nodeKey(floorId, e.to), floorId, listOf(a, b))
                if (e.bidirectional) walk(nodeKey(floorId, e.to), nodeKey(floorId, e.from), floorId, listOf(b, a))
            }
            sources.forEach { (key, point) ->
                val at = attachments[key] ?: return@forEach
                if (at.nodeId != null) walk(key, nodeKey(floorId, at.nodeId), floorId, listOf(point, at.point))
                else {
                    val e = requireNotNull(at.edge)
                    val a = byId.getValue(e.from).point; val b = byId.getValue(e.to).point
                    walk(key, nodeKey(floorId, e.to), floorId, listOf(point, at.point, b))
                    if (e.bidirectional || at.point.distanceTo(a) < 1e-6) walk(key, nodeKey(floorId, e.from), floorId, listOf(point, at.point, a))
                }
            }
            sinks.forEach { (key, point) ->
                val at = attachments[key] ?: return@forEach
                if (at.nodeId != null) walk(nodeKey(floorId, at.nodeId), key, floorId, listOf(at.point, point))
                else {
                    val e = requireNotNull(at.edge)
                    val a = byId.getValue(e.from).point; val b = byId.getValue(e.to).point
                    walk(nodeKey(floorId, e.from), key, floorId, listOf(a, at.point, point))
                    if (e.bidirectional || at.point.distanceTo(b) < 1e-6) walk(nodeKey(floorId, e.to), key, floorId, listOf(b, at.point, point))
                }
            }
        }

        /**
         * A source and a sink joined without passing a graph node: along the same mapped path (respecting one-way
         * paths), or, from a link arrival only, straight across a short wall-free gap such as a stair landing.
         */
        private fun directTransfer(
            floorId: String, floor: MapFloor, s: String, sp: MetricPoint, sa: Attachment?,
            t: String, tp: MetricPoint, ta: Attachment?,
        ) {
            if (sa?.edge != null && ta?.edge != null && sa.edge.id == ta.edge.id) {
                val e = sa.edge
                val forward = alongEdge(floorId, e, sa.point) <= alongEdge(floorId, e, ta.point) + 1e-9
                if (forward || e.bidirectional) walk(s, t, floorId, listOf(sp, sa.point, ta.point, tp))
            }
            if (s != START && sp.distanceTo(tp) <= LANDING_TRANSFER_METRES && floor.isPathInsideWalkableSpace(sp, tp) && !floor.crossesWall(sp, tp))
                walk(s, t, floorId, listOf(sp, tp))
        }

        private fun alongEdge(floorId: String, e: NavigationEdge, p: MetricPoint): Double =
            graphs.getValue(floorId).nodes.first { it.id == e.from }.point.distanceTo(p)

        private fun walk(from: String, to: String, floorId: String, points: List<MetricPoint>) {
            val step = Step(from, points, floorId, null)
            val cost = step.metres / WALKING_SPEED_MPS
            if (cost < (costs[from to to] ?: Double.POSITIVE_INFINITY)) {
                adjacency[from]?.removeAll { it.first == to }
                link(from, to, step, cost)
            }
        }

        private fun usable(graph: NavigationGraph) = graph.edges.filter { it.walkable && (!stepFreeOnly || it.accessible) }

        /** The nearest wall-free attachment within reach, as [NavigationGraph.route] attaches its start. */
        private fun attach(graph: NavigationGraph, point: MetricPoint): Attachment? {
            val floor = graph.floor
            fun allows(a: MetricPoint, b: MetricPoint) = floor.isPathInsideWalkableSpace(a, b) && !floor.crossesWall(a, b)
            val byId = graph.nodes.associateBy { it.id }
            val onEdges = usable(graph).mapNotNull { e ->
                val p = projectOnSegment(point, byId.getValue(e.from).point, byId.getValue(e.to).point)
                if (point.distanceTo(p) > maxAttachmentMetres || !allows(point, p)) null else Attachment(p, e, null, point.distanceTo(p))
            }
            val onNodes = graph.nodes.mapNotNull { n ->
                if (n.point.distanceTo(point) <= maxAttachmentMetres && allows(point, n.point))
                    Attachment(n.point, null, n.id, point.distanceTo(n.point)) else null
            }
            return (onEdges + onNodes).minByOrNull { it.offsetMetres }
        }

        private fun search(target: String): MultiFloorRoute? {
            data class Entry(val key: String, val g: Double)
            val queue = PriorityQueue<Entry>(compareBy<Entry> { it.g }.thenBy { it.key })
            val best = mutableMapOf(START to 0.0)
            val via = mutableMapOf<String, Step>()
            queue += Entry(START, 0.0)
            while (queue.isNotEmpty()) {
                val current = queue.remove()
                if (current.g > (best[current.key] ?: Double.POSITIVE_INFINITY)) continue
                if (current.key == target) return assemble(target, via, current.g)
                adjacency[current.key].orEmpty().forEach { (next, step) ->
                    val g = current.g + costs.getValue(current.key to next)
                    if (g < (best[next] ?: Double.POSITIVE_INFINITY)) {
                        best[next] = g; via[next] = step; queue += Entry(next, g)
                    }
                }
            }
            return null
        }

        private fun assemble(target: String, via: Map<String, Step>, seconds: Double): MultiFloorRoute {
            val steps = generateSequence(via[target]) { via[it.parent] }.toList().reversed()
            val legs = mutableListOf<RouteLeg>()
            var floorId = startFloorId
            val points = mutableListOf<MetricPoint>()
            var walkMetres = 0.0
            fun closeWalk(toConnector: String?) {
                val clean = points.fold(mutableListOf<MetricPoint>()) { out, p ->
                    if (out.lastOrNull()?.distanceTo(p)?.let { it > 1e-6 } != false) out += p
                    out
                }
                val metres = clean.zipWithNext().sumOf { (a, b) -> a.distanceTo(b) }
                legs += WalkLeg(floorId, clean, metres, metres / WALKING_SPEED_MPS, toConnector)
                walkMetres += metres
                points.clear()
            }
            steps.forEach { step ->
                val portal = step.portal
                if (portal == null) points += step.points
                else {
                    if (points.isEmpty()) points += portal.fromCentre
                    closeWalk(portal.label)
                    legs += ConnectorLeg(listOf(portal), portal.kind, portal.label, portal.fromFloorId, portal.toFloorId,
                        portal.levelDelta, portal.fromCentre, portal.toCentre, portal.travelTimeSeconds)
                    floorId = portal.toFloorId
                    points += portal.toCentre
                }
            }
            closeWalk(null)
            return MultiFloorRoute(mergeChainedRides(legs), walkMetres, seconds)
        }
    }

    companion object {
        /** Typical indoor walking speed, used to compare walking with stairs and lifts. */
        const val WALKING_SPEED_MPS = 1.25
        /** Longest straight, wall-free hop between two link points on a floor without using its path graph. */
        const val LANDING_TRANSFER_METRES = 5.0
        private const val START = "start"
        private fun nodeKey(floorId: String, nodeId: String) = "n|$floorId|$nodeId"
        private fun entryKey(p: FloorPortal) = "in|${p.index}"
        private fun exitKey(p: FloorPortal) = "out|${p.index}"

        /**
         * Joins ride → short walk → ride when both rides use the same link name and kind in the same direction,
         * e.g. Stairs 1 GF→F1 then Stairs 1 F1→F2 becomes "up 2 floors by Stairs 1". The landing walk's time is kept.
         */
        fun mergeChainedRides(legs: List<RouteLeg>): List<RouteLeg> {
            val out = mutableListOf<RouteLeg>()
            legs.forEach { leg ->
                val walk = out.getOrNull(out.size - 1) as? WalkLeg
                val ride = out.getOrNull(out.size - 2) as? ConnectorLeg
                if (leg is ConnectorLeg && walk != null && ride != null && walk.distanceMetres <= LANDING_TRANSFER_METRES &&
                    ride.kind == leg.kind && ride.label.equals(leg.label, ignoreCase = true) &&
                    ride.levelDelta * leg.levelDelta > 0
                ) {
                    out.removeAt(out.size - 1); out.removeAt(out.size - 1)
                    out += ride.copy(portals = ride.portals + leg.portals, toFloorId = leg.toFloorId,
                        levelDelta = ride.levelDelta + leg.levelDelta, exit = leg.exit,
                        seconds = ride.seconds + walk.seconds + leg.seconds)
                } else out += leg
            }
            return out
        }
    }
}

/** Plain instruction text for floor legs, e.g. "Walk 18 m to Stairs 1", "Go up 2 floors to C-F4 by Stairs 1". */
object RouteLegText {
    fun instruction(leg: RouteLeg, floorNames: Map<String, String>, destinationName: String): String = when (leg) {
        is WalkLeg -> {
            val metres = leg.distanceMetres.roundToInt()
            val target = leg.toConnector ?: destinationName
            when {
                metres >= 1 -> "Walk $metres m to $target"
                leg.toConnector != null -> "Go to $target"
                else -> "Arrive at $target"
            }
        }
        is ConnectorLeg -> {
            val n = abs(leg.levelDelta)
            val direction = if (leg.levelDelta > 0) "up" else "down"
            val floors = if (n == 1) "1 floor" else "$n floors"
            "Go $direction $floors to ${floorNames[leg.toFloorId] ?: leg.toFloorId} by ${connectorName(leg)}"
        }
    }

    fun instructions(route: MultiFloorRoute, floorNames: Map<String, String>, destinationName: String): List<String> =
        route.legs.mapIndexedNotNull { i, leg ->
            // A zero-length walk to a link is folded into the ride that follows it.
            if (leg is WalkLeg && leg.toConnector != null && leg.distanceMetres < 1.0 && i + 1 < route.legs.size) null
            else instruction(leg, floorNames, destinationName)
        }

    /** "About 3 min · 45 m walking". */
    fun summary(route: MultiFloorRoute): String {
        val minutes = ceil(route.seconds / 60.0).toInt().coerceAtLeast(1)
        return "About $minutes min · ${route.walkMetres.roundToInt()} m walking"
    }

    /** The link's name, with its kind added when the name does not say it, e.g. "Main (lift)". */
    fun connectorName(leg: ConnectorLeg): String =
        if (leg.label.contains(leg.kind.label, ignoreCase = true)) leg.label else "${leg.label} (${leg.kind.label.lowercase()})"
}
