package com.turn.fieldtest.data.export

import com.turn.fieldtest.data.local.*
import kotlinx.serialization.Serializable

/** Lossless, versioned JSON envelope suitable for a complete offline Room backup/export. */
@Serializable
data class TurnDatabaseExport(
    val schemaVersion: Int = CURRENT_EXPORT_SCHEMA,
    val exportedAtEpochMillis: Long,
    val venues: List<VenueEntity> = emptyList(),
    val floors: List<FloorEntity> = emptyList(),
    val floorPlanAssets: List<FloorPlanAssetEntity> = emptyList(),
    val walkableRegions: List<WalkableRegionEntity> = emptyList(),
    val wallSegments: List<WallSegmentEntity> = emptyList(),
    val verticalTransitions: List<VerticalTransitionEntity> = emptyList(),
    val pointsOfInterest: List<PointOfInterestEntity> = emptyList(),
    val referencePoints: List<ReferencePointEntity> = emptyList(),
    val qrAnchors: List<QrAnchorEntity> = emptyList(),
    val surveySessions: List<SurveySessionEntity> = emptyList(),
    val wifiScanSnapshots: List<WifiScanSnapshotEntity> = emptyList(),
    val wifiObservations: List<WifiObservationEntity> = emptyList(),
    val aggregatedWifiFingerprints: List<AggregatedWifiFingerprintEntity> = emptyList(),
    val sensorSessions: List<SensorSessionEntity> = emptyList(),
    val pdrEvents: List<PdrEventEntity> = emptyList(),
    val positioningSessions: List<PositioningSessionEntity> = emptyList(),
    val positionEstimates: List<PositionEstimateEntity> = emptyList(),
    val correctionEvents: List<CorrectionEventEntity> = emptyList(),
    val testRuns: List<TestRunEntity> = emptyList(),
    val testCheckpoints: List<TestCheckpointEntity> = emptyList(),
    val testSamples: List<TestSampleEntity> = emptyList(),
    val beaconDefinitions: List<BeaconDefinitionEntity> = emptyList(),
    val bleObservations: List<BleObservationEntity> = emptyList(),
    val aggregatedBleFingerprints: List<AggregatedBleFingerprintEntity> = emptyList(),
) {
    /** Validate identity and ownership before any repository writes occur. */
    fun validationErrors(): List<String> = buildList {
        if (schemaVersion != CURRENT_EXPORT_SCHEMA) add("Unsupported export schema $schemaVersion")
        checkUnique("venue", venues.map(VenueEntity::id))
        checkUnique("floor", floors.map(FloorEntity::id))
        checkUnique("reference point", referencePoints.map(ReferencePointEntity::id))
        checkUnique("survey session", surveySessions.map(SurveySessionEntity::id))
        checkUnique("Wi-Fi snapshot", wifiScanSnapshots.map(WifiScanSnapshotEntity::id))
        checkUnique("positioning session", positioningSessions.map(PositioningSessionEntity::id))
        checkUnique("test checkpoint", testCheckpoints.map(TestCheckpointEntity::id))

        val venueIds = venues.mapTo(hashSetOf(), VenueEntity::id)
        val floorIds = floors.mapTo(hashSetOf(), FloorEntity::id)
        val referenceIds = referencePoints.mapTo(hashSetOf(), ReferencePointEntity::id)
        val surveyIds = surveySessions.mapTo(hashSetOf(), SurveySessionEntity::id)
        val snapshotIds = wifiScanSnapshots.mapTo(hashSetOf(), WifiScanSnapshotEntity::id)
        val sensorIds = sensorSessions.mapTo(hashSetOf(), SensorSessionEntity::id)
        val positioningIds = positioningSessions.mapTo(hashSetOf(), PositioningSessionEntity::id)
        val runIds = testRuns.mapTo(hashSetOf(), TestRunEntity::id)
        val checkpointIds = testCheckpoints.mapTo(hashSetOf(), TestCheckpointEntity::id)

        floors.filter { it.venueId !in venueIds }.forEach { add("Floor ${it.id} references unknown venue ${it.venueId}") }
        floorPlanAssets.filter { it.floorId !in floorIds }.forEach { add("Floor asset ${it.id} references unknown floor") }
        walkableRegions.filter { it.floorId !in floorIds }.forEach { add("Walkable region ${it.id} references unknown floor") }
        wallSegments.filter { it.floorId !in floorIds }.forEach { add("Wall ${it.id} references unknown floor") }
        verticalTransitions.filter { it.fromFloorId !in floorIds || it.toFloorId !in floorIds }
            .forEach { add("Vertical transition ${it.id} references an unknown floor") }
        pointsOfInterest.filter { it.floorId !in floorIds }.forEach { add("POI ${it.id} references unknown floor") }
        referencePoints.filter { it.floorId !in floorIds }.forEach { add("Reference point ${it.id} references unknown floor") }
        qrAnchors.filter { it.floorId !in floorIds }.forEach { add("QR anchor ${it.id} references unknown floor") }
        surveySessions.filter { it.venueId !in venueIds || it.floorId !in floorIds || it.referencePointId !in referenceIds }
            .forEach { add("Survey session ${it.id} has an invalid venue, floor or reference point") }
        wifiScanSnapshots.filter { it.surveySessionId !in surveyIds }.forEach { add("Wi-Fi snapshot ${it.id} references unknown survey") }
        wifiObservations.filter { it.snapshotId !in snapshotIds || it.surveySessionId !in surveyIds || it.referencePointId !in referenceIds }
            .forEach { add("Wi-Fi observation ${it.id} has an invalid owner") }
        aggregatedWifiFingerprints.filter { it.surveySessionId !in surveyIds || it.referencePointId !in referenceIds }
            .forEach { add("Wi-Fi fingerprint ${it.id} has an invalid owner") }
        pdrEvents.filter { it.sensorSessionId !in sensorIds }.forEach { add("PDR event ${it.id} references unknown sensor session") }
        positioningSessions.filter { it.venueId !in venueIds || (it.initialFloorId != null && it.initialFloorId !in floorIds) }
            .forEach { add("Positioning session ${it.id} has an invalid venue or floor") }
        positionEstimates.filter { it.positioningSessionId !in positioningIds || (it.floorId != null && it.floorId !in floorIds) }
            .forEach { add("Position estimate ${it.id} has an invalid owner or floor") }
        correctionEvents.filter { it.positioningSessionId !in positioningIds }.forEach { add("Correction ${it.id} has an invalid owner") }
        testCheckpoints.filter { it.venueId !in venueIds || it.floorId !in floorIds }.forEach { add("Checkpoint ${it.id} has an invalid venue or floor") }
        testRuns.filter { it.venueId !in venueIds }.forEach { add("Test run ${it.id} references unknown venue") }
        testSamples.filter { it.testRunId !in runIds || it.checkpointId !in checkpointIds || it.trueFloorId !in floorIds }
            .forEach { add("Test sample ${it.id} has invalid ground-truth ownership") }
        beaconDefinitions.filter { it.venueId !in venueIds || (it.floorId != null && it.floorId !in floorIds) }
            .forEach { add("Beacon ${it.id} has an invalid venue or floor") }
        bleObservations.filter { it.surveySessionId !in surveyIds }.forEach { add("BLE observation ${it.id} references unknown survey") }
        aggregatedBleFingerprints.filter { it.surveySessionId !in surveyIds || it.referencePointId !in referenceIds }
            .forEach { add("BLE fingerprint ${it.id} has an invalid owner") }
    }

    private fun MutableList<String>.checkUnique(label: String, ids: List<String>) {
        ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.forEach {
            add("Duplicate $label ID '$it'")
        }
    }

    companion object {
        const val CURRENT_EXPORT_SCHEMA = 1
    }
}

/** Smaller portable definition when radio traces and test data should not be shared. */
@Serializable
data class VenueDefinitionExport(
    val schemaVersion: Int = TurnDatabaseExport.CURRENT_EXPORT_SCHEMA,
    val venue: VenueEntity,
    val floors: List<FloorEntity>,
    val floorPlanAssets: List<FloorPlanAssetEntity> = emptyList(),
    val walkableRegions: List<WalkableRegionEntity> = emptyList(),
    val wallSegments: List<WallSegmentEntity> = emptyList(),
    val verticalTransitions: List<VerticalTransitionEntity> = emptyList(),
    val pointsOfInterest: List<PointOfInterestEntity> = emptyList(),
    val referencePoints: List<ReferencePointEntity> = emptyList(),
    val qrAnchors: List<QrAnchorEntity> = emptyList(),
    val testCheckpoints: List<TestCheckpointEntity> = emptyList(),
    val beaconDefinitions: List<BeaconDefinitionEntity> = emptyList(),
)
