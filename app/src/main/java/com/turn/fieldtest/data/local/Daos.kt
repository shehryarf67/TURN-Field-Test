package com.turn.fieldtest.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface VenueDao {
    @Query("SELECT * FROM venues ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<VenueEntity>>

    @Query("SELECT * FROM venues WHERE id = :id")
    suspend fun get(id: String): VenueEntity?

    @Upsert
    suspend fun upsert(venue: VenueEntity)

    @Delete
    suspend fun delete(venue: VenueEntity)
}

@Dao
interface FloorPlanDao {
    @Query("SELECT * FROM floors WHERE venueId = :venueId ORDER BY levelNumber")
    fun observeFloors(venueId: String): Flow<List<FloorEntity>>

    @Query("SELECT * FROM floors WHERE id = :floorId")
    suspend fun getFloor(floorId: String): FloorEntity?

    @Upsert
    suspend fun upsertFloor(floor: FloorEntity)

    @Delete
    suspend fun deleteFloor(floor: FloorEntity)

    @Query("SELECT * FROM floor_plan_assets WHERE floorId = :floorId LIMIT 1")
    fun observeAsset(floorId: String): Flow<FloorPlanAssetEntity?>

    @Upsert
    suspend fun upsertAsset(asset: FloorPlanAssetEntity)

    @Query("DELETE FROM floor_plan_assets WHERE floorId = :floorId")
    suspend fun deleteAssetForFloor(floorId: String)

    @Query("SELECT * FROM walkable_regions WHERE floorId = :floorId ORDER BY name COLLATE NOCASE")
    fun observeWalkableRegions(floorId: String): Flow<List<WalkableRegionEntity>>

    @Upsert
    suspend fun upsertWalkableRegion(region: WalkableRegionEntity)

    @Delete
    suspend fun deleteWalkableRegion(region: WalkableRegionEntity)

    @Query("SELECT * FROM wall_segments WHERE floorId = :floorId")
    fun observeWalls(floorId: String): Flow<List<WallSegmentEntity>>

    @Upsert
    suspend fun upsertWall(wall: WallSegmentEntity)

    @Delete
    suspend fun deleteWall(wall: WallSegmentEntity)

    @Query("SELECT * FROM vertical_transitions WHERE fromFloorId = :floorId OR toFloorId = :floorId")
    fun observeTransitions(floorId: String): Flow<List<VerticalTransitionEntity>>

    @Upsert
    suspend fun upsertTransition(transition: VerticalTransitionEntity)

    @Delete
    suspend fun deleteTransition(transition: VerticalTransitionEntity)

    @Query("SELECT * FROM points_of_interest WHERE floorId = :floorId ORDER BY name COLLATE NOCASE")
    fun observePointsOfInterest(floorId: String): Flow<List<PointOfInterestEntity>>

    @Upsert
    suspend fun upsertPointOfInterest(point: PointOfInterestEntity)

    @Delete
    suspend fun deletePointOfInterest(point: PointOfInterestEntity)

    @Query("SELECT * FROM reference_points WHERE floorId = :floorId ORDER BY name COLLATE NOCASE")
    fun observeReferencePoints(floorId: String): Flow<List<ReferencePointEntity>>

    @Query("SELECT * FROM reference_points WHERE id = :id")
    suspend fun getReferencePoint(id: String): ReferencePointEntity?

    @Upsert
    suspend fun upsertReferencePoint(point: ReferencePointEntity)

    @Delete
    suspend fun deleteReferencePoint(point: ReferencePointEntity)

    @Query("SELECT * FROM qr_anchors WHERE floorId = :floorId ORDER BY anchorId")
    fun observeQrAnchors(floorId: String): Flow<List<QrAnchorEntity>>

    @Query("SELECT * FROM qr_anchors WHERE anchorId = :anchorId AND enabled = 1 LIMIT 1")
    suspend fun getQrAnchorByAnchorId(anchorId: String): QrAnchorEntity?

    @Upsert
    suspend fun upsertQrAnchor(anchor: QrAnchorEntity)

    @Delete
    suspend fun deleteQrAnchor(anchor: QrAnchorEntity)
}

@Dao
interface SurveyDao {
    @Query("SELECT * FROM survey_sessions WHERE venueId = :venueId ORDER BY startedAtEpochMillis DESC")
    fun observeSessions(venueId: String): Flow<List<SurveySessionEntity>>

    @Query("SELECT * FROM survey_sessions WHERE id = :id")
    suspend fun getSession(id: String): SurveySessionEntity?

    @Upsert
    suspend fun upsertSession(session: SurveySessionEntity)

    @Upsert
    suspend fun upsertSnapshot(snapshot: WifiScanSnapshotEntity)

    @Upsert
    suspend fun upsertObservations(observations: List<WifiObservationEntity>)

    /**
     * A cached broadcast may be retained for diagnostics, but its observations are marked stale and
     * aggregation queries below intentionally use only fresh snapshots.
     */
    @Transaction
    suspend fun storeSnapshot(
        snapshot: WifiScanSnapshotEntity,
        observations: List<WifiObservationEntity>,
    ) {
        require(observations.all { it.snapshotId == snapshot.id })
        require(observations.all { it.surveySessionId == snapshot.surveySessionId })
        upsertSnapshot(snapshot)
        upsertObservations(observations)
    }

    @Query("SELECT * FROM wifi_scan_snapshots WHERE surveySessionId = :sessionId ORDER BY scanSequence")
    fun observeSnapshots(sessionId: String): Flow<List<WifiScanSnapshotEntity>>

    @Query("SELECT * FROM wifi_scan_snapshots WHERE surveySessionId = :sessionId ORDER BY scanSequence")
    suspend fun snapshotsForAggregation(sessionId: String): List<WifiScanSnapshotEntity>

    @Query("SELECT * FROM wifi_observations WHERE snapshotId = :snapshotId ORDER BY rssiDbm DESC")
    suspend fun observationsForSnapshot(snapshotId: String): List<WifiObservationEntity>

    @Query(
        """
        SELECT wifi_observations.* FROM wifi_observations
        INNER JOIN wifi_scan_snapshots ON wifi_scan_snapshots.id = wifi_observations.snapshotId
        WHERE wifi_observations.surveySessionId = :sessionId
          AND wifi_scan_snapshots.isFresh = 1
          AND wifi_scan_snapshots.resultsUpdated = 1
        ORDER BY wifi_observations.bssid, wifi_observations.observedAtEpochMillis
        """,
    )
    suspend fun freshObservationsForAggregation(sessionId: String): List<WifiObservationEntity>

    @Upsert
    suspend fun upsertAggregatedFingerprints(fingerprints: List<AggregatedWifiFingerprintEntity>)

    @Query("SELECT * FROM aggregated_wifi_fingerprints WHERE referencePointId = :referencePointId ORDER BY bssid")
    fun observeAggregatedFingerprints(referencePointId: String): Flow<List<AggregatedWifiFingerprintEntity>>

    @Query("SELECT * FROM aggregated_wifi_fingerprints WHERE surveySessionId = :sessionId ORDER BY bssid")
    suspend fun aggregatedFingerprintsForSession(sessionId: String): List<AggregatedWifiFingerprintEntity>

    @Query(
        """
        SELECT aggregated_wifi_fingerprints.* FROM aggregated_wifi_fingerprints
        INNER JOIN reference_points ON reference_points.id = aggregated_wifi_fingerprints.referencePointId
        INNER JOIN floors ON floors.id = reference_points.floorId
        WHERE floors.venueId = :venueId AND aggregated_wifi_fingerprints.excludedFromMatching = 0
        """,
    )
    suspend fun matchingFingerprintsForVenue(venueId: String): List<AggregatedWifiFingerprintEntity>

    @Query("UPDATE aggregated_wifi_fingerprints SET excludedFromMatching = :excluded, exclusionReason = :reason WHERE id = :id")
    suspend fun setFingerprintExcluded(id: String, excluded: Boolean, reason: String?)
}

@Dao
interface PositioningDao {
    @Upsert
    suspend fun upsertSensorSession(session: SensorSessionEntity)

    @Upsert
    suspend fun upsertPdrEvent(event: PdrEventEntity)

    @Query("SELECT * FROM pdr_events WHERE sensorSessionId = :sessionId ORDER BY sequence")
    fun observePdrEvents(sessionId: String): Flow<List<PdrEventEntity>>

    @Upsert
    suspend fun upsertPositioningSession(session: PositioningSessionEntity)

    @Query("SELECT * FROM positioning_sessions WHERE id = :id")
    suspend fun getPositioningSession(id: String): PositioningSessionEntity?

    @Upsert
    suspend fun upsertEstimate(estimate: PositionEstimateEntity)

    @Query("SELECT * FROM position_estimates WHERE positioningSessionId = :sessionId ORDER BY sequence, method")
    fun observeEstimates(sessionId: String): Flow<List<PositionEstimateEntity>>

    @Query("SELECT * FROM position_estimates WHERE positioningSessionId = :sessionId AND method = :method ORDER BY sequence DESC LIMIT 1")
    suspend fun latestEstimate(sessionId: String, method: String): PositionEstimateEntity?

    @Upsert
    suspend fun upsertCorrection(event: CorrectionEventEntity)

    @Query("SELECT * FROM correction_events WHERE positioningSessionId = :sessionId ORDER BY timestampEpochMillis")
    fun observeCorrections(sessionId: String): Flow<List<CorrectionEventEntity>>

    @Transaction
    suspend fun recordEstimateAndCorrection(
        estimate: PositionEstimateEntity,
        correction: CorrectionEventEntity?,
    ) {
        upsertEstimate(estimate)
        correction?.let { upsertCorrection(it) }
    }
}

@Dao
interface EvaluationDao {
    @Upsert
    suspend fun upsertRun(run: TestRunEntity)

    @Query("SELECT * FROM test_runs WHERE venueId = :venueId ORDER BY startedAtEpochMillis DESC")
    fun observeRuns(venueId: String): Flow<List<TestRunEntity>>

    @Upsert
    suspend fun upsertCheckpoint(checkpoint: TestCheckpointEntity)

    @Delete
    suspend fun deleteCheckpoint(checkpoint: TestCheckpointEntity)

    @Query("SELECT * FROM test_checkpoints WHERE venueId = :venueId AND enabled = 1 ORDER BY checkpointCode")
    fun observeCheckpoints(venueId: String): Flow<List<TestCheckpointEntity>>

    @Upsert
    suspend fun upsertSample(sample: TestSampleEntity)

    @Query("SELECT * FROM test_samples WHERE testRunId = :runId ORDER BY timestampEpochMillis")
    fun observeSamples(runId: String): Flow<List<TestSampleEntity>>

    @Query("SELECT * FROM test_samples WHERE testRunId = :runId ORDER BY timestampEpochMillis")
    suspend fun samplesForExport(runId: String): List<TestSampleEntity>
}

@Dao
interface BleDao {
    @Upsert
    suspend fun upsertBeacon(beacon: BeaconDefinitionEntity)

    @Delete
    suspend fun deleteBeacon(beacon: BeaconDefinitionEntity)

    @Query("SELECT * FROM beacon_definitions WHERE venueId = :venueId ORDER BY displayName COLLATE NOCASE")
    fun observeBeacons(venueId: String): Flow<List<BeaconDefinitionEntity>>

    @Query("SELECT * FROM beacon_definitions WHERE venueId = :venueId AND enabled = 1")
    suspend fun enabledBeacons(venueId: String): List<BeaconDefinitionEntity>

    @Upsert
    suspend fun upsertObservations(observations: List<BleObservationEntity>)

    @Upsert
    suspend fun upsertAggregatedFingerprints(fingerprints: List<AggregatedBleFingerprintEntity>)

    @Query("SELECT * FROM aggregated_ble_fingerprints WHERE referencePointId = :referencePointId ORDER BY stableIdentifier")
    fun observeAggregatedFingerprints(referencePointId: String): Flow<List<AggregatedBleFingerprintEntity>>
}
