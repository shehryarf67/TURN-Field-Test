package com.turn.fieldtest.data.repository

import com.turn.fieldtest.data.local.*
import kotlinx.coroutines.flow.Flow

class RoomVenueRepository(private val dao: VenueDao) : VenueRepository {
    override fun observeVenues(): Flow<List<VenueEntity>> = dao.observeAll()
    override suspend fun venue(id: String): VenueEntity? = dao.get(id)
    override suspend fun save(venue: VenueEntity) = dao.upsert(venue)
    override suspend fun delete(venue: VenueEntity) = dao.delete(venue)
}

class RoomFloorPlanRepository(private val dao: FloorPlanDao) : FloorPlanRepository {
    override fun observeFloors(venueId: String) = dao.observeFloors(venueId)
    override suspend fun floor(id: String) = dao.getFloor(id)
    override suspend fun save(floor: FloorEntity) = dao.upsertFloor(floor)
    override suspend fun delete(floor: FloorEntity) = dao.deleteFloor(floor)
    override fun observeAsset(floorId: String) = dao.observeAsset(floorId)
    override suspend fun saveAsset(asset: FloorPlanAssetEntity) = dao.upsertAsset(asset)
    override suspend fun deleteAsset(floorId: String) = dao.deleteAssetForFloor(floorId)
    override fun observeWalkableRegions(floorId: String) = dao.observeWalkableRegions(floorId)
    override suspend fun save(region: WalkableRegionEntity) = dao.upsertWalkableRegion(region)
    override suspend fun delete(region: WalkableRegionEntity) = dao.deleteWalkableRegion(region)
    override fun observeWalls(floorId: String) = dao.observeWalls(floorId)
    override suspend fun save(wall: WallSegmentEntity) = dao.upsertWall(wall)
    override suspend fun delete(wall: WallSegmentEntity) = dao.deleteWall(wall)
    override fun observeTransitions(floorId: String) = dao.observeTransitions(floorId)
    override suspend fun save(transition: VerticalTransitionEntity) = dao.upsertTransition(transition)
    override suspend fun delete(transition: VerticalTransitionEntity) = dao.deleteTransition(transition)
    override fun observePointsOfInterest(floorId: String) = dao.observePointsOfInterest(floorId)
    override suspend fun save(point: PointOfInterestEntity) = dao.upsertPointOfInterest(point)
    override suspend fun delete(point: PointOfInterestEntity) = dao.deletePointOfInterest(point)
    override fun observeReferencePoints(floorId: String) = dao.observeReferencePoints(floorId)
    override suspend fun referencePoint(id: String) = dao.getReferencePoint(id)
    override suspend fun save(point: ReferencePointEntity) = dao.upsertReferencePoint(point)
    override suspend fun delete(point: ReferencePointEntity) = dao.deleteReferencePoint(point)
    override fun observeQrAnchors(floorId: String) = dao.observeQrAnchors(floorId)
    override suspend fun qrAnchor(anchorId: String) = dao.getQrAnchorByAnchorId(anchorId)
    override suspend fun save(anchor: QrAnchorEntity) = dao.upsertQrAnchor(anchor)
    override suspend fun delete(anchor: QrAnchorEntity) = dao.deleteQrAnchor(anchor)
}

class RoomSurveyRepository(private val dao: SurveyDao) : SurveyRepository {
    override fun observeSessions(venueId: String) = dao.observeSessions(venueId)
    override suspend fun session(id: String) = dao.getSession(id)
    override suspend fun save(session: SurveySessionEntity) = dao.upsertSession(session)
    override suspend fun storeSnapshot(snapshot: WifiScanSnapshotEntity, observations: List<WifiObservationEntity>) =
        dao.storeSnapshot(snapshot, observations)
    override fun observeSnapshots(sessionId: String) = dao.observeSnapshots(sessionId)
    override suspend fun snapshotsForAggregation(sessionId: String) = dao.snapshotsForAggregation(sessionId)
    override suspend fun observations(snapshotId: String) = dao.observationsForSnapshot(snapshotId)
    override suspend fun freshObservationsForAggregation(sessionId: String) = dao.freshObservationsForAggregation(sessionId)
    override suspend fun saveAggregates(fingerprints: List<AggregatedWifiFingerprintEntity>) = dao.upsertAggregatedFingerprints(fingerprints)
    override fun observeAggregates(referencePointId: String) = dao.observeAggregatedFingerprints(referencePointId)
    override suspend fun aggregatesForSession(sessionId: String) = dao.aggregatedFingerprintsForSession(sessionId)
    override suspend fun matchingFingerprints(venueId: String) = dao.matchingFingerprintsForVenue(venueId)
    override suspend fun setExcluded(id: String, excluded: Boolean, reason: String?) = dao.setFingerprintExcluded(id, excluded, reason)
}

class RoomPositioningRepository(private val dao: PositioningDao) : PositioningRepository {
    override suspend fun saveSensorSession(session: SensorSessionEntity) = dao.upsertSensorSession(session)
    override suspend fun savePdrEvent(event: PdrEventEntity) = dao.upsertPdrEvent(event)
    override fun observePdrEvents(sensorSessionId: String) = dao.observePdrEvents(sensorSessionId)
    override suspend fun saveSession(session: PositioningSessionEntity) = dao.upsertPositioningSession(session)
    override suspend fun session(id: String) = dao.getPositioningSession(id)
    override suspend fun saveEstimate(estimate: PositionEstimateEntity, correction: CorrectionEventEntity?) =
        dao.recordEstimateAndCorrection(estimate, correction)
    override fun observeEstimates(sessionId: String) = dao.observeEstimates(sessionId)
    override suspend fun latestEstimate(sessionId: String, method: String) = dao.latestEstimate(sessionId, method)
    override fun observeCorrections(sessionId: String) = dao.observeCorrections(sessionId)
}

class RoomEvaluationRepository(private val dao: EvaluationDao) : EvaluationRepository {
    override suspend fun saveRun(run: TestRunEntity) = dao.upsertRun(run)
    override fun observeRuns(venueId: String) = dao.observeRuns(venueId)
    override suspend fun saveCheckpoint(checkpoint: TestCheckpointEntity) = dao.upsertCheckpoint(checkpoint)
    override suspend fun deleteCheckpoint(checkpoint: TestCheckpointEntity) = dao.deleteCheckpoint(checkpoint)
    override fun observeCheckpoints(venueId: String) = dao.observeCheckpoints(venueId)
    override suspend fun saveSample(sample: TestSampleEntity) = dao.upsertSample(sample)
    override fun observeSamples(runId: String) = dao.observeSamples(runId)
    override suspend fun samplesForExport(runId: String) = dao.samplesForExport(runId)
}

class RoomBleRepository(private val dao: BleDao) : BleRepository {
    override suspend fun saveBeacon(beacon: BeaconDefinitionEntity) = dao.upsertBeacon(beacon)
    override suspend fun deleteBeacon(beacon: BeaconDefinitionEntity) = dao.deleteBeacon(beacon)
    override fun observeBeacons(venueId: String) = dao.observeBeacons(venueId)
    override suspend fun enabledBeacons(venueId: String) = dao.enabledBeacons(venueId)
    override suspend fun saveObservations(observations: List<BleObservationEntity>) = dao.upsertObservations(observations)
    override suspend fun saveAggregates(fingerprints: List<AggregatedBleFingerprintEntity>) = dao.upsertAggregatedFingerprints(fingerprints)
    override fun observeAggregates(referencePointId: String) = dao.observeAggregatedFingerprints(referencePointId)
}

data class RoomRepositories(
    val venues: VenueRepository,
    val floorPlans: FloorPlanRepository,
    val surveys: SurveyRepository,
    val positioning: PositioningRepository,
    val evaluation: EvaluationRepository,
    val ble: BleRepository,
) {
    companion object {
        fun from(database: TurnDatabase) = RoomRepositories(
            venues = RoomVenueRepository(database.venueDao()),
            floorPlans = RoomFloorPlanRepository(database.floorPlanDao()),
            surveys = RoomSurveyRepository(database.surveyDao()),
            positioning = RoomPositioningRepository(database.positioningDao()),
            evaluation = RoomEvaluationRepository(database.evaluationDao()),
            ble = RoomBleRepository(database.bleDao()),
        )
    }
}
