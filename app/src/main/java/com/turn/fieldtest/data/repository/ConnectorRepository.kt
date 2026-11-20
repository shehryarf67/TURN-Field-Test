package com.turn.fieldtest.data.repository

import androidx.room.withTransaction
import com.turn.fieldtest.core.*
import com.turn.fieldtest.data.local.TurnDatabase
import com.turn.fieldtest.data.local.VerticalTransitionEntity
import kotlinx.coroutines.flow.first
import java.util.UUID

/** A saved floor link with the names needed to show it. */
data class FloorConnector(
    val entity: VerticalTransitionEntity,
    val kind: ConnectorKind,
    val fromFloorName: String, val fromLevel: Int,
    val toFloorName: String, val toLevel: Int,
) {
    fun draft() = ConnectorDraft(kind, entity.fromFloorId, MetricPoint(entity.fromXMetres, entity.fromYMetres),
        entity.toFloorId, MetricPoint(entity.toXMetres, entity.toYMetres), entity.label, entity.travelTimeSeconds, entity.activationRadiusMetres)
}

/** Stores links between floors of one building, validated against both floors' saved maps. */
class ConnectorRepository(private val database: TurnDatabase, private val projects: ProjectRepository = ProjectRepository(database)) {
    private val floors get() = database.floorPlanDao()

    suspend fun connectors(venueId: String): List<FloorConnector> {
        val venueFloors = floors.observeFloors(venueId).first().associateBy { it.id }
        return venueFloors.keys.flatMap { floors.observeTransitions(it).first() }.distinctBy { it.id }
            .filter { it.fromFloorId in venueFloors && it.toFloorId in venueFloors }
            .map { t ->
                val a = venueFloors.getValue(t.fromFloorId); val b = venueFloors.getValue(t.toFloorId)
                FloorConnector(t, ConnectorKind.parse(t.kind) ?: ConnectorKind.STAIRS, a.name, a.levelNumber, b.name, b.levelNumber)
            }.sortedWith(compareBy({ minOf(it.fromLevel, it.toLevel) }, { it.entity.label }))
    }

    /** Saves [draft] (new, or replacing [replacingId]) after checking it against both floors; returns its id. */
    suspend fun save(draft: ConnectorDraft, bidirectional: Boolean, accessible: Boolean, verifiedOnSite: Boolean, replacingId: String? = null): String =
        database.withTransaction {
            val a = requireNotNull(floors.getFloor(draft.fromFloorId)) { "The first floor no longer exists" }
            val b = requireNotNull(floors.getFloor(draft.toFloorId)) { "The second floor no longer exists" }
            require(a.venueId == b.venueId) { "Floors can only be linked within one building" }
            val problems = ConnectorRules.problems(draft, floorMap(a.venueId, a.id), floorMap(b.venueId, b.id), a.levelNumber, b.levelNumber)
            require(problems.isEmpty()) { problems.joinToString("; ") }
            // Two rows for one staircase would draw twice and give routing parallel links; edit the saved one instead.
            // One-way links in opposite directions (an up and a down escalator) are a pair, not a duplicate.
            connectors(a.venueId).firstOrNull { it.entity.id != replacingId && it.kind == draft.kind &&
                it.draft().sameEndsAs(draft, allowReversed = it.entity.bidirectional && bidirectional) }?.let {
                throw IllegalArgumentException("${it.entity.label.ifBlank { "Another link" }} already joins these two spots; edit that link instead")
            }
            val id = replacingId ?: "LINK-${UUID.randomUUID()}"
            floors.upsertTransition(VerticalTransitionEntity(id, draft.fromFloorId, draft.toFloorId, draft.kind.name,
                draft.from.x, draft.from.y, draft.to.x, draft.to.y, draft.activationRadiusMetres, bidirectional, true,
                draft.label.trim(), draft.travelTimeSeconds, accessible, verifiedOnSite))
            id
        }

    suspend fun delete(id: String) {
        val entity = connectorById(id) ?: return
        floors.deleteTransition(entity)
    }

    suspend fun setVerified(id: String, verified: Boolean) {
        val entity = requireNotNull(connectorById(id)) { "This link no longer exists" }
        floors.upsertTransition(entity.copy(verifiedOnSite = verified))
    }

    /** Unsaved stair links proposed from each floor's numbered stair landings (adjacent levels only). */
    suspend fun suggestions(venueId: String): List<ConnectorDraft> {
        val venueFloors = floors.observeFloors(venueId).first()
        val landings = venueFloors.mapNotNull { f ->
            // load() also gives mapper anchors their descriptive "Stairs landing, … (n)" names.
            val saved = runCatching { projects.load(ProjectContext(venueId, f.id)) }.getOrNull() ?: return@mapNotNull null
            FloorLandings(f.id, f.levelNumber, saved.points.mapNotNull { p ->
                StairLandingMatcher.landingNumber(p.name)?.let { it to MetricPoint(p.xMetres, p.yMetres) }
            }.toMap())
        }
        return StairLandingMatcher.suggest(landings, connectors(venueId).map { it.draft() })
    }

    private suspend fun connectorById(id: String): VerticalTransitionEntity? =
        floors.transition(id)

    private suspend fun floorMap(venueId: String, floorId: String): MapFloor? =
        runCatching { projects.load(ProjectContext(venueId, floorId)).metricMap().floors[floorId] }.getOrNull()
}
