package com.turn.fieldtest.data.repository

import com.turn.fieldtest.core.ExcludedLink
import com.turn.fieldtest.core.ExclusionReason
import com.turn.fieldtest.core.FloorElevations
import com.turn.fieldtest.core.FloorLinkInput
import com.turn.fieldtest.core.FloorTopology
import com.turn.fieldtest.core.FloorTopologyBuilder
import com.turn.fieldtest.core.MapFloor
import com.turn.fieldtest.core.MetricMap
import com.turn.fieldtest.core.MetricPoint

/**
 * Everything a live session needs about the building, frozen at session start (plan Step 7).
 *
 * Single-floor sessions (tracking off, no usable link from the start floor) have [topology] = [FloorTopology.NONE],
 * [metricMap] = `start.metricMap()` and [floorIds] = {start floor}: exactly today's session map (invariant I1).
 */
data class SessionBuilding(
    val start: ProjectContext,
    val floorMaps: Map<String, SavedFloorMap>,
    val metricMap: MetricMap,
    val topology: FloorTopology,
    val floorIds: Set<String>,
    val names: Map<String, String>,
    val levels: Map<String, Int>,
    val excluded: List<ExcludedLink>,
    val skippedFloors: List<Pair<String, String>>,
    /** Saved links in the building that were considered (0 when tracking is off). */
    val linkCount: Int = 0,
    /** Ids of the considered links that touch the start floor. */
    val startLinkIds: Set<String> = emptySet(),
    /** Real elevations of every session floor, or null (nominal storey height, the behaviour before schema 8). */
    val elevations: FloorElevations? = null,
) {
    val multiFloor: Boolean get() = !topology.isEmpty

    /**
     * The same building reduced to its start floor, exactly as a single-floor session builds it (I1). Used when no
     * floor coordinator can be created for a multi-floor topology, so the legacy path never sees other floors.
     */
    fun singleFloor(): SessionBuilding {
        if (!multiFloor) return this
        val saved = floorMaps.getValue(start.floorId)
        return copy(
            floorMaps = mapOf(start.floorId to saved), metricMap = saved.metricMap(), topology = FloorTopology.NONE,
            floorIds = setOf(start.floorId), names = mapOf(start.floorId to saved.floor.name),
            levels = mapOf(start.floorId to saved.floor.levelNumber), elevations = null,
        )
    }
}

/**
 * Pure assembly of a [SessionBuilding] from already loaded floors. [others] are the building's other floors that the
 * links reference; floors whose walkable map cannot be built are skipped together with their links.
 * The start floor's map is built exactly as today (`start.metricMap()`), so an unusable start floor still fails.
 */
fun assembleSessionBuilding(
    start: SavedFloorMap, others: List<SavedFloorMap>, connectors: List<FloorConnector>,
    floorTrackingEnabled: Boolean, useUnverifiedLinks: Boolean,
): SessionBuilding {
    val startMap = start.metricMap()
    val startId = start.floor.id
    val startLinks = connectors.filter { it.entity.fromFloorId == startId || it.entity.toFloorId == startId }.map { it.entity.id }.toSet()
    fun single(excluded: List<ExcludedLink>, skipped: List<Pair<String, String>>, links: Int) = SessionBuilding(
        start.context, mapOf(startId to start), startMap, FloorTopology.NONE, setOf(startId),
        mapOf(startId to start.floor.name), mapOf(startId to start.floor.levelNumber), excluded, skipped, links, startLinks,
    )
    if (!floorTrackingEnabled || connectors.isEmpty()) return single(emptyList(), emptyList(), 0)
    val skipped = mutableListOf<Pair<String, String>>()
    val usable = mutableMapOf(startId to start)
    val mapFloors = mutableMapOf(startId to startMap.floors.getValue(startId))
    others.filter { it.floor.id != startId && it.venue.id == start.venue.id }.distinctBy { it.floor.id }.forEach { saved ->
        runCatching { saved.metricMap().floors.getValue(saved.floor.id) }
            .onSuccess { usable[saved.floor.id] = saved; mapFloors[saved.floor.id] = it }
            .onFailure { skipped += saved.floor.id to (it.message ?: "map could not be read") }
    }
    val levels = usable.mapValues { it.value.floor.levelNumber }
    val links = connectors.map { it.toLinkInput() }
    val build = FloorTopologyBuilder.build(startId, mapFloors, levels, links, useUnverifiedLinks)
    if (build.topology.isEmpty) return single(build.excluded, skipped, connectors.size)
    val ids = build.sessionFloorIds
    val floors: Map<String, MapFloor> = ids.associateWith { mapFloors.getValue(it) }
    return SessionBuilding(
        start = start.context,
        floorMaps = ids.associateWith { usable.getValue(it) },
        metricMap = MetricMap(floors),
        topology = build.topology,
        floorIds = ids,
        names = ids.associateWith { usable.getValue(it).floor.name },
        levels = ids.associateWith { levels.getValue(it) },
        excluded = build.excluded,
        skippedFloors = skipped,
        linkCount = connectors.size,
        startLinkIds = startLinks,
        elevations = FloorElevations.of(ids.associateWith { levels.getValue(it) }, ids.associateWith { usable.getValue(it).floor.elevationMetres }),
    )
}

/** A saved connector as the topology builder reads it. */
fun FloorConnector.toLinkInput() = FloorLinkInput(
    id = entity.id, kind = kind,
    fromFloorId = entity.fromFloorId, from = MetricPoint(entity.fromXMetres, entity.fromYMetres),
    toFloorId = entity.toFloorId, to = MetricPoint(entity.toXMetres, entity.toYMetres),
    radiusMetres = entity.activationRadiusMetres, bidirectional = entity.bidirectional,
    enabled = entity.enabled, verifiedOnSite = entity.verifiedOnSite,
    travelTimeSeconds = entity.travelTimeSeconds, label = entity.label, accessible = entity.accessible,
)

/**
 * The start status line for floor tracking (plan §5), or null when nothing needs saying (tracking off, or a
 * building without links).
 */
fun floorTrackingStartStatus(building: SessionBuilding, floorTrackingEnabled: Boolean, barometerPresent: Boolean): String? {
    if (!floorTrackingEnabled) return null
    if (building.multiFloor) {
        val floors = building.floorIds.size
        val links = building.topology.portals.map { it.linkId }.distinct().size
        val barometer = if (barometerPresent) "barometer ready" else "no barometer: floor from Wi-Fi and stairs/lift"
        val heights = if (building.elevations != null) " · floor heights set" else ""
        return "Multi-floor tracking · $floors floors · $links ${if (links == 1) "link" else "links"} · $barometer$heights"
    }
    val unchecked = building.excluded.count { it.reason == ExclusionReason.UNVERIFIED && it.linkId in building.startLinkIds }
    if (unchecked > 0) {
        return if (unchecked == 1) "1 unchecked link ignored · check it on site or allow unchecked links"
            else "$unchecked unchecked links ignored · check them on site or allow unchecked links"
    }
    return if (building.startLinkIds.isNotEmpty()) "Floor tracking off: no checked links from this floor" else null
}

/**
 * Loads a [SessionBuilding] for a live session. The start floor is loaded exactly as before
 * ([ProjectRepository.load] with repairs persisted); other floors are read only when tracking is on, only those
 * the building's links reference, and without writing anything. The route preview between floors passes
 * [persistStartRepairs] = false, so planning a route never writes to the open floor either.
 */
class SessionBuildingLoader(private val projects: ProjectRepository, private val connectors: ConnectorRepository) {
    suspend fun load(
        context: ProjectContext, floorTrackingEnabled: Boolean, useUnverifiedLinks: Boolean, persistStartRepairs: Boolean = true,
    ): SessionBuilding {
        val start = projects.load(context, persistRepairs = persistStartRepairs)
        if (!floorTrackingEnabled) return assembleSessionBuilding(start, emptyList(), emptyList(), false, useUnverifiedLinks)
        val links = runCatching { connectors.connectors(context.venueId) }.getOrDefault(emptyList())
        val referenced = links.flatMap { listOf(it.entity.fromFloorId, it.entity.toFloorId) }.toSet() - context.floorId
        val others = referenced.mapNotNull { id ->
            runCatching { projects.load(ProjectContext(context.venueId, id), persistRepairs = false) }.getOrNull()
        }
        return assembleSessionBuilding(start, others, links, true, useUnverifiedLinks)
    }
}
