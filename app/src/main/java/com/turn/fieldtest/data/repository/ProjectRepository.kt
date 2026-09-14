package com.turn.fieldtest.data.repository

import androidx.room.withTransaction
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

    suspend fun load(context: ProjectContext): SavedFloorMap = database.withTransaction {
        val venue = requireNotNull(repositories.venues.venue(context.venueId)) { "Saved venue is unavailable" }
        val floor = requireNotNull(repositories.floorPlans.floor(context.floorId)) { "Saved floor is unavailable" }
        require(floor.venueId == venue.id) { "This floor belongs to another venue" }
        SavedFloorMap(venue, floor,
            repositories.floorPlans.observeAsset(floor.id).first(),
            repositories.floorPlans.observeWalkableRegions(floor.id).first(),
            repositories.floorPlans.observeWalls(floor.id).first(),
            repositories.floorPlans.observeReferencePoints(floor.id).first(),
            repositories.floorPlans.observeQrAnchors(floor.id).first(),
            database.floorPlanDao().hasMeasurements(floor.id, venue.id))
    }
}
