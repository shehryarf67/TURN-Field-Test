package com.turn.fieldtest.data.repository

import com.turn.fieldtest.data.local.AggregatedBleFingerprintEntity
import com.turn.fieldtest.data.local.AggregatedWifiFingerprintEntity
import com.turn.fieldtest.data.local.BeaconDefinitionEntity
import com.turn.fieldtest.data.local.BleObservationEntity
import com.turn.fieldtest.data.local.CorrectionEventEntity
import com.turn.fieldtest.data.local.FloorEntity
import com.turn.fieldtest.data.local.FloorPlanAssetEntity
import com.turn.fieldtest.data.local.PdrEventEntity
import com.turn.fieldtest.data.local.PointOfInterestEntity
import com.turn.fieldtest.data.local.PositionEstimateEntity
import com.turn.fieldtest.data.local.PositioningSessionEntity
import com.turn.fieldtest.data.local.QrAnchorEntity
import com.turn.fieldtest.data.local.ReferencePointEntity
import com.turn.fieldtest.data.local.SensorSessionEntity
import com.turn.fieldtest.data.local.SurveySessionEntity
import com.turn.fieldtest.data.local.TestCheckpointEntity
import com.turn.fieldtest.data.local.TestRunEntity
import com.turn.fieldtest.data.local.TestSampleEntity
import com.turn.fieldtest.data.local.VenueEntity
import com.turn.fieldtest.data.local.VerticalTransitionEntity
import com.turn.fieldtest.data.local.WalkableRegionEntity
import com.turn.fieldtest.data.local.WallSegmentEntity
import com.turn.fieldtest.data.local.WifiObservationEntity
import com.turn.fieldtest.data.local.WifiScanSnapshotEntity
import kotlinx.coroutines.flow.Flow

interface VenueRepository {
    fun observeVenues(): Flow<List<VenueEntity>>
    suspend fun venue(id: String): VenueEntity?
    suspend fun save(venue: VenueEntity)
    suspend fun delete(venue: VenueEntity)
}

interface FloorPlanRepository {
    fun observeFloors(venueId: String): Flow<List<FloorEntity>>
    suspend fun floor(id: String): FloorEntity?
    suspend fun save(floor: FloorEntity)
    suspend fun delete(floor: FloorEntity)
    fun observeAsset(floorId: String): Flow<FloorPlanAssetEntity?>
    suspend fun saveAsset(asset: FloorPlanAssetEntity)
    suspend fun deleteAsset(floorId: String)
    fun observeWalkableRegions(floorId: String): Flow<List<WalkableRegionEntity>>
    suspend fun save(region: WalkableRegionEntity)
    suspend fun delete(region: WalkableRegionEntity)
    fun observeWalls(floorId: String): Flow<List<WallSegmentEntity>>
    suspend fun save(wall: WallSegmentEntity)
    suspend fun delete(wall: WallSegmentEntity)
    fun observeTransitions(floorId: String): Flow<List<VerticalTransitionEntity>>
    suspend fun save(transition: VerticalTransitionEntity)
    suspend fun delete(transition: VerticalTransitionEntity)
    fun observePointsOfInterest(floorId: String): Flow<List<PointOfInterestEntity>>
    suspend fun save(point: PointOfInterestEntity)
    suspend fun delete(point: PointOfInterestEntity)
    fun observeReferencePoints(floorId: String): Flow<List<ReferencePointEntity>>
    suspend fun referencePoint(id: String): ReferencePointEntity?
    suspend fun save(point: ReferencePointEntity)
    suspend fun delete(point: ReferencePointEntity)
    fun observeQrAnchors(floorId: String): Flow<List<QrAnchorEntity>>
    suspend fun qrAnchor(anchorId: String): QrAnchorEntity?
    suspend fun save(anchor: QrAnchorEntity)
    suspend fun delete(anchor: QrAnchorEntity)
}

interface SurveyRepository {
    fun observeSessions(venueId: String): Flow<List<SurveySessionEntity>>
    suspend fun session(id: String): SurveySessionEntity?
    suspend fun save(session: SurveySessionEntity)
    suspend fun storeSnapshot(snapshot: WifiScanSnapshotEntity, observations: List<WifiObservationEntity>)
    fun observeSnapshots(sessionId: String): Flow<List<WifiScanSnapshotEntity>>
    suspend fun snapshotsForAggregation(sessionId: String): List<WifiScanSnapshotEntity>
    suspend fun observations(snapshotId: String): List<WifiObservationEntity>
    suspend fun freshObservationsForAggregation(sessionId: String): List<WifiObservationEntity>
    suspend fun saveAggregates(fingerprints: List<AggregatedWifiFingerprintEntity>)
    fun observeAggregates(referencePointId: String): Flow<List<AggregatedWifiFingerprintEntity>>
    suspend fun aggregatesForSession(sessionId: String): List<AggregatedWifiFingerprintEntity>
    suspend fun matchingFingerprints(venueId: String): List<AggregatedWifiFingerprintEntity>
    suspend fun setExcluded(id: String, excluded: Boolean, reason: String?)
}

interface PositioningRepository {
    suspend fun saveSensorSession(session: SensorSessionEntity)
    suspend fun savePdrEvent(event: PdrEventEntity)
    fun observePdrEvents(sensorSessionId: String): Flow<List<PdrEventEntity>>
    suspend fun saveSession(session: PositioningSessionEntity)
    suspend fun session(id: String): PositioningSessionEntity?
    suspend fun saveEstimate(estimate: PositionEstimateEntity, correction: CorrectionEventEntity? = null)
    fun observeEstimates(sessionId: String): Flow<List<PositionEstimateEntity>>
    suspend fun latestEstimate(sessionId: String, method: String): PositionEstimateEntity?
    fun observeCorrections(sessionId: String): Flow<List<CorrectionEventEntity>>
}

interface EvaluationRepository {
    suspend fun saveRun(run: TestRunEntity)
    fun observeRuns(venueId: String): Flow<List<TestRunEntity>>
    suspend fun saveCheckpoint(checkpoint: TestCheckpointEntity)
    suspend fun deleteCheckpoint(checkpoint: TestCheckpointEntity)
    fun observeCheckpoints(venueId: String): Flow<List<TestCheckpointEntity>>
    suspend fun saveSample(sample: TestSampleEntity)
    fun observeSamples(runId: String): Flow<List<TestSampleEntity>>
    suspend fun samplesForExport(runId: String): List<TestSampleEntity>
}

interface BleRepository {
    suspend fun saveBeacon(beacon: BeaconDefinitionEntity)
    suspend fun deleteBeacon(beacon: BeaconDefinitionEntity)
    fun observeBeacons(venueId: String): Flow<List<BeaconDefinitionEntity>>
    suspend fun enabledBeacons(venueId: String): List<BeaconDefinitionEntity>
    suspend fun saveObservations(observations: List<BleObservationEntity>)
    suspend fun saveAggregates(fingerprints: List<AggregatedBleFingerprintEntity>)
    fun observeAggregates(referencePointId: String): Flow<List<AggregatedBleFingerprintEntity>>
}
