package com.turn.fieldtest.core

import java.util.PriorityQueue

data class NavigationNode(val id: String, val floorId: String, val point: MetricPoint, val poiId: String? = null)
data class NavigationEdge(val id: String, val from: String, val to: String, val distanceMetres: Double,
    val walkable: Boolean = true, val bidirectional: Boolean = true, val accessible: Boolean = true)
data class NavigationRoute(val points: List<MetricPoint>, val distanceMetres: Double, val nodeIds: List<String>)

/** Metric A* with a bounded, wall-checked attachment to a mapped path. No blue-dot snapping. */
class NavigationGraph(val floor: MapFloor, val nodes: List<NavigationNode>, val edges: List<NavigationEdge>) {
    private val byId = nodes.associateBy { it.id }
    fun validationErrors(): List<String> = buildList {
        if (byId.size != nodes.size) add("Duplicate navigation node IDs")
        if (edges.map { it.id }.distinct().size != edges.size) add("Duplicate path IDs")
        nodes.forEach {
            if (it.floorId != floor.id || !floor.isWalkable(it.point)) add("Node ${it.id} is outside this floor's walkable area")
        }
        edges.forEach { edge ->
            val a = byId[edge.from]; val b = byId[edge.to]
            if (a == null || b == null || a.id == b.id) add("Path ${edge.id} needs two different nodes")
            else if (!edge.distanceMetres.isFinite() || edge.distanceMetres <= 0.0 ||
                kotlin.math.abs(edge.distanceMetres - a.point.distanceTo(b.point)) > 0.01) add("Path ${edge.id} has an invalid metric distance")
            else if (edge.walkable && !allows(a.point, b.point)) add("Path ${edge.id} crosses a wall or leaves walkable space")
        }
    }
    private fun allows(a: MetricPoint, b: MetricPoint) = floor.isPathInsideWalkableSpace(a, b) && !floor.crossesWall(a, b)

    fun route(start: MetricPoint, destinationNodeId: String, accessibleOnly: Boolean = false,
        maxAttachmentMetres: Double = 3.0): NavigationRoute? {
        require(maxAttachmentMetres.isFinite() && maxAttachmentMetres >= 0)
        if (validationErrors().isNotEmpty() || !floor.isWalkable(start)) return null
        val target = byId[destinationNodeId] ?: return null
        val usable = edges.filter { it.walkable && (!accessibleOnly || it.accessible) }
        data class Attachment(val point: MetricPoint, val starts: List<Pair<String, Double>>)
        val attachments = usable.mapNotNull { edge ->
            val a = byId.getValue(edge.from).point; val b = byId.getValue(edge.to).point
            val p = projectOnSegment(start, a, b)
            if (start.distanceTo(p) > maxAttachmentMetres || !allows(start, p)) null
            else Attachment(p, buildList {
                add(edge.to to p.distanceTo(b))
                if (edge.bidirectional || p.distanceTo(a) < 1e-6) add(edge.from to p.distanceTo(a))
            })
        } + nodes.mapNotNull { node ->
            if (node.point.distanceTo(start) <= maxAttachmentMetres && allows(start, node.point))
                Attachment(node.point, listOf(node.id to 0.0)) else null
        }
        // Prefer the nearest physical path/node; don't jump to a distant component to manufacture a route.
        val attachment = attachments.minByOrNull { it.point.distanceTo(start) } ?: return null
        data class Entry(val id: String, val g: Double, val f: Double)
        val queue = PriorityQueue<Entry>(compareBy<Entry> { it.f }.thenBy { it.id })
        val cost = mutableMapOf<String, Double>(); val parent = mutableMapOf<String, String>()
        attachment.starts.forEach { (id, along) ->
            val g = start.distanceTo(attachment.point) + along
            if (g < (cost[id] ?: Double.POSITIVE_INFINITY)) {
                cost[id] = g; queue.add(Entry(id, g, g + byId.getValue(id).point.distanceTo(target.point)))
            }
        }
        val adjacency = mutableMapOf<String, MutableList<Pair<String, Double>>>()
        usable.forEach {
            adjacency.getOrPut(it.from) { mutableListOf() }.add(it.to to it.distanceMetres)
            if (it.bidirectional) adjacency.getOrPut(it.to) { mutableListOf() }.add(it.from to it.distanceMetres)
        }
        while (queue.isNotEmpty()) {
            val current = queue.remove()
            if (current.g > (cost[current.id] ?: Double.POSITIVE_INFINITY)) continue
            if (current.id == target.id) {
                val ids = mutableListOf(current.id)
                while (parent[ids.last()] != null) ids.add(parent.getValue(ids.last()))
                ids.reverse()
                val points = (listOf(start, attachment.point) + ids.map { byId.getValue(it).point })
                    .fold(mutableListOf<MetricPoint>()) { result, p ->
                        if (result.lastOrNull()?.distanceTo(p)?.let { it > 1e-6 } != false) result.add(p)
                        result
                    }
                return NavigationRoute(points, points.zipWithNext().sumOf { (a, b) -> a.distanceTo(b) }, ids)
            }
            adjacency[current.id].orEmpty().forEach { (next, weight) ->
                val g = current.g + weight
                if (g < (cost[next] ?: Double.POSITIVE_INFINITY)) {
                    cost[next] = g; parent[next] = current.id
                    queue.add(Entry(next, g, g + byId.getValue(next).point.distanceTo(target.point)))
                }
            }
        }
        return null
    }
}

fun projectOnSegment(p: MetricPoint, a: MetricPoint, b: MetricPoint): MetricPoint {
    val dx = b.x - a.x; val dy = b.y - a.y
    val lengthSquared = dx * dx + dy * dy
    val t = if (lengthSquared == 0.0) 0.0 else (((p.x - a.x) * dx + (p.y - a.y) * dy) / lengthSquared).coerceIn(0.0, 1.0)
    return MetricPoint(a.x + t * dx, a.y + t * dy)
}
