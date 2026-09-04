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
    ],
    version = TurnDatabase.SCHEMA_VERSION,
    exportSchema = false,
)
@TypeConverters(RoomConverters::class)
abstract class TurnDatabase : RoomDatabase() {
    abstract fun venueDao(): VenueDao
    abstract fun floorPlanDao(): FloorPlanDao
    abstract fun surveyDao(): SurveyDao
    abstract fun positioningDao(): PositioningDao
    abstract fun evaluationDao(): EvaluationDao
    abstract fun bleDao(): BleDao
    abstract fun backupDao(): BackupDao

    companion object {
        const val SCHEMA_VERSION = 1
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
    val ALL: Array<Migration> = emptyArray()
}
