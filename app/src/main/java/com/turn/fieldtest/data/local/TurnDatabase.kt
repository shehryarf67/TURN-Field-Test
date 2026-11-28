package com.turn.fieldtest.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import com.turn.fieldtest.data.export.BackupDao

@Database(
    entities = [
        com.turn.fieldtest.mapper.data.ScanDraftEntity::class,
        NavigationNodeEntity::class,
        NavigationEdgeEntity::class,
        VenueEntity::class,
        FloorEntity::class,
        FloorPlanAssetEntity::class,
        WalkableRegionEntity::class,
        WallSegmentEntity::class,
        VerticalTransitionEntity::class,
        PointOfInterestEntity::class,
        ReferencePointEntity::class,
        QrAnchorEntity::class,
        SurveySessionEntity::class,
        WifiScanSnapshotEntity::class,
        WifiObservationEntity::class,
        AggregatedWifiFingerprintEntity::class,
        SensorSessionEntity::class,
        PdrEventEntity::class,
        PositioningSessionEntity::class,
        PositionEstimateEntity::class,
        CorrectionEventEntity::class,
        TestRunEntity::class,
        TestCheckpointEntity::class,
        TestSampleEntity::class,
        BeaconDefinitionEntity::class,
        BleObservationEntity::class,
        AggregatedBleFingerprintEntity::class,
        MagneticCellEntity::class,
        MagneticWalkEntity::class,
        FloorTransitionEventEntity::class,
    ],
    version = TurnDatabase.SCHEMA_VERSION,
    exportSchema = true,
)
@TypeConverters(RoomConverters::class)
abstract class TurnDatabase : RoomDatabase() {
    abstract fun scanDraftDao(): com.turn.fieldtest.mapper.data.ScanDraftDao
    abstract fun navigationDao(): NavigationDao
    abstract fun venueDao(): VenueDao
    abstract fun floorPlanDao(): FloorPlanDao
    abstract fun floorMoveDao(): FloorMoveDao
    abstract fun surveyDao(): SurveyDao
    abstract fun positioningDao(): PositioningDao
    abstract fun evaluationDao(): EvaluationDao
    abstract fun bleDao(): BleDao
    abstract fun backupDao(): BackupDao
    abstract fun magneticDao(): MagneticDao
    abstract fun floorTransitionEventDao(): FloorTransitionEventDao

    companion object {
        const val SCHEMA_VERSION = 8
        const val DATABASE_NAME = "turn-field-test.db"

        fun build(context: Context, databaseName: String = DATABASE_NAME): TurnDatabase =
            Room.databaseBuilder(context.applicationContext, TurnDatabase::class.java, databaseName)
                .addMigrations(*TurnDatabaseMigrations.ALL)
                .build()
    }
}

/**
 * Append explicit migrations here whenever [TurnDatabase.SCHEMA_VERSION] changes. Destructive
 * fallback is intentionally not enabled because survey and independent test data are irreplaceable.
 */
object TurnDatabaseMigrations {
    val FROM_1_TO_2 = object : Migration(1, 2) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS navigation_nodes (id TEXT NOT NULL, floorId TEXT NOT NULL, xMetres REAL NOT NULL, yMetres REAL NOT NULL, poiId TEXT, PRIMARY KEY(id), FOREIGN KEY(floorId) REFERENCES floors(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(poiId) REFERENCES points_of_interest(id) ON UPDATE NO ACTION ON DELETE SET NULL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_navigation_nodes_floorId ON navigation_nodes(floorId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_navigation_nodes_poiId ON navigation_nodes(poiId)")
            db.execSQL("CREATE TABLE IF NOT EXISTS navigation_edges (id TEXT NOT NULL, floorId TEXT NOT NULL, fromNodeId TEXT NOT NULL, toNodeId TEXT NOT NULL, distanceMetres REAL NOT NULL, walkable INTEGER NOT NULL, bidirectional INTEGER NOT NULL, accessible INTEGER NOT NULL, kind TEXT NOT NULL, PRIMARY KEY(id), FOREIGN KEY(floorId) REFERENCES floors(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(fromNodeId) REFERENCES navigation_nodes(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(toNodeId) REFERENCES navigation_nodes(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_navigation_edges_floorId ON navigation_edges(floorId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_navigation_edges_fromNodeId ON navigation_edges(fromNodeId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_navigation_edges_toNodeId ON navigation_edges(toNodeId)")
            db.execSQL("ALTER TABLE survey_sessions ADD COLUMN targetSnapshotCount INTEGER")
            db.execSQL("ALTER TABLE survey_sessions ADD COLUMN completionStatus TEXT NOT NULL DEFAULT 'LEGACY'")
        }
    }
    val FROM_2_TO_3 = object : Migration(2,3) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS walk_scan_drafts (id TEXT NOT NULL, floorId TEXT NOT NULL, documentJson TEXT NOT NULL, status TEXT NOT NULL, updatedAtEpochMillis INTEGER NOT NULL, PRIMARY KEY(id), FOREIGN KEY(floorId) REFERENCES floors(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_walk_scan_drafts_floorId ON walk_scan_drafts(floorId)")
        }
    }
    /** Additive: magnetic map cells and survey-walk lineage. No existing table changes. */
    val FROM_3_TO_4 = object : Migration(3, 4) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `magnetic_cells` (`floorId` TEXT NOT NULL, `cellX` INTEGER NOT NULL, `cellY` INTEGER NOT NULL, `cellSizeMetres` REAL NOT NULL, `count` INTEGER NOT NULL, `meanMagnitude` REAL NOT NULL, `m2Magnitude` REAL NOT NULL, `meanVertical` REAL NOT NULL, `m2Vertical` REAL NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`floorId`, `cellX`, `cellY`), FOREIGN KEY(`floorId`) REFERENCES `floors`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_magnetic_cells_floorId` ON `magnetic_cells` (`floorId`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `magnetic_walks` (`id` TEXT NOT NULL, `floorId` TEXT NOT NULL, `fromReferencePointId` TEXT NOT NULL, `toReferencePointId` TEXT NOT NULL, `startedAtEpochMillis` INTEGER NOT NULL, `endedAtEpochMillis` INTEGER NOT NULL, `stepCount` INTEGER NOT NULL, `lengthMetres` REAL NOT NULL, `readingCount` INTEGER NOT NULL, `status` TEXT NOT NULL, `rejectionReason` TEXT, `deviceModel` TEXT NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`floorId`) REFERENCES `floors`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_magnetic_walks_floorId` ON `magnetic_walks` (`floorId`)")
        }
    }
    /** Additive: per-floor mapper source so one building can hold several mapper floors. */
    val FROM_4_TO_5 = object : Migration(4, 5) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `floors` ADD COLUMN `mapperSourceJson` TEXT")
            // Before schema 5 every mapper import was its own one-floor venue whose notes ended with the source JSON.
            // Move it onto that floor so later floors of the building can never pick it up by accident.
            db.execSQL("""
                UPDATE floors SET mapperSourceJson = (
                    SELECT substr(v.notes, instr(v.notes, char(10) || '{') + 1) FROM venues v WHERE v.id = floors.venueId)
                WHERE mapperSourceJson IS NULL AND venueId IN (
                    SELECT v.id FROM venues v WHERE v.notes LIKE 'TURN-Mapper %' AND instr(v.notes, char(10) || '{') > 0
                    AND (SELECT COUNT(*) FROM floors f WHERE f.venueId = v.id) = 1)
            """.trimIndent())
        }
    }
    /** Additive: connector label, travel time, step-free flag and on-site verification. */
    val FROM_5_TO_6 = object : Migration(5, 6) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `vertical_transitions` ADD COLUMN `label` TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE `vertical_transitions` ADD COLUMN `travelTimeSeconds` REAL NOT NULL DEFAULT 15.0")
            db.execSQL("ALTER TABLE `vertical_transitions` ADD COLUMN `accessible` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `vertical_transitions` ADD COLUMN `verifiedOnSite` INTEGER NOT NULL DEFAULT 0")
            // Lifts and ramps are step-free by nature; older rows predate the flag.
            db.execSQL("UPDATE `vertical_transitions` SET `accessible` = 1 WHERE `kind` IN ('LIFT', 'ELEVATOR', 'RAMP')")
        }
    }
    /** Additive: the floor-tracking event log of multi-floor sessions. No existing table changes. */
    val FROM_6_TO_7 = object : Migration(6, 7) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `floor_transition_events` (`id` TEXT NOT NULL, `positioningSessionId` TEXT NOT NULL, `sequence` INTEGER NOT NULL, `type` TEXT NOT NULL, `fromFloorId` TEXT, `toFloorId` TEXT, `linkId` TEXT, `movedShare` REAL, `floorVoteAfter` REAL, `heightDeltaMetres` REAL, `pressureHpa` REAL, `barometerHealth` TEXT NOT NULL, `sensorTimestampNanos` INTEGER, `timestampEpochMillis` INTEGER NOT NULL, `detail` TEXT NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`positioningSessionId`) REFERENCES `positioning_sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`fromFloorId`) REFERENCES `floors`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL , FOREIGN KEY(`toFloorId`) REFERENCES `floors`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL , FOREIGN KEY(`linkId`) REFERENCES `vertical_transitions`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_floor_transition_events_positioningSessionId_sequence` ON `floor_transition_events` (`positioningSessionId`, `sequence`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_floor_transition_events_fromFloorId` ON `floor_transition_events` (`fromFloorId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_floor_transition_events_toFloorId` ON `floor_transition_events` (`toFloorId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_floor_transition_events_linkId` ON `floor_transition_events` (`linkId`)")
        }
    }
    /** Additive: per-floor elevation and the optional floor-to-floor frame alignment. Existing rows keep NULL (unchanged behaviour). */
    val FROM_7_TO_8 = object : Migration(7, 8) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `floors` ADD COLUMN `elevationMetres` REAL")
            db.execSQL("ALTER TABLE `floors` ADD COLUMN `frameAlignmentJson` TEXT")
        }
    }
    val ALL: Array<Migration> = arrayOf(FROM_1_TO_2,FROM_2_TO_3,FROM_3_TO_4,FROM_4_TO_5,FROM_5_TO_6,FROM_6_TO_7,FROM_7_TO_8)
}
