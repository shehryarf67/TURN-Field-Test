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
    abstract fun surveyDao(): SurveyDao
    abstract fun positioningDao(): PositioningDao
    abstract fun evaluationDao(): EvaluationDao
    abstract fun bleDao(): BleDao
    abstract fun backupDao(): BackupDao
    abstract fun magneticDao(): MagneticDao

    companion object {
        const val SCHEMA_VERSION = 4
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
    val ALL: Array<Migration> = arrayOf(FROM_1_TO_2,FROM_2_TO_3,FROM_3_TO_4)
}
