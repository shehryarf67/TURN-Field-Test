package com.turn.fieldtest.data.repository

import androidx.room.withTransaction
import com.turn.fieldtest.core.MagneticFeature
import com.turn.fieldtest.core.MagneticFingerprintMap
import com.turn.fieldtest.core.MetricPoint
import com.turn.fieldtest.data.local.MagneticCellEntity
import com.turn.fieldtest.data.local.MagneticWalkEntity
import com.turn.fieldtest.data.local.TurnDatabase
import com.turn.fieldtest.data.local.toMagneticMap

/** Persists magnetic survey walks and merges accepted ones into the floor's magnetic map. */
class MagneticRepository(private val database: TurnDatabase) {
    private val dao get() = database.magneticDao()

    suspend fun map(floorId: String): MagneticFingerprintMap = dao.cells(floorId).toMagneticMap(floorId)

    suspend fun walks(floorId: String): List<MagneticWalkEntity> = dao.walks(floorId)

    /**
     * Records [walk] for lineage and, when [samples] is non-null, merges them into the stored cells in the
     * same transaction, so a failed write never leaves a walk counted without its data (or the reverse).
     */
    suspend fun recordWalk(walk: MagneticWalkEntity, samples: List<Pair<MetricPoint, MagneticFeature>>?, now: Long): MagneticFingerprintMap =
        database.withTransaction {
            dao.insertWalk(walk)
            val map = map(walk.floorId)
            if (samples != null) {
                val touched = samples.map { (point, feature) -> map.add(point, feature); map.keyFor(point) }.toSet()
                dao.upsertCells(touched.map { key ->
                    val s = map.cells.getValue(key)
                    MagneticCellEntity(walk.floorId, key.x, key.y, map.cellSizeMetres, s.count, s.meanMagnitude, s.m2Magnitude,
                        s.meanVertical, s.m2Vertical, now)
                })
            }
            map
        }

    suspend fun clear(floorId: String) = database.withTransaction { dao.clearCells(floorId); dao.clearWalks(floorId) }
}
