package com.turn.fieldtest.mapper.data

import androidx.room.*
import com.turn.fieldtest.data.local.FloorEntity
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName="walk_scan_drafts",foreignKeys=[ForeignKey(entity=FloorEntity::class,parentColumns=["id"],childColumns=["floorId"],onDelete=ForeignKey.CASCADE)],indices=[Index("floorId")])
data class ScanDraftEntity(@PrimaryKey val id: String,val floorId: String,val documentJson: String,val status: String,val updatedAtEpochMillis: Long)
@Dao interface ScanDraftDao {
    @Query("SELECT * FROM walk_scan_drafts WHERE floorId=:floor ORDER BY updatedAtEpochMillis DESC") suspend fun forFloor(floor: String): List<ScanDraftEntity>
    @Query("SELECT * FROM walk_scan_drafts WHERE id=:id") suspend fun get(id: String): ScanDraftEntity?
    @Upsert suspend fun save(value: ScanDraftEntity)
}
