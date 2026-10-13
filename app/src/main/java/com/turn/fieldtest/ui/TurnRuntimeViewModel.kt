package com.turn.fieldtest.ui

import android.app.Application
import android.graphics.BitmapFactory
import com.turn.fieldtest.platform.sensors.DisplayHeading
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
import com.turn.fieldtest.data.repository.*
import com.turn.fieldtest.data.settings.TurnDataMode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.turn.fieldtest.data.settings.TurnSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.catch
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
import com.turn.fieldtest.data.local.FloorPlanAssetEntity
import com.turn.fieldtest.data.local.CorrectionEventEntity
import com.turn.fieldtest.data.local.PdrEventEntity
import com.turn.fieldtest.data.local.PositionEstimateEntity
import com.turn.fieldtest.data.local.PositioningSessionEntity
import com.turn.fieldtest.data.local.ReferencePointEntity
import com.turn.fieldtest.data.local.SensorSessionEntity
import com.turn.fieldtest.data.local.SurveySessionEntity
import com.turn.fieldtest.data.local.VenueEntity
import com.turn.fieldtest.data.survey.WifiSurveyCaptureService
import com.turn.fieldtest.core.NavigationGraph
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
    val appState = TurnAppState().apply { mode = DataMode.REAL_DEVICE; startupLoading = true }

    private val app = application as TurnApplication
    private val wifiScanner = app.wifiScanner
    private val sensorSource = app.sensorSource
    private val repositories = app.repositories
    private val projects = ProjectRepository(app.database)
    private val eventMutex = Mutex()
    private var sessionMap: MetricMap? = null
    private var sessionGraph: NavigationGraph? = null
    private var sessionInitialHeadingRadians = 0.0
    private var savedNavigationGraph: NavigationGraph? = null
    private val liveWifiEvidence = com.turn.fieldtest.platform.wifi.LiveWifiEvidenceGate()
    private val activeContext get() = requireNotNull(appState.activeProject) { "Open a saved project first" }
    private val captureService = WifiSurveyCaptureService(repositories.surveys)

    private var foreground = false
    private var activeSurvey: SurveySessionEntity? = null
    private var surveyScanSequence = 0L
    private var surveyScanJob: Job? = null
    private var liveScanJob: Job? = null
    private val surveyBssids = linkedSetOf<String>()
    private val latestSensorValues = linkedMapOf<TurnSensorType, String>()
    private val lastDiagnosticValueNanos = mutableMapOf<TurnSensorType, Long>()
    private var activePositioningSession: PositioningSessionEntity? = null
    private var activeSensorSession: SensorSessionEntity? = null
    private var particleFilter: ParticleFilter? = null
    private var rawPdrTracker: PdrTracker? = null
    private var liveEstimateSequence = 0L
    private var livePdrSequence = 0L
    private var latestDeviceYawRadians: Double? = null
    private var liveYawBaselineRadians: Double? = null
    private var lastLiveHeadingSensorNanos: Long? = null
    private var headingAlignmentNotBeforeNanos = 0L
    private var lastHeadingDisplayRotation: Int? = null
    private var latestRotationVector: FloatArray? = null
    private var latestMapHeadingRadians = 0.0
    private var previousGyroscopeTimestampNanos: Long? = null
    private var gyroNeedsRealignment = false
    private val stepHeadingHistory = com.turn.fieldtest.core.StepHeadingHistory()
    private var lastSensorUiPublishNanos = 0L
    private var lastHeadingUiPublishNanos = 0L
    private var lastHeadingReceiptEpochMillis: Long? = null
    private var rawPdrFloorId: String? = null

    fun captureCheckpoint(input: CheckpointInput) {
        val context = appState.activeProject ?: return
        val session = activePositioningSession ?: return
        if (appState.mode != DataMode.REAL_DEVICE || !appState.liveRunning || appState.realEvaluationBusy) return
        if (input.code.isBlank() || !input.x.isFinite() || !input.y.isFinite() ||
            input.x !in 0.0..appState.floorWidthMetres.toDouble() ||
            input.y !in 0.0..appState.floorHeightMetres.toDouble()) {
            appState.realEvaluationStatus = "Checkpoint code and coordinates inside the active floor are required"
            return
        }
        // Freeze estimates at the button press, before any suspend/database work. Truth is never
        // passed to the matcher, raw PDR tracker, or particle filter.
        val now = System.currentTimeMillis()
        fun snapshot(point: Offset?, floor: String?, radius: Double? = null): EvaluationEstimate? =
            if (point == null || floor == null) null else EvaluationEstimate(point.x.toDouble(), point.y.toDouble(), floor, radius)
        val wifi = snapshot(appState.realWifiPosition, appState.realWifiFloorId, appState.realWifiUncertaintyMetres)
        val raw = snapshot(appState.realRawPdrPosition, rawPdrFloorId)
        val fused = if (appState.realLiveInitialized) snapshot(appState.realLivePosition,
            appState.realLiveFloorId, appState.realLiveUncertaintyMetres) else null
        val truth = MetricPoint(input.x, input.y)
        val wifiError = evaluateAtCheckpoint(truth, context.floorId, wifi)
        val rawError = evaluateAtCheckpoint(truth, context.floorId, raw)
        val fusedError = evaluateAtCheckpoint(truth, context.floorId, fused)
        fun encoded(value: EvaluationEstimate?) = value?.let { Json.encodeToString(EvaluationEstimate.serializer(), it) }
        val checkpointId = "CP-${context.floorId}-${input.code}"
        val runId = "TEST-${session.id}"
        val sample = TestSampleEntity(
            id = "SAMPLE-${UUID.randomUUID()}", testRunId = runId, checkpointId = checkpointId,
            trueFloorId = context.floorId, trueXMetres = input.x, trueYMetres = input.y,
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
                "candidateDetails" to JsonArray(appState.realWifiNeighbourDetails.map(::JsonPrimitive)),
                "evidenceStatus" to JsonPrimitive(appState.realWifiEvidenceStatus),
                "usableFixEpochMillis" to (appState.realWifiFixEpochMillis?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull),
            )).toString(),
            mapConstraintEventsJson = JsonObject(mapOf("rejectedParticleMoves" to JsonPrimitive(appState.realLiveMapRejectedCount))).toString(),
            timestampEpochMillis = now,
        )
        appState.realEvaluationBusy = true
        viewModelScope.launch {
            try {
                app.database.withTransaction {
                    val existing = repositories.evaluation.observeCheckpoints(context.venueId).first().firstOrNull { it.checkpointCode == input.code }
                    require(existing == null || (existing.floorId == context.floorId && existing.xMetres == input.x && existing.yMetres == input.y)) {
                        "Checkpoint code already belongs to a different coordinate"
                    }
                    require(repositories.floorPlans.observeReferencePoints(context.floorId).first().none {
                        MetricPoint(it.xMetres, it.yMetres).distanceTo(truth) < 0.05
                    }) { "Choose an independent checkpoint, separate from a training reference point" }
                    val run = repositories.evaluation.observeRuns(context.venueId).first().firstOrNull { it.id == runId }
                    if (run == null) repositories.evaluation.saveRun(TestRunEntity(
                        id = runId, venueId = context.venueId, positioningSessionId = session.id,
                        anonymousDeviceId = "DEVICE-${UUID.randomUUID()}", deviceManufacturer = Build.MANUFACTURER,
                        deviceModel = Build.MODEL, androidVersion = Build.VERSION.RELEASE,
                        appVersion = BuildConfig.VERSION_NAME, startedAtEpochMillis = now,
                    ))
                    if (existing == null) repositories.evaluation.saveCheckpoint(TestCheckpointEntity(
                        checkpointId, context.venueId, context.floorId, input.code, input.x, input.y))
                    repositories.evaluation.saveSample(sample.copy(checkpointId = existing?.id ?: checkpointId))
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
            projects.observeProjects().catch { error ->
                appState.projectStatus = "Could not read saved projects: ${error.message}"
            }.collect { appState.projects = it }
        }
        viewModelScope.launch {
            val saved = app.settings.settings.first()
            appState.knnK = saved.wifiK
            appState.missingRssi = saved.missingRssiDbm
            appState.deviceOffsetNormalization = saved.normalizeDeviceOffset
            appState.strideMetres = saved.defaultStrideMetres.toFloat()
            appState.particleCount = saved.particleCount
            appState.darkTheme = saved.darkTheme
            appState.mode = DataMode.valueOf(saved.dataMode.name)
            try {
                if (appState.mode == DataMode.REAL_DEVICE) restoreSelectedProject() else appState.loadDemoMap()
            } catch (error: Exception) { appState.projectStatus = "Could not restore project: ${error.message}" }
            finally { appState.startupLoading = false }
            refreshWifiPermissionStatus()
            if (foreground && appState.mode == DataMode.REAL_DEVICE) startRealSources()
            snapshotFlow {
                saved.copy(
                    dataMode = TurnDataMode.valueOf(appState.mode.name),
                    activeVenueId = appState.activeProject?.venueId ?: saved.activeVenueId,
                    activeFloorId = appState.activeProject?.floorId ?: saved.activeFloorId,
                    wifiK = appState.knnK,
                    missingRssiDbm = appState.missingRssi,
                    normalizeDeviceOffset = appState.deviceOffsetNormalization,
                    defaultStrideMetres = appState.strideMetres.toDouble(),
                    particleCount = appState.particleCount,
                    darkTheme = appState.darkTheme,
                )
            }.distinctUntilChanged().collect { settings ->
                try {
                    app.settings.update { previous -> previous.copy(
                        dataMode = settings.dataMode, activeVenueId = settings.activeVenueId, activeFloorId = settings.activeFloorId,
                        wifiK = settings.wifiK, missingRssiDbm = settings.missingRssiDbm,
                        normalizeDeviceOffset = settings.normalizeDeviceOffset, defaultStrideMetres = settings.defaultStrideMetres,
                        particleCount = settings.particleCount, darkTheme = settings.darkTheme,
                    ) }
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (error: Exception) { appState.projectStatus = "Could not save preferences: ${error.message}" }
            }
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
            wifiScanner.batches.collect { batch -> eventMutex.withLock {
                try { handleWifiBatch(batch) }
                catch (error: Exception) { stopAfterLiveFailure(error) }
            } }
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
            sensorSource.samples.collect { sample -> eventMutex.withLock {
                try { handleSensorSample(sample) }
                catch (error: Exception) { stopAfterLiveFailure(error) }
            } }
        }
    }

    fun onForeground() {
        foreground = true
        refreshWifiPermissionStatus()
        if (!appState.startupLoading && appState.mode == DataMode.REAL_DEVICE) startRealSources()
    }

    /** Fail a physical session visibly rather than crash or continue with unrecorded movement. */
    private suspend fun stopAfterLiveFailure(error: Exception) {
        if (error is kotlinx.coroutines.CancellationException) throw error
        val reason = "Tracking stopped after a processing/storage error: ${error.message ?: error::class.java.simpleName}"
        if (activePositioningSession != null) finishActiveLive(reason)
        else appState.realLiveStatus = reason
    }

    fun onBackground() {
        foreground = false
        appState.liveRunning = false
        appState.surveyRunning = false
        appState.surveyStarting = false
        appState.diagnosticWalkRunning = false
        surveyScanJob?.cancel()
        liveScanJob?.cancel()
        if (activeSurvey != null) {
            viewModelScope.launch { eventMutex.withLock { finishActiveSurvey("Paused when TURN left the foreground") } }
        }
        if (activePositioningSession != null) {
            viewModelScope.launch { eventMutex.withLock { finishActiveLive("Paused when TURN left the foreground") } }
        }
        wifiScanner.stop()
        sensorSource.stop()
    }

    private fun projectChangeAllowed(): Boolean {
        if (appState.startupLoading || appState.projectBusy || appState.sessionLocked ||
            activeSurvey != null || activePositioningSession != null || appState.realEvaluationBusy) {
            appState.projectStatus = "Finish the active operation before changing project or mode"
            appState.selectDestination(com.turn.fieldtest.ui.model.TurnDestination.VENUES)
            return false
        }
        if (appState.hasUnsavedMapChanges) {
            appState.projectStatus = "Save your map changes or reload the saved map before switching"
            appState.selectDestination(com.turn.fieldtest.ui.model.TurnDestination.VENUES)
            return false
        }
        return true
    }

    fun setMode(mode: DataMode) {
        if (appState.mode == mode || !projectChangeAllowed()) return
        appState.mode = mode
        appState.liveRunning = false
        appState.surveyRunning = false
        appState.diagnosticWalkRunning = false
        appState.realMapReady = false
        if (mode == DataMode.DEMO) {
            wifiScanner.stop()
            sensorSource.stop()
            appState.loadDemoMap()
            appState.surveyAcceptedSnapshots = 8
            appState.surveyCachedIgnored = 2
        } else {
            appState.clearMapDraft(force = true)
            appState.removeFloorPlanImage(force = true)
            appState.projectBusy = true
            viewModelScope.launch {
                try { restoreSelectedProject() }
                catch (error: Exception) { appState.projectStatus = "Could not load project: ${error.message}" }
                finally { appState.projectBusy = false }
            }
            if (foreground) startRealSources()
        }
        refreshWifiPermissionStatus()
    }

    fun createProject(input: ProjectInput) {
        if (appState.mode != DataMode.REAL_DEVICE || !projectChangeAllowed()) return
        appState.projectBusy = true
        viewModelScope.launch {
            try {
                val context = projects.create(input, System.currentTimeMillis())
                loadProject(context)
                appState.selectDestination(com.turn.fieldtest.ui.model.TurnDestination.FLOOR_EDITOR)
            } catch (error: Exception) { appState.projectStatus = "Could not create project: ${error.message}" }
            finally { appState.projectBusy = false }
        }
    }

    fun openProject(context: ProjectContext, discardDraft: Boolean = false) {
        if (appState.mode != DataMode.REAL_DEVICE) return
        if (discardDraft) {
            if (appState.projectBusy || appState.sessionLocked || appState.startupLoading || appState.realEvaluationBusy) return
        } else if (!projectChangeAllowed()) return
        appState.projectBusy = true
        viewModelScope.launch {
            try { loadProject(context) }
            catch (error: Exception) { appState.projectStatus = "Could not open project: ${error.message}" }
            finally { appState.projectBusy = false }
        }
    }

    private suspend fun restoreSelectedProject() {
        val saved = app.settings.settings.first()
        val available = projects.observeProjects().first()
        appState.projects = available
        val context = appState.activeProject ?: resolveSavedProject(available, saved.activeVenueId, saved.activeFloorId)
        if (context != null) loadProject(context)
        else {
            appState.clearMapDraft(force = true)
            appState.removeFloorPlanImage(force = true)
            appState.realMapReady = false
            appState.projectStatus = if (saved.activeFloorId != null) "Saved project is unavailable. Choose a project below." else "Open or create a project to begin"
        }
    }

    private suspend fun loadProject(context: ProjectContext) {
        val savedMap = projects.load(context)
        val venue = savedMap.venue
        val floor = savedMap.floor
        val asset = savedMap.asset
        val points = savedMap.points
        val regions = savedMap.regions
        val walls = savedMap.walls
        val anchors = savedMap.anchors
        val restoredPolygon = regions.firstOrNull { it.enabled }?.let { region ->
            Json.parseToJsonElement(region.polygonJson).jsonArray.map { vertex ->
                Offset(vertex.jsonObject.getValue("x").jsonPrimitive.double.toFloat(),
                    vertex.jsonObject.getValue("y").jsonPrimitive.double.toFloat())
            }
        }
        app.settings.update { it.copy(activeVenueId = context.venueId, activeFloorId = context.floorId) }
        appState.draftWalkablePolygon.clear()
        appState.draftWalls.clear()
        appState.referencePoints.clear()
        appState.selectedSurveyReferencePointId = ""
        appState.qrAnchors.clear()
        appState.calibrationPoints.clear()
        appState.pendingWallStart = null
        appState.venueName = venue.name
        appState.floorName = floor.name
        appState.floorLevel = floor.levelNumber
        appState.floorWidthMetres = floor.widthMetres.toFloat()
        appState.floorHeightMetres = floor.heightMetres.toFloat()
        appState.floorPlanContentUri = asset?.contentUri
        appState.floorPlanMimeType = asset?.mimeType
        appState.floorPlanPixelWidth = asset?.pixelWidth
        appState.floorPlanPixelHeight = asset?.pixelHeight
        appState.floorPlanMetresPerPixel = asset?.metresPerPixel
        if (asset != null && asset.pixelWidth != null && asset.pixelHeight != null) {
            fun restoredCalibrationPoint(imageX: Double?, imageY: Double?): Offset? {
                if (imageX == null || imageY == null) return null
                return Offset(
                    (imageX / asset.pixelWidth * floor.widthMetres).toFloat(),
                    ((1.0 - imageY / asset.pixelHeight) * floor.heightMetres).toFloat(),
                )
            }
            listOfNotNull(
                restoredCalibrationPoint(asset.calibrationImageX1, asset.calibrationImageY1),
                restoredCalibrationPoint(asset.calibrationImageX2, asset.calibrationImageY2),
            ).let(appState.calibrationPoints::addAll)
        }
        if (points.isNotEmpty()) {
            appState.referencePoints.addAll(points.map {
                MapPointUi(it.id, appState.floorName, Offset(it.xMetres.toFloat(), it.yMetres.toFloat()), it.name)
            })
            if (points.none { it.id == appState.selectedSurveyReferencePointId }) {
                appState.selectedSurveyReferencePointId = points.first().id
            }
        }
        restoredPolygon?.let(appState.draftWalkablePolygon::addAll)
        appState.draftWalls.addAll(walls.filter { it.enabled }.map {
                Offset(it.startXMetres.toFloat(), it.startYMetres.toFloat()) to
                    Offset(it.endXMetres.toFloat(), it.endYMetres.toFloat())
            })
        appState.qrAnchors.addAll(anchors.filter { it.enabled }.map {
            MapPointUi(it.anchorId, appState.floorName, Offset(it.xMetres.toFloat(), it.yMetres.toFloat()), it.anchorId)
        })
        appState.realMapReady = true
        appState.editorStatus = if (restoredPolygon == null) {
            "Blank physical map ready · import a floor-plan image to begin"
        } else {
            "Saved physical map loaded"
        }

        appState.activeProject = context
        appState.navigationNodes.clear(); appState.navigationNodes.addAll(savedMap.nodes)
        appState.navigationEdges.clear(); appState.navigationEdges.addAll(savedMap.edges)
        appState.destinations.clear(); appState.destinations.addAll(savedMap.destinations)
        appState.selectedNodeId = null; appState.selectedDestinationId = null
        appState.manualStartPoint = null; appState.pickingStart = false
        appState.clearRoute(); appState.liveEstimateEpochMillis = null
        savedNavigationGraph = runCatching { NavigationGraph(savedMap.metricMap().floors.getValue(context.floorId), savedMap.nodes.map { it.core() }, savedMap.edges.map { it.core() }) }.getOrNull()
        appState.mapHasMeasurements = savedMap.hasMeasurements
        appState.mapEditorSupported = regions.count { it.enabled } <= 1 && floor.originXMetres == 0.0 && floor.originYMetres == 0.0
        appState.markMapSaved()
        appState.surveyAcceptedSnapshots = 0
        appState.surveyCachedIgnored = 0
        appState.surveyRawObservationCount = 0
        appState.surveyDistinctBssidCount = 0
        appState.realSurveyAggregates = emptyList()
        appState.surveySessionLabel = "No active survey"
        appState.surveySaveStatus = "Saved fingerprints remain available for matching and export"
        appState.surveyRuntimeStatus = "Select a reference point to collect fresh Wi-Fi snapshots"
        appState.realLivePosition = null
        appState.realRawPdrPosition = null
        appState.realWifiPosition = null
        appState.realFusedTrail = emptyList()
        appState.realRawPdrTrail = emptyList()
        appState.realWifiFixes = emptyList()
        appState.realParticleCloud = emptyList()
        appState.realLiveInitialized = false
        appState.headingChoiceConfirmed = false
        appState.pickingHeading = false
        appState.realHeadingAligned = false
        appState.realHeadingStatus = "Choose your walking direction on the map before starting"
        appState.realLiveFloorId = null
        appState.realWifiFloorId = null
        appState.realLiveConfidence = 0.0
        appState.realLiveFloorConfidence = 0.0
        appState.realLiveUncertaintyMetres = null
        appState.realLiveStepCount = 0L
        appState.realLiveDistanceMetres = 0.0
        appState.realNearestFingerprintIds = emptyList()
        appState.realTestSamples.clear()
        appState.realLiveStatus = "Ready to start a physical session on ${floor.name}"
        refreshSurveyProgress(context)
        appState.projectStatus = "Opened ${venue.name} · ${floor.name}"
        if (!appState.mapEditorSupported) appState.editorStatus = "Map preserved. Editing multiple walkable regions or a custom origin is not supported yet."
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
        if (appState.activeProject == null || appState.projectBusy || appState.startupLoading) return
        val context = activeContext
        if (appState.sessionLocked || activeSurvey != null || appState.surveyRunning || appState.surveyStarting) {
            appState.surveyRuntimeStatus = "A survey session is already active; pause it before starting another"
            return
        }
        if (appState.liveRunning || activePositioningSession != null) {
            appState.surveyRuntimeStatus = "Stop Live locate before collecting a training fingerprint"
            return
        }
        if (!realWifiReady("Survey")) {
            appState.surveyRuntimeStatus = "Collection not started: ${appState.realWifiIssue ?: "Wi-Fi is not ready"}"
            return
        }
        appState.sessionLocked = true
        appState.surveyStarting = true
        appState.surveyRunning = true
        appState.surveyRuntimeStatus = "Saving the displayed map and creating the physical survey session…"
        viewModelScope.launch {
            runCatching {
                val now = System.currentTimeMillis()
                // A newly placed point may be visible in Compose before it has been saved in
                // Room. Bind collection to the exact validated map shown to the researcher.
                persistActiveMap(now)
                appState.markMapSaved()
                requireSavedMapContext(metadata.referencePointId)
                val selectedPoint = repositories.floorPlans.referencePoint(metadata.referencePointId)
                    ?: error("Unknown survey reference point ${metadata.referencePointId}")
                SurveySessionEntity(
                    id = "SUR-${UUID.randomUUID()}",
                    venueId = context.venueId,
                    floorId = context.floorId,
                    referencePointId = selectedPoint.id,
                    targetSnapshotCount = appState.surveyTargetSnapshots.coerceIn(1, 50),
                    completionStatus = "COLLECTING",
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
                activeSurvey = session
                appState.mapHasMeasurements = true
                if (!foreground || appState.mode != DataMode.REAL_DEVICE || !appState.surveyRunning) {
                    eventMutex.withLock { finishActiveSurvey("Collection stopped because TURN left the foreground while starting") }
                    return@onSuccess
                }
                surveyScanSequence = 0L
                surveyBssids.clear()
                appState.surveyAcceptedSnapshots = 0
                appState.surveyCachedIgnored = 0
                appState.surveyRawObservationCount = 0
                appState.surveyDistinctBssidCount = 0
                appState.realSurveyAggregates = emptyList()
                appState.surveySessionLabel = session.id
                appState.surveyStarting = false
                appState.surveyRuntimeStatus = "Collecting physical WifiManager broadcasts"
                appState.surveySaveStatus = "Room session created; waiting for the first scan result"
                appState.surveyRunning = true
                startSurveyScanLoop()
            }.onFailure { failure ->
                appState.surveyStarting = false
                appState.surveyRunning = false
                appState.sessionLocked = false
                appState.surveyRuntimeStatus = "Collection not started: ${failure.message ?: failure::class.java.simpleName}"
                appState.surveySaveStatus = failure.message ?: failure::class.java.simpleName
            }
        }
    }

    fun finishRealSurvey() {
        appState.surveyStarting = false
        if (activeSurvey == null) {
            appState.surveyRunning = false
            return
        }
        viewModelScope.launch { eventMutex.withLock { finishActiveSurvey("Researcher finished the bounded collection") } }
    }

    fun toggleRealLive() {
        if (appState.mode != DataMode.REAL_DEVICE || appState.liveStarting) return
        if (appState.liveRunning || activePositioningSession != null) {
            viewModelScope.launch { eventMutex.withLock { finishActiveLive("Researcher stopped the physical session") } }
        } else {
            beginRealLive()
        }
    }

    /** Researcher-declared heading calibration; never changes position or invents sensor motion. */
    fun alignLiveHeading(degrees: Double) {
        if (!degrees.isFinite() || appState.mode != DataMode.REAL_DEVICE || !appState.liveRunning ||
            appState.realHeadingAlignmentBusy) return
        appState.realHeadingAlignmentBusy = true
        val requestedAtNanos = android.os.SystemClock.elapsedRealtimeNanos()
        viewModelScope.launch {
            try {
                eventMutex.withLock {
                    val session = activePositioningSession ?: return@withLock
                    if (!appState.liveRunning) return@withLock
                    val aligned = normalizeRadians(Math.toRadians(degrees))
                    val priorDegrees = Math.toDegrees(latestMapHeadingRadians)
                    app.database.positioningDao().upsertCorrection(CorrectionEventEntity(
                        id = "COR-${UUID.randomUUID()}", positioningSessionId = session.id,
                        floorId = activeContext.floorId, type = "MANUAL_HEADING_ALIGNMENT",
                        sourceId = "USER_MAP_DIRECTION", accepted = true,
                        reason = JsonObject(mapOf("priorHeadingDegrees" to JsonPrimitive(priorDegrees),
                            "declaredHeadingDegrees" to JsonPrimitive(degrees),
                            "positionChanged" to JsonPrimitive(false))).toString(),
                        timestampEpochMillis = System.currentTimeMillis()))
                    sessionInitialHeadingRadians = aligned
                    latestMapHeadingRadians = aligned
                    liveYawBaselineRadians = null
                    latestRotationVector = null
                    lastHeadingDisplayRotation = null
                    previousGyroscopeTimestampNanos = null
                    gyroNeedsRealignment = false
                    lastLiveHeadingSensorNanos = null
                    appState.realHeadingSampleEpochMillis = null
                    headingAlignmentNotBeforeNanos = requestedAtNanos
                    stepHeadingHistory.reset(requestedAtNanos)
                    rawPdrTracker?.setHeading(aligned)
                    appState.initialHeadingDegrees = degrees
                    appState.realLiveHeadingDegrees = Math.toDegrees(aligned)
                    appState.realHeadingAligned = false
                    appState.pickingHeading = false
                    appState.realHeadingStatus = "Stay still · realigning to your declared direction on the next sensor sample"
                    appState.realLiveStatus = "Manual heading alignment recorded; position and existing trails retained"
                }
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                appState.realHeadingStatus = "Heading was not changed: ${failure.message}"
            } finally {
                appState.realHeadingAlignmentBusy = false
            }
        }
    }

    fun requestLiveScan() {
        if (!realWifiReady("Live locate")) return
        liveWifiEvidence.requireMeasurementsAfter(android.os.SystemClock.elapsedRealtimeNanos() / 1_000L)
        appState.realWifiPosition = null
        appState.realWifiFixEpochMillis = null
        appState.realWifiEvidenceStatus = "Refresh requested · waiting for measurements taken after this request"
        viewModelScope.launch { requestRealScan("Live locate") }
    }

    fun relocalizeWithNextWifi() {
        viewModelScope.launch {
            eventMutex.withLock {
                if (appState.mode != DataMode.REAL_DEVICE || !appState.liveRunning) return@withLock
                particleFilter = null
                appState.realLiveInitialized = false
                appState.realLivePosition = null
                appState.realParticleCloud = emptyList()
                appState.realLiveConfidence = 0.0
                appState.realLiveFloorConfidence = 0.0
                appState.clearRoute()
                appState.routeStatus = "Waiting for a fresh position before routing"
                appState.realLiveStatus = "Global relocalization armed; waiting for a fresh Wi-Fi fix"
                requestLiveScan()
            }
        }
    }

    private fun beginRealLive() {
        if (appState.activeProject == null || appState.projectBusy || appState.startupLoading) return
        val context = activeContext
        if (appState.sessionLocked || activePositioningSession != null) {
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
        if (sensorSource.state.value.selectedStepSource == null || sensorSource.state.value.selectedHeadingSource == null) {
            appState.realLiveStatus = "Step or heading sensor unavailable. Check Diagnostics and permissions before walking."
            return
        }
        if (!appState.headingChoiceConfirmed) { appState.realLiveStatus = "Choose or confirm your walking direction on the drawing before starting"; return }
        if (!appState.initialHeadingDegrees.isFinite()) { appState.realLiveStatus = "Enter a valid map heading"; return }
        sessionInitialHeadingRadians = normalizeRadians(Math.toRadians(appState.initialHeadingDegrees))
        val chosenStart = appState.manualStartPoint?.let { MetricPoint(it.x.toDouble(), it.y.toDouble()) }
        appState.sessionLocked = true
        appState.liveStarting = true
        appState.liveRunning = true
        appState.realLiveStatus = "Creating physical positioning session…"
        viewModelScope.launch {
            runCatching {
                val now = System.currentTimeMillis()
                require(!appState.hasUnsavedMapChanges) { "Save or reload map changes before starting Live locate" }
                val saved = projects.load(context)
                sessionMap = saved.metricMap()
                if (chosenStart != null) require(sessionMap!!.floors.getValue(context.floorId).isWalkable(chosenStart)) { "Known start must be inside saved walkable space" }
                sessionGraph = NavigationGraph(sessionMap!!.floors.getValue(context.floorId), saved.nodes.map { it.core() }, saved.edges.map { it.core() })
                liveWifiEvidence.reset(android.os.SystemClock.elapsedRealtimeNanos() / 1_000L)
                appState.realWifiPosition = null
                appState.realWifiFixEpochMillis = null
                appState.realWifiNeighbourDetails = emptyList()
                appState.realWifiEvidenceStatus = "Waiting for fresh live evidence"
                val sourceState = sensorSource.state.value
                val sensorSession = SensorSessionEntity(
                    id = "SNS-${UUID.randomUUID()}",
                    dataMode = DataMode.REAL_DEVICE.name,
                    selectedStepSource = sourceState.selectedStepSource?.name ?: "UNAVAILABLE",
                    selectedHeadingSource = sourceState.selectedHeadingSource?.name ?: "UNAVAILABLE",
                    initialStrideMetres = appState.strideMetres.toDouble(),
                    headingOffsetDegrees = appState.initialHeadingDegrees,
                    startedAtEpochMillis = now,
                )
                val positioningSession = PositioningSessionEntity(
                    id = "POS-${UUID.randomUUID()}",
                    venueId = context.venueId,
                    sensorSessionId = sensorSession.id,
                    initialFloorId = context.floorId,
                    initializationType = if (chosenStart == null) "WAITING_FOR_FRESH_WIFI" else "MANUAL",
                    dataMode = DataMode.REAL_DEVICE.name,
                    particleCount = appState.particleCount,
                    startedAtEpochMillis = now,
                )
                app.database.withTransaction {
                    repositories.positioning.saveSensorSession(sensorSession)
                    repositories.positioning.saveSession(positioningSession)
                }
                sensorSession to positioningSession
            }.onSuccess { (sensorSession, positioningSession) ->
                appState.liveStarting = false
                activeSensorSession = sensorSession
                activePositioningSession = positioningSession
                appState.mapHasMeasurements = true
                if (!foreground || appState.mode != DataMode.REAL_DEVICE || !appState.liveRunning) {
                    eventMutex.withLock { finishActiveLive("Stopped because TURN left the foreground while starting") }
                    return@onSuccess
                }
                particleFilter = null
                rawPdrTracker = null
                liveEstimateSequence = 0L
                livePdrSequence = 0L
                liveYawBaselineRadians = null
                lastLiveHeadingSensorNanos = null
                headingAlignmentNotBeforeNanos = android.os.SystemClock.elapsedRealtimeNanos()
                stepHeadingHistory.reset(headingAlignmentNotBeforeNanos)
                appState.realLiveDetectedSteps = 0
                appState.realLiveStepStatus = "Waiting for a physical step event"
                appState.realLiveRecordingStatus = "Session saved · waiting for movement"
                appState.realHeadingAligned = false
                appState.realHeadingStatus = "Stay still · waiting for a fresh heading sensor sample"
                lastHeadingDisplayRotation = null
                latestRotationVector = null
                latestMapHeadingRadians = sessionInitialHeadingRadians
                appState.realLiveHeadingDegrees = Math.toDegrees(sessionInitialHeadingRadians)
                previousGyroscopeTimestampNanos = null
                gyroNeedsRealignment = false
                appState.realHeadingSampleEpochMillis = null
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
                appState.realLiveStatus = "Waiting for a fresh Wi-Fi fix. Heading aligned to %.0f° on the map.".format(appState.initialHeadingDegrees)
                if (chosenStart != null) {
                    try { eventMutex.withLock { initializeManual(chosenStart, context.floorId) } }
                    catch (error: Exception) { appState.realLiveStatus = "Known start could not initialize tracking: ${error.message}. Stop and choose another point." }
                }
                startLiveScanLoop()
            }.onFailure { failure ->
                appState.liveRunning = false
                appState.liveStarting = false
                appState.sessionLocked = false
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
                val now = System.currentTimeMillis()
                val next = wifiScanner.state.value.nextPermittedRequestAtEpochMillis
                if (next != null && next > now) {
                    val seconds = ((next - now + 999L) / 1_000L).coerceAtLeast(1L)
                    appState.surveyRuntimeStatus = "Waiting ${seconds}s for Android's next permitted Wi-Fi request; collection remains active"
                    delay(next - now + 250L)
                    continue
                }
                requestRealScan("Survey")
                val requestedAt = System.currentTimeMillis()
                val nextAfterRequest = wifiScanner.state.value.nextPermittedRequestAtEpochMillis
                val waitMillis = if (nextAfterRequest == null) 30_000L else (nextAfterRequest - requestedAt).coerceAtLeast(1_000L)
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
                if (source == "Live locate") {
                    appState.realWifiEvidenceStatus = "Scan not accepted: ${result.issue.displayName()} · no new requested fix"
                    appState.realLiveStatus = appState.realWifiEvidenceStatus
                }
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
                fresh = batch.isIndependentFreshScan && accessPoint.ageMillisAtReceipt in 0L..15_000L &&
                    accessPoint.scanTimestampMicros <= batch.receivedAtElapsedMillis * 1_000L + 999L,
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
            if (appState.surveyAcceptedSnapshots >= (session.targetSnapshotCount ?: appState.surveyTargetSnapshots)) {
                finishActiveSurvey("Target reached; bounded survey completed automatically")
            }
        }.onFailure { failure ->
            appState.surveyRuntimeStatus = "Scan received but Room persistence failed"
            appState.surveySaveStatus = failure.message ?: failure::class.java.simpleName
        }
    }

    private suspend fun finishActiveSurvey(reason: String) {
        val session = activeSurvey ?: return
        surveyScanJob?.cancel()
        surveyScanJob = null
        appState.surveyRunning = false
        appState.surveyStarting = false
        runCatching {
            app.database.withTransaction {
                val freshCount = repositories.surveys.snapshotsForAggregation(session.id).count { it.isFresh && it.resultsUpdated }
                repositories.surveys.save(session.copy(endedAtEpochMillis = System.currentTimeMillis(),
                    completionStatus = if (session.targetSnapshotCount != null && freshCount >= session.targetSnapshotCount) "COMPLETED" else "PARTIAL"))
                captureService.recomputeAggregates(session.id, System.currentTimeMillis())
            }
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
            appState.surveyCloseFailed = false
            activeSurvey = null
            appState.sessionLocked = false
            refreshSurveyProgress(ProjectContext(session.venueId, session.floorId))
        }.onFailure { failure ->
            appState.surveyRuntimeStatus = "$reason; saving failed. Press Finish to retry."
            appState.surveyCloseFailed = true
            appState.surveySaveStatus = failure.message ?: failure::class.java.simpleName
        }
    }

    fun savePilotMap() {
        if (appState.mode != DataMode.REAL_DEVICE || !appState.canEditMap) return
        appState.projectBusy = true
        viewModelScope.launch {
            try {
                persistActiveMap(System.currentTimeMillis())
                appState.markMapSaved()
                savedNavigationGraph = runCatching { appState.draftNavigationGraph() }.getOrNull()
                appState.editorStatus = "Map saved on this device"
                appState.projectStatus = "Map saved · ready to reopen"
            } catch (error: Exception) { appState.editorStatus = "Map save rejected: ${error.message}" }
            finally { appState.projectBusy = false }
        }
    }

    fun deleteSavedMap() {
        if (appState.activeProject == null || appState.projectBusy || appState.startupLoading) return
        val context = activeContext
        if (appState.mode != DataMode.REAL_DEVICE || !appState.canEditMap || activeSurvey != null || activePositioningSession != null) {
            appState.editorStatus = "Stop physical sessions before deleting the saved map"
            return
        }
        appState.projectBusy = true
        appState.editorStatus = "Deleting saved map and linked fingerprints…"
        viewModelScope.launch {
            runCatching {
                app.database.withTransaction {
                    repositories.floorPlans.observeReferencePoints(context.floorId).first()
                        .forEach { repositories.floorPlans.delete(it) }
                    repositories.floorPlans.observeQrAnchors(context.floorId).first()
                        .forEach { repositories.floorPlans.delete(it) }
                    repositories.floorPlans.observeWalls(context.floorId).first()
                        .forEach { repositories.floorPlans.delete(it) }
                    repositories.floorPlans.observeWalkableRegions(context.floorId).first()
                        .forEach { repositories.floorPlans.delete(it) }
                    repositories.floorPlans.deleteAsset(context.floorId)
                    app.database.navigationDao().clearEdges(context.floorId)
                    app.database.navigationDao().removeNodes(context.floorId, emptyList())
                    app.database.navigationDao().removeDestinations(context.floorId, emptyList())
                }
            }.onSuccess {
                appState.clearMapDraft(force = true)
                appState.removeFloorPlanImage(force = true)
                appState.mapHasMeasurements = projects.load(context).hasMeasurements
                appState.markMapSaved()
                savedNavigationGraph = runCatching { appState.draftNavigationGraph() }.getOrNull()
                appState.editorStatus = "Saved map and linked fingerprints deleted from this device"
                appState.surveyRuntimeStatus = "Create and save a map before collecting"
            }.onFailure { error ->
                appState.editorStatus = "Delete failed: ${error.message ?: error.javaClass.simpleName}"
            }
            appState.projectBusy = false
        }
    }

    fun importFloorPlan(uri: Uri) {
        if (!appState.canEditMap) return
        if (appState.mode != DataMode.REAL_DEVICE) {
            appState.editorStatus = "Switch to REAL DEVICE mode before importing a physical map"
            return
        }
        appState.projectBusy = true
        appState.editorStatus = "Reading floor-plan image…"
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    val stream = resolver.openInputStream(uri)
                        ?: error("The selected image could not be opened")
                    stream.use { BitmapFactory.decodeStream(it, null, options) }
                    require(options.outWidth > 0 && options.outHeight > 0) { "The selected file is not a readable PNG or JPEG" }
                    val directory = java.io.File(getApplication<Application>().filesDir, "floor-plans").apply { mkdirs() }
                    val localImage = java.io.File(directory, "${UUID.randomUUID()}.image")
                    try {
                        resolver.openInputStream(uri)?.use { source -> localImage.outputStream().use { source.copyTo(it) } }
                            ?: error("The selected image could not be copied")
                    } catch (error: Exception) {
                        localImage.delete()
                        throw error
                    }
                    ImportedFloorPlan(
                        uri = Uri.fromFile(localImage).toString(),
                        mimeType = resolver.getType(uri) ?: "image/*",
                        pixelWidth = options.outWidth,
                        pixelHeight = options.outHeight,
                    )
                }
            }.onSuccess { image ->
                appState.projectBusy = false
                appState.floorPlanContentUri = image.uri
                appState.floorPlanMimeType = image.mimeType
                appState.floorPlanPixelWidth = image.pixelWidth
                appState.floorPlanPixelHeight = image.pixelHeight
                appState.floorPlanMetresPerPixel = null
                appState.calibrationPoints.clear()
                if (appState.draftWalkablePolygon.isEmpty()) {
                    val height = appState.floorWidthMetres * image.pixelHeight.toFloat() / image.pixelWidth.toFloat()
                    appState.resizeFloor(appState.floorWidthMetres, height)
                }
                appState.editorTool = com.turn.fieldtest.ui.model.EditorTool.CALIBRATION
                appState.editorStatus = "Image imported · tap scale point A, then point B"
            }.onFailure { error ->
                appState.editorStatus = "Import failed: ${error.message ?: error.javaClass.simpleName}"
            }
            appState.projectBusy = false
        }
    }

    fun onFloorPlanImportCancelled() {
        appState.editorStatus = "Image selection cancelled; current map unchanged"
    }

    private suspend fun requireSavedMapContext(referencePointId: String? = null) {
        val context = activeContext
        require(repositories.venues.venue(context.venueId) != null) { "Save the floor map before collecting" }
        require(repositories.floorPlans.floor(context.floorId) != null) { "Save the floor dimensions before collecting" }
        require(repositories.floorPlans.observeWalkableRegions(context.floorId).first().any { it.enabled }) {
            "Draw and save a walkable polygon before collecting"
        }
        if (referencePointId != null) {
            require(repositories.floorPlans.referencePoint(referencePointId)?.floorId == context.floorId) {
                "Select a saved reference point before collecting"
            }
        }
    }

    private suspend fun persistActiveMap(now: Long) = app.database.withTransaction {
        val context = activeContext
        val polygon = appState.draftWalkablePolygon.takeIf { it.isNotEmpty() }?.let { vertices -> MetricPolygon(vertices.map { MetricPoint(it.x.toDouble(), it.y.toDouble()) }) }
        require(appState.mapEditorSupported) { "This saved map uses geometry the editor cannot safely modify" }
        polygon?.let { com.turn.fieldtest.core.validateSimpleWalkablePolygon(it) }
        require(appState.referencePoints.map { it.id }.distinct().size == appState.referencePoints.size) {
            "Duplicate reference-point IDs"
        }
        appState.referencePoints.forEach { point ->
            require(polygon?.contains(MetricPoint(point.metres.x.toDouble(), point.metres.y.toDouble())) == true) {
                "${point.id} is outside walkable space"
            }
        }
        require(appState.venueName.isNotBlank()) { "Enter a venue name" }
        require(appState.floorName.isNotBlank()) { "Enter a floor name" }
        require(appState.floorWidthMetres > 0f && appState.floorHeightMetres > 0f) { "Enter valid floor dimensions" }
        if (appState.navigationNodes.isNotEmpty() || appState.navigationEdges.isNotEmpty()) {
            requireNotNull(polygon) { "Draw a walkable area before saving navigation nodes" }
            val errors = appState.draftNavigationGraph().validationErrors()
            require(errors.isEmpty()) { errors.joinToString("; ") }
        }
        require(appState.navigationNodes.all { it.floorId == context.floorId } && appState.navigationEdges.all { it.floorId == context.floorId } && appState.destinations.all { it.floorId == context.floorId }) { "Navigation data belongs to a different floor" }
        require(appState.navigationNodes.all { node -> node.poiId == null || appState.destinations.any { it.id == node.poiId && it.xMetres == node.xMetres && it.yMetres == node.yMetres } }) { "Each destination must match its navigation node" }
        val previousVenue = requireNotNull(repositories.venues.venue(context.venueId))
        val previousFloor = requireNotNull(repositories.floorPlans.floor(context.floorId))
        require(previousFloor.venueId == context.venueId)
        if (app.database.floorPlanDao().hasMeasurements(context.floorId, context.venueId)) {
            require(previousFloor.widthMetres.toFloat() == appState.floorWidthMetres &&
                previousFloor.heightMetres.toFloat() == appState.floorHeightMetres) { "Cannot rescale a floor with recorded measurements" }
        }
        repositories.venues.save(previousVenue.copy(name = appState.venueName.trim(), updatedAtEpochMillis = now))
        repositories.floorPlans.save(previousFloor.copy(
            name = appState.floorName.trim(), levelNumber = appState.floorLevel,
            widthMetres = if (previousFloor.widthMetres.toFloat() == appState.floorWidthMetres) previousFloor.widthMetres else appState.floorWidthMetres.toDouble(),
            heightMetres = if (previousFloor.heightMetres.toFloat() == appState.floorHeightMetres) previousFloor.heightMetres else appState.floorHeightMetres.toDouble(),
            updatedAtEpochMillis = now,
        ))
        val imageUri = appState.floorPlanContentUri
        if (imageUri == null) {
            repositories.floorPlans.deleteAsset(context.floorId)
        } else {
            val widthPixels = appState.floorPlanPixelWidth
            val heightPixels = appState.floorPlanPixelHeight
            val previousAsset = repositories.floorPlans.observeAsset(context.floorId).first()
            val a = appState.calibrationPoints.getOrNull(0)
            val b = appState.calibrationPoints.getOrNull(1)
            repositories.floorPlans.saveAsset(FloorPlanAssetEntity(
                id = previousAsset?.id ?: "ASSET-${context.floorId}",
                floorId = context.floorId,
                contentUri = imageUri,
                mimeType = appState.floorPlanMimeType ?: "image/*",
                pixelWidth = widthPixels,
                pixelHeight = heightPixels,
                calibrationImageX1 = a?.let { widthPixels?.times(it.x / appState.floorWidthMetres)?.toDouble() },
                calibrationImageY1 = a?.let { heightPixels?.times(1f - it.y / appState.floorHeightMetres)?.toDouble() },
                calibrationImageX2 = b?.let { widthPixels?.times(it.x / appState.floorWidthMetres)?.toDouble() },
                calibrationImageY2 = b?.let { heightPixels?.times(1f - it.y / appState.floorHeightMetres)?.toDouble() },
                calibrationDistanceMetres = if (a != null && b != null) MetricPoint(a.x.toDouble(), a.y.toDouble()).distanceTo(MetricPoint(b.x.toDouble(), b.y.toDouble())) else null,
                metresPerPixel = appState.floorPlanMetresPerPixel,
                importedAtEpochMillis = previousAsset?.takeIf { it.contentUri == imageUri }?.importedAtEpochMillis ?: now,
            ))
        }
        val draftIds = appState.referencePoints.map { it.id }.toSet()
        val existingPoints = repositories.floorPlans.observeReferencePoints(context.floorId).first()
        val surveySessions = repositories.surveys.observeSessions(context.venueId).first()
        val removedPoints = existingPoints.filter { it.id !in draftIds }
        val protectedIds = removedPoints.map { it.id }.filter { id -> surveySessions.any { it.referencePointId == id } }
        require(protectedIds.isEmpty()) {
            "Cannot remove fingerprinted point(s) ${protectedIds.joinToString()}; export first, then use Delete saved map"
        }
        removedPoints.forEach { repositories.floorPlans.delete(it) }
        appState.referencePoints.forEach { point ->
            val previous = repositories.floorPlans.referencePoint(point.id)
            require(previous == null || previous.floorId == context.floorId) { "Reference point belongs to another floor" }
            val coordinateChanged = previous != null &&
                (previous.xMetres.toFloat() != point.metres.x || previous.yMetres.toFloat() != point.metres.y)
            val hasTrainingData = coordinateChanged && repositories.surveys.observeSessions(context.venueId).first()
                .any { it.referencePointId == point.id }
            require(!hasTrainingData) {
                "${point.id} already has fingerprints; use a new ID instead of moving its training coordinate"
            }
            repositories.floorPlans.save(
                ReferencePointEntity(
                    id = point.id,
                    floorId = context.floorId,
                    name = point.label,
                    xMetres = previous?.takeUnless { coordinateChanged }?.xMetres ?: point.metres.x.toDouble(),
                    yMetres = previous?.takeUnless { coordinateChanged }?.yMetres ?: point.metres.y.toDouble(),
                    isInsideWalkableSpace = true,
                    notes = previous?.notes,
                    updatedAtEpochMillis = now,
                ),
            )
        }
        val anchorIds = appState.qrAnchors.map { it.id }.toSet()
        val savedAnchors = repositories.floorPlans.observeQrAnchors(context.floorId).first()
        savedAnchors.filter { it.enabled && it.anchorId !in anchorIds }.forEach { repositories.floorPlans.delete(it) }
        appState.qrAnchors.forEach { point ->
            val previous = savedAnchors.firstOrNull { it.anchorId == point.id }
            val anchor = previous ?: com.turn.fieldtest.data.local.QrAnchorEntity(
                id = "ANCHOR-${point.id}", floorId = context.floorId, anchorId = point.id,
                xMetres = point.metres.x.toDouble(), yMetres = point.metres.y.toDouble())
            repositories.floorPlans.save(anchor.copy(
                xMetres = if (anchor.xMetres.toFloat() == point.metres.x) anchor.xMetres else point.metres.x.toDouble(),
                yMetres = if (anchor.yMetres.toFloat() == point.metres.y) anchor.yMetres else point.metres.y.toDouble()))
        }
        val regions = repositories.floorPlans.observeWalkableRegions(context.floorId).first().filter { it.enabled }
        require(regions.size <= 1) { "Multiple saved walkable regions must be preserved" }
        if (appState.draftWalkablePolygon.isEmpty()) regions.forEach { repositories.floorPlans.delete(it) }
        else repositories.floorPlans.save(WalkableRegionEntity(
            id = regions.firstOrNull()?.id ?: "REGION-${context.floorId}", floorId = context.floorId,
            name = regions.firstOrNull()?.name ?: "Walkable area",
            polygonJson = regions.firstOrNull()?.takeIf { previous ->
                decodePolygon(previous.polygonJson).vertices.map { Offset(it.x.toFloat(), it.y.toFloat()) } == appState.draftWalkablePolygon.toList()
            }?.polygonJson ?: JsonArray(appState.draftWalkablePolygon.map {
                JsonObject(mapOf("x" to JsonPrimitive(it.x.toDouble()), "y" to JsonPrimitive(it.y.toDouble())))
            }).toString(), updatedAtEpochMillis = now,
        ))
        val savedWalls = repositories.floorPlans.observeWalls(context.floorId).first().filter { it.enabled }
        val retained = mutableSetOf<String>()
        appState.draftWalls.forEachIndexed { index, (start, end) ->
            require(start != end) { "Wall $index has zero length" }
            val previous = savedWalls.firstOrNull {
                it.id !in retained && it.startXMetres.toFloat() == start.x && it.startYMetres.toFloat() == start.y &&
                    it.endXMetres.toFloat() == end.x && it.endYMetres.toFloat() == end.y
            }
            val wall = previous ?: WallSegmentEntity(
                id = "WALL-${UUID.randomUUID()}", floorId = context.floorId,
                startXMetres = start.x.toDouble(), startYMetres = start.y.toDouble(),
                endXMetres = end.x.toDouble(), endYMetres = end.y.toDouble())
            retained += wall.id
            repositories.floorPlans.save(wall)
        }
        savedWalls.filter { it.id !in retained }.forEach { repositories.floorPlans.delete(it) }
        val navigation = app.database.navigationDao()
        navigation.clearEdges(context.floorId)
        navigation.removeNodes(context.floorId, appState.navigationNodes.map { it.id })
        navigation.removeDestinations(context.floorId, appState.destinations.map { it.id })
        navigation.saveDestinations(appState.destinations.toList())
        navigation.saveNodes(appState.navigationNodes.toList())
        navigation.saveEdges(appState.navigationEdges.toList())
    }

    private suspend fun processLiveWifiBatch(batch: WifiScanBatch) {
        val positioningSession = activePositioningSession ?: return
        val freshness = when {
            batch.isIndependentFreshScan -> CoreWifiFreshness.FRESH
            else -> CoreWifiFreshness.CACHED
        }
        val fingerprints = loadPilotFingerprints()
        if (fingerprints.isEmpty()) {
            appState.realWifiPosition = null
            appState.realWifiFixEpochMillis = null
            appState.realWifiEvidenceStatus = "No completed fingerprints on this floor · correction not applied"
            appState.realLiveStatus = "No saved fingerprints found; survey at least one reference point first"
            return
        }
        val matcher = WeightedKnnWifiMatcher(
            WifiMatcherConfig(
                k = appState.knnK,
                minimumSharedBssids = 3,
                missingRssiDbm = appState.missingRssi.toDouble(),
                normalization = if (appState.deviceOffsetNormalization) {
                    WifiNormalization.DEVICE_OFFSET
                } else {
                    WifiNormalization.RAW
                },
            ),
        )
        val matchStarted = System.currentTimeMillis()
        val eligibleAps = liveWifiEvidence.consume(batch)
        if (freshness == CoreWifiFreshness.FRESH && eligibleAps.isEmpty()) {
            appState.realWifiEvidenceStatus = "No new post-request AP measurements · correction not applied"
            appState.realLiveStatus = appState.realWifiEvidenceStatus
            return
        }
        val match = matcher.match(
            liveRssiByBssid = eligibleAps.associate { it.bssid.lowercase() to it.rssiDbm.toDouble() },
            fingerprints = fingerprints,
            freshness = freshness,
        )
        appState.realNearestFingerprintIds = match.neighbours.map {
            it.referencePointId.substringBefore("::")
        }.distinct()
        appState.realWifiMatchDistance = match.weightedMatchDistance.takeIf(Double::isFinite)
        appState.realWifiNeighbourDetails = match.neighbours.map {
            "${it.referencePointId}: %.1f dB · %.0f%% weight · ${it.sharedBssidCount} shared APs".format(it.distance, it.weight * 100)
        }

        if (freshness != CoreWifiFreshness.FRESH) {
            particleFilter?.correctWithWifi(match)
            appState.realWifiEvidenceStatus = "Cached/stale broadcast · correction not applied"
            appState.lastCorrectionType = "cached Wi-Fi result ignored"
            appState.realLiveStatus = "Cached/stale Wi-Fi retained for diagnostics; fusion was not corrected"
            return
        }
        if (match.estimatedPosition == null || match.estimatedFloorId == null || match.unlikeDatabase || match.spatiallyAmbiguous) {
            appState.lastCorrectionType = "confidence degraded"
            appState.realWifiPosition = null
            appState.realWifiFixEpochMillis = null
            appState.realWifiEvidenceStatus = when {
                match.spatiallyAmbiguous -> "Ambiguous Wi-Fi: similar matches at distant reference points · correction not applied"
                match.estimatedPosition == null -> "Insufficient shared APs: need at least 3 per candidate · correction not applied"
                else -> "Fresh scan unlike saved fingerprints · correction not applied"
            }
            appState.realLiveStatus = appState.realWifiEvidenceStatus
            return
        }

        if (currentPilotMap().floors[match.estimatedFloorId]?.isWalkable(match.estimatedPosition) != true) {
            appState.realWifiPosition = null
            appState.realWifiFixEpochMillis = null
            appState.realWifiEvidenceStatus = "Wi-Fi average falls outside walkable space · correction not applied"
            appState.realLiveStatus = appState.realWifiEvidenceStatus
            return
        }
        appState.realWifiPosition = match.estimatedPosition.toUiOffset()
        appState.realWifiFloorId = match.estimatedFloorId
        appState.realWifiUncertaintyMetres = match.uncertaintyRadiusMetres
        appState.realWifiFixEpochMillis = batch.receivedAtEpochMillis
        appState.realWifiFixes = (appState.realWifiFixes + match.estimatedPosition.toUiOffset()).takeLast(MAX_TRAIL_POINTS)
        appState.realWifiEvidenceStatus = "Fresh Wi-Fi match · ${eligibleAps.size} new APs · ${match.neighbours.size} competitive reference points"
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
                withContext(Dispatchers.Default) { filter.initialize(
                    AbsoluteFix(
                        position = match.estimatedPosition,
                        floorId = match.estimatedFloorId,
                        source = AbsoluteFixSource.WIFI,
                    ),
                    positionStdMetres = (match.uncertaintyRadiusMetres / 2.0).coerceIn(0.35, 4.0),
                ) }
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
                initialHeadingRadians = latestMapHeadingRadians,
                stepProcessor = StepProcessor(availableSteps),
                strideModel = StrideModel(appState.strideMetres.toDouble()),
            )
                rawPdrFloorId = match.estimatedFloorId
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
            val correction = withContext(Dispatchers.Default) { requireNotNull(particleFilter).correctWithWifi(match) }
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
        val context = activeContext
        val rows = repositories.surveys.matchingFingerprints(context.venueId)
        return rows.groupBy { it.referencePointId }.mapNotNull { (referencePointId, pointRows) ->
            val point = repositories.floorPlans.referencePoint(referencePointId)?.takeIf { it.floorId == context.floorId } ?: return@mapNotNull null
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

    private fun currentPilotMap(): MetricMap = requireNotNull(sessionMap) { "No saved session map" }

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
        if (activePositioningSession == null) return
        liveScanJob?.cancel()
        liveScanJob = null
        appState.liveRunning = false
        appState.realLiveStatus = reason
        appState.headingChoiceConfirmed = false
        appState.pickingHeading = false
        appState.realHeadingAligned = false
        appState.realHeadingSampleEpochMillis = null
        appState.realHeadingStatus = "Navigation stopped · choose your starting direction before the next walk"
        val endedAt = System.currentTimeMillis()
        try {
            app.database.withTransaction {
                activeSensorSession?.let { repositories.positioning.saveSensorSession(it.copy(endedAtEpochMillis = endedAt)) }
                activePositioningSession?.let { repositories.positioning.saveSession(it.copy(endedAtEpochMillis = endedAt)) }
                activePositioningSession?.let { session ->
                    repositories.evaluation.observeRuns(session.venueId).first().firstOrNull { it.id == "TEST-${session.id}" }
                        ?.let { repositories.evaluation.saveRun(it.copy(endedAtEpochMillis = endedAt)) }
                }
            }
        } catch (error: Exception) {
            appState.liveCloseFailed = true
            appState.realLiveStatus = "Could not save session end: ${error.message}. Retry saving before switching projects."
            return
        }
        appState.liveCloseFailed = false
        appState.clearRoute(); appState.routeStatus = "Navigation stopped"
        appState.realLiveInitialized = false; appState.realLivePosition = null
        activeSensorSession = null
        activePositioningSession = null
        sessionMap = null
        appState.sessionLocked = false
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
            appState.clearRoute(); appState.routeStatus = "Position lost · request a fresh fix"
            appState.realLiveStatus = "Filter lost; request a fresh Wi-Fi fix"
            return
        }
        appState.realLiveInitialized = true
        appState.realLivePosition = summary.position.toUiOffset()
        appState.liveEstimateEpochMillis = System.currentTimeMillis()
        appState.realLiveFloorId = summary.floorId
        appState.realLiveConfidence = summary.confidence
        appState.realLiveFloorConfidence = summary.floorConfidence
        appState.realLiveUncertaintyMetres = summary.uncertaintyRadiusMetres
        appState.realFusedTrail = (appState.realFusedTrail + summary.position.toUiOffset()).takeLast(MAX_TRAIL_POINTS)
        appState.realParticleCloud = filter.particles
            .filterIndexed { index, _ -> index % (filter.particles.size / 100).coerceAtLeast(1) == 0 }
            .take(100)
            .map { it.position.toUiOffset() }
        updateRoute()
    }

    fun selectNavigationDestination(id: String?) {
        appState.selectedDestinationId = id
        appState.clearRoute()
        updateRoute()
    }

    private fun updateRoute() {
        val destination = appState.selectedDestinationId ?: return
        val position = appState.realLivePosition
        if (!appState.liveRunning || !appState.realLiveInitialized || position == null) {
            appState.clearRoute(); appState.routeStatus = "Initialize a position to calculate a route"; return
        }
        val graph = sessionGraph ?: savedNavigationGraph
        val node = graph?.nodes?.firstOrNull { it.poiId == destination }
        if (graph == null || node == null) {
            appState.clearRoute(); appState.routeStatus = "Connect this destination to the saved path graph"; return
        }
        val route = graph.route(MetricPoint(position.x.toDouble(), position.y.toDouble()), node.id)
        if (route == null) {
            appState.clearRoute(); appState.routeStatus = "No walkable route from this position. Check paths or relocalize."; return
        }
        appState.routePoints = route.points.map { it.toUiOffset() }
        appState.remainingDistanceMetres = route.distanceMetres
        appState.destinationReached = route.distanceMetres <= 1.5 && appState.realLiveConfidence >= 0.35 &&
            (appState.realLiveUncertaintyMetres ?: Double.POSITIVE_INFINITY) <= 3.0
        appState.routeStatus = if (appState.destinationReached) "Destination reached" else if (appState.realLiveConfidence < 0.35)
            "Route available · position uncertain" else "Follow the blue route"
    }

    private suspend fun initializeManual(point: MetricPoint, floorId: String) {
        val session = requireNotNull(activePositioningSession)
        val filter = ParticleFilter(currentPilotMap(), ParticleFilterConfig(particleCount = appState.particleCount), session.startedAtEpochMillis)
        withContext(Dispatchers.Default) { filter.initialize(AbsoluteFix(point, floorId, AbsoluteFixSource.MANUAL), positionStdMetres = 0.5) }
        particleFilter = filter
        val sources = if (sensorSource.state.value.selectedStepSource == TurnSensorType.STEP_DETECTOR) setOf(StepSource.STEP_DETECTOR) else emptySet()
        rawPdrTracker = PdrTracker(point, latestMapHeadingRadians, StepProcessor(sources), StrideModel(appState.strideMetres.toDouble()))
        rawPdrFloorId = floorId
        appState.realRawPdrPosition = point.toUiOffset(); appState.realRawPdrTrail = listOf(point.toUiOffset())
        appState.lastCorrectionType = "manual known-location anchor"
        appState.realLiveStatus = "Tracking from your known start · Wi-Fi will correct drift when available"
        updateRealParticleUi()
        val summary = requireNotNull(filter.summary())
        repositories.positioning.saveEstimate(PositionEstimateEntity(
            id = "EST-${session.id}-MANUAL", positioningSessionId = session.id, sequence = liveEstimateSequence++,
            method = "FUSED", floorId = floorId, xMetres = summary.position.x, yMetres = summary.position.y,
            headingRadians = latestMapHeadingRadians, confidenceRadiusMetres = summary.uncertaintyRadiusMetres,
            confidenceScore = summary.confidence, floorConfidence = summary.floorConfidence, stepCount = 0,
            timestampEpochMillis = System.currentTimeMillis(), status = "MANUAL_INITIALIZATION"))
    }

    private suspend fun refreshSurveyProgress(context: ProjectContext) {
        val sessions = repositories.surveys.observeSessions(context.venueId).first().filter { it.floorId == context.floorId }
        val values = sessions.groupBy { it.referencePointId }.mapValues { (_, rows) ->
            val session = rows.filter { it.completionStatus == "COMPLETED" }.maxByOrNull { it.startedAtEpochMillis }
                ?: rows.maxBy { it.startedAtEpochMillis }
            val count = repositories.surveys.snapshotsForAggregation(session.id).count { it.isFresh && it.resultsUpdated }
            val aps = repositories.surveys.aggregatesForSession(session.id).size
            SurveyPointProgress(if (session.completionStatus == "COLLECTING") "PARTIAL" else session.completionStatus,
                count, session.targetSnapshotCount, aps)
        }
        if (appState.activeProject == context) appState.surveyPointProgress = values
    }

    private fun MetricPoint.toUiOffset() = androidx.compose.ui.geometry.Offset(x.toFloat(), y.toFloat())

    private fun median(values: List<Double>): Double {
        require(values.isNotEmpty())
        val middle = values.size / 2
        return if (values.size % 2 == 1) values[middle] else (values[middle - 1] + values[middle]) / 2.0
    }

    private suspend fun handleSensorSample(sample: SensorSample) {
        if (appState.mode != DataMode.REAL_DEVICE || sample.simulated) return
        if (sample.sensorTimestampNanos - (lastDiagnosticValueNanos[sample.type] ?: 0L) >= 200_000_000L ||
            sample.kind == SensorSampleKind.STEP_DETECTED || sample.kind == SensorSampleKind.ACCURACY_CHANGED) {
            latestSensorValues[sample.type] = sample.displayValue()
            lastDiagnosticValueNanos[sample.type] = sample.sensorTimestampNanos
        }
        updateLiveHeading(sample)
        if (appState.diagnosticWalkRunning && sample.kind == SensorSampleKind.STEP_DETECTED) {
            appState.diagnosticWalkSteps += 1
            appState.realDiagnosticDistanceMetres = appState.diagnosticWalkSteps * appState.strideMetres.toDouble()
        }
        if (appState.liveRunning && sample.kind == SensorSampleKind.STEP_DETECTED) {
            processLiveStep(sample)
        }
        val uiNow = android.os.SystemClock.elapsedRealtimeNanos()
        if (uiNow - lastSensorUiPublishNanos < 200_000_000L) return
        lastSensorUiPublishNanos = uiNow
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
        if (appState.liveRunning && sample.sensorTimestampNanos < headingAlignmentNotBeforeNanos) return
        val selected = sensorSource.state.value.selectedHeadingSource
        val previousRotation = lastHeadingDisplayRotation
        val displayChanged = previousRotation != null && previousRotation != sample.displayRotation
        lastHeadingDisplayRotation = sample.displayRotation
        if (displayChanged && appState.liveRunning && selected == TurnSensorType.GYROSCOPE) {
            latestMapHeadingRadians = normalizeRadians(latestMapHeadingRadians +
                DisplayHeading.rotationDeltaRadians(requireNotNull(previousRotation), sample.displayRotation))
        }
        val rotationSample = sample.kind == SensorSampleKind.RELATIVE_ROTATION_VECTOR ||
            sample.kind == SensorSampleKind.ROTATION_VECTOR
        val validRotationSample = rotationSample && sample.type == selected && sample.values.size >= 3 &&
            sample.values.all { it.isFinite() }
        if (appState.liveRunning && validRotationSample &&
            lastLiveHeadingSensorNanos?.let { sample.sensorTimestampNanos <= it } == true) return
        if (validRotationSample) {
            latestRotationVector = sample.values.toFloatArray()
        }
        if ((validRotationSample || displayChanged) &&
            selected in setOf(TurnSensorType.GAME_ROTATION_VECTOR, TurnSensorType.ROTATION_VECTOR)) {
            latestRotationVector?.let { vector ->
                runCatching {
                    val yaw = DisplayHeading.azimuth(vector, sample.displayRotation)
                    latestDeviceYawRadians = yaw
                    if (appState.liveRunning) {
                        val baseline = liveYawBaselineRadians ?: yaw.also { liveYawBaselineRadians = it }
                        // The visible screen's top is the declared forward direction in either orientation.
                        latestMapHeadingRadians = com.turn.fieldtest.core.MapHeading.fromRelativeAzimuth(sessionInitialHeadingRadians, baseline, yaw)
                        if (validRotationSample && yaw.isFinite()) {
                            lastLiveHeadingSensorNanos = sample.sensorTimestampNanos
                            lastHeadingReceiptEpochMillis = sample.receivedAtEpochMillis
                            stepHeadingHistory.record(sample.sensorTimestampNanos, latestMapHeadingRadians)
                            appState.realHeadingAligned = true
                            appState.realHeadingStatus = "Heading aligned · check the arrow before walking"
                        }
                    }
                }
            }
        }
        if (sample.kind == SensorSampleKind.ANGULAR_VELOCITY && sample.type == selected && selected == TurnSensorType.GYROSCOPE &&
            sample.values.getOrNull(2)?.isFinite() == true) {
            val previous = previousGyroscopeTimestampNanos
            if (previous != null && sample.sensorTimestampNanos <= previous) return
            if (appState.liveRunning && previous != null &&
                com.turn.fieldtest.core.MapHeading.gyroIntervalSeconds(previous, sample.sensorTimestampNanos) == null) {
                gyroNeedsRealignment = true
            }
            previousGyroscopeTimestampNanos = sample.sensorTimestampNanos
            if (appState.liveRunning && gyroNeedsRealignment) {
                appState.realHeadingAligned = false
                appState.realHeadingStatus = "Gyroscope gap · stop and use Correct walking direction to realign"
                return
            }
            if (appState.liveRunning) {
                lastLiveHeadingSensorNanos = sample.sensorTimestampNanos
                lastHeadingReceiptEpochMillis = sample.receivedAtEpochMillis
                appState.realHeadingAligned = true
                appState.realHeadingStatus = "Gyroscope heading aligned · check regularly for drift"
            }
            if (previous != null && appState.liveRunning) {
                val deltaSeconds = com.turn.fieldtest.core.MapHeading.gyroIntervalSeconds(previous, sample.sensorTimestampNanos) ?: return
                val z = sample.values.getOrNull(2)?.toDouble() ?: return
                // Natural-axis +z is counter-clockwise for a face-up phone; display turns are handled above.
                latestMapHeadingRadians = normalizeRadians(latestMapHeadingRadians + z * deltaSeconds)
            }
            if (appState.liveRunning) stepHeadingHistory.record(sample.sensorTimestampNanos, latestMapHeadingRadians)
        }
        val uiNow = android.os.SystemClock.elapsedRealtimeNanos()
        if (uiNow - lastHeadingUiPublishNanos >= 100_000_000L) {
            lastHeadingUiPublishNanos = uiNow
            appState.realLiveHeadingDegrees = latestMapHeadingRadians * 180.0 / PI
            appState.realHeadingSampleEpochMillis = lastHeadingReceiptEpochMillis
        }
    }

    private suspend fun processLiveStep(sample: SensorSample) {
        val sensorSession = activeSensorSession ?: return
        val positioningSession = activePositioningSession ?: return
        appState.realLiveDetectedSteps += 1
        val stepHeading = stepHeadingHistory.headingForStep(sample.sensorTimestampNanos)
        val tracker = rawPdrTracker
        val rejection = when {
            sensorSource.state.value.selectedHeadingSource == null -> "HEADING_SENSOR_UNAVAILABLE"
            tracker == null -> "POSITION_NOT_INITIALIZED"
            gyroNeedsRealignment -> "GYROSCOPE_REALIGNMENT_REQUIRED"
            stepHeading == null -> "HEADING_NOT_ALIGNED_OR_STALE"
            else -> null
        }
        if (rejection != null) {
            val sequence = livePdrSequence++
            repositories.positioning.savePdrEvent(PdrEventEntity(
                id = "PDR-${sensorSession.id}-$sequence", sensorSessionId = sensorSession.id,
                sequence = sequence, eventType = "STEP", source = StepSource.STEP_DETECTOR.name,
                timestampEpochMillis = sample.receivedAtEpochMillis, sensorTimestampNanos = sample.sensorTimestampNanos,
                stepDelta = 0, strideMetres = 0.0, headingRadians = latestMapHeadingRadians,
                accepted = false, rejectionReason = rejection))
            appState.realLiveStepStatus = when (rejection) {
                "POSITION_NOT_INITIALIZED" -> "Step detected · choose a known start or wait for a confident Wi-Fi position"
                "GYROSCOPE_REALIGNMENT_REQUIRED" -> "Step detected · correct walking direction before moving"
                else -> "Step detected · no measured heading at that step's timestamp"
            }
            appState.realLiveRecordingStatus = "Step saved with reason: $rejection"
            return
        }
        val filter = particleFilter
        val heading = requireNotNull(stepHeading)
        val event = requireNotNull(tracker).processStep(
            signal = StepSignal(sample.sensorTimestampNanos, StepSource.STEP_DETECTOR),
            headingRadians = heading,
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
                headingRadians = heading,
                accepted = event.decision.acceptedForMovement,
                rejectionReason = event.decision.reason.takeUnless {
                    it == com.turn.fieldtest.core.StepRejectionReason.NONE
                }?.name,
            ),
        )
        if (!event.decision.acceptedForMovement) {
            appState.realLiveStepStatus = "Step ignored: ${event.decision.reason}"
            appState.realLiveRecordingStatus = "Rejected step saved"
            return
        }

        val prediction = withContext(Dispatchers.Default) { filter?.predictStep(event.strideMetres, heading) }
        appState.realLiveStepStatus = if (prediction?.accepted == true) "Step accepted · position updated"
            else "Step recorded · fused position lost; request a fresh Wi-Fi fix"
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
                headingRadians = heading,
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
                    headingRadians = heading,
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
        appState.realLiveRecordingStatus = "Saved ${appState.realLiveStepCount} movement steps on this phone"
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

        const val MAX_TRAIL_POINTS = 500
    }
}

private data class ImportedFloorPlan(
    val uri: String,
    val mimeType: String,
    val pixelWidth: Int,
    val pixelHeight: Int,
)
