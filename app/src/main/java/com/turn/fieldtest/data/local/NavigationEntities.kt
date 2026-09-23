package com.turn.fieldtest.data.local

import androidx.room.*
import com.turn.fieldtest.core.*
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "navigation_nodes", foreignKeys = [
    ForeignKey(entity = FloorEntity::class, parentColumns = ["id"], childColumns = ["floorId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = PointOfInterestEntity::class, parentColumns = ["id"], childColumns = ["poiId"], onDelete = ForeignKey.SET_NULL)
], indices = [Index("floorId"), Index("poiId")])
data class NavigationNodeEntity(@PrimaryKey val id: String, val floorId: String,
    val xMetres: Double, val yMetres: Double, val poiId: String? = null) {
    fun core() = NavigationNode(id, floorId, MetricPoint(xMetres, yMetres), poiId)
}

@Serializable
@Entity(tableName = "navigation_edges", foreignKeys = [
    ForeignKey(entity = FloorEntity::class, parentColumns = ["id"], childColumns = ["floorId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = NavigationNodeEntity::class, parentColumns = ["id"], childColumns = ["fromNodeId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = NavigationNodeEntity::class, parentColumns = ["id"], childColumns = ["toNodeId"], onDelete = ForeignKey.CASCADE)
], indices = [Index("floorId"), Index("fromNodeId"), Index("toNodeId")])
data class NavigationEdgeEntity(@PrimaryKey val id: String, val floorId: String,
    val fromNodeId: String, val toNodeId: String, val distanceMetres: Double,
    val walkable: Boolean = true, val bidirectional: Boolean = true, val accessible: Boolean = true,
    val kind: String = "CORRIDOR") {
    fun core() = NavigationEdge(id, fromNodeId, toNodeId, distanceMetres, walkable, bidirectional, accessible)
}

@Dao
interface NavigationDao {
    @Query("SELECT * FROM navigation_nodes WHERE floorId = :floorId ORDER BY id") suspend fun nodes(floorId: String): List<NavigationNodeEntity>
    @Query("SELECT * FROM navigation_edges WHERE floorId = :floorId ORDER BY id") suspend fun edges(floorId: String): List<NavigationEdgeEntity>
    @Query("SELECT * FROM points_of_interest WHERE floorId = :floorId ORDER BY name") suspend fun destinations(floorId: String): List<PointOfInterestEntity>
    @Upsert suspend fun saveNodes(nodes: List<NavigationNodeEntity>)
    @Upsert suspend fun saveEdges(edges: List<NavigationEdgeEntity>)
    @Upsert suspend fun saveDestinations(points: List<PointOfInterestEntity>)
    @Query("DELETE FROM navigation_edges WHERE floorId = :floorId") suspend fun clearEdges(floorId: String)
    @Query("DELETE FROM navigation_nodes WHERE floorId = :floorId AND id NOT IN (:keepIds)") suspend fun removeNodes(floorId: String, keepIds: List<String>)
    @Query("DELETE FROM points_of_interest WHERE floorId = :floorId AND id NOT IN (:keepIds)") suspend fun removeDestinations(floorId: String, keepIds: List<String>)
}
