package com.turn.fieldtest.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import com.turn.fieldtest.ui.model.DataMode
import com.turn.fieldtest.ui.model.EditorTool
import com.turn.fieldtest.ui.model.EvaluationSampleUi
import com.turn.fieldtest.ui.model.FingerprintAggregateUi
import com.turn.fieldtest.ui.model.MapPointUi
import com.turn.fieldtest.ui.model.SensorReadingUi
import com.turn.fieldtest.ui.model.TurnDemoData
import com.turn.fieldtest.ui.model.TurnDestination
import com.turn.fieldtest.ui.model.WifiAccessPointUi
import kotlin.math.hypot

@Stable
class TurnAppState {
    var destination by mutableStateOf(TurnDestination.VENUES)
    var mode by mutableStateOf(DataMode.DEMO)
    var compactMenuOpen by mutableStateOf(false)
    var selectedVenueId by mutableStateOf(TurnDemoData.venues.first().id)
    var selectedFloorId by mutableStateOf(TurnDemoData.venues.first().floors.first().id)

    var editorTool by mutableStateOf(EditorTool.SELECT)
    var mapLayerVisible by mutableStateOf(true)
    var geometryLayerVisible by mutableStateOf(true)
    var labelsLayerVisible by mutableStateOf(true)
    var editorStatus by mutableStateOf("Metric geometry ready")
    var realMapReady by mutableStateOf(false)
    var venueName by mutableStateOf("Computing Block Pilot")
    var floorName by mutableStateOf("Ground floor")
    var floorLevel by mutableIntStateOf(0)
    var floorWidthMetres by mutableStateOf(42f)
    var floorHeightMetres by mutableStateOf(28f)
    var floorPlanContentUri by mutableStateOf<String?>(null)
    var floorPlanMimeType by mutableStateOf<String?>(null)
    var floorPlanPixelWidth by mutableStateOf<Int?>(null)
    var floorPlanPixelHeight by mutableStateOf<Int?>(null)
    var floorPlanMetresPerPixel by mutableStateOf<Double?>(null)
    val calibrationPoints = mutableStateListOf<Offset>()
    val draftWalkablePolygon = mutableStateListOf(
        Offset(4f, 7f), Offset(37f, 7f), Offset(37f, 12f), Offset(17f, 12f),
        Offset(17f, 23f), Offset(9f, 23f), Offset(9f, 12f), Offset(4f, 12f)
    )
    val draftWalls = mutableStateListOf(
        Offset(4f, 7f) to Offset(37f, 7f),
        Offset(37f, 7f) to Offset(37f, 12f),
        Offset(17f, 12f) to Offset(17f, 23f)
    )
    val referencePoints = mutableStateListOf(
        MapPointUi("RP-G-01", "Ground", Offset(6f, 9.5f), "RP 01"),
        MapPointUi("RP-G-04", "Ground", Offset(13f, 9.5f), "RP 04"),
        MapPointUi("RP-G-07", "Ground", Offset(14f, 18f), "RP 07"),
        MapPointUi("RP-G-10", "Ground", Offset(25f, 9.5f), "RP 10")
    )
    val qrAnchors = mutableStateListOf(
        MapPointUi("QR-G-ENTRANCE", "Ground", Offset(5f, 9.5f), "Entrance"),
        MapPointUi("QR-G-STAIRS", "Ground", Offset(16f, 11f), "Stairs")
    )
    var pendingWallStart by mutableStateOf<Offset?>(null)

    var diagnosticScanSequence by mutableIntStateOf(7)
    var diagnosticWalkRunning by mutableStateOf(false)
    var diagnosticWalkSteps by mutableIntStateOf(18)

    // These values are populated only by Android hardware adapters. Keeping the real-device
    // surface separate from TurnDemoData makes an accidental simulated fallback visible.
    var wifiPermissionStatus by mutableStateOf("Not requested")
    var realWifiMonitoring by mutableStateOf(false)
    var realWifiRequestInFlight by mutableStateOf(false)
    var realWifiRequestAccepted by mutableStateOf<Boolean?>(null)
    var realWifiResultsUpdated by mutableStateOf<Boolean?>(null)
    var realWifiFresh by mutableStateOf<Boolean?>(null)
    var realWifiIssue by mutableStateOf<String?>(null)
    var realWifiThrottlingEnabled by mutableStateOf<Boolean?>(null)
    var realWifiNewestAgeMillis by mutableStateOf<Long?>(null)
    var realWifiLastBatchEpochMillis by mutableStateOf<Long?>(null)
    var realWifiNextPermittedRequestEpochMillis by mutableStateOf<Long?>(null)
    var realWifiAccessPoints by mutableStateOf<List<WifiAccessPointUi>>(emptyList())
    var realSensorReadings by mutableStateOf<List<SensorReadingUi>>(emptyList())
    var realSensorRunning by mutableStateOf(false)
    var realDiagnosticDistanceMetres by mutableStateOf(0.0)
    var realDiagnosticStatus by mutableStateOf("Not started")

    var surveyRunning by mutableStateOf(false)
    var surveyStarting by mutableStateOf(false)
    var surveyAcceptedSnapshots by mutableIntStateOf(8)
    var surveyCachedIgnored by mutableIntStateOf(2)
    var surveyTargetSnapshots by mutableIntStateOf(12)
    var surveyRawObservationCount by mutableIntStateOf(0)
    var surveyDistinctBssidCount by mutableIntStateOf(0)
    var surveySessionLabel by mutableStateOf("No real-device session")
    var selectedSurveyReferencePointId by mutableStateOf("RP-G-07")
    var surveyRuntimeStatus by mutableStateOf("Ready to create a bounded local Room session")
    var surveySaveStatus by mutableStateOf("Nothing from REAL DEVICE has been stored yet")
    var realSurveyAggregates by mutableStateOf<List<FingerprintAggregateUi>>(emptyList())

    var liveRunning by mutableStateOf(false)
    var replayIndex by mutableIntStateOf(TurnDemoData.fusedTrail.lastIndex)
    var showParticles by mutableStateOf(false)
    var showRawPdr by mutableStateOf(true)
    var showWifiFixes by mutableStateOf(true)
    var showConfidence by mutableStateOf(true)
    var relocalizationCount by mutableIntStateOf(1)
    var lastCorrectionType by mutableStateOf("fresh Wi-Fi correction")

    // Live real-device state is populated only by TurnRuntimeViewModel. Lists are replaced
    // atomically so the Canvas never observes a partially updated trajectory.
    var realLiveInitialized by mutableStateOf(false)
    var realLiveStatus by mutableStateOf("Collect fingerprints, then start a physical session")
    var realLivePosition by mutableStateOf<Offset?>(null)
    var realRawPdrPosition by mutableStateOf<Offset?>(null)
    var realWifiPosition by mutableStateOf<Offset?>(null)
    var realWifiFloorId by mutableStateOf<String?>(null)
    var realWifiUncertaintyMetres by mutableStateOf<Double?>(null)
    var realFusedTrail by mutableStateOf<List<Offset>>(emptyList())
    var realRawPdrTrail by mutableStateOf<List<Offset>>(emptyList())
    var realWifiFixes by mutableStateOf<List<Offset>>(emptyList())
    var realParticleCloud by mutableStateOf<List<Offset>>(emptyList())
    var realLiveFloorId by mutableStateOf<String?>(null)
    var realLiveConfidence by mutableStateOf(0.0)
    var realLiveFloorConfidence by mutableStateOf(0.0)
    var realLiveUncertaintyMetres by mutableStateOf<Double?>(null)
    var realLiveStepCount by mutableLongStateOf(0L)
    var realLiveDistanceMetres by mutableStateOf(0.0)
    var realLiveHeadingDegrees by mutableStateOf(0.0)
    var realLiveHeadingSource by mutableStateOf("unavailable")
    var realNearestFingerprintIds by mutableStateOf<List<String>>(emptyList())
    var realWifiMatchDistance by mutableStateOf<Double?>(null)
    var realLiveMapRejectedCount by mutableIntStateOf(0)
    var realLastWifiCorrectionEpochMillis by mutableStateOf<Long?>(null)
    var realLiveSessionLabel by mutableStateOf("No physical positioning session")

    var selectedCheckpoint by mutableStateOf("CP-G-09")
    val evaluationSamples = mutableStateListOf<EvaluationSampleUi>().apply {
        addAll(TurnDemoData.evaluationSamples)
    }

    var lastDataAction by mutableStateOf("No import or export operation in progress")
    var realExportBusy by mutableStateOf(false)
    var realEvaluationBusy by mutableStateOf(false)
    var realEvaluationStatus by mutableStateOf("Start Live locate to capture independent checkpoints")
    val realTestSamples = mutableStateListOf<com.turn.fieldtest.data.local.TestSampleEntity>()

    var knnK by mutableIntStateOf(4)
    var missingRssi by mutableIntStateOf(-100)
    var strideMetres by mutableStateOf(0.73f)
    var particleCount by mutableIntStateOf(600)
    var deviceOffsetNormalization by mutableStateOf(false)
    var accelerometerFallback by mutableStateOf(false)
    var bleConfigured by mutableStateOf(false)
    var darkTheme by mutableStateOf(true)

    fun selectDestination(value: TurnDestination) {
        destination = value
        compactMenuOpen = false
    }

    fun addMapPoint(metricPoint: Offset) {
        if (mode == DataMode.REAL_DEVICE && (surveyRunning || liveRunning || !realMapReady)) {
            editorStatus = "Wait for map loading and stop physical sessions before editing"
            return
        }
        when (editorTool) {
            EditorTool.CALIBRATION -> {
                if (calibrationPoints.size == 2) calibrationPoints.clear()
                calibrationPoints.add(metricPoint)
                editorStatus = if (calibrationPoints.size == 1) {
                    "Calibration point A set · tap point B"
                } else {
                    "Points A–B selected · enter their measured distance and apply"
                }
            }
            EditorTool.WALKABLE -> {
                draftWalkablePolygon.add(metricPoint)
                editorStatus = "Walkable vertex ${draftWalkablePolygon.size} · ${formatPoint(metricPoint)}"
            }
            EditorTool.WALL -> {
                val start = pendingWallStart
                if (start == null) {
                    pendingWallStart = metricPoint
                    editorStatus = "Wall start ${formatPoint(metricPoint)} · select endpoint"
                } else {
                    draftWalls.add(start to metricPoint)
                    pendingWallStart = null
                    editorStatus = "Wall stored in metric coordinates"
                }
            }
            EditorTool.REFERENCE_POINT -> {
                val id = nextReferencePointId()
                referencePoints.add(MapPointUi(id, floorName, metricPoint, id))
                selectedSurveyReferencePointId = id
                editorStatus = "$id placed at ${formatPoint(metricPoint)}"
            }
            EditorTool.QR_ANCHOR -> {
                val id = "QR-G-${(qrAnchors.size + 1).toString().padStart(2, '0')}"
                qrAnchors.add(MapPointUi(id, "Ground", metricPoint, "QR ${qrAnchors.size + 1}"))
                editorStatus = "$id placed at ${formatPoint(metricPoint)}"
            }
            else -> editorStatus = "${editorTool.label} placed at ${formatPoint(metricPoint)}"
        }
    }

    fun clearMapDraft() {
        draftWalkablePolygon.clear()
        draftWalls.clear()
        referencePoints.clear()
        qrAnchors.clear()
        calibrationPoints.clear()
        pendingWallStart = null
        editorTool = EditorTool.SELECT
        selectedSurveyReferencePointId = ""
        editorStatus = "Blank map ready · import an image or draw the walkable outline"
    }

    fun loadDemoMap() {
        venueName = "Computing Block Pilot"
        floorName = "Ground floor"
        floorLevel = 0
        floorWidthMetres = 42f
        floorHeightMetres = 28f
        floorPlanContentUri = null
        floorPlanMimeType = null
        floorPlanPixelWidth = null
        floorPlanPixelHeight = null
        floorPlanMetresPerPixel = null
        draftWalkablePolygon.clear()
        draftWalkablePolygon.addAll(listOf(
            Offset(4f, 7f), Offset(37f, 7f), Offset(37f, 12f), Offset(17f, 12f),
            Offset(17f, 23f), Offset(9f, 23f), Offset(9f, 12f), Offset(4f, 12f),
        ))
        draftWalls.clear()
        draftWalls.addAll(listOf(
            Offset(4f, 7f) to Offset(37f, 7f),
            Offset(37f, 7f) to Offset(37f, 12f),
            Offset(17f, 12f) to Offset(17f, 23f),
        ))
        referencePoints.clear()
        referencePoints.addAll(listOf(
            MapPointUi("RP-G-01", "Ground", Offset(6f, 9.5f), "RP 01"),
            MapPointUi("RP-G-04", "Ground", Offset(13f, 9.5f), "RP 04"),
            MapPointUi("RP-G-07", "Ground", Offset(14f, 18f), "RP 07"),
            MapPointUi("RP-G-10", "Ground", Offset(25f, 9.5f), "RP 10"),
        ))
        qrAnchors.clear()
        qrAnchors.addAll(listOf(
            MapPointUi("QR-G-ENTRANCE", "Ground", Offset(5f, 9.5f), "Entrance"),
            MapPointUi("QR-G-STAIRS", "Ground", Offset(16f, 11f), "Stairs"),
        ))
        selectedSurveyReferencePointId = "RP-G-07"
        editorTool = EditorTool.SELECT
        editorStatus = "Metric demo geometry ready"
    }

    fun undoCurrentTool() {
        when (editorTool) {
            EditorTool.CALIBRATION -> if (calibrationPoints.isNotEmpty()) calibrationPoints.removeAt(calibrationPoints.lastIndex)
            EditorTool.WALKABLE -> if (draftWalkablePolygon.isNotEmpty()) draftWalkablePolygon.removeAt(draftWalkablePolygon.lastIndex)
            EditorTool.WALL -> if (pendingWallStart != null) pendingWallStart = null else if (draftWalls.isNotEmpty()) draftWalls.removeAt(draftWalls.lastIndex)
            EditorTool.REFERENCE_POINT -> if (referencePoints.isNotEmpty()) referencePoints.removeAt(referencePoints.lastIndex)
            EditorTool.QR_ANCHOR -> if (qrAnchors.isNotEmpty()) qrAnchors.removeAt(qrAnchors.lastIndex)
            else -> Unit
        }
        editorStatus = "Last ${editorTool.label.lowercase()} action undone"
    }

    fun finishWalkablePolygon() {
        editorStatus = if (draftWalkablePolygon.size >= 3) {
            editorTool = EditorTool.SELECT
            "Walkable polygon closed with ${draftWalkablePolygon.size} vertices"
        } else {
            "Add at least three walkable vertices"
        }
    }

    fun addReferencePointAt(idInput: String, x: Float, y: Float) {
        val clean = idInput.trim().uppercase().replace(Regex("[^A-Z0-9_-]"), "-")
        if (clean.isBlank()) {
            editorStatus = "Enter a reference-point ID"
            return
        }
        if (x !in 0f..floorWidthMetres || y !in 0f..floorHeightMetres) {
            editorStatus = "Reference point must be inside the floor dimensions"
            return
        }
        if (referencePoints.any { it.id == clean }) {
            editorStatus = "$clean already exists"
            return
        }
        referencePoints.add(MapPointUi(clean, floorName, Offset(x, y), clean))
        selectedSurveyReferencePointId = clean
        editorStatus = "$clean added at ${formatPoint(Offset(x, y))}"
    }

    fun removeReferencePoint(id: String) {
        referencePoints.removeAll { it.id == id }
        if (selectedSurveyReferencePointId == id) {
            selectedSurveyReferencePointId = referencePoints.firstOrNull()?.id.orEmpty()
        }
        editorStatus = "$id removed from the draft · Save to persist"
    }

    fun applyCalibration(measuredDistanceMetres: Double): Boolean {
        if (calibrationPoints.size != 2 || measuredDistanceMetres <= 0.0 || !measuredDistanceMetres.isFinite()) {
            editorStatus = "Select points A and B, then enter a positive measured distance"
            return false
        }
        val a = calibrationPoints[0]
        val b = calibrationPoints[1]
        val draftDistance = hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble())
        if (draftDistance <= 0.0) {
            editorStatus = "Calibration points must be different"
            return false
        }
        val scale = (measuredDistanceMetres / draftDistance).toFloat()
        resizeFloor(floorWidthMetres * scale, floorHeightMetres * scale)
        floorPlanMetresPerPixel = floorPlanPixelWidth?.takeIf { it > 0 }?.let { floorWidthMetres.toDouble() / it }
        editorTool = EditorTool.SELECT
        editorStatus = "Calibrated · floor is %.2f m × %.2f m".format(floorWidthMetres, floorHeightMetres)
        return true
    }

    fun resizeFloor(width: Float, height: Float): Boolean {
        if (!width.isFinite() || !height.isFinite() || width <= 0f || height <= 0f || width > 1000f || height > 1000f) {
            editorStatus = "Floor dimensions must be between 0 and 1000 metres"
            return false
        }
        val xScale = width / floorWidthMetres
        val yScale = height / floorHeightMetres
        fun scaled(point: Offset) = Offset(point.x * xScale, point.y * yScale)
        val polygon = draftWalkablePolygon.map(::scaled)
        val walls = draftWalls.map { scaled(it.first) to scaled(it.second) }
        val points = referencePoints.map { it.copy(metres = scaled(it.metres), floor = floorName) }
        val anchors = qrAnchors.map { it.copy(metres = scaled(it.metres), floor = floorName) }
        val calibration = calibrationPoints.map(::scaled)
        draftWalkablePolygon.clear(); draftWalkablePolygon.addAll(polygon)
        draftWalls.clear(); draftWalls.addAll(walls)
        referencePoints.clear(); referencePoints.addAll(points)
        qrAnchors.clear(); qrAnchors.addAll(anchors)
        calibrationPoints.clear(); calibrationPoints.addAll(calibration)
        pendingWallStart = pendingWallStart?.let(::scaled)
        floorWidthMetres = width
        floorHeightMetres = height
        floorPlanMetresPerPixel = floorPlanPixelWidth?.takeIf { it > 0 }?.let { width.toDouble() / it }
        return true
    }

    fun removeFloorPlanImage() {
        floorPlanContentUri = null
        floorPlanMimeType = null
        floorPlanPixelWidth = null
        floorPlanPixelHeight = null
        floorPlanMetresPerPixel = null
        calibrationPoints.clear()
        editorStatus = "Background image removed from the draft"
    }

    private fun nextReferencePointId(): String {
        var number = referencePoints.size + 1
        var candidate: String
        do {
            candidate = "RP-${number.toString().padStart(2, '0')}"
            number += 1
        } while (referencePoints.any { it.id == candidate })
        return candidate
    }

    fun toggleSurvey() {
        if (surveyAcceptedSnapshots >= surveyTargetSnapshots) {
            surveyAcceptedSnapshots = 0
            surveyCachedIgnored = 0
        }
        surveyRunning = !surveyRunning
    }

    fun acceptDemoSurveySnapshot(fresh: Boolean) {
        if (!surveyRunning) return
        if (fresh) surveyAcceptedSnapshots = (surveyAcceptedSnapshots + 1).coerceAtMost(surveyTargetSnapshots)
        else surveyCachedIgnored += 1
        if (surveyAcceptedSnapshots >= surveyTargetSnapshots) surveyRunning = false
    }

    fun stepReplay() {
        replayIndex = if (replayIndex >= TurnDemoData.fusedTrail.lastIndex) 0 else replayIndex + 1
        lastCorrectionType = if (replayIndex in setOf(0, 5, 10)) "fresh Wi-Fi correction" else "PDR prediction"
    }

    fun captureEvaluationSample() {
        val sequence = evaluationSamples.size + 1
        val adjustment = (sequence % 4) * 0.2
        evaluationSamples.add(
            EvaluationSampleUi(
                checkpointId = selectedCheckpoint,
                wifiErrorMetres = 3.5 + adjustment,
                pdrErrorMetres = 5.2 + adjustment,
                fusedErrorMetres = 2.0 + adjustment,
                constrainedErrorMetres = 1.8 + adjustment,
                floorCorrect = true,
                confidenceContainedTruth = sequence % 3 != 0,
                timestamp = "14:${(36 + sequence).coerceAtMost(59)}:00"
            )
        )
    }

    private fun formatPoint(point: Offset): String = "%.1f m, %.1f m".format(point.x, point.y)
}

@Composable
fun rememberTurnAppState(): TurnAppState = remember { TurnAppState() }
