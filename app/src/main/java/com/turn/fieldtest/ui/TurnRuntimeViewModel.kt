package com.turn.fieldtest.ui

import android.app.Application
import android.hardware.SensorManager
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.snapshotFlow
import android.net.Uri
import com.turn.fieldtest.data.export.ResearchDataset
import com.turn.fieldtest.data.export.ResearchDataExport
import com.turn.fieldtest.data.export.TurnDatabaseExport
import com.turn.fieldtest.platform.storage.KotlinxJsonTransferCodec
import com.turn.fieldtest.platform.storage.CsvTableCodec
import com.turn.fieldtest.platform.storage.TransferResult
import com.turn.fieldtest.data.settings.TurnSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.room.withTransaction
import com.turn.fieldtest.data.local.WalkableRegionEntity
import com.turn.fieldtest.data.local.WallSegmentEntity
import com.turn.fieldtest.ui.model.MapPointUi
import androidx.compose.ui.geometry.Offset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.double
import com.turn.fieldtest.core.EvaluationEstimate
import com.turn.fieldtest.core.evaluateAtCheckpoint
import com.turn.fieldtest.data.local.TestRunEntity
import com.turn.fieldtest.data.local.TestSampleEntity
import com.turn.fieldtest.data.local.TestCheckpointEntity
import com.turn.fieldtest.ui.screens.CheckpointInput
import com.turn.fieldtest.BuildConfig
import com.turn.fieldtest.TurnApplication
import com.turn.fieldtest.data.local.FloorEntity
import com.turn.fieldtest.data.local.CorrectionEventEntity
import com.turn.fieldtest.data.local.PdrEventEntity
import com.turn.fieldtest.data.local.PositionEstimateEntity
import com.turn.fieldtest.data.local.PositioningSessionEntity
import com.turn.fieldtest.data.local.ReferencePointEntity
import com.turn.fieldtest.data.local.SensorSessionEntity
import com.turn.fieldtest.data.local.SurveySessionEntity
import com.turn.fieldtest.data.local.VenueEntity
import com.turn.fieldtest.data.survey.WifiSurveyCaptureService
import com.turn.fieldtest.core.AbsoluteFix
import com.turn.fieldtest.core.AbsoluteFixSource
import com.turn.fieldtest.core.MapFloor
import com.turn.fieldtest.core.MetricMap
import com.turn.fieldtest.core.MetricPoint
import com.turn.fieldtest.core.MetricPolygon
import com.turn.fieldtest.core.ParticleFilter
import com.turn.fieldtest.core.ParticleFilterConfig
import com.turn.fieldtest.core.PdrTracker
import com.turn.fieldtest.core.StepProcessor
import com.turn.fieldtest.core.StepSignal
import com.turn.fieldtest.core.StepSource
import com.turn.fieldtest.core.StrideModel
import com.turn.fieldtest.core.WallSegment
import com.turn.fieldtest.core.WeightedKnnWifiMatcher
import com.turn.fieldtest.core.WifiCorrectionKind
import com.turn.fieldtest.core.WifiFingerprint
import com.turn.fieldtest.core.WifiMatcherConfig
import com.turn.fieldtest.core.WifiNormalization
import com.turn.fieldtest.core.WifiScanFreshness as CoreWifiFreshness
import com.turn.fieldtest.core.normalizeRadians
import com.turn.fieldtest.platform.permissions.TurnCapability
import com.turn.fieldtest.platform.sensors.SensorAvailability
import com.turn.fieldtest.platform.sensors.SensorSample
import com.turn.fieldtest.platform.sensors.SensorSampleKind
import com.turn.fieldtest.platform.sensors.TurnSensorType
import com.turn.fieldtest.platform.wifi.WifiFreshness
import com.turn.fieldtest.platform.wifi.WifiScanBatch
import com.turn.fieldtest.platform.wifi.WifiScanIssue
import com.turn.fieldtest.platform.wifi.WifiScanRequestResult
import com.turn.fieldtest.ui.model.DataMode
import com.turn.fieldtest.ui.model.FingerprintAggregateUi
import com.turn.fieldtest.ui.model.SensorReadingUi
import com.turn.fieldtest.ui.model.WifiAccessPointUi
import java.util.UUID
import kotlin.math.PI
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class SurveyCaptureMetadata(
    val referencePointId: String,
    val orientationLabel: String?,
    val crowdConditionLabel: String?,
    val researcherNotes: String?,
)

/**
 * Bridges Compose to process-level hardware and Room services. Real-device values can enter the UI
 * only through this class; demo replay remains entirely inside the explicit DEMO branch.
 */
class TurnRuntimeViewModel(application: Application) : AndroidViewModel(application) {
    val appState = TurnAppState()

    private val app = application as TurnApplication
    private val wifiScanner = app.wifiScanner
    private val sensorSource = app.sensorSource
    private val repositories = app.repositories
    private val captureService = WifiSurveyCaptureService(repositories.surveys)

    private var foreground = false
    private var activeSurvey: SurveySessionEntity? = null
    private var surveyScanSequence = 0L
    private var surveyScanJob: Job? = null
    private var liveScanJob: Job? = null
    private val surveyBssids = linkedSetOf<String>()
    private val latestSensorValues = linkedMapOf<TurnSensorType, String>()
    private var activePositioningSession: PositioningSessionEntity? = null
    private var activeSensorSession: SensorSessionEntity? = null
    private var particleFilter: ParticleFilter? = null
    private var rawPdrTracker: PdrTracker? = null
    private var liveEstimateSequence = 0L
    private var livePdrSequence = 0L
    private var latestDeviceYawRadians: Double? = null
    private var liveYawBaselineRadians: Double? = null
    private var latestMapHeadingRadians = 0.0
    private var previousGyroscopeTimestampNanos: Long? = null
    private var rawPdrFloorId: String? = null

    fun captureCheckpoint(input: CheckpointInput) {
        val session = activePositioningSession ?: return
        if (appState.mode != DataMode.REAL_DEVICE || !appState.liveRunning || appState.realEvaluationBusy) return
        if (input.code.isBlank() || !input.x.isFinite() || !input.y.isFinite() ||
            input.x !in 0.0..42.0 || input.y !in 0.0..28.0) {
            appState.realEvaluationStatus = "Checkpoint code and finite pilot coordinates are required"
            return
        }
        // Freeze estimates at the button press, before any suspend/database work. Truth is never
        // passed to the matcher, raw PDR tracker, or particle filter.
        val now = System.currentTimeMillis()
        fun snapshot(point: Offset?, floor: String?, radius: Double? = null): EvaluationEstimate? =
            if (point == null || floor == null) null else EvaluationEstimate(point.x.toDouble(), point.y.toDouble(), floor, radius)
        val wifi = snapshot(appState.realWifiPosition, appState.realWifiFloorId, appState.realWifiUncertaintyMetres)
        val raw = snapshot(appState.realRawPdrPosition, rawPdrFloorId)
        val fused = if (particleFilter?.isLost == false) snapshot(appState.realLivePosition,
            appState.realLiveFloorId, appState.realLiveUncertaintyMetres) else null
        val truth = MetricPoint(input.x, input.y)
        val wifiError = evaluateAtCheckpoint(truth, PILOT_FLOOR_ID, wifi)
        val rawError = evaluateAtCheckpoint(truth, PILOT_FLOOR_ID, raw)
        val fusedError = evaluateAtCheckpoint(truth, PILOT_FLOOR_ID, fused)
        fun encoded(value: EvaluationEstimate?) = value?.let { Json.encodeToString(EvaluationEstimate.serializer(), it) }
        val checkpointId = "CP-$PILOT_VENUE_ID-${input.code}"
        val runId = "TEST-${session.id}"
        val sample = TestSampleEntity(
            id = "SAMPLE-${UUID.randomUUID()}", testRunId = runId, checkpointId = checkpointId,
            trueFloorId = PILOT_FLOOR_ID, trueXMetres = input.x, trueYMetres = input.y,
            wifiOnlyEstimateJson = encoded(wifi), rawPdrEstimateJson = encoded(raw), fusedEstimateJson = encoded(fused),
            wifiOnlyHorizontalErrorMetres = wifiError.horizontalMetres,
            rawPdrHorizontalErrorMetres = rawError.horizontalMetres, fusedHorizontalErrorMetres = fusedError.horizontalMetres,
            wifiOnlyFloorCorrect = wifiError.floorCorrect, rawPdrFloorCorrect = rawError.floorCorrect,
            fusedFloorCorrect = fusedError.floorCorrect, confidenceRadiusMetres = fused?.confidenceRadius,
            actualErrorInsideConfidence = fusedError.insideConfidence,
            timeSinceLastWifiCorrectionMillis = appState.realLastWifiCorrectionEpochMillis?.let { (now - it).coerceAtLeast(0) },
            stepCount = appState.realLiveStepCount, wifiAccessPointCount = appState.realWifiAccessPoints.size,
            nearestFingerprintDetailsJson = JsonObject(mapOf(
                "ids" to JsonArray(appState.realNearestFingerprintIds.map(::JsonPrimitive)),
                "matchDistance" to (appState.realWifiMatchDistance?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull),
            )).toString(),
            mapConstraintEventsJson = JsonObject(mapOf("rejectedParticleMoves" to JsonPrimitive(appState.realLiveMapRejectedCount))).toString(),
            timestampEpochMillis = now,
        )
        appState.realEvaluationBusy = true
        viewModelScope.launch {
            try {
                app.database.withTransaction {
                    val existing = repositories.evaluation.observeCheckpoints(PILOT_VENUE_ID).first().firstOrNull { it.id == checkpointId }
                    require(existing == null || (existing.floorId == PILOT_FLOOR_ID && existing.xMetres == input.x && existing.yMetres == input.y)) {
                        "Checkpoint code already belongs to a different coordinate"
                    }
                    require(repositories.floorPlans.observeReferencePoints(PILOT_FLOOR_ID).first().none {
                        MetricPoint(it.xMetres, it.yMetres).distanceTo(truth) < 0.05
                    }) { "Choose an independent checkpoint, separate from a training reference point" }
                    val run = repositories.evaluation.observeRuns(PILOT_VENUE_ID).first().firstOrNull { it.id == runId }
                    if (run == null) repositories.evaluation.saveRun(TestRunEntity(
                        id = runId, venueId = PILOT_VENUE_ID, positioningSessionId = session.id,
                        anonymousDeviceId = "DEVICE-${UUID.randomUUID()}", deviceManufacturer = Build.MANUFACTURER,
                        deviceModel = Build.MODEL, androidVersion = Build.VERSION.RELEASE,
                        appVersion = BuildConfig.VERSION_NAME, startedAtEpochMillis = now,
                    ))
                    if (existing == null) repositories.evaluation.saveCheckpoint(TestCheckpointEntity(
                        checkpointId, PILOT_VENUE_ID, PILOT_FLOOR_ID, input.code, input.x, input.y))
                    repositories.evaluation.saveSample(sample)
                }
                appState.realTestSamples.add(sample)
                appState.realEvaluationStatus = "Saved ${input.code}: ${appState.realTestSamples.size} independent capture(s) this session"
            } catch (error: Exception) {
                appState.realEvaluationStatus = "Capture rejected: ${error.message}"
            } finally {
                appState.realEvaluationBusy = false
            }
        }
    }

    init {
        viewModelScope.launch {
            val saved = app.settings.settings.first()
            appState.knnK = saved.wifiK
            appState.missingRssi = saved.missingRssiDbm
            appState.deviceOffsetNormalization = saved.normalizeDeviceOffset
            appState.strideMetres = saved.defaultStrideMetres.toFloat()
            appState.particleCount = saved.particleCount
            appState.darkTheme = saved.darkTheme
            snapshotFlow {
                TurnSettings(
                    wifiK = appState.knnK,
                    missingRssiDbm = appState.missingRssi,
                    normalizeDeviceOffset = appState.deviceOffsetNormalization,
                    defaultStrideMetres = appState.strideMetres.toDouble(),
                    particleCount = appState.particleCount,
                    darkTheme = appState.darkTheme,
                )
            }.distinctUntilChanged().collect { settings -> app.settings.update { settings } }
        }
        viewModelScope.launch {
            wifiScanner.state.collectLatest { scannerState ->
                appState.realWifiMonitoring = scannerState.monitoring
                appState.realWifiRequestInFlight = scannerState.requestInFlight
                appState.realWifiRequestAccepted = scannerState.lastRequestAccepted
                appState.realWifiResultsUpdated = scannerState.lastResultsUpdated
                appState.realWifiFresh = scannerState.lastFreshness?.let { it == WifiFreshness.FRESH }
                appState.realWifiIssue = scannerState.lastIssue?.displayName()
                appState.realWifiThrottlingEnabled = scannerState.scanThrottlingEnabled
                appState.realWifiLastBatchEpochMillis = scannerState.lastBatchAtEpochMillis
                appState.realWifiNextPermittedRequestEpochMillis = scannerState.nextPermittedRequestAtEpochMillis
            }
        }
        viewModelScope.launch {
            wifiScanner.batches.collect { batch -> handleWifiBatch(batch) }
        }
        viewModelScope.launch {
            sensorSource.state.collectLatest { sourceState ->
                appState.realSensorRunning = sourceState.running
                appState.realSensorReadings = sourceState.availability.map { availability ->
                    availability.toUi(
                        latestValue = latestSensorValues[availability.type],
                        selectedStep = sourceState.selectedStepSource,
                        selectedHeading = sourceState.selectedHeadingSource,
                    )
                }
            }
        }
        viewModelScope.launch {
            sensorSource.samples.collect { sample -> handleSensorSample(sample) }
        }
    }

    fun onForeground() {
        foreground = true
        refreshWifiPermissionStatus()
        if (appState.mode == DataMode.REAL_DEVICE) startRealSources()
    }

    fun onBackground() {
        foreground = false
        appState.liveRunning = false
        appState.surveyRunning = false
        appState.diagnosticWalkRunning = false
        surveyScanJob?.cancel()
        liveScanJob?.cancel()
        if (activeSurvey != null) {
            viewModelScope.launch { finishActiveSurvey("Paused when TURN left the foreground") }
        }
        if (activePositioningSession != null) {
            viewModelScope.launch { finishActiveLive("Paused when TURN left the foreground") }
        }
        wifiScanner.stop()
        sensorSource.stop()
    }

    fun setMode(mode: DataMode) {
        if (appState.mode == mode) return
        if (activeSurvey != null) {
            viewModelScope.launch { finishActiveSurvey("Session closed when data mode changed") }
        }
        if (activePositioningSession != null) {
            viewModelScope.launch { finishActiveLive("Session closed when data mode changed") }
        }
        appState.mode = mode
        appState.liveRunning = false
        appState.diagnosticWalkRunning = false
        if (mode == DataMode.REAL_DEVICE) {
            appState.realMapReady = false
            viewModelScope.launch {
                try {
                    val points = repositories.floorPlans.observeReferencePoints(PILOT_FLOOR_ID).first()
                    val regions = repositories.floorPlans.observeWalkableRegions(PILOT_FLOOR_ID).first()
                    val walls = repositories.floorPlans.observeWalls(PILOT_FLOOR_ID).first()
                    val restoredPolygon = regions.firstOrNull { it.enabled }?.let { region ->
                        Json.parseToJsonElement(region.polygonJson).jsonArray.map { vertex ->
                            Offset(vertex.jsonObject.getValue("x").jsonPrimitive.double.toFloat(),
                                vertex.jsonObject.getValue("y").jsonPrimitive.double.toFloat())
                        }
                    }
                    if (points.isNotEmpty()) {
                        appState.referencePoints.clear()
                        appState.referencePoints.addAll(points.map {
                            MapPointUi(it.id, "Ground", Offset(it.xMetres.toFloat(), it.yMetres.toFloat()), it.name)
                        })
                        if (points.none { it.id == appState.selectedSurveyReferencePointId }) {
                            appState.selectedSurveyReferencePointId = points.first().id
                        }
                    }
                    if (restoredPolygon != null) {
                        appState.draftWalkablePolygon.clear()
                        appState.draftWalkablePolygon.addAll(restoredPolygon)
                        appState.draftWalls.clear()
                        appState.draftWalls.addAll(walls.filter { it.enabled }.map {
                            Offset(it.startXMetres.toFloat(), it.startYMetres.toFloat()) to
                                Offset(it.endXMetres.toFloat(), it.endYMetres.toFloat())
                        })
                    }
                    appState.realMapReady = true
                    appState.editorStatus = "Physical pilot map loaded; Save writes metric geometry to Room"
                } catch (error: Exception) {
                    appState.editorStatus = "Could not load physical map: ${error.message}"
                }
            }
            appState.surveyAcceptedSnapshots = 0
            appState.surveyCachedIgnored = 0
            appState.surveyRawObservationCount = 0
            appState.surveyDistinctBssidCount = 0
            appState.realSurveyAggregates = emptyList()
            appState.surveySessionLabel = "No real-device session"
            appState.surveyRuntimeStatus = "Ready to create a bounded local Room session"
            appState.surveySaveStatus = "Nothing from REAL DEVICE has been stored yet"
            refreshWifiPermissionStatus()
            if (foreground) startRealSources()
        } else {
            surveyScanJob?.cancel()
            wifiScanner.stop()
            sensorSource.stop()
            appState.surveyRunning = false
            appState.surveyAcceptedSnapshots = 8
            appState.surveyCachedIgnored = 2
            appState.wifiPermissionStatus = "Not needed in DEMO mode"
        }
    }

    fun missingWifiPermissions(): Array<String> = app.permissionChecker
        .missingPermissions(TurnCapability.WIFI_SCAN)
        .toTypedArray()

    fun missingMotionPermissions(): Array<String> = app.permissionChecker
        .missingPermissions(TurnCapability.PDR_MOTION).toTypedArray()

    fun refreshMotionSources() {
        if (appState.mode == DataMode.REAL_DEVICE && foreground) {
            sensorSource.stop()
            sensorSource.start()
        }
    }

    fun onMotionPermissionDenied() {
        appState.realDiagnosticStatus = "Physical activity permission denied; step tracking unavailable"
        appState.realLiveStatus = "Grant Physical activity permission to start PDR tracking"
    }

    fun exportRealData(uri: Uri, dataset: ResearchDataset) {
        if (appState.realExportBusy) return
        if (appState.mode != DataMode.REAL_DEVICE || activeSurvey != null || activePositioningSession != null) {
            appState.lastDataAction = "Export blocked: stop physical collection and positioning first"
            return
        }
        appState.realExportBusy = true
        appState.lastDataAction = "Preparing ${dataset.label} from stored physical records…"
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val value = app.backupRepository.createExport(System.currentTimeMillis())
                    ResearchDataExport.requirePhysicalData(value)
                    if (dataset == ResearchDataset.BACKUP) {
                        app.safTransferService.export(uri, value, KotlinxJsonTransferCodec(TurnDatabaseExport.serializer()))
                    } else {
                        app.safTransferService.export(uri, ResearchDataExport.csv(value, dataset), CsvTableCodec)
                    }
                }
                appState.lastDataAction = when (result) {
                    is TransferResult.Success -> "Exported ${dataset.label}; stored data unchanged"
                    is TransferResult.Failure -> "Export failed: ${result.message}"
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                appState.lastDataAction = "Export failed: ${error.message ?: error.javaClass.simpleName}"
            } finally {
                appState.realExportBusy = false
            }
        }
    }

    fun refreshWifiPermissionStatus() {
        appState.wifiPermissionStatus = when {
            appState.mode == DataMode.DEMO -> "Not needed in DEMO mode"
            missingWifiPermissions().isEmpty() -> "Granted for Wi-Fi-derived location"
            else -> "Required before physical Wi-Fi scanning"
        }
    }

    fun onWifiPermissionDenied() {
        val missing = missingWifiPermissions()
        appState.wifiPermissionStatus = "Denied or incomplete (${missing.size} permission${if (missing.size == 1) "" else "s"})"
        appState.realWifiIssue = WifiScanIssue.PERMISSION_DENIED.displayName()
        appState.surveyRuntimeStatus = "Collection not started: Wi-Fi permissions were not granted"
    }

    fun requestDiagnosticScan() {
        if (!realWifiReady("Diagnostic scan")) return
        viewModelScope.launch { requestRealScan("Diagnostic scan") }
    }

    fun toggleDiagnosticWalk() {
        if (appState.mode != DataMode.REAL_DEVICE) return
        if (missingMotionPermissions().isNotEmpty()) {
            onMotionPermissionDenied()
            return
        }
        if (!appState.diagnosticWalkRunning) {
            appState.diagnosticWalkSteps = 0
            appState.realDiagnosticDistanceMetres = 0.0
            appState.realDiagnosticStatus = "Recording physical sensor events"
            appState.diagnosticWalkRunning = true
        } else {
            appState.diagnosticWalkRunning = false
            appState.realDiagnosticStatus = "Stopped; physical samples retained in this screen"
        }
    }

    fun beginRealSurvey(metadata: SurveyCaptureMetadata) {
        if (activeSurvey != null || appState.surveyRunning) return
        if (appState.liveRunning) {
            appState.surveyRuntimeStatus = "Stop Live locate before collecting a training fingerprint"
            return
        }
        if (!realWifiReady("Survey")) return
        appState.surveyRunning = true
        appState.surveyRuntimeStatus = "Creating physical survey session…"
        viewModelScope.launch {
            runCatching {
                val now = System.currentTimeMillis()
                ensurePilotContext(now)
                val selectedPoint = repositories.floorPlans.referencePoint(metadata.referencePointId)
                    ?: error("Unknown survey reference point ${metadata.referencePointId}")
                SurveySessionEntity(
                    id = "SUR-${UUID.randomUUID()}",
                    venueId = PILOT_VENUE_ID,
                    floorId = PILOT_FLOOR_ID,
                    referencePointId = selectedPoint.id,
                    knownXMetres = selectedPoint.xMetres,
                    knownYMetres = selectedPoint.yMetres,
                    deviceManufacturer = Build.MANUFACTURER,
                    deviceModel = Build.MODEL,
                    androidVersion = Build.VERSION.RELEASE,
                    appVersion = BuildConfig.VERSION_NAME,
                    researcherNotes = metadata.researcherNotes?.trim()?.takeIf(String::isNotEmpty),
                    orientationLabel = metadata.orientationLabel,
                    crowdConditionLabel = metadata.crowdConditionLabel,
                    dataMode = DataMode.REAL_DEVICE.name,
                    startedAtEpochMillis = now,
                ).also { repositories.surveys.save(it) }
            }.onSuccess { session ->
                if (!foreground || appState.mode != DataMode.REAL_DEVICE || !appState.surveyRunning) {
                    repositories.surveys.save(session.copy(endedAtEpochMillis = System.currentTimeMillis()))
                    appState.surveyRunning = false
                    return@onSuccess
                }
                activeSurvey = session
                surveyScanSequence = 0L
                surveyBssids.clear()
                appState.surveyAcceptedSnapshots = 0
                appState.surveyCachedIgnored = 0
                appState.surveyRawObservationCount = 0
                appState.surveyDistinctBssidCount = 0
                appState.realSurveyAggregates = emptyList()
                appState.surveySessionLabel = session.id
                appState.surveyRuntimeStatus = "Collecting physical WifiManager broadcasts"
                appState.surveySaveStatus = "Room session created; waiting for the first scan result"
                appState.surveyRunning = true
                startSurveyScanLoop()
            }.onFailure { failure ->
                appState.surveyRunning = false
                appState.surveyRuntimeStatus = "Could not create survey session"
                appState.surveySaveStatus = failure.message ?: failure::class.java.simpleName
            }
        }
    }

    fun finishRealSurvey() {
        if (activeSurvey == null) {
            appState.surveyRunning = false
            return
        }
        viewModelScope.launch { finishActiveSurvey("Researcher finished the bounded collection") }
    }

    fun toggleRealLive() {
        if (appState.mode != DataMode.REAL_DEVICE) return
        if (appState.liveRunning) {
            viewModelScope.launch { finishActiveLive("Researcher stopped the physical session") }
        } else {
            beginRealLive()
        }
    }

    fun requestLiveScan() {
        if (!realWifiReady("Live locate")) return
        viewModelScope.launch { requestRealScan("Live locate") }
    }

    fun relocalizeWithNextWifi() {
        if (appState.mode != DataMode.REAL_DEVICE || !appState.liveRunning) return
        particleFilter = null
        appState.realLiveInitialized = false
        appState.realLivePosition = null
        appState.realParticleCloud = emptyList()
        appState.realLiveConfidence = 0.0
        appState.realLiveFloorConfidence = 0.0
        appState.realLiveStatus = "Global relocalization armed; waiting for a fresh Wi-Fi fix"
        requestLiveScan()
    }

    private fun beginRealLive() {
        if (activePositioningSession != null) {
            appState.realLiveStatus = "Previous session is still closing; try again after it stops"
            return
        }
        if (missingMotionPermissions().isNotEmpty()) {
            onMotionPermissionDenied()
            return
        }
        if (activeSurvey != null || appState.surveyRunning) {
            appState.realLiveStatus = "Finish the active fingerprint survey before positioning"
            return
        }
        if (!realWifiReady("Live locate")) return
        appState.liveRunning = true
        appState.realLiveStatus = "Creating physical positioning session…"
        viewModelScope.launch {
            runCatching {
                val now = System.currentTimeMillis()
                ensurePilotContext(now)
                val sourceState = sensorSource.state.value
                val sensorSession = SensorSessionEntity(
                    id = "SNS-${UUID.randomUUID()}",
                    dataMode = DataMode.REAL_DEVICE.name,
                    selectedStepSource = sourceState.selectedStepSource?.name ?: "UNAVAILABLE",
                    selectedHeadingSource = sourceState.selectedHeadingSource?.name ?: "UNAVAILABLE",
                    initialStrideMetres = appState.strideMetres.toDouble(),
                    startedAtEpochMillis = now,
                )
                val positioningSession = PositioningSessionEntity(
                    id = "POS-${UUID.randomUUID()}",
                    venueId = PILOT_VENUE_ID,
                    sensorSessionId = sensorSession.id,
                    initializationType = "WAITING_FOR_FRESH_WIFI",
                    dataMode = DataMode.REAL_DEVICE.name,
                    particleCount = appState.particleCount,
                    startedAtEpochMillis = now,
                )
                repositories.positioning.saveSensorSession(sensorSession)
                repositories.positioning.saveSession(positioningSession)
                sensorSession to positioningSession
            }.onSuccess { (sensorSession, positioningSession) ->
                if (!foreground || appState.mode != DataMode.REAL_DEVICE || !appState.liveRunning) {
                    val endedAt = System.currentTimeMillis()
                    repositories.positioning.saveSensorSession(sensorSession.copy(endedAtEpochMillis = endedAt))
                    repositories.positioning.saveSession(positioningSession.copy(endedAtEpochMillis = endedAt))
                    appState.liveRunning = false
                    return@onSuccess
                }
                activeSensorSession = sensorSession
                activePositioningSession = positioningSession
                particleFilter = null
                rawPdrTracker = null
                liveEstimateSequence = 0L
                livePdrSequence = 0L
                liveYawBaselineRadians = latestDeviceYawRadians
                latestMapHeadingRadians = 0.0
                previousGyroscopeTimestampNanos = null
                appState.liveRunning = true
                appState.realLiveInitialized = false
                appState.realLivePosition = null
                appState.realRawPdrPosition = null
                appState.realWifiPosition = null
                appState.realWifiFloorId = null
                appState.realWifiUncertaintyMetres = null
                appState.realTestSamples.clear()
                appState.realEvaluationStatus = "Live session ready for independent checkpoint capture"
                rawPdrFloorId = null
                appState.realLastWifiCorrectionEpochMillis = null
                appState.relocalizationCount = 0
                appState.realFusedTrail = emptyList()
                appState.realRawPdrTrail = emptyList()
                appState.realWifiFixes = emptyList()
                appState.realParticleCloud = emptyList()
                appState.realLiveStepCount = 0L
                appState.realLiveDistanceMetres = 0.0
                appState.realLiveMapRejectedCount = 0
                appState.realLiveSessionLabel = positioningSession.id
                appState.realLiveHeadingSource = sensorSource.state.value.selectedHeadingSource?.name ?: "unavailable"
                appState.realLiveStatus = "Waiting for a fresh Wi-Fi fix; hold the phone facing map +x"
                startLiveScanLoop()
            }.onFailure { failure ->
                appState.liveRunning = false
                appState.realLiveStatus = "Could not create physical session: ${failure.message ?: failure::class.java.simpleName}"
            }
        }
    }

    private fun startLiveScanLoop() {
        liveScanJob?.cancel()
        liveScanJob = viewModelScope.launch {
            while (isActive && foreground && appState.liveRunning && activePositioningSession != null) {
                requestRealScan("Live locate")
                val now = System.currentTimeMillis()
                val next = wifiScanner.state.value.nextPermittedRequestAtEpochMillis
                val waitMillis = if (next == null) 30_000L else (next - now).coerceAtLeast(1_000L)
                delay(waitMillis + 250L)
            }
        }
    }

    private fun startRealSources() {
        wifiScanner.start()
        sensorSource.start()
    }

    private fun realWifiReady(action: String): Boolean {
        if (appState.mode != DataMode.REAL_DEVICE) return false
        if (!appState.realMapReady) {
            appState.realWifiIssue = "Physical map is still loading or failed to load; see Floor-plan editor"
            return false
        }
        if (!foreground) {
            appState.realWifiIssue = "$action unavailable while TURN is not foreground"
            return false
        }
        refreshWifiPermissionStatus()
        if (missingWifiPermissions().isNotEmpty()) {
            appState.realWifiIssue = WifiScanIssue.PERMISSION_DENIED.displayName()
            return false
        }
        startRealSources()
        return true
    }

    private fun startSurveyScanLoop() {
        surveyScanJob?.cancel()
        surveyScanJob = viewModelScope.launch {
            while (isActive && foreground && appState.surveyRunning && activeSurvey != null) {
                requestRealScan("Survey")
                val now = System.currentTimeMillis()
                val next = wifiScanner.state.value.nextPermittedRequestAtEpochMillis
                val waitMillis = if (next == null) 30_000L else (next - now).coerceAtLeast(1_000L)
                delay(waitMillis + 250L)
            }
        }
    }

    private suspend fun requestRealScan(source: String) {
        when (val result = wifiScanner.requestScan()) {
            is WifiScanRequestResult.Accepted -> {
                appState.realWifiRequestAccepted = true
                if (activeSurvey != null) appState.surveyRuntimeStatus = "$source request accepted; waiting for Android's result broadcast"
            }
            is WifiScanRequestResult.Rejected -> {
                appState.realWifiRequestAccepted = false
                appState.realWifiIssue = result.issue.displayName()
                if (activeSurvey != null) appState.surveyRuntimeStatus = "$source request not accepted: ${result.issue.displayName()}"
            }
        }
    }

    private suspend fun handleWifiBatch(batch: WifiScanBatch) {
        if (appState.mode != DataMode.REAL_DEVICE) return
        if (batch.simulated) {
            appState.realWifiIssue = "Simulated batch rejected in REAL DEVICE mode"
            return
        }

        appState.realWifiResultsUpdated = batch.resultsUpdated
        appState.realWifiFresh = batch.isIndependentFreshScan
        appState.realWifiIssue = batch.issue?.displayName()
        appState.realWifiThrottlingEnabled = batch.scanThrottlingEnabled
        appState.realWifiNewestAgeMillis = batch.newestResultAgeMillis
        appState.realWifiLastBatchEpochMillis = batch.receivedAtEpochMillis
        appState.realWifiNextPermittedRequestEpochMillis = batch.nextPermittedRequestAtEpochMillis
        appState.realWifiAccessPoints = batch.accessPoints.map { accessPoint ->
            WifiAccessPointUi(
                bssid = accessPoint.bssid,
                ssid = accessPoint.ssid ?: "<hidden SSID>",
                rssiDbm = accessPoint.rssiDbm,
                frequencyMhz = accessPoint.frequencyMhz,
                channel = accessPoint.channel,
                ageSeconds = ((accessPoint.ageMillisAtReceipt + 999L) / 1_000L).toInt(),
                fresh = batch.isIndependentFreshScan,
            )
        }

        if (appState.liveRunning) processLiveWifiBatch(batch)

        val session = activeSurvey ?: return
        if (!appState.surveyRunning) return
        runCatching {
            val entities = captureService.record(session, surveyScanSequence++, batch)
            val aggregates = captureService.recomputeAggregates(session.id, System.currentTimeMillis())
            entities to aggregates
        }.onSuccess { (entities, aggregates) ->
            appState.surveyRawObservationCount += entities.observations.size
            surveyBssids += entities.observations.map { it.bssid }
            appState.surveyDistinctBssidCount = surveyBssids.size
            if (entities.snapshot.isFresh) appState.surveyAcceptedSnapshots += 1
            else appState.surveyCachedIgnored += 1
            appState.realSurveyAggregates = aggregates.map { aggregate ->
                FingerprintAggregateUi(
                    bssid = aggregate.bssid,
                    medianDbm = aggregate.medianRssiDbm,
                    meanDbm = aggregate.meanRssiDbm,
                    standardDeviation = aggregate.standardDeviationDb,
                    range = aggregate.minimumRssiDbm..aggregate.maximumRssiDbm,
                    observations = aggregate.observationCount,
                    detectionRatePercent = (aggregate.detectionRate * 100.0).toInt(),
                    stable = !aggregate.unstable,
                )
            }
            appState.surveyRuntimeStatus = if (entities.snapshot.isFresh) {
                "Fresh physical snapshot stored and included in aggregation"
            } else {
                "Cached/stale snapshot stored for lineage and excluded from aggregation"
            }
            appState.surveySaveStatus = "Room contains ${surveyScanSequence} snapshot record(s) for this session"
            if (appState.surveyAcceptedSnapshots >= appState.surveyTargetSnapshots) {
                finishActiveSurvey("Target reached; bounded survey completed automatically")
            }
        }.onFailure { failure ->
            appState.surveyRuntimeStatus = "Scan received but Room persistence failed"
            appState.surveySaveStatus = failure.message ?: failure::class.java.simpleName
        }
    }

    private suspend fun finishActiveSurvey(reason: String) {
        val session = activeSurvey ?: return
        activeSurvey = null
        surveyScanJob?.cancel()
        surveyScanJob = null
        appState.surveyRunning = false
        runCatching {
            repositories.surveys.save(session.copy(endedAtEpochMillis = System.currentTimeMillis()))
            captureService.recomputeAggregates(session.id, System.currentTimeMillis())
        }.onSuccess { aggregates ->
            appState.realSurveyAggregates = aggregates.map { aggregate ->
                FingerprintAggregateUi(
                    bssid = aggregate.bssid,
                    medianDbm = aggregate.medianRssiDbm,
                    meanDbm = aggregate.meanRssiDbm,
                    standardDeviation = aggregate.standardDeviationDb,
                    range = aggregate.minimumRssiDbm..aggregate.maximumRssiDbm,
                    observations = aggregate.observationCount,
                    detectionRatePercent = (aggregate.detectionRate * 100.0).toInt(),
                    stable = !aggregate.unstable,
                )
            }
            appState.surveyRuntimeStatus = reason
            appState.surveySaveStatus = "Closed ${session.id}; ${aggregates.size} aggregate fingerprint row(s) stored"
        }.onFailure { failure ->
            appState.surveyRuntimeStatus = "$reason; final aggregate write failed"
            appState.surveySaveStatus = failure.message ?: failure::class.java.simpleName
        }
    }

    fun savePilotMap() {
        if (appState.mode != DataMode.REAL_DEVICE) {
            appState.editorStatus = "Demo draft kept in memory; switch to Real Device to save a physical map"
            return
        }
        if (!appState.realMapReady || activeSurvey != null || activePositioningSession != null) {
            appState.editorStatus = "Wait for map loading and stop physical sessions before saving"
            return
        }
        viewModelScope.launch {
            runCatching { ensurePilotContext(System.currentTimeMillis()) }
                .onSuccess { appState.editorStatus = "Metric polygon, walls and reference points saved to Room" }
                .onFailure { appState.editorStatus = "Map save rejected: ${it.message}" }
        }
    }

    private suspend fun ensurePilotContext(now: Long) = app.database.withTransaction {
        val polygon = MetricPolygon(appState.draftWalkablePolygon.map { MetricPoint(it.x.toDouble(), it.y.toDouble()) })
        com.turn.fieldtest.core.validateSimpleWalkablePolygon(polygon)
        require(appState.referencePoints.map { it.id }.distinct().size == appState.referencePoints.size) {
            "Duplicate reference-point IDs"
        }
        appState.referencePoints.forEach { point ->
            require(polygon.contains(MetricPoint(point.metres.x.toDouble(), point.metres.y.toDouble()))) {
                "${point.id} is outside walkable space"
            }
        }
        if (repositories.venues.venue(PILOT_VENUE_ID) == null) {
            repositories.venues.save(
                VenueEntity(
                    id = PILOT_VENUE_ID,
                    name = "Computing Block Pilot",
                    notes = "Local TURN pilot context; verify dimensions and coordinates before field collection.",
                    createdAtEpochMillis = now,
                    updatedAtEpochMillis = now,
                ),
            )
        }
        if (repositories.floorPlans.floor(PILOT_FLOOR_ID) == null) {
            repositories.floorPlans.save(
                FloorEntity(
                    id = PILOT_FLOOR_ID,
                    venueId = PILOT_VENUE_ID,
                    name = "Ground floor",
                    levelNumber = 0,
                    widthMetres = 42.0,
                    heightMetres = 28.0,
                    createdAtEpochMillis = now,
                    updatedAtEpochMillis = now,
                ),
            )
        }
        appState.referencePoints.forEach { point ->
            val previous = repositories.floorPlans.referencePoint(point.id)
            require(previous == null ||
                (previous.xMetres == point.metres.x.toDouble() && previous.yMetres == point.metres.y.toDouble())) {
                "${point.id} already identifies a saved coordinate; create a new point ID to relocate it"
            }
            repositories.floorPlans.save(
                ReferencePointEntity(
                    id = point.id,
                    floorId = PILOT_FLOOR_ID,
                    name = point.label,
                    xMetres = point.metres.x.toDouble(),
                    yMetres = point.metres.y.toDouble(),
                    isInsideWalkableSpace = true,
                    notes = "TURN pilot point; confirm the physical marker and metric coordinate before collection.",
                    updatedAtEpochMillis = now,
                ),
            )
        }
        repositories.floorPlans.save(WalkableRegionEntity(
            id = "REGION-PILOT", floorId = PILOT_FLOOR_ID, name = "Pilot walkable region",
            polygonJson = JsonArray(appState.draftWalkablePolygon.map {
                JsonObject(mapOf("x" to JsonPrimitive(it.x.toDouble()), "y" to JsonPrimitive(it.y.toDouble())))
            }).toString(), updatedAtEpochMillis = now,
        ))
        repositories.floorPlans.observeWalls(PILOT_FLOOR_ID).first().forEach { repositories.floorPlans.delete(it) }
        appState.draftWalls.forEachIndexed { index, (start, end) ->
            require(start != end) { "Wall $index has zero length" }
            repositories.floorPlans.save(WallSegmentEntity(
                id = "WALL-PILOT-$index", floorId = PILOT_FLOOR_ID,
                startXMetres = start.x.toDouble(), startYMetres = start.y.toDouble(),
                endXMetres = end.x.toDouble(), endYMetres = end.y.toDouble(),
            ))
        }
    }

    private suspend fun processLiveWifiBatch(batch: WifiScanBatch) {
        val positioningSession = activePositioningSession ?: return
        val freshness = when {
            batch.isIndependentFreshScan -> CoreWifiFreshness.FRESH
            else -> CoreWifiFreshness.CACHED
        }
        val fingerprints = loadPilotFingerprints()
        if (fingerprints.isEmpty()) {
            appState.realLiveStatus = "No saved fingerprints found; survey at least one reference point first"
            return
        }
        val matcher = WeightedKnnWifiMatcher(
            WifiMatcherConfig(
                k = appState.knnK,
                missingRssiDbm = appState.missingRssi.toDouble(),
                normalization = if (appState.deviceOffsetNormalization) {
                    WifiNormalization.DEVICE_OFFSET
                } else {
                    WifiNormalization.RAW
                },
            ),
        )
        val matchStarted = System.currentTimeMillis()
        val match = matcher.match(
            liveRssiByBssid = batch.accessPoints.associate { it.bssid to it.rssiDbm.toDouble() },
            fingerprints = fingerprints,
            freshness = freshness,
        )
        appState.realNearestFingerprintIds = match.neighbours.map {
            it.referencePointId.substringBefore("::")
        }.distinct()
        appState.realWifiMatchDistance = match.weightedMatchDistance.takeIf(Double::isFinite)
        if (freshness == CoreWifiFreshness.FRESH) {
            appState.realWifiPosition = match.estimatedPosition?.takeUnless { match.unlikeDatabase }?.toUiOffset()
            appState.realWifiFloorId = match.estimatedFloorId.takeUnless { match.unlikeDatabase }
            appState.realWifiUncertaintyMetres = match.uncertaintyRadiusMetres.takeIf { it.isFinite() }
        }
        match.estimatedPosition?.let { position ->
            if (freshness == CoreWifiFreshness.FRESH) {
                appState.realWifiFixes = (appState.realWifiFixes + position.toUiOffset()).takeLast(MAX_TRAIL_POINTS)
            }
        }

        if (freshness != CoreWifiFreshness.FRESH) {
            particleFilter?.correctWithWifi(match)
            appState.lastCorrectionType = "cached Wi-Fi result ignored"
            appState.realLiveStatus = "Cached/stale Wi-Fi retained for diagnostics; fusion was not corrected"
            return
        }
        if (match.estimatedPosition == null || match.estimatedFloorId == null || match.unlikeDatabase) {
            appState.lastCorrectionType = "confidence degraded"
            appState.realLiveStatus = "Fresh scan does not resemble the fingerprint database"
            return
        }

        val prior = particleFilter?.summary()
        if (particleFilter == null || particleFilter?.isLost == true) {
            if (match.confidence < 0.55 || match.floorAgreement < 0.7) {
                appState.realLiveStatus = "Wi-Fi fix is ambiguous; waiting for stronger position/floor evidence"
                return
            }
            val filter = ParticleFilter(
                metricMap = currentPilotMap(),
                config = ParticleFilterConfig(particleCount = appState.particleCount),
                seed = positioningSession.startedAtEpochMillis,
            )
            runCatching {
                filter.initialize(
                    AbsoluteFix(
                        position = match.estimatedPosition,
                        floorId = match.estimatedFloorId,
                        source = AbsoluteFixSource.WIFI,
                    ),
                    positionStdMetres = (match.uncertaintyRadiusMetres / 2.0).coerceIn(0.35, 4.0),
                )
            }.onFailure { failure ->
                appState.realLiveStatus = "Wi-Fi fix lies outside configured walkable geometry: ${failure.message}"
                return
            }
            particleFilter = filter
            val availableSteps = if (
                sensorSource.state.value.selectedStepSource == TurnSensorType.STEP_DETECTOR
            ) setOf(StepSource.STEP_DETECTOR) else emptySet()
            if (rawPdrTracker == null) {
                rawPdrTracker = PdrTracker(
                initialPosition = match.estimatedPosition,
                initialHeadingRadians = 0.0,
                stepProcessor = StepProcessor(availableSteps),
                strideModel = StrideModel(appState.strideMetres.toDouble()),
            )
                rawPdrFloorId = match.estimatedFloorId
                liveYawBaselineRadians = latestDeviceYawRadians
                latestMapHeadingRadians = 0.0
                appState.realRawPdrPosition = match.estimatedPosition.toUiOffset()
                appState.realRawPdrTrail = listOf(match.estimatedPosition.toUiOffset())
            } else {
                appState.relocalizationCount += 1
            }
            appState.realLiveInitialized = true
            appState.lastCorrectionType = if (appState.relocalizationCount > 0) "globally relocalized" else "first confident Wi-Fi fix"
            appState.realLiveStatus = "Fresh Wi-Fi initialized the particle filter; original raw PDR is retained"
            activePositioningSession = positioningSession.copy(
                initialFloorId = match.estimatedFloorId,
                initializationType = "FRESH_WIFI",
            ).also { repositories.positioning.saveSession(it) }
        } else {
            val correction = requireNotNull(particleFilter).correctWithWifi(match)
            if (correction.kind == WifiCorrectionKind.GLOBAL_RELOCALIZATION) {
                appState.relocalizationCount += 1
            }
            appState.lastCorrectionType = when (correction.kind) {
                WifiCorrectionKind.APPLIED -> "fresh Wi-Fi correction"
                WifiCorrectionKind.GLOBAL_RELOCALIZATION -> "globally relocalized"
                WifiCorrectionKind.FILTER_LOST -> "filter lost"
                WifiCorrectionKind.IGNORED_NO_ESTIMATE -> "Wi-Fi produced no estimate"
                WifiCorrectionKind.IGNORED_NOT_FRESH -> "cached Wi-Fi result ignored"
            }
            appState.realLiveStatus = when (correction.kind) {
                WifiCorrectionKind.APPLIED -> "Fresh Wi-Fi corrected particle weights"
                WifiCorrectionKind.GLOBAL_RELOCALIZATION -> "Strong distant Wi-Fi match globally relocalized the filter"
                WifiCorrectionKind.FILTER_LOST -> "Particle filter lost; request Wi-Fi or re-anchor"
                else -> appState.lastCorrectionType
            }
        }
        if (particleFilter?.summary() != null && appState.lastCorrectionType in listOf(
                "first confident Wi-Fi fix", "fresh Wi-Fi correction", "globally relocalized")) {
            appState.realLastWifiCorrectionEpochMillis = batch.receivedAtEpochMillis
        }
        updateRealParticleUi()
        persistWifiEstimate(
            session = requireNotNull(activePositioningSession),
            matchPosition = match.estimatedPosition,
            floorId = match.estimatedFloorId,
            confidence = match.confidence,
            uncertainty = match.uncertaintyRadiusMetres,
            neighbourIds = appState.realNearestFingerprintIds,
            wifiAgeMillis = batch.newestResultAgeMillis,
            latencyMillis = System.currentTimeMillis() - matchStarted,
            prior = prior?.position,
        )
    }

    private suspend fun loadPilotFingerprints(): List<WifiFingerprint> {
        val rows = repositories.surveys.matchingFingerprints(PILOT_VENUE_ID)
        return rows.groupBy { it.referencePointId }.mapNotNull { (referencePointId, pointRows) ->
            val point = repositories.floorPlans.referencePoint(referencePointId) ?: return@mapNotNull null
            val vector = pointRows.groupBy { it.bssid.lowercase() }.mapValues { (_, bssidRows) ->
                bssidRows.map { it.medianRssiDbm }.sorted().let(::median)
            }
            WifiFingerprint(
                referencePointId = referencePointId,
                floorId = point.floorId,
                position = MetricPoint(point.xMetres, point.yMetres),
                rssiByBssid = vector,
            )
        }
    }

    private fun currentPilotMap(): MetricMap {
        val walkable = MetricPolygon(
            appState.draftWalkablePolygon.map { MetricPoint(it.x.toDouble(), it.y.toDouble()) },
        )
        val walls = appState.draftWalls.mapIndexedNotNull { index, (start, end) ->
            if (start == end) null else WallSegment(
                id = "W-$index",
                start = MetricPoint(start.x.toDouble(), start.y.toDouble()),
                end = MetricPoint(end.x.toDouble(), end.y.toDouble()),
            )
        }
        return MetricMap(mapOf(PILOT_FLOOR_ID to MapFloor(PILOT_FLOOR_ID, listOf(walkable), walls)))
    }

    private suspend fun persistWifiEstimate(
        session: PositioningSessionEntity,
        matchPosition: MetricPoint,
        floorId: String,
        confidence: Double,
        uncertainty: Double,
        neighbourIds: List<String>,
        wifiAgeMillis: Long?,
        latencyMillis: Long,
        prior: MetricPoint?,
    ) {
        val now = System.currentTimeMillis()
        val sequence = liveEstimateSequence++
        val neighboursJson = JsonArray(neighbourIds.map(::JsonPrimitive)).toString()
        repositories.positioning.saveEstimate(
            PositionEstimateEntity(
                id = "EST-${session.id}-$sequence-WIFI",
                positioningSessionId = session.id,
                sequence = sequence,
                method = "WIFI_ONLY",
                floorId = floorId,
                xMetres = matchPosition.x,
                yMetres = matchPosition.y,
                confidenceRadiusMetres = uncertainty.takeIf(Double::isFinite),
                confidenceScore = confidence,
                floorConfidence = appState.realLiveFloorConfidence,
                nearestFingerprintIdsJson = neighboursJson,
                wifiResultAgeMillis = wifiAgeMillis,
                stepCount = appState.realLiveStepCount,
                timestampEpochMillis = now,
                latencyMillis = latencyMillis,
                status = appState.lastCorrectionType,
            ),
            CorrectionEventEntity(
                id = "COR-${UUID.randomUUID()}",
                positioningSessionId = session.id,
                floorId = floorId,
                type = appState.lastCorrectionType.uppercase().replace(' ', '_'),
                sourceId = neighbourIds.firstOrNull(),
                priorXMetres = prior?.x,
                priorYMetres = prior?.y,
                correctedXMetres = appState.realLivePosition?.x?.toDouble(),
                correctedYMetres = appState.realLivePosition?.y?.toDouble(),
                accepted = particleFilter?.summary() != null && appState.lastCorrectionType in listOf(
                    "first confident Wi-Fi fix", "fresh Wi-Fi correction", "globally relocalized"),
                globalRelocalization = appState.lastCorrectionType == "globally relocalized",
                timestampEpochMillis = now,
            ),
        )
        particleFilter?.summary()?.let { summary ->
            repositories.positioning.saveEstimate(
                PositionEstimateEntity(
                    id = "EST-${session.id}-$sequence-FUSED",
                    positioningSessionId = session.id,
                    sequence = sequence,
                    method = "FUSED",
                    floorId = summary.floorId,
                    xMetres = summary.position.x,
                    yMetres = summary.position.y,
                    headingRadians = latestMapHeadingRadians,
                    confidenceRadiusMetres = summary.uncertaintyRadiusMetres,
                    confidenceScore = summary.confidence,
                    floorConfidence = summary.floorConfidence,
                    particleSpreadMetres = summary.spatialSpreadMetres,
                    nearestFingerprintIdsJson = neighboursJson,
                    wifiResultAgeMillis = wifiAgeMillis,
                    stepCount = appState.realLiveStepCount,
                    timestampEpochMillis = now,
                    latencyMillis = latencyMillis,
                    status = appState.lastCorrectionType,
                ),
            )
        }
    }

    private suspend fun finishActiveLive(reason: String) {
        liveScanJob?.cancel()
        liveScanJob = null
        appState.liveRunning = false
        appState.realLiveStatus = reason
        val endedAt = System.currentTimeMillis()
        activeSensorSession?.let { repositories.positioning.saveSensorSession(it.copy(endedAtEpochMillis = endedAt)) }
        activePositioningSession?.let { repositories.positioning.saveSession(it.copy(endedAtEpochMillis = endedAt)) }
        activePositioningSession?.let { session ->
            repositories.evaluation.observeRuns(PILOT_VENUE_ID).first().firstOrNull { it.id == "TEST-${session.id}" }
                ?.let { repositories.evaluation.saveRun(it.copy(endedAtEpochMillis = endedAt)) }
        }
        activeSensorSession = null
        activePositioningSession = null
        particleFilter = null
        rawPdrTracker = null
    }

    private fun updateRealParticleUi() {
        val filter = particleFilter ?: return
        val summary = filter.summary()
        if (summary == null) {
            appState.realLiveInitialized = false
            appState.realLivePosition = null
            appState.realParticleCloud = emptyList()
            appState.realLiveConfidence = 0.0
            appState.realLiveFloorConfidence = 0.0
            appState.realLiveUncertaintyMetres = null
            appState.realLiveStatus = "Filter lost; request a fresh Wi-Fi fix"
            return
        }
        appState.realLiveInitialized = true
        appState.realLivePosition = summary.position.toUiOffset()
        appState.realLiveFloorId = summary.floorId
        appState.realLiveConfidence = summary.confidence
        appState.realLiveFloorConfidence = summary.floorConfidence
        appState.realLiveUncertaintyMetres = summary.uncertaintyRadiusMetres
        appState.realFusedTrail = (appState.realFusedTrail + summary.position.toUiOffset()).takeLast(MAX_TRAIL_POINTS)
        appState.realParticleCloud = filter.particles
            .filterIndexed { index, _ -> index % (filter.particles.size / 100).coerceAtLeast(1) == 0 }
            .take(100)
            .map { it.position.toUiOffset() }
    }

    private fun MetricPoint.toUiOffset() = androidx.compose.ui.geometry.Offset(x.toFloat(), y.toFloat())

    private fun median(values: List<Double>): Double {
        require(values.isNotEmpty())
        val middle = values.size / 2
        return if (values.size % 2 == 1) values[middle] else (values[middle - 1] + values[middle]) / 2.0
    }

    private suspend fun handleSensorSample(sample: SensorSample) {
        if (appState.mode != DataMode.REAL_DEVICE || sample.simulated) return
        latestSensorValues[sample.type] = sample.displayValue()
        updateLiveHeading(sample)
        if (appState.diagnosticWalkRunning && sample.kind == SensorSampleKind.STEP_DETECTED) {
            appState.diagnosticWalkSteps += 1
            appState.realDiagnosticDistanceMetres = appState.diagnosticWalkSteps * appState.strideMetres.toDouble()
        }
        if (appState.liveRunning && sample.kind == SensorSampleKind.STEP_DETECTED) {
            processLiveStep(sample)
        }
        val sourceState = sensorSource.state.value
        appState.realSensorReadings = sourceState.availability.map { availability ->
            availability.toUi(
                latestValue = latestSensorValues[availability.type],
                selectedStep = sourceState.selectedStepSource,
                selectedHeading = sourceState.selectedHeadingSource,
            )
        }
    }

    private fun updateLiveHeading(sample: SensorSample) {
        when (sample.kind) {
            SensorSampleKind.RELATIVE_ROTATION_VECTOR,
            SensorSampleKind.ROTATION_VECTOR,
            -> {
                if (sample.type != sensorSource.state.value.selectedHeadingSource) return
                if (sample.values.size < 3) return
                val matrix = FloatArray(9)
                runCatching {
                    SensorManager.getRotationMatrixFromVector(matrix, sample.values.toFloatArray())
                    val orientation = SensorManager.getOrientation(matrix, FloatArray(3))
                    val yaw = orientation[0].toDouble()
                    latestDeviceYawRadians = yaw
                    if (appState.liveRunning) {
                        val baseline = liveYawBaselineRadians ?: yaw.also { liveYawBaselineRadians = it }
                        // Android yaw grows clockwise; TURN's metric convention grows counter-clockwise.
                        latestMapHeadingRadians = normalizeRadians(-(yaw - baseline))
                    }
                }
            }
            SensorSampleKind.ANGULAR_VELOCITY -> {
                if (sensorSource.state.value.selectedHeadingSource != TurnSensorType.GYROSCOPE) return
                val previous = previousGyroscopeTimestampNanos
                previousGyroscopeTimestampNanos = sample.sensorTimestampNanos
                if (previous != null && appState.liveRunning) {
                    val deltaSeconds = (sample.sensorTimestampNanos - previous).coerceAtLeast(0L) / 1_000_000_000.0
                    val z = sample.values.getOrNull(2)?.toDouble() ?: return
                    // Right-handed Android +z rotation is counter-clockwise for a face-up phone.
                    latestMapHeadingRadians = normalizeRadians(latestMapHeadingRadians + z * deltaSeconds)
                }
            }
            else -> Unit
        }
        appState.realLiveHeadingDegrees = latestMapHeadingRadians * 180.0 / PI
    }

    private suspend fun processLiveStep(sample: SensorSample) {
        if (sensorSource.state.value.selectedHeadingSource == null) {
            appState.realLiveStatus = "No active heading sensor; Wi-Fi fixes available but PDR movement is unavailable"
            return
        }
        val tracker = rawPdrTracker ?: return
        val filter = particleFilter
        val sensorSession = activeSensorSession ?: return
        val positioningSession = activePositioningSession ?: return
        val event = tracker.processStep(
            signal = StepSignal(sample.sensorTimestampNanos, StepSource.STEP_DETECTOR),
            headingRadians = latestMapHeadingRadians,
        )
        val pdrSequence = livePdrSequence++
        repositories.positioning.savePdrEvent(
            PdrEventEntity(
                id = "PDR-${sensorSession.id}-$pdrSequence",
                sensorSessionId = sensorSession.id,
                sequence = pdrSequence,
                eventType = "STEP",
                source = StepSource.STEP_DETECTOR.name,
                timestampEpochMillis = sample.receivedAtEpochMillis,
                sensorTimestampNanos = sample.sensorTimestampNanos,
                stepDelta = if (event.decision.acceptedForMovement) 1 else 0,
                strideMetres = event.strideMetres,
                headingRadians = latestMapHeadingRadians,
                accepted = event.decision.acceptedForMovement,
                rejectionReason = event.decision.reason.takeUnless {
                    it == com.turn.fieldtest.core.StepRejectionReason.NONE
                }?.name,
            ),
        )
        if (!event.decision.acceptedForMovement) return

        val prediction = filter?.predictStep(event.strideMetres, latestMapHeadingRadians)
        appState.realLiveStepCount = event.stateAfter.acceptedStepCount.toLong()
        appState.realLiveDistanceMetres = event.stateAfter.estimatedDistanceMetres
        appState.realRawPdrPosition = event.stateAfter.position.toUiOffset()
        appState.realRawPdrTrail = (appState.realRawPdrTrail + event.stateAfter.position.toUiOffset()).takeLast(MAX_TRAIL_POINTS)
        appState.realLiveMapRejectedCount += prediction?.rejectedParticleCount ?: 0
        appState.lastCorrectionType = if ((prediction?.rejectedParticleCount ?: 0) > 0) {
            "map constraint applied"
        } else {
            "PDR prediction"
        }
        appState.realLiveStatus = if (prediction?.accepted == true) {
            "Step ${event.stateAfter.acceptedStepCount}: ${appState.lastCorrectionType}"
        } else {
            "Raw PDR continues; fused filter needs a fresh Wi-Fi fix"
        }
        updateRealParticleUi()

        val sequence = liveEstimateSequence++
        repositories.positioning.saveEstimate(
            PositionEstimateEntity(
                id = "EST-${positioningSession.id}-$sequence-RAW",
                positioningSessionId = positioningSession.id,
                sequence = sequence,
                method = "RAW_PDR",
                floorId = rawPdrFloorId,
                xMetres = event.stateAfter.position.x,
                yMetres = event.stateAfter.position.y,
                headingRadians = latestMapHeadingRadians,
                confidenceScore = 0.0,
                floorConfidence = appState.realLiveFloorConfidence,
                stepCount = appState.realLiveStepCount,
                timestampEpochMillis = sample.receivedAtEpochMillis,
                status = "PDR prediction",
            ),
        )
        prediction?.summary?.let { summary ->
            repositories.positioning.saveEstimate(
                PositionEstimateEntity(
                    id = "EST-${positioningSession.id}-$sequence-FUSED",
                    positioningSessionId = positioningSession.id,
                    sequence = sequence,
                    method = "FUSED",
                    floorId = summary.floorId,
                    xMetres = summary.position.x,
                    yMetres = summary.position.y,
                    headingRadians = latestMapHeadingRadians,
                    confidenceRadiusMetres = summary.uncertaintyRadiusMetres,
                    confidenceScore = summary.confidence,
                    floorConfidence = summary.floorConfidence,
                    particleSpreadMetres = summary.spatialSpreadMetres,
                    stepCount = appState.realLiveStepCount,
                    timestampEpochMillis = sample.receivedAtEpochMillis,
                    status = appState.lastCorrectionType,
                ),
            )
        }
    }

    private fun SensorAvailability.toUi(
        latestValue: String?,
        selectedStep: TurnSensorType?,
        selectedHeading: TurnSensorType?,
    ) = SensorReadingUi(
        name = type.displayName(),
        value = when {
            !available -> "unavailable"
            latestValue != null -> latestValue
            else -> "no physical sample yet"
        },
        available = available,
        quality = when (type) {
            selectedStep -> "selected step source"
            selectedHeading -> "selected heading source"
            else -> vendor ?: name ?: "Android sensor"
        },
    )

    private fun SensorSample.displayValue(): String = when (kind) {
        SensorSampleKind.STEP_DETECTED -> "physical step event"
        SensorSampleKind.STEP_COUNTER_TOTAL -> "${values.firstOrNull()?.toLong() ?: 0L} total"
        SensorSampleKind.PRESSURE -> values.firstOrNull()?.let { "%.2f hPa".format(it) } ?: "empty sample"
        SensorSampleKind.ACCURACY_CHANGED -> "accuracy $accuracy"
        else -> values.take(3).joinToString(prefix = "[", postfix = "]") { "%.3f".format(it) }
    }

    private fun TurnSensorType.displayName(): String = name.lowercase().split('_').joinToString(" ") {
        it.replaceFirstChar(Char::uppercase)
    }

    private fun WifiScanIssue.displayName(): String = when (this) {
        WifiScanIssue.WIFI_DISABLED -> "Wi-Fi is disabled"
        WifiScanIssue.LOCATION_SERVICES_DISABLED -> "Location services are disabled"
        WifiScanIssue.PERMISSION_DENIED -> "Required Wi-Fi location permission is missing"
        WifiScanIssue.SCANNER_NOT_STARTED -> "Foreground Wi-Fi receiver is not started"
        WifiScanIssue.REQUEST_REJECTED_OR_THROTTLED -> "Android rejected or throttled the scan request"
        WifiScanIssue.CLIENT_RATE_LIMITED -> "TURN is waiting for the next permitted request"
        WifiScanIssue.SECURITY_EXCEPTION -> "Android denied access to Wi-Fi results"
        WifiScanIssue.HARDWARE_OR_API_FAILURE -> "Wi-Fi hardware or API failure"
        WifiScanIssue.NO_ACCESS_POINTS -> "No access points were returned"
    }

    override fun onCleared() {
        surveyScanJob?.cancel()
        liveScanJob?.cancel()
        wifiScanner.stop()
        sensorSource.stop()
        super.onCleared()
    }

    companion object {
        const val PILOT_VENUE_ID = "VEN-CS-01"
        const val PILOT_FLOOR_ID = "FL-G"
        const val PILOT_REFERENCE_POINT_ID = "RP-G-07"
        const val PILOT_X_METRES = 14.0
        const val PILOT_Y_METRES = 18.0
        const val MAX_TRAIL_POINTS = 500
    }
}
