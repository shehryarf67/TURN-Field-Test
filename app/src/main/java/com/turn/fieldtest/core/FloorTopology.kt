package com.turn.fieldtest.core

/** A saved floor link as the tracking session sees it, before it is checked. Points are in each floor's own frame. */
data class FloorLinkInput(
    val id: String, val kind: ConnectorKind,
    val fromFloorId: String, val from: MetricPoint,
    val toFloorId: String, val to: MetricPoint,
    val radiusMetres: Double, val bidirectional: Boolean,
    val enabled: Boolean, val verifiedOnSite: Boolean,
    val travelTimeSeconds: Double, val label: String,
)

/**
 * One directed way from [fromFloorId] to [toFloorId]. A bidirectional link gives two portals, a one-way link one.
 * [fromCentre] is in the frame of [fromFloorId] and [toCentre] in the frame of [toFloorId]; [index] is the
 * portal's position in [FloorTopology.portals].
 */
data class FloorPortal(
    val index: Int, val linkId: String, val kind: ConnectorKind,
    val fromFloorId: String, val fromCentre: MetricPoint,
    val toFloorId: String, val toCentre: MetricPoint,
    val radiusMetres: Double, val levelDelta: Int,
    val travelTimeSeconds: Double, val verifiedOnSite: Boolean, val label: String,
) {
    init {
        require(index >= 0) { "Portal index must not be negative" }
        require(fromFloorId != toFloorId) { "A portal must join different floors" }
        require(radiusMetres.isFinite() && radiusMetres > 0.0) { "Portal radius must be positive" }
    }
}

/** The usable, directed floor links of one session. [NONE] means the session tracks a single floor. */
class FloorTopology(val levels: Map<String, Int>, val portals: List<FloorPortal>) {
    init {
        require(portals.withIndex().all { (i, p) -> p.index == i }) { "Portal indices must match their positions" }
        require(portals.all { it.fromFloorId in levels && it.toFloorId in levels }) { "Every portal floor needs a level" }
    }

    private val byFloor: Map<String, List<FloorPortal>> = portals.groupBy { it.fromFloorId }

    val isEmpty: Boolean get() = portals.isEmpty()

    /** Portals leaving [floorId], in portal order. */
    fun portalsFrom(floorId: String): List<FloorPortal> = byFloor[floorId].orEmpty()

    /** True when a portal joins [a] and [b] in either direction. */
    fun adjacent(a: String, b: String): Boolean =
        a != b && portals.any { (it.fromFloorId == a && it.toFloorId == b) || (it.fromFloorId == b && it.toFloorId == a) }

    /**
     * Portals leaving [floor] whose area holds [point]: within radius + [slackMetres] of the portal centre, and
     * reachable from the centre without leaving walkable space or crossing a wall. Sorted by distance, then link id.
     */
    fun capture(floor: MapFloor, point: MetricPoint, slackMetres: Double): List<FloorPortal> {
        require(slackMetres.isFinite() && slackMetres >= 0.0) { "Slack must be finite and not negative" }
        return portalsFrom(floor.id)
            .map { it to it.fromCentre.distanceTo(point) }
            .filter { (p, d) ->
                d <= p.radiusMetres + slackMetres &&
                    floor.isPathInsideWalkableSpace(p.fromCentre, point) && !floor.crossesWall(p.fromCentre, point)
            }
            .sortedWith(compareBy<Pair<FloorPortal, Double>> { it.second }.thenBy { it.first.linkId })
            .map { it.first }
    }

    companion object {
        val NONE: FloorTopology = FloorTopology(emptyMap(), emptyList())
    }
}

/** Why a saved link was left out of a session. */
enum class ExclusionReason { DISABLED, UNVERIFIED, FLOOR_MISSING, ENDPOINT_NOT_WALKABLE, RULES }

data class ExcludedLink(val linkId: String, val label: String, val reason: ExclusionReason)

/** The session topology, the floors it can reach from the start floor, and the links that were left out. */
data class TopologyBuild(val topology: FloorTopology, val sessionFloorIds: Set<String>, val excluded: List<ExcludedLink>)

object FloorTopologyBuilder {
    /**
     * Checks every link, turns the usable ones into directed portals, and keeps the floors reachable from
     * [startFloorId] (undirected, so a one-way link still joins its two floors). If no other floor is reachable
     * the topology is [FloorTopology.NONE] and the session stays single-floor.
     */
    fun build(
        startFloorId: String, floors: Map<String, MapFloor>, levels: Map<String, Int>,
        links: List<FloorLinkInput>, useUnverifiedLinks: Boolean,
    ): TopologyBuild {
        val excluded = mutableListOf<ExcludedLink>()
        val usable = links.filter { link ->
            val reason = exclusionReason(link, floors, levels, useUnverifiedLinks)
            if (reason != null) excluded += ExcludedLink(link.id, link.label, reason)
            reason == null
        }
        val reached = linkedSetOf(startFloorId)
        val queue = ArrayDeque(listOf(startFloorId))
        while (queue.isNotEmpty()) {
            val floor = queue.removeFirst()
            usable.forEach { link ->
                val next = when (floor) { link.fromFloorId -> link.toFloorId; link.toFloorId -> link.fromFloorId; else -> null }
                if (next != null && reached.add(next)) queue.addLast(next)
            }
        }
        if (reached.size < 2) return TopologyBuild(FloorTopology.NONE, setOf(startFloorId), excluded)
        val portals = mutableListOf<FloorPortal>()
        usable.filter { it.fromFloorId in reached }.forEach { link ->
            val delta = levels.getValue(link.toFloorId) - levels.getValue(link.fromFloorId)
            fun add(fromId: String, from: MetricPoint, toId: String, to: MetricPoint, levelDelta: Int) {
                portals += FloorPortal(portals.size, link.id, link.kind, fromId, from, toId, to, link.radiusMetres,
                    levelDelta, link.travelTimeSeconds, link.verifiedOnSite, link.label)
            }
            add(link.fromFloorId, link.from, link.toFloorId, link.to, delta)
            if (link.bidirectional) add(link.toFloorId, link.to, link.fromFloorId, link.from, -delta)
        }
        return TopologyBuild(FloorTopology(reached.associateWith { levels.getValue(it) }, portals), reached, excluded)
    }

    private fun exclusionReason(
        link: FloorLinkInput, floors: Map<String, MapFloor>, levels: Map<String, Int>, useUnverifiedLinks: Boolean,
    ): ExclusionReason? {
        if (!link.enabled) return ExclusionReason.DISABLED
        if (!link.verifiedOnSite && !useUnverifiedLinks) return ExclusionReason.UNVERIFIED
        val fromFloor = floors[link.fromFloorId]; val toFloor = floors[link.toFloorId]
        val fromLevel = levels[link.fromFloorId]; val toLevel = levels[link.toFloorId]
        if (fromFloor == null || toFloor == null || fromLevel == null || toLevel == null) return ExclusionReason.FLOOR_MISSING
        if (!fromFloor.isWalkable(link.from) || !toFloor.isWalkable(link.to)) return ExclusionReason.ENDPOINT_NOT_WALKABLE
        val draft = ConnectorDraft(link.kind, link.fromFloorId, link.from, link.toFloorId, link.to, link.label,
            link.travelTimeSeconds, link.radiusMetres)
        if (ConnectorRules.problems(draft, fromFloor, toFloor, fromLevel, toLevel).isNotEmpty()) return ExclusionReason.RULES
        return null
    }
}
