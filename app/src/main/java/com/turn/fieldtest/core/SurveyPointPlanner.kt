package com.turn.fieldtest.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor

enum class SurveyPointReason { JUNCTION, ENTRANCE, CORRIDOR, OPEN_AREA }

data class SuggestedSurveyPoint(val position: MetricPoint, val reason: SurveyPointReason)

/**
 * Proposes Wi-Fi survey locations where people will actually walk: junctions and turns first, then evenly
 * spaced points along paths, then path ends and room entrances, then open walkable space paths do not reach.
 * Every proposal lies in walkable space, so a match averaged from neighbouring points stays usable for correction.
 */
object SurveyPointPlanner {
    fun suggest(
        floor: MapFloor,
        nodes: List<NavigationNode>,
        edges: List<NavigationEdge>,
        existing: List<MetricPoint>,
        spacingMetres: Double = 3.0,
        maxPoints: Int = 150,
    ): List<SuggestedSurveyPoint> {
        require(spacingMetres.isFinite() && spacingMetres >= 1.0) { "Spacing must be at least 1 m" }
        // Points closer than the spacing are not distinguishable by Wi-Fi and only lengthen the survey.
        val minimumGap = spacingMetres
        val taken = existing.toMutableList()
        val result = mutableListOf<SuggestedSurveyPoint>()
        fun offer(point: MetricPoint, reason: SurveyPointReason, gap: Double = minimumGap) {
            if (result.size >= maxPoints || !floor.isWalkable(point)) return
            if (taken.any { it.distanceTo(point) < gap }) return
            taken += point; result += SuggestedSurveyPoint(point, reason)
        }

        val byId = nodes.filter { it.floorId == floor.id }.associateBy { it.id }
        val usable = edges.filter { it.walkable && byId[it.from] != null && byId[it.to] != null }
        val neighbours = mutableMapOf<String, MutableList<String>>()
        usable.forEach {
            neighbours.getOrPut(it.from) { mutableListOf() } += it.to
            neighbours.getOrPut(it.to) { mutableListOf() } += it.from
        }
        fun isTurn(id: String): Boolean {
            val links = neighbours[id].orEmpty()
            if (links.size != 2) return false
            val here = byId.getValue(id).point
            val a = byId.getValue(links[0]).point; val b = byId.getValue(links[1]).point
            val first = atan2(here.y - a.y, here.x - a.x); val second = atan2(b.y - here.y, b.x - here.x)
            return abs(normalizeRadians(second - first)) > Math.toRadians(30.0)
        }
        val degree = { id: String -> neighbours[id].orEmpty().size }
        // Order matters: earlier points claim their surroundings. Junctions and turns sit on the path's centre
        // line, so the line is laid first; doors and dead ends near a wall only fill what the line leaves.
        byId.values.filter { it.poiId == null && (degree(it.id) >= 3 || isTurn(it.id)) }
            .sortedBy { it.id }.forEach { offer(it.point, SurveyPointReason.JUNCTION) }
        usable.sortedBy { it.id }.forEach { edge ->
            val a = byId.getValue(edge.from).point; val b = byId.getValue(edge.to).point
            // Never sample more finely than the spacing, or neighbouring samples reject each other.
            val steps = floor(a.distanceTo(b) / spacingMetres).toInt()
            for (i in 1 until steps) {
                val t = i.toDouble() / steps
                offer(MetricPoint(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t), SurveyPointReason.CORRIDOR)
            }
        }
        byId.values.filter { it.poiId == null && degree(it.id) == 1 }.sortedBy { it.id }.forEach { offer(it.point, SurveyPointReason.JUNCTION) }
        byId.values.filter { it.poiId != null }.sortedBy { it.id }.forEach { offer(it.point, SurveyPointReason.ENTRANCE) }
        // Open areas: a coarse grid, kept a full spacing away from path coverage so rooms are not over-sampled.
        val vertices = floor.walkableRegions.flatMap { it.vertices }
        if (vertices.isNotEmpty()) {
            val minX = vertices.minOf { it.x }; val maxX = vertices.maxOf { it.x }
            val minY = vertices.minOf { it.y }; val maxY = vertices.maxOf { it.y }
            var y = floor(minY / spacingMetres) * spacingMetres + spacingMetres / 2
            while (y < maxY) {
                var x = floor(minX / spacingMetres) * spacingMetres + spacingMetres / 2
                while (x < maxX) { offer(MetricPoint(x, y), SurveyPointReason.OPEN_AREA, spacingMetres); x += spacingMetres }
                y += spacingMetres
            }
        }
        return result
    }
}
