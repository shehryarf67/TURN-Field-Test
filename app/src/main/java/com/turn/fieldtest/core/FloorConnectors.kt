package com.turn.fieldtest.core

import kotlin.math.abs

/** How people move between floors. Defaults are starting points; measure on site and edit. */
enum class ConnectorKind(val label: String, val stepFree: Boolean, val defaultSecondsPerFloor: Double, val badge: String) {
    STAIRS("Stairs", false, 15.0, "S"),
    LIFT("Lift", true, 25.0, "L"),
    ESCALATOR("Escalator", false, 12.0, "E"),
    RAMP("Ramp", true, 20.0, "R");

    companion object {
        fun parse(value: String): ConnectorKind? = entries.firstOrNull { it.name == value.uppercase() } ?: if (value.uppercase() == "ELEVATOR") LIFT else null
    }
}

/** Plain description of a link between a point on one floor and a point on another. */
data class ConnectorDraft(
    val kind: ConnectorKind,
    val fromFloorId: String, val from: MetricPoint,
    val toFloorId: String, val to: MetricPoint,
    val label: String,
    val travelTimeSeconds: Double,
    val activationRadiusMetres: Double = 2.0,
) {
    /**
     * True when [other] joins the same two floors at (nearly) the same spots. With [allowReversed] a link drawn the
     * other way round also matches; turn it off for one-way links, e.g. an up and a down escalator side by side.
     */
    fun sameEndsAs(other: ConnectorDraft, nearMetres: Double = 2.0, allowReversed: Boolean = true): Boolean =
        (fromFloorId == other.fromFloorId && toFloorId == other.toFloorId && from.distanceTo(other.from) < nearMetres && to.distanceTo(other.to) < nearMetres) ||
            (allowReversed && fromFloorId == other.toFloorId && toFloorId == other.fromFloorId && from.distanceTo(other.to) < nearMetres && to.distanceTo(other.from) < nearMetres)
}

object ConnectorRules {
    /**
     * Problems that make a link unusable for tracking or routing. Both ends must be in walkable space,
     * because floor changes are only allowed for positions inside a connector area.
     */
    fun problems(draft: ConnectorDraft, fromFloor: MapFloor?, toFloor: MapFloor?, fromLevel: Int?, toLevel: Int?): List<String> = buildList {
        if (draft.fromFloorId == draft.toFloorId) add("Choose two different floors")
        if (fromFloor == null || toFloor == null) add("Both floors need a saved walkable map")
        if (fromFloor != null && !fromFloor.isWalkable(draft.from)) add("The point on the first floor is outside walkable space")
        if (toFloor != null && !toFloor.isWalkable(draft.to)) add("The point on the second floor is outside walkable space")
        if (fromLevel != null && toLevel != null && fromLevel == toLevel) add("The two floors are at the same level")
        if (draft.label.isBlank() || draft.label.length > 60) add("Give the link a name of up to 60 characters, e.g. Stairs 1")
        if (!draft.travelTimeSeconds.isFinite() || draft.travelTimeSeconds !in 1.0..600.0) add("Travel time must be between 1 and 600 seconds")
        if (!draft.activationRadiusMetres.isFinite() || draft.activationRadiusMetres !in 0.5..5.0) add("Area radius must be between 0.5 and 5 m")
    }

    /** Default travel time for [kind] across the level difference, e.g. stairs over two floors = 30 s. */
    fun defaultTravelSeconds(kind: ConnectorKind, fromLevel: Int, toLevel: Int) =
        kind.defaultSecondsPerFloor * abs(fromLevel - toLevel).coerceAtLeast(1)
}

/** A floor's stair landings keyed by their number, e.g. "Stairs landing, C-F4 (2)" → 2. */
data class FloorLandings(val floorId: String, val level: Int, val landings: Map<Int, MetricPoint>)

/**
 * Proposes stair links between floors at adjacent levels by matching landing numbers. Landing numbers come
 * from automatic mapper labels and are not guaranteed to be the same staircase on every floor, so every
 * proposal must be confirmed on site before it is trusted.
 */
object StairLandingMatcher {
    private val pattern = Regex("""^Stairs landing, .*\((\d+)\)\s*$""", RegexOption.IGNORE_CASE)

    fun landingNumber(name: String): Int? = pattern.find(name.trim())?.groupValues?.get(1)?.toIntOrNull()

    fun suggest(floors: List<FloorLandings>, existing: List<ConnectorDraft>, nearMetres: Double = 2.0): List<ConnectorDraft> {
        val byLevel = floors.sortedBy { it.level }
        return byLevel.zipWithNext().filter { (a, b) -> b.level - a.level == 1 }.flatMap { (lower, upper) ->
            lower.landings.keys.intersect(upper.landings.keys).sorted().mapNotNull { n ->
                val from = lower.landings.getValue(n); val to = upper.landings.getValue(n)
                val draft = ConnectorDraft(ConnectorKind.STAIRS, lower.floorId, from, upper.floorId, to,
                    "Stairs $n", ConnectorRules.defaultTravelSeconds(ConnectorKind.STAIRS, lower.level, upper.level))
                draft.takeIf { existing.none { e -> e.sameEndsAs(draft, nearMetres) } }
            }
        }
    }
}
