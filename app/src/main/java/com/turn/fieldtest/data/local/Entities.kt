package com.turn.fieldtest.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Database rows deliberately use metres and elapsed/epoch timestamps rather than display pixels.
 * Complex geometry is stored as versioned GeoJSON-compatible JSON so an image can be replaced
 * without invalidating measured data.
 */
@Entity(
    tableName = "venues",
    indices = [Index(value = ["name"]), Index(value = ["updatedAtEpochMillis"])],
)
@Serializable
data class VenueEntity(
    @PrimaryKey val id: String,
    val name: String,
    val notes: String? = null,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "floors",
    foreignKeys = [ForeignKey(entity = VenueEntity::class, parentColumns = ["id"], childColumns = ["venueId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("venueId"), Index(value = ["venueId", "levelNumber"], unique = true)],
)
@Serializable
data class FloorEntity(
    @PrimaryKey val id: String,
    val venueId: String,
    val name: String,
    val levelNumber: Int,
    val widthMetres: Double,
    val heightMetres: Double,
    val originXMetres: Double = 0.0,
    val originYMetres: Double = 0.0,
    val imageYAxisPointsDown: Boolean = true,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "floor_plan_assets",
    foreignKeys = [ForeignKey(entity = FloorEntity::class, parentColumns = ["id"], childColumns = ["floorId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["floorId"], unique = true)],
)
@Serializable
data class FloorPlanAssetEntity(
    @PrimaryKey val id: String,
    val floorId: String,
    /** Persisted Storage Access Framework URI; image bytes are not duplicated in Room. */
    val contentUri: String,
    val mimeType: String,
    val pixelWidth: Int? = null,
    val pixelHeight: Int? = null,
    val calibrationImageX1: Double? = null,
    val calibrationImageY1: Double? = null,
    val calibrationImageX2: Double? = null,
    val calibrationImageY2: Double? = null,
    val calibrationDistanceMetres: Double? = null,
    val metresPerPixel: Double? = null,
    val importedAtEpochMillis: Long,
)

@Entity(
    tableName = "walkable_regions",
    foreignKeys = [ForeignKey(entity = FloorEntity::class, parentColumns = ["id"], childColumns = ["floorId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("floorId")],
)
@Serializable
data class WalkableRegionEntity(
    @PrimaryKey val id: String,
    val floorId: String,
    val name: String,
    /** Versioned array of metric {x,y} vertices. */
    val polygonJson: String,
    val enabled: Boolean = true,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "wall_segments",
    foreignKeys = [ForeignKey(entity = FloorEntity::class, parentColumns = ["id"], childColumns = ["floorId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("floorId")],
)
@Serializable
data class WallSegmentEntity(
    @PrimaryKey val id: String,
    val floorId: String,
    val startXMetres: Double,
    val startYMetres: Double,
    val endXMetres: Double,
    val endYMetres: Double,
    val kind: String = "WALL",
    val enabled: Boolean = true,
)

@Entity(
    tableName = "vertical_transitions",
    foreignKeys = [
        ForeignKey(entity = FloorEntity::class, parentColumns = ["id"], childColumns = ["fromFloorId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = FloorEntity::class, parentColumns = ["id"], childColumns = ["toFloorId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("fromFloorId"), Index("toFloorId"), Index(value = ["fromFloorId", "toFloorId", "kind"])],
)
@Serializable
data class VerticalTransitionEntity(
    @PrimaryKey val id: String,
    val fromFloorId: String,
    val toFloorId: String,
    val kind: String,
    val fromXMetres: Double,
    val fromYMetres: Double,
    val toXMetres: Double,
    val toYMetres: Double,
    val activationRadiusMetres: Double = 2.0,
    val bidirectional: Boolean = true,
    val enabled: Boolean = true,
)

@Entity(
    tableName = "points_of_interest",
    foreignKeys = [ForeignKey(entity = FloorEntity::class, parentColumns = ["id"], childColumns = ["floorId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("floorId"), Index(value = ["floorId", "name"])],
)
@Serializable
data class PointOfInterestEntity(
    @PrimaryKey val id: String,
    val floorId: String,
    val name: String,
    val kind: String,
    val xMetres: Double,
    val yMetres: Double,
    val notes: String? = null,
)

@Entity(
    tableName = "reference_points",
    foreignKeys = [ForeignKey(entity = FloorEntity::class, parentColumns = ["id"], childColumns = ["floorId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("floorId"), Index(value = ["floorId", "name"], unique = true)],
)
@Serializable
data class ReferencePointEntity(
    @PrimaryKey val id: String,
    val floorId: String,
    val name: String,
    val xMetres: Double,
    val yMetres: Double,
    val isInsideWalkableSpace: Boolean,
    val notes: String? = null,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "qr_anchors",
    foreignKeys = [ForeignKey(entity = FloorEntity::class, parentColumns = ["id"], childColumns = ["floorId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("floorId"), Index(value = ["anchorId"], unique = true)],
)
@Serializable
data class QrAnchorEntity(
    @PrimaryKey val id: String,
    val floorId: String,
    val anchorId: String,
    val xMetres: Double,
    val yMetres: Double,
    val initialDirectionDegrees: Double? = null,
    val schemaVersion: Int = 1,
    val enabled: Boolean = true,
)

@Entity(
    tableName = "survey_sessions",
    foreignKeys = [
        ForeignKey(entity = VenueEntity::class, parentColumns = ["id"], childColumns = ["venueId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = FloorEntity::class, parentColumns = ["id"], childColumns = ["floorId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ReferencePointEntity::class, parentColumns = ["id"], childColumns = ["referencePointId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("venueId"), Index("floorId"), Index("referencePointId"), Index("startedAtEpochMillis")],
)
@Serializable
data class SurveySessionEntity(
    @PrimaryKey val id: String,
    val venueId: String,
    val floorId: String,
    val referencePointId: String,
    val knownXMetres: Double,
    val knownYMetres: Double,
    val deviceManufacturer: String,
    val deviceModel: String,
    val androidVersion: String,
    val appVersion: String,
    val researcherNotes: String? = null,
    val orientationLabel: String? = null,
    val crowdConditionLabel: String? = null,
    val dataMode: String,
    val startedAtEpochMillis: Long,
    val endedAtEpochMillis: Long? = null,
)

@Entity(
    tableName = "wifi_scan_snapshots",
    foreignKeys = [ForeignKey(entity = SurveySessionEntity::class, parentColumns = ["id"], childColumns = ["surveySessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("surveySessionId"), Index(value = ["surveySessionId", "scanSequence"], unique = true), Index("resultsReceivedAtEpochMillis")],
)
@Serializable
data class WifiScanSnapshotEntity(
    @PrimaryKey val id: String,
    val surveySessionId: String,
    val scanSequence: Long,
    val requestedAtEpochMillis: Long,
    val resultsReceivedAtEpochMillis: Long,
    val newestResultAgeMillis: Long?,
    val requestAccepted: Boolean,
    val resultsUpdated: Boolean,
    val isFresh: Boolean,
    val isCached: Boolean,
    val visibleAccessPointCount: Int,
    val throttled: Boolean,
    val nextPermittedRequestAtEpochMillis: Long? = null,
    val failureCode: String? = null,
)

@Entity(
    tableName = "wifi_observations",
    foreignKeys = [
        ForeignKey(entity = WifiScanSnapshotEntity::class, parentColumns = ["id"], childColumns = ["snapshotId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = SurveySessionEntity::class, parentColumns = ["id"], childColumns = ["surveySessionId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ReferencePointEntity::class, parentColumns = ["id"], childColumns = ["referencePointId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("snapshotId"), Index("surveySessionId"), Index("referencePointId"), Index("bssid"), Index(value = ["snapshotId", "bssid"], unique = true)],
)
@Serializable
data class WifiObservationEntity(
    @PrimaryKey val id: String,
    val snapshotId: String,
    val surveySessionId: String,
    val referencePointId: String,
    val bssid: String,
    val ssid: String?,
    val rssiDbm: Int,
    val frequencyMhz: Int,
    val channel: Int? = null,
    val scanTimestampMicros: Long,
    val observedAtEpochMillis: Long,
    val isFresh: Boolean,
)

@Entity(
    tableName = "aggregated_wifi_fingerprints",
    foreignKeys = [
        ForeignKey(entity = ReferencePointEntity::class, parentColumns = ["id"], childColumns = ["referencePointId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = SurveySessionEntity::class, parentColumns = ["id"], childColumns = ["surveySessionId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("referencePointId"), Index("surveySessionId"), Index("bssid"), Index(value = ["surveySessionId", "referencePointId", "bssid"], unique = true)],
)
@Serializable
data class AggregatedWifiFingerprintEntity(
    @PrimaryKey val id: String,
    val referencePointId: String,
    val surveySessionId: String,
    val bssid: String,
    val medianRssiDbm: Double,
    val meanRssiDbm: Double,
    val standardDeviationDb: Double,
    val minimumRssiDbm: Int,
    val maximumRssiDbm: Int,
    val observationCount: Int,
    val eligibleFreshSnapshotCount: Int,
    val totalFreshSnapshotCount: Int,
    val detectionRate: Double,
    val unstable: Boolean = false,
    val excludedFromMatching: Boolean = false,
    val exclusionReason: String? = null,
    val calculatedAtEpochMillis: Long,
)

@Entity(tableName = "sensor_sessions", indices = [Index("startedAtEpochMillis"), Index("dataMode")])
@Serializable
data class SensorSessionEntity(
    @PrimaryKey val id: String,
    val dataMode: String,
    val selectedStepSource: String,
    val selectedHeadingSource: String,
    val initialStrideMetres: Double,
    val heightMetres: Double? = null,
    val strideScale: Double = 1.0,
    val headingOffsetDegrees: Double = 0.0,
    val startedAtEpochMillis: Long,
    val endedAtEpochMillis: Long? = null,
)

@Entity(
    tableName = "pdr_events",
    foreignKeys = [ForeignKey(entity = SensorSessionEntity::class, parentColumns = ["id"], childColumns = ["sensorSessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sensorSessionId"), Index(value = ["sensorSessionId", "sequence"], unique = true), Index("timestampEpochMillis")],
)
@Serializable
data class PdrEventEntity(
    @PrimaryKey val id: String,
    val sensorSessionId: String,
    val sequence: Long,
    val eventType: String,
    val source: String,
    val timestampEpochMillis: Long,
    val sensorTimestampNanos: Long,
    val stepDelta: Int = 0,
    val strideMetres: Double? = null,
    val headingRadians: Double? = null,
    val deltaHeadingRadians: Double? = null,
    val pressureHpa: Double? = null,
    val rawValuesJson: String? = null,
    val accepted: Boolean = true,
    val rejectionReason: String? = null,
)

@Entity(
    tableName = "positioning_sessions",
    foreignKeys = [
        ForeignKey(entity = VenueEntity::class, parentColumns = ["id"], childColumns = ["venueId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = FloorEntity::class, parentColumns = ["id"], childColumns = ["initialFloorId"], onDelete = ForeignKey.SET_NULL),
        ForeignKey(entity = SensorSessionEntity::class, parentColumns = ["id"], childColumns = ["sensorSessionId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index("venueId"), Index("initialFloorId"), Index("sensorSessionId"), Index("startedAtEpochMillis")],
)
@Serializable
data class PositioningSessionEntity(
    @PrimaryKey val id: String,
    val venueId: String,
    val initialFloorId: String? = null,
    val sensorSessionId: String? = null,
    val initializationType: String? = null,
    val dataMode: String,
    val wifiOnlyEnabled: Boolean = true,
    val particleCount: Int,
    val startedAtEpochMillis: Long,
    val endedAtEpochMillis: Long? = null,
    val failureReason: String? = null,
)

@Entity(
    tableName = "position_estimates",
    foreignKeys = [
        ForeignKey(entity = PositioningSessionEntity::class, parentColumns = ["id"], childColumns = ["positioningSessionId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = FloorEntity::class, parentColumns = ["id"], childColumns = ["floorId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index("positioningSessionId"), Index("floorId"), Index(value = ["positioningSessionId", "sequence", "method"], unique = true), Index("timestampEpochMillis")],
)
@Serializable
data class PositionEstimateEntity(
    @PrimaryKey val id: String,
    val positioningSessionId: String,
    val sequence: Long,
    val method: String,
    val floorId: String? = null,
    val xMetres: Double,
    val yMetres: Double,
    val headingRadians: Double? = null,
    val confidenceRadiusMetres: Double? = null,
    val confidenceScore: Double,
    val floorConfidence: Double,
    val particleSpreadMetres: Double? = null,
    val nearestFingerprintIdsJson: String? = null,
    val wifiResultAgeMillis: Long? = null,
    val stepCount: Long,
    val timestampEpochMillis: Long,
    val latencyMillis: Long? = null,
    val status: String,
)

@Entity(
    tableName = "correction_events",
    foreignKeys = [
        ForeignKey(entity = PositioningSessionEntity::class, parentColumns = ["id"], childColumns = ["positioningSessionId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = FloorEntity::class, parentColumns = ["id"], childColumns = ["floorId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index("positioningSessionId"), Index("floorId"), Index("timestampEpochMillis")],
)
@Serializable
data class CorrectionEventEntity(
    @PrimaryKey val id: String,
    val positioningSessionId: String,
    val floorId: String? = null,
    val type: String,
    val sourceId: String? = null,
    val priorXMetres: Double? = null,
    val priorYMetres: Double? = null,
    val correctedXMetres: Double? = null,
    val correctedYMetres: Double? = null,
    val accepted: Boolean,
    val globalRelocalization: Boolean = false,
    val reason: String? = null,
    val timestampEpochMillis: Long,
)

@Entity(
    tableName = "test_runs",
    foreignKeys = [
        ForeignKey(entity = VenueEntity::class, parentColumns = ["id"], childColumns = ["venueId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = PositioningSessionEntity::class, parentColumns = ["id"], childColumns = ["positioningSessionId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index("venueId"), Index("positioningSessionId"), Index("startedAtEpochMillis")],
)
@Serializable
data class TestRunEntity(
    @PrimaryKey val id: String,
    val venueId: String,
    val positioningSessionId: String? = null,
    val anonymousDeviceId: String,
    val deviceManufacturer: String,
    val deviceModel: String,
    val androidVersion: String,
    val appVersion: String,
    val notes: String? = null,
    val startedAtEpochMillis: Long,
    val endedAtEpochMillis: Long? = null,
)

@Entity(
    tableName = "test_checkpoints",
    foreignKeys = [
        ForeignKey(entity = VenueEntity::class, parentColumns = ["id"], childColumns = ["venueId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = FloorEntity::class, parentColumns = ["id"], childColumns = ["floorId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("venueId"), Index("floorId"), Index(value = ["venueId", "checkpointCode"], unique = true)],
)
@Serializable
data class TestCheckpointEntity(
    @PrimaryKey val id: String,
    val venueId: String,
    val floorId: String,
    val checkpointCode: String,
    val xMetres: Double,
    val yMetres: Double,
    val notes: String? = null,
    val enabled: Boolean = true,
)

@Entity(
    tableName = "test_samples",
    foreignKeys = [
        ForeignKey(entity = TestRunEntity::class, parentColumns = ["id"], childColumns = ["testRunId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = TestCheckpointEntity::class, parentColumns = ["id"], childColumns = ["checkpointId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = FloorEntity::class, parentColumns = ["id"], childColumns = ["trueFloorId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index("testRunId"), Index("checkpointId"), Index("trueFloorId"), Index("timestampEpochMillis")],
)
@Serializable
data class TestSampleEntity(
    @PrimaryKey val id: String,
    val testRunId: String,
    val checkpointId: String,
    val trueFloorId: String,
    val trueXMetres: Double,
    val trueYMetres: Double,
    val wifiOnlyEstimateJson: String? = null,
    val rawPdrEstimateJson: String? = null,
    val mapConstrainedEstimateJson: String? = null,
    val fusedEstimateJson: String? = null,
    val wifiOnlyHorizontalErrorMetres: Double? = null,
    val rawPdrHorizontalErrorMetres: Double? = null,
    val mapConstrainedHorizontalErrorMetres: Double? = null,
    val fusedHorizontalErrorMetres: Double? = null,
    val wifiOnlyFloorCorrect: Boolean? = null,
    val rawPdrFloorCorrect: Boolean? = null,
    val mapConstrainedFloorCorrect: Boolean? = null,
    val fusedFloorCorrect: Boolean? = null,
    val confidenceRadiusMetres: Double? = null,
    val actualErrorInsideConfidence: Boolean? = null,
    val timeSinceLastWifiCorrectionMillis: Long? = null,
    val positioningLatencyMillis: Long? = null,
    val stepCount: Long,
    val wifiAccessPointCount: Int,
    val nearestFingerprintDetailsJson: String? = null,
    val mapConstraintEventsJson: String? = null,
    val timestampEpochMillis: Long,
)

@Entity(
    tableName = "beacon_definitions",
    foreignKeys = [
        ForeignKey(entity = VenueEntity::class, parentColumns = ["id"], childColumns = ["venueId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = FloorEntity::class, parentColumns = ["id"], childColumns = ["floorId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index("venueId"), Index("floorId"), Index(value = ["stableIdentifier"], unique = true)],
)
@Serializable
data class BeaconDefinitionEntity(
    @PrimaryKey val id: String,
    val venueId: String,
    val floorId: String? = null,
    val stableIdentifier: String,
    val protocol: String,
    val displayName: String,
    val xMetres: Double? = null,
    val yMetres: Double? = null,
    val enabled: Boolean = true,
    val metadataJson: String? = null,
)

@Entity(
    tableName = "ble_observations",
    foreignKeys = [
        ForeignKey(entity = BeaconDefinitionEntity::class, parentColumns = ["id"], childColumns = ["beaconDefinitionId"], onDelete = ForeignKey.SET_NULL),
        ForeignKey(entity = SurveySessionEntity::class, parentColumns = ["id"], childColumns = ["surveySessionId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("beaconDefinitionId"), Index("surveySessionId"), Index("stableIdentifier"), Index("observedAtEpochMillis")],
)
@Serializable
data class BleObservationEntity(
    @PrimaryKey val id: String,
    val beaconDefinitionId: String? = null,
    val surveySessionId: String,
    val stableIdentifier: String,
    val protocol: String,
    val rssiDbm: Int,
    val txPowerDbm: Int? = null,
    val observedAtEpochMillis: Long,
    val rawPayloadHex: String,
    val simulated: Boolean = false,
)

@Entity(
    tableName = "aggregated_ble_fingerprints",
    foreignKeys = [
        ForeignKey(entity = ReferencePointEntity::class, parentColumns = ["id"], childColumns = ["referencePointId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = SurveySessionEntity::class, parentColumns = ["id"], childColumns = ["surveySessionId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("referencePointId"), Index("surveySessionId"), Index("stableIdentifier"), Index(value = ["surveySessionId", "referencePointId", "stableIdentifier"], unique = true)],
)
@Serializable
data class AggregatedBleFingerprintEntity(
    @PrimaryKey val id: String,
    val referencePointId: String,
    val surveySessionId: String,
    val stableIdentifier: String,
    val medianRssiDbm: Double,
    val meanRssiDbm: Double,
    val standardDeviationDb: Double,
    val minimumRssiDbm: Int,
    val maximumRssiDbm: Int,
    val observationCount: Int,
    val detectionRate: Double,
    val excludedFromMatching: Boolean = false,
    val calculatedAtEpochMillis: Long,
)
