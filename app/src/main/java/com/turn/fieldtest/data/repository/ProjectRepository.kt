package com.turn.fieldtest.data.repository

import androidx.room.withTransaction
import com.turn.fieldtest.core.FloorAlignment
import com.turn.fieldtest.core.FloorAlignmentSolver
import com.turn.fieldtest.core.FloorElevations
import com.turn.fieldtest.core.MapFloor
import com.turn.fieldtest.core.MetricMap
import com.turn.fieldtest.core.MetricPoint
import com.turn.fieldtest.core.MetricPolygon
import com.turn.fieldtest.core.WallSegment
import com.turn.fieldtest.core.validateSimpleWalkablePolygon
import com.turn.fieldtest.data.local.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import java.util.UUID

/** Physical project identity is independent of display names and demo selection. */
data class ProjectContext(val venueId: String, val floorId: String) {
    init {
        require(venueId.isNotBlank() && floorId.isNotBlank())
    }
}

data class ProjectSummary(
    val venueId: String,
    val floorId: String,
    val venueName: String,
    val floorName: String,
    val referencePointCount: Int,
    val fingerprintPointCount: Int,
    val surveySessionCount: Int,
    val positioningSessionCount: Int,
    val floorLevel: Int = 0,
) {
    val context: ProjectContext get() = ProjectContext(venueId, floorId)
}

/** Pure selection policy: missing saved IDs never silently open an unrelated project. */
fun resolveSavedProject(projects: List<ProjectSummary>, venueId: String?, floorId: String?): ProjectContext? {
    if (venueId != null || floorId != null) {
        return projects.firstOrNull { it.venueId == venueId && it.floorId == floorId }?.context
    }
    return projects.singleOrNull()?.context
}

data class ProjectInput(val venueName: String, val floorName: String) {
    fun validated(): ProjectInput {
        val result = copy(venueName = venueName.trim(), floorName = floorName.trim())
        require(result.venueName.isNotEmpty() && result.floorName.isNotEmpty()) { "Enter a venue and floor name" }
        require(result.venueName.length <= 120 && result.floorName.length <= 120) { "Names must be at most 120 characters" }
        return result
    }
}

/** All rows are read in one transaction, then retained unchanged for the active session. */
data class SavedFloorMap(
    val venue: VenueEntity,
    val floor: FloorEntity,
    val asset: FloorPlanAssetEntity?,
    val regions: List<WalkableRegionEntity>,
    val walls: List<WallSegmentEntity>,
    val points: List<ReferencePointEntity>,
    val anchors: List<QrAnchorEntity>,
    val hasMeasurements: Boolean,
    val nodes: List<NavigationNodeEntity> = emptyList(),
    val edges: List<NavigationEdgeEntity> = emptyList(),
    val destinations: List<PointOfInterestEntity> = emptyList(),
) {
    val context get() = ProjectContext(venue.id, floor.id)

    fun metricMap(): MetricMap {
        val polygons = regions.filter { it.enabled }.map { decodePolygon(it.polygonJson) }
        require(polygons.isNotEmpty()) { "Draw and save a walkable polygon before starting a session" }
        polygons.forEach(::validateSimpleWalkablePolygon)
        return MetricMap(mapOf(floor.id to MapFloor(floor.id, polygons, walls.filter { it.enabled }.map {
            WallSegment(it.id, MetricPoint(it.startXMetres, it.startYMetres), MetricPoint(it.endXMetres, it.endYMetres))
        })))
    }
}

fun decodePolygon(value: String): MetricPolygon = MetricPolygon(Json.parseToJsonElement(value).jsonArray.map {
    MetricPoint(it.jsonObject.getValue("x").jsonPrimitive.double, it.jsonObject.getValue("y").jsonPrimitive.double)
})

data class DestinationArea(val destinationId: String, val polygon: MetricPolygon)

/** Recover persisted mapper room outlines without treating rooms as walkable corridors. */
fun SavedFloorMap.destinationAreas(): List<DestinationArea> = mapperDestinationAreas()

class ProjectRepository(private val database: TurnDatabase) {
    private val repositories = RoomRepositories.from(database)
    fun observeProjects() = database.floorPlanDao().observeProjects()

    suspend fun create(input: ProjectInput, now: Long): ProjectContext = database.withTransaction {
        val names = input.validated()
        val context = ProjectContext("VEN-${UUID.randomUUID()}", "FL-${UUID.randomUUID()}")
        repositories.venues.save(VenueEntity(context.venueId, names.venueName,
            createdAtEpochMillis = now, updatedAtEpochMillis = now))
        repositories.floorPlans.save(FloorEntity(context.floorId, context.venueId, names.floorName,
            levelNumber = 0, widthMetres = 42.0, heightMetres = 28.0,
            createdAtEpochMillis = now, updatedAtEpochMillis = now))
        context
    }

    /** Adds an empty floor to an existing building; floor identity stays independent of its name. */
    suspend fun addFloor(venueId: String, floorName: String, levelNumber: Int, now: Long): ProjectContext = database.withTransaction {
        val name = floorName.trim()
        require(name.isNotEmpty() && name.length <= 120) { "Enter a floor name of at most 120 characters" }
        requireNotNull(repositories.venues.venue(venueId)) { "Saved building is unavailable" }
        val siblings = database.floorPlanDao().observeFloors(venueId).first()
        require(siblings.none { it.name.equals(name, ignoreCase = true) }) { "This building already has a floor named $name" }
        // floors has a unique (venueId, levelNumber) index; @Upsert would otherwise skip the row silently.
        require(siblings.none { it.levelNumber == levelNumber }) { "This building already has a floor at level $levelNumber" }
        val context = ProjectContext(venueId, "FL-${UUID.randomUUID()}")
        repositories.floorPlans.save(FloorEntity(context.floorId, venueId, name, levelNumber = levelNumber,
            widthMetres = 42.0, heightMetres = 28.0, createdAtEpochMillis = now, updatedAtEpochMillis = now))
        context
    }

    /**
     * Saves every floor's elevation in one building (schema 8). Either all are null (cleared: nominal storey height)
     * or every floor of the building gets a valid elevation ([FloorElevations.validationError]).
     */
    suspend fun setFloorElevations(venueId: String, elevations: Map<String, Double?>, now: Long) = database.withTransaction {
        val floors = database.floorPlanDao().observeFloors(venueId).first()
        require(elevations.keys.all { id -> floors.any { it.id == id } }) { "A floor is not part of this building" }
        val values = floors.associate { it.id to elevations[it.id] }
        if (values.values.any { it != null }) {
            FloorElevations.validationError(floors.associate { it.id to it.levelNumber }, values, floors.associate { it.id to it.name })
                ?.let { throw IllegalArgumentException(it) }
        }
        floors.filter { it.elevationMetres != values[it.id] }
            .forEach { repositories.floorPlans.save(it.copy(elevationMetres = values[it.id], updatedAtEpochMillis = now)) }
    }

    /**
     * Saves (or with null removes) [floorId]'s alignment onto another floor of the same building (schema 8). An
     * alignment the other way round between the same two floors is removed, so each pair has one.
     */
    suspend fun setFloorAlignment(floorId: String, alignment: FloorAlignment?, now: Long) = database.withTransaction {
        val floor = requireNotNull(repositories.floorPlans.floor(floorId)) { "Saved floor is unavailable" }
        if (alignment == null) {
            repositories.floorPlans.save(floor.copy(frameAlignmentJson = null, updatedAtEpochMillis = now))
            return@withTransaction
        }
        require(alignment.toFloorId != floorId) { "Align a floor to another floor" }
        val target = requireNotNull(repositories.floorPlans.floor(alignment.toFloorId)) { "The other floor no longer exists" }
        require(target.venueId == floor.venueId) { "Floors can only be aligned within one building" }
        // One slot per floor: never silently replace this floor's alignment to a third floor (see FloorAlignmentSolver.placement).
        val placement = FloorAlignmentSolver.placement(floorId, alignment, FloorAlignment.fromJsonOrNull(floor.frameAlignmentJson),
            FloorAlignment.fromJsonOrNull(target.frameAlignmentJson), mapOf(floor.id to floor.name, target.id to target.name))
        val byId = mapOf(floor.id to floor, target.id to target)
        placement.clearFloorId?.let { repositories.floorPlans.save(byId.getValue(it).copy(frameAlignmentJson = null, updatedAtEpochMillis = now)) }
        repositories.floorPlans.save(byId.getValue(placement.storeOnFloorId).copy(frameAlignmentJson = placement.stored.toJson(), updatedAtEpochMillis = now))
    }

    /**
     * Reads one saved floor. Missing mapper rooms and descriptive mapper reference names are repaired in the
     * returned copy; with [persistRepairs] (the default) those repairs are also written back. Tracking sessions load
     * the other floors of a building with `false`, so starting a session never writes to floors it only reads.
     */
    suspend fun load(context: ProjectContext, persistRepairs: Boolean = true): SavedFloorMap = database.withTransaction {
        val venue = requireNotNull(repositories.venues.venue(context.venueId)) { "Saved venue is unavailable" }
        val floor = requireNotNull(repositories.floorPlans.floor(context.floorId)) { "Saved floor is unavailable" }
        require(floor.venueId == venue.id) { "This floor belongs to another venue" }
        val saved=SavedFloorMap(venue, floor,
            repositories.floorPlans.observeAsset(floor.id).first(),
            repositories.floorPlans.observeWalkableRegions(floor.id).first(),
            repositories.floorPlans.observeWalls(floor.id).first(),
            repositories.floorPlans.observeReferencePoints(floor.id).first(),
            repositories.floorPlans.observeQrAnchors(floor.id).first(),
            database.floorPlanDao().hasMeasurements(floor.id, venue.id),
            database.navigationDao().nodes(floor.id), database.navigationDao().edges(floor.id),
            database.navigationDao().destinations(floor.id))
        val missing=saved.missingMapperRooms()
        if(persistRepairs && missing.isNotEmpty()) database.navigationDao().saveDestinations(missing)
        val renamed=saved.descriptiveMapperReferences()
        if(persistRepairs) renamed.forEach {database.floorPlanDao().upsertReferencePoint(it)}
        saved.copy(destinations=(saved.destinations+missing).sortedBy {it.name.lowercase(java.util.Locale.ROOT)},
            points=saved.points.map {p->renamed.firstOrNull {it.id==p.id} ?: p})
    }
}
