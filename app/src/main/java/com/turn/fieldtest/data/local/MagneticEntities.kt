package com.turn.fieldtest.data.local

import androidx.room.*
import com.turn.fieldtest.core.MagneticCellKey
import com.turn.fieldtest.core.MagneticCellStats
import com.turn.fieldtest.core.MagneticFingerprintMap
import kotlinx.serialization.Serializable

/** One cell of a floor's magnetic map: running statistics merged from every accepted survey walk. */
@Serializable
@Entity(tableName = "magnetic_cells", primaryKeys = ["floorId", "cellX", "cellY"], foreignKeys = [
    ForeignKey(entity = FloorEntity::class, parentColumns = ["id"], childColumns = ["floorId"], onDelete = ForeignKey.CASCADE),
], indices = [Index("floorId")])
data class MagneticCellEntity(
    val floorId: String, val cellX: Int, val cellY: Int, val cellSizeMetres: Double,
    val count: Int, val meanMagnitude: Double, val m2Magnitude: Double, val meanVertical: Double, val m2Vertical: Double,
    val updatedAtEpochMillis: Long,
) {
    fun stats() = MagneticCellStats(count, meanMagnitude, m2Magnitude, meanVertical, m2Vertical)
}

/** Lineage of every magnetic survey walk, including rejected ones and why. */
@Serializable
@Entity(tableName = "magnetic_walks", foreignKeys = [
    ForeignKey(entity = FloorEntity::class, parentColumns = ["id"], childColumns = ["floorId"], onDelete = ForeignKey.CASCADE),
], indices = [Index("floorId")])
data class MagneticWalkEntity(
    @PrimaryKey val id: String, val floorId: String,
    val fromReferencePointId: String, val toReferencePointId: String,
    val startedAtEpochMillis: Long, val endedAtEpochMillis: Long,
    val stepCount: Int, val lengthMetres: Double, val readingCount: Int,
    val status: String, val rejectionReason: String? = null, val deviceModel: String,
)

@Dao
interface MagneticDao {
    @Query("SELECT * FROM magnetic_cells WHERE floorId = :floorId")
    suspend fun cells(floorId: String): List<MagneticCellEntity>

    @Upsert
    suspend fun upsertCells(cells: List<MagneticCellEntity>)

    @Insert
    suspend fun insertWalk(walk: MagneticWalkEntity)

    @Query("SELECT * FROM magnetic_walks WHERE floorId = :floorId ORDER BY startedAtEpochMillis DESC")
    suspend fun walks(floorId: String): List<MagneticWalkEntity>

    @Query("DELETE FROM magnetic_cells WHERE floorId = :floorId")
    suspend fun clearCells(floorId: String)

    @Query("DELETE FROM magnetic_walks WHERE floorId = :floorId")
    suspend fun clearWalks(floorId: String)
}

fun List<MagneticCellEntity>.toMagneticMap(floorId: String): MagneticFingerprintMap =
    MagneticFingerprintMap(floorId, filter { it.floorId == floorId }.associate { MagneticCellKey(it.cellX, it.cellY) to it.stats() },
        cellSizeMetres = firstOrNull()?.cellSizeMetres ?: MagneticFingerprintMap.CELL_SIZE_METRES)
