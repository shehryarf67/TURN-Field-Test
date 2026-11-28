package com.turn.fieldtest.data.local

import androidx.room.Dao
import androidx.room.Query

/** Reparents venue-owned records from a building with exactly one floor, inside the floor move transaction. */
@Dao
interface FloorMoveDao {
    // Scan drafts are transferred separately. A slot with any other saved floor data cannot be replaced.
    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM floor_plan_assets WHERE floorId = :floor
            UNION ALL SELECT 1 FROM walkable_regions WHERE floorId = :floor
            UNION ALL SELECT 1 FROM wall_segments WHERE floorId = :floor
            UNION ALL SELECT 1 FROM navigation_nodes WHERE floorId = :floor
            UNION ALL SELECT 1 FROM navigation_edges WHERE floorId = :floor
            UNION ALL SELECT 1 FROM points_of_interest WHERE floorId = :floor
            UNION ALL SELECT 1 FROM reference_points WHERE floorId = :floor
            UNION ALL SELECT 1 FROM qr_anchors WHERE floorId = :floor
            UNION ALL SELECT 1 FROM vertical_transitions WHERE fromFloorId = :floor OR toFloorId = :floor
            UNION ALL SELECT 1 FROM survey_sessions WHERE floorId = :floor
            UNION ALL SELECT 1 FROM positioning_sessions WHERE initialFloorId = :floor
            UNION ALL SELECT 1 FROM position_estimates WHERE floorId = :floor
            UNION ALL SELECT 1 FROM correction_events WHERE floorId = :floor
            UNION ALL SELECT 1 FROM test_checkpoints WHERE floorId = :floor
            UNION ALL SELECT 1 FROM test_samples WHERE trueFloorId = :floor
            UNION ALL SELECT 1 FROM beacon_definitions WHERE floorId = :floor
            UNION ALL SELECT 1 FROM magnetic_cells WHERE floorId = :floor
            UNION ALL SELECT 1 FROM magnetic_walks WHERE floorId = :floor
            UNION ALL SELECT 1 FROM floor_transition_events WHERE fromFloorId = :floor OR toFloorId = :floor
        )
    """)
    suspend fun hasSavedFloorData(floor: String): Boolean

    @Query("UPDATE survey_sessions SET venueId = :to WHERE venueId = :from")
    suspend fun surveys(from: String, to: String)

    @Query("UPDATE positioning_sessions SET venueId = :to WHERE venueId = :from")
    suspend fun positioning(from: String, to: String)

    @Query("UPDATE test_runs SET venueId = :to WHERE venueId = :from")
    suspend fun testRuns(from: String, to: String)

    @Query("SELECT a.checkpointCode FROM test_checkpoints a JOIN test_checkpoints b ON a.checkpointCode = b.checkpointCode WHERE a.venueId = :from AND b.venueId = :to LIMIT 1")
    suspend fun duplicateCheckpoint(from: String, to: String): String?

    @Query("UPDATE test_checkpoints SET venueId = :to WHERE venueId = :from")
    suspend fun checkpoints(from: String, to: String)

    @Query("UPDATE beacon_definitions SET venueId = :to WHERE venueId = :from")
    suspend fun beacons(from: String, to: String)
}
