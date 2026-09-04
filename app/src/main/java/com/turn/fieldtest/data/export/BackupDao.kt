package com.turn.fieldtest.data.export

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.turn.fieldtest.data.local.*

/** Lossless Room export/import boundary. Imports merge by stable ID and never silently wipe data. */
@Dao
interface BackupDao {
    @Query("SELECT * FROM venues ORDER BY id") suspend fun venues(): List<VenueEntity>
    @Query("SELECT * FROM floors ORDER BY id") suspend fun floors(): List<FloorEntity>
    @Query("SELECT * FROM floor_plan_assets ORDER BY id") suspend fun floorPlanAssets(): List<FloorPlanAssetEntity>
    @Query("SELECT * FROM walkable_regions ORDER BY id") suspend fun walkableRegions(): List<WalkableRegionEntity>
    @Query("SELECT * FROM wall_segments ORDER BY id") suspend fun wallSegments(): List<WallSegmentEntity>
    @Query("SELECT * FROM vertical_transitions ORDER BY id") suspend fun verticalTransitions(): List<VerticalTransitionEntity>
    @Query("SELECT * FROM points_of_interest ORDER BY id") suspend fun pointsOfInterest(): List<PointOfInterestEntity>
    @Query("SELECT * FROM reference_points ORDER BY id") suspend fun referencePoints(): List<ReferencePointEntity>
    @Query("SELECT * FROM qr_anchors ORDER BY id") suspend fun qrAnchors(): List<QrAnchorEntity>
    @Query("SELECT * FROM survey_sessions ORDER BY id") suspend fun surveySessions(): List<SurveySessionEntity>
    @Query("SELECT * FROM wifi_scan_snapshots ORDER BY id") suspend fun wifiScanSnapshots(): List<WifiScanSnapshotEntity>
    @Query("SELECT * FROM wifi_observations ORDER BY id") suspend fun wifiObservations(): List<WifiObservationEntity>
    @Query("SELECT * FROM aggregated_wifi_fingerprints ORDER BY id") suspend fun aggregatedWifiFingerprints(): List<AggregatedWifiFingerprintEntity>
    @Query("SELECT * FROM sensor_sessions ORDER BY id") suspend fun sensorSessions(): List<SensorSessionEntity>
    @Query("SELECT * FROM pdr_events ORDER BY id") suspend fun pdrEvents(): List<PdrEventEntity>
    @Query("SELECT * FROM positioning_sessions ORDER BY id") suspend fun positioningSessions(): List<PositioningSessionEntity>
    @Query("SELECT * FROM position_estimates ORDER BY id") suspend fun positionEstimates(): List<PositionEstimateEntity>
    @Query("SELECT * FROM correction_events ORDER BY id") suspend fun correctionEvents(): List<CorrectionEventEntity>
    @Query("SELECT * FROM test_runs ORDER BY id") suspend fun testRuns(): List<TestRunEntity>
    @Query("SELECT * FROM test_checkpoints ORDER BY id") suspend fun testCheckpoints(): List<TestCheckpointEntity>
    @Query("SELECT * FROM test_samples ORDER BY id") suspend fun testSamples(): List<TestSampleEntity>
    @Query("SELECT * FROM beacon_definitions ORDER BY id") suspend fun beaconDefinitions(): List<BeaconDefinitionEntity>
    @Query("SELECT * FROM ble_observations ORDER BY id") suspend fun bleObservations(): List<BleObservationEntity>
    @Query("SELECT * FROM aggregated_ble_fingerprints ORDER BY id") suspend fun aggregatedBleFingerprints(): List<AggregatedBleFingerprintEntity>

    @Upsert suspend fun upsertVenues(values: List<VenueEntity>)
    @Upsert suspend fun upsertFloors(values: List<FloorEntity>)
    @Upsert suspend fun upsertFloorPlanAssets(values: List<FloorPlanAssetEntity>)
    @Upsert suspend fun upsertWalkableRegions(values: List<WalkableRegionEntity>)
    @Upsert suspend fun upsertWallSegments(values: List<WallSegmentEntity>)
    @Upsert suspend fun upsertVerticalTransitions(values: List<VerticalTransitionEntity>)
    @Upsert suspend fun upsertPointsOfInterest(values: List<PointOfInterestEntity>)
    @Upsert suspend fun upsertReferencePoints(values: List<ReferencePointEntity>)
    @Upsert suspend fun upsertQrAnchors(values: List<QrAnchorEntity>)
    @Upsert suspend fun upsertSurveySessions(values: List<SurveySessionEntity>)
    @Upsert suspend fun upsertWifiScanSnapshots(values: List<WifiScanSnapshotEntity>)
    @Upsert suspend fun upsertWifiObservations(values: List<WifiObservationEntity>)
    @Upsert suspend fun upsertAggregatedWifiFingerprints(values: List<AggregatedWifiFingerprintEntity>)
    @Upsert suspend fun upsertSensorSessions(values: List<SensorSessionEntity>)
    @Upsert suspend fun upsertPdrEvents(values: List<PdrEventEntity>)
    @Upsert suspend fun upsertPositioningSessions(values: List<PositioningSessionEntity>)
    @Upsert suspend fun upsertPositionEstimates(values: List<PositionEstimateEntity>)
    @Upsert suspend fun upsertCorrectionEvents(values: List<CorrectionEventEntity>)
    @Upsert suspend fun upsertTestRuns(values: List<TestRunEntity>)
    @Upsert suspend fun upsertTestCheckpoints(values: List<TestCheckpointEntity>)
    @Upsert suspend fun upsertTestSamples(values: List<TestSampleEntity>)
    @Upsert suspend fun upsertBeaconDefinitions(values: List<BeaconDefinitionEntity>)
    @Upsert suspend fun upsertBleObservations(values: List<BleObservationEntity>)
    @Upsert suspend fun upsertAggregatedBleFingerprints(values: List<AggregatedBleFingerprintEntity>)

    @Transaction
    suspend fun createExport(exportedAtEpochMillis: Long): TurnDatabaseExport = TurnDatabaseExport(
        exportedAtEpochMillis = exportedAtEpochMillis,
        venues = venues(),
        floors = floors(),
        floorPlanAssets = floorPlanAssets(),
        walkableRegions = walkableRegions(),
        wallSegments = wallSegments(),
        verticalTransitions = verticalTransitions(),
        pointsOfInterest = pointsOfInterest(),
        referencePoints = referencePoints(),
        qrAnchors = qrAnchors(),
        surveySessions = surveySessions(),
        wifiScanSnapshots = wifiScanSnapshots(),
        wifiObservations = wifiObservations(),
        aggregatedWifiFingerprints = aggregatedWifiFingerprints(),
        sensorSessions = sensorSessions(),
        pdrEvents = pdrEvents(),
        positioningSessions = positioningSessions(),
        positionEstimates = positionEstimates(),
        correctionEvents = correctionEvents(),
        testRuns = testRuns(),
        testCheckpoints = testCheckpoints(),
        testSamples = testSamples(),
        beaconDefinitions = beaconDefinitions(),
        bleObservations = bleObservations(),
        aggregatedBleFingerprints = aggregatedBleFingerprints(),
    )

    @Transaction
    suspend fun mergeValidated(value: TurnDatabaseExport) {
        val errors = value.validationErrors()
        require(errors.isEmpty()) { errors.joinToString(prefix = "Invalid TURN import: ", separator = "; ") }
        upsertVenues(value.venues)
        upsertFloors(value.floors)
        upsertFloorPlanAssets(value.floorPlanAssets)
        upsertWalkableRegions(value.walkableRegions)
        upsertWallSegments(value.wallSegments)
        upsertVerticalTransitions(value.verticalTransitions)
        upsertPointsOfInterest(value.pointsOfInterest)
        upsertReferencePoints(value.referencePoints)
        upsertQrAnchors(value.qrAnchors)
        upsertSurveySessions(value.surveySessions)
        upsertWifiScanSnapshots(value.wifiScanSnapshots)
        upsertWifiObservations(value.wifiObservations)
        upsertAggregatedWifiFingerprints(value.aggregatedWifiFingerprints)
        upsertSensorSessions(value.sensorSessions)
        upsertPdrEvents(value.pdrEvents)
        upsertPositioningSessions(value.positioningSessions)
        upsertPositionEstimates(value.positionEstimates)
        upsertCorrectionEvents(value.correctionEvents)
        upsertTestRuns(value.testRuns)
        upsertTestCheckpoints(value.testCheckpoints)
        upsertTestSamples(value.testSamples)
        upsertBeaconDefinitions(value.beaconDefinitions)
        upsertBleObservations(value.bleObservations)
        upsertAggregatedBleFingerprints(value.aggregatedBleFingerprints)
    }
}

class RoomBackupRepository(private val dao: BackupDao) {
    suspend fun createExport(exportedAtEpochMillis: Long): TurnDatabaseExport =
        dao.createExport(exportedAtEpochMillis)

    suspend fun mergeValidated(value: TurnDatabaseExport) = dao.mergeValidated(value)
}
