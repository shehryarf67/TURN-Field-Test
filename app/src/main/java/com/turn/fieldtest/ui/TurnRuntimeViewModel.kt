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
import com.turn.fieldtest.core.MultiFloorRouteResult
import com.turn.fieldtest.core.RouteFailure
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
import com.turn.fieldtest.core.WifiMatchResult
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
        set(value) { field=value; appState.navigationPreviewGraph=value }
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
    private var liveEndedAtEpochMillis: Long? = null
    // Magnetic fingerprinting: per-step feature window, the floor map, and an in-progress survey walk.
    private val magneticRepository = com.turn.fieldtest.data.repository.MagneticRepository(app.database)
    // Declared before init: loadProject (run from init) reads the building's floor links.
    private val connectorRepository = com.turn.fieldtest.data.repository.ConnectorRepository(app.database, projects)
    private val magneticWindow = com.turn.fieldtest.core.MagneticStepWindow()
    private var latestGravity: DoubleArray? = null
    private var latestGravityNanos: Long? = null
    private var lastMagneticBias: DoubleArray? = null
    private var lastLiveMagneticObservation: com.turn.fieldtest.core.MagneticObservation? = null
    /** Magnetic maps frozen for the running live session, keyed by floor, like [sessionMap]. */
    private var sessionMagneticMaps: Map<String, com.turn.fieldtest.core.MagneticFingerprintMap> = emptyMap()
    private class MagneticWalk(val fromPointId: String, val toPointId: String, val startedAtEpochMillis: Long) {
        val observations = mutableListOf<com.turn.fieldtest.core.MagneticObservation?>()
    }
    private var activeMagneticWalk: MagneticWalk? = null
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
    // Multi-floor tracking (plan Steps 7-8). In single-floor sessions floorCoordinator stays null and every branch
    // below takes the legacy path (invariant I1); sessionBuilding then holds only the start floor.
    private val sessionLoader = SessionBuildingLoader(projects, connectorRepository)
    private var sessionBuilding: SessionBuilding? = null
    /** The floor the live session started on, frozen; the displayed floor may follow other floors. */
    private var sessionContext: ProjectContext? = null
    private var sessionGraphs: Map<String, NavigationGraph> = emptyMap()
    private var floorCoordinator: com.turn.fieldtest.core.FloorTrackingCoordinator? = null
    /** Router between floors for the live multi-floor session (phase 3); null in single-floor sessions. */
    private var sessionRouter: com.turn.fieldtest.core.MultiFloorRouter? = null
    /** The building and router the route preview between floors last loaded, with what they were loaded for. */
    private var previewBuilding: Pair<Any, PreviewBuilding>? = null
    private val liveTrailsByFloor = mutableMapOf<String, FloorTrails>()
    /** Raw PDR trail of a multi-floor session; shown only while its start floor is displayed. */
    private var multiFloorRawPdrTrail: List<Offset> = emptyList()
    private var userRelocalizePending = false
    private var lastFloorTickNanos = Long.MIN_VALUE
    /** Latest coordinator event, kept for diagnostics; every event is also persisted (floor_transition_events). */
    private var lastFloorEvent: com.turn.fieldtest.core.FloorTrackingEvent? = null
    /** Numbers and holds coordinator events until the session row exists (CONFIG is emitted before it is saved). */
    private val floorEventLog = com.turn.fieldtest.data.repository.FloorEventLog()

    fun captureCheckpoint(input: CheckpointInput) {
        val context = appState.activeProject ?: return
        val session = activePositioningSession ?: return
        if (appState.mode != DataMode.REAL_DEVICE || !appState.liveRunning || appState.realEvaluationBusy) return
        // Single-floor sessions: the truth floor is the open floor, bounded by its size, exactly as before.
        // Multi-floor sessions: the researcher names the truth floor (default: the start floor), never the displayed one.
        val building = sessionBuilding?.takeIf { floorCoordinator != null }
        val trueFloorId = if (building == null) context.floorId else input.floorId?.takeIf { it in building.floorIds }
            ?: sessionContext?.floorId ?: context.floorId
        val trueFloor = building?.floorMaps?.get(trueFloorId)?.floor
        val widthLimit = trueFloor?.widthMetres ?: appState.floorWidthMetres.toDouble()
        val heightLimit = trueFloor?.heightMetres ?: appState.floorHeightMetres.toDouble()
        if (building != null && appState.liveFloorTracking?.state == com.turn.fieldtest.core.FloorState.CHANGING) {
            appState.realEvaluationStatus = "Floor change in progress · capture once the floor has settled"
            return
        }
        if (input.code.isBlank() || !input.x.isFinite() || !input.y.isFinite() ||
            input.x !in 0.0..widthLimit ||
            input.y !in 0.0..heightLimit) {
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
        val wifiError = evaluateAtCheckpoint(truth, trueFloorId, wifi)
        val rawError = evaluateAtCheckpoint(truth, trueFloorId, raw)
        val fusedError = evaluateAtCheckpoint(truth, trueFloorId, fused)
        fun encoded(value: EvaluationEstimate?) = value?.let { Json.encodeToString(EvaluationEstimate.serializer(), it) }
        val checkpointId = "CP-${trueFloorId}-${input.code}"
        val runId = "TEST-${session.id}"
        val sample = TestSampleEntity(
            id = "SAMPLE-${UUID.randomUUID()}", testRunId = runId, checkpointId = checkpointId,
            trueFloorId = trueFloorId, trueXMetres = input.x, trueYMetres = input.y,
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
                    require(existing == null || (existing.floorId == trueFloorId && existing.xMetres == input.x && existing.yMetres == input.y)) {
                        "Checkpoint code already belongs to a different coordinate"
                    }
                    require(repositories.floorPlans.observeReferencePoints(trueFloorId).first().none {
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
                        checkpointId, context.venueId, trueFloorId, input.code, input.x, input.y))
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
            // Route preview to a place on another floor (phase 3). Same-floor previews stay in the screen as before.
            snapshotFlow {
                val context = appState.activeProject
                val place = appState.selectedPlaceOnOtherFloor()
                if (appState.mode != DataMode.REAL_DEVICE || appState.liveRunning || appState.liveStarting || context == null ||
                    place == null || appState.hasUnsavedMapChanges) null
                else CrossFloorPreviewRequest(context, place, appState.manualStartPoint?.let { MetricPoint(it.x.toDouble(), it.y.toDouble()) },
                    appState.floorTrackingEnabled, appState.useUncheckedFloorLinks, appState.stepFreeRouting, appState.floorConnectors,
                    appState.navigationPreviewGraph)
            }.distinctUntilChanged().collectLatest { request ->
                appState.crossFloorPreview = request?.let {
                    try { planCrossFloorPreview(it) }
                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (error: Exception) { CrossFloorPreview(it.place.id, it.context.floorId, it.start, null, "Could not plan a route between floors: ${error.message}") }
                }
            }
        }
        viewModelScope.launch {
            val saved = app.settings.settings.first()
            appState.knnK = saved.wifiK
            appState.missingRssi = saved.missingRssiDbm
            appState.deviceOffsetNormalization = saved.normalizeDeviceOffset
            appState.strideMetres = saved.defaultStrideMetres.toFloat()
            appState.particleCount = saved.particleCount
            appState.darkTheme = saved.darkTheme
            appState.floorTrackingEnabled = saved.floorTrackingEnabled
            appState.useUncheckedFloorLinks = saved.useUncheckedFloorLinks
            appState.stepFreeRouting = saved.stepFreeRouting
            appState.floorDetectionOverrides = saved.floorDetectionOverrides
            appState.mode = DataMode.valueOf(saved.dataMode.name)
            try {
                if (appState.mode == DataMode.REAL_DEVICE) {
                    restoreSelectedProject()
                    if (appState.realMapReady) appState.selectDestination(
                        if (appState.allWalkablePolygons().any { it.size >= 3 }) com.turn.fieldtest.ui.model.TurnDestination.LIVE_LOCATE
                        else com.turn.fieldtest.ui.model.TurnDestination.FLOOR_EDITOR)
                } else appState.loadDemoMap()
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
                    floorTrackingEnabled = appState.floorTrackingEnabled,
                    useUncheckedFloorLinks = appState.useUncheckedFloorLinks,
                    stepFreeRouting = appState.stepFreeRouting,
                    floorDetectionOverrides = appState.floorDetectionOverrides,
                )
            }.distinctUntilChanged().collect { settings ->
                try {
                    app.settings.update { previous -> previous.copy(
                        dataMode = settings.dataMode, activeVenueId = settings.activeVenueId, activeFloorId = settings.activeFloorId,
                        wifiK = settings.wifiK, missingRssiDbm = settings.missingRssiDbm,
                        normalizeDeviceOffset = settings.normalizeDeviceOffset, defaultStrideMetres = settings.defaultStrideMetres,
                        particleCount = settings.particleCount, darkTheme = settings.darkTheme,
                        floorTrackingEnabled = settings.floorTrackingEnabled, useUncheckedFloorLinks = settings.useUncheckedFloorLinks,
                        stepFreeRouting = settings.stepFreeRouting, floorDetectionOverrides = settings.floorDetectionOverrides,
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
                rawRecorder?.let { recorder -> recordRaw { recorder.wifi(batch) } }
                try { handleWifiBatch(batch) }
                catch (error: Exception) { stopAfterLiveFailure(error) }
            } }
        }
        viewModelScope.launch {
            sensorSource.state.collectLatest { sourceState ->
                appState.realSensorRunning = sourceState.running
                appState.magneticSensorStatus = when {
                    sourceState.availability.none { it.type == TurnSensorType.MAGNETOMETER && it.available } ->
                        "No magnetometer on this phone · Wi-Fi and step tracking continue without magnetic evidence"
                    sourceState.magnetometerMode == null -> "Magnetometer present but not running"
                    else -> "Magnetometer ready (${sourceState.magnetometerMode})"
                }
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
                rawRecorder?.let { recorder -> recordRaw { recorder.sensor(sample) } }
                try { handleSensorSample(sample) }
                catch (error: Exception) { stopAfterLiveFailure(error) }
            } }
        }
    }

    fun onForeground() {
        foreground = true
        refreshWifiPermissionStatus()
        // A finished recording survives a process restart, so it can still be saved afterwards.
        if (rawRecorder == null && !appState.rawRecordingSaving) appState.rawRecordingSaveable =
            runCatching { rawRecordingFile.length() > com.turn.fieldtest.platform.sensors.RawSensorRecorder.HEADER.length }.getOrDefault(false)
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
        if (rawRecorder != null) stopRawRecording("Recording stopped because TURN left the foreground")
        if (activeMagneticWalk != null) {
            cancelMagneticWalk()
            appState.magneticWalkStatus = "Walk cancelled because TURN left the foreground · sensors stop in the background"
        }
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
        if (rawRecorder != null) stopRawRecording("Recording stopped when switching data mode")
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

    /** Adds an empty floor to [venueId] and opens it in Map setup. */
    fun addFloor(venueId: String, floorName: String, levelNumber: Int) {
        if (appState.mode != DataMode.REAL_DEVICE || !projectChangeAllowed() || appState.hasUnsavedMapChanges) return
        appState.projectBusy = true
        viewModelScope.launch {
            try {
                val context = projects.addFloor(venueId, floorName, levelNumber, System.currentTimeMillis())
                loadProject(context)
                appState.selectDestination(com.turn.fieldtest.ui.model.TurnDestination.FLOOR_EDITOR)
            } catch (error: Exception) { appState.projectStatus = "Could not add floor: ${error.message}" }
            finally { appState.projectBusy = false }
        }
    }

    /** Loads the open building's floor links, and with [withFloorMaps] every floor's saved map for picking points. */
    fun refreshFloorLinks() = refreshFloorLinks(withFloorMaps = true)

    private fun refreshFloorLinks(withFloorMaps: Boolean) {
        val venueId = appState.activeProject?.venueId ?: return
        viewModelScope.launch {
            runCatching {
                // Another building may have been opened while this loaded; its results must not land in that building.
                val maps = if (withFloorMaps) app.database.floorPlanDao().observeFloors(venueId).first()
                    .mapNotNull { f -> runCatching { projects.load(ProjectContext(venueId, f.id)) }.getOrNull()?.let { f.id to it } }.toMap() else null
                val links = connectorRepository.connectors(venueId)
                if (appState.activeProject?.venueId == venueId) {
                    maps?.let { appState.buildingFloorMaps = it }
                    appState.floorConnectors = links
                }
            }.onFailure { if (appState.activeProject?.venueId == venueId) appState.floorLinksStatus = "Could not load floor links: ${it.message}" }
        }
    }

    private fun connectorAction(working: String, block: suspend () -> String) {
        if (appState.floorLinksBusy || appState.sessionLocked) { appState.floorLinksStatus = "Finish the running session first"; return }
        appState.floorLinksBusy = true; appState.floorLinksStatus = working
        viewModelScope.launch {
            try {
                appState.floorLinksStatus = block()
                appState.activeProject?.venueId?.let { appState.floorConnectors = connectorRepository.connectors(it) }
            } catch (error: Exception) { appState.floorLinksStatus = "Not saved: ${error.message ?: error.javaClass.simpleName}" }
            finally { appState.floorLinksBusy = false }
        }
    }

    fun saveConnector(save: ConnectorSave) = connectorAction("Saving link…") {
        connectorRepository.save(save.draft, save.bidirectional, save.accessible, save.verifiedOnSite, save.replacingId)
        // Matched by position, not name: a suggestion renamed during review is still the same staircase.
        appState.connectorSuggestions = appState.connectorSuggestions.filterNot { it.sameEndsAs(save.draft) }
        "${save.draft.label} saved" + if (save.verifiedOnSite) "" else " · confirm on site, then mark it verified"
    }

    fun deleteConnector(id: String) = connectorAction("Removing link…") {
        connectorRepository.delete(id); "Link removed"
    }

    fun setConnectorVerified(id: String, verified: Boolean) = connectorAction("Updating link…") {
        connectorRepository.setVerified(id, verified); if (verified) "Marked as checked on site" else "Marked as not yet checked"
    }

    /** Saves the open building's floor elevations (all null clears them); the next session start uses them. */
    fun saveFloorElevations(elevations: Map<String, Double?>) {
        val venueId = appState.activeProject?.venueId ?: return
        connectorAction("Saving floor heights…") {
            projects.setFloorElevations(venueId, elevations, System.currentTimeMillis())
            refreshFloorLinks(withFloorMaps = true)
            if (elevations.values.all { it == null }) "Floor heights cleared · standard 3.5 m storeys"
            else "Floor heights saved · used from the next tracking session"
        }
    }

    /** Saves (or with null removes) [floorId]'s alignment onto another floor of the open building. */
    fun saveFloorAlignment(floorId: String, alignment: com.turn.fieldtest.core.FloorAlignment?) =
        connectorAction(if (alignment == null) "Removing alignment…" else "Saving alignment…") {
            projects.setFloorAlignment(floorId, alignment, System.currentTimeMillis())
            refreshFloorLinks(withFloorMaps = true)
            if (alignment == null) "Alignment removed" else "Floors aligned · use it to place and check link ends"
        }

    fun suggestConnectors() {
        val venueId = appState.activeProject?.venueId ?: return
        appState.floorLinksStatus = "Looking for numbered stair landings on each floor…"
        viewModelScope.launch {
            runCatching { connectorRepository.suggestions(venueId) }
                .also { if (appState.activeProject?.venueId != venueId) return@launch }
                .onSuccess {
                    appState.connectorSuggestions = it
                    appState.floorLinksStatus = if (it.isEmpty()) "No stair landings to pair on adjacent floors · draw links by hand"
                        else "${it.size} suggested stair links · landing numbers are automatic, so check each on site"
                }
                .onFailure { appState.floorLinksStatus = "Could not suggest links: ${it.message}" }
        }
    }

    fun importMapperPackage(asset: String? = null, uri: Uri? = null, replace: Boolean = false, intoVenueId: String? = null) {
        if(appState.mode != DataMode.REAL_DEVICE || appState.projectBusy || appState.sessionLocked || appState.liveRunning ||
            appState.surveyRunning || appState.liveStarting || appState.surveyStarting || appState.liveCloseFailed || appState.surveyCloseFailed ||
            appState.diagnosticWalkRunning || appState.startupLoading || appState.realEvaluationBusy || appState.hasUnsavedMapChanges) return
        appState.projectBusy=true
        viewModelScope.launch {
            var committed=false
            try {
                val context=withContext(Dispatchers.IO) {
                    val stream=if(asset!=null) app.assets.open(asset) else app.contentResolver.openInputStream(requireNotNull(uri))!!
                    val bytes=stream.use { input ->
                        val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                        while(true) {val count=input.read(buffer);if(count<0)break;require(output.size()+count<=10_000_000) {"Map package is too large"};output.write(buffer,0,count)}
                        output.toByteArray()
                    }
                    val text=bytes.toString(Charsets.UTF_8)
                    val parsed=MapperPackage.parse(text)
                    MapperImporter(app.database,app).install(parsed,text,replace,intoVenueId).also {committed=true}
                }
                loadProject(context)
                appState.selectDestination(com.turn.fieldtest.ui.model.TurnDestination.LIVE_LOCATE)
                appState.projectStatus=if(intoVenueId!=null) "Floor added to the building · collect Wi-Fi fingerprints on it" else "Mapper floor imported · supplied metric scale · collect new Wi-Fi fingerprints"
            } catch(e: Exception) {
                if(e is kotlinx.coroutines.CancellationException) throw e
                appState.projectStatus=if(committed) "Mapper floor saved, but opening failed: ${e.message}. Reopen it from Buildings."
                    else "Mapper import rejected: ${e.message}. Existing data was not replaced."
            } finally {appState.projectBusy=false}
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
        val switchingFloor = appState.activeProject != context
        if (switchingFloor) appState.mapSetupSection = "Scale"
        val venue = savedMap.venue
        val floor = savedMap.floor
        app.settings.update { it.copy(activeVenueId = context.venueId, activeFloorId = context.floorId) }
        applyFloorDisplay(savedMap, context)
        appState.selectedNodeId = null; appState.selectedDestinationId = null
        appState.manualStartPoint = null; appState.pickingStart = false
        appState.clearRoute(); appState.liveEstimateEpochMillis = null
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
        runCatching { refreshMagneticCoverage(context) }
        // Links are drawn on every floor map; suggestions and picker maps belong to one building only.
        appState.connectorSuggestions = emptyList(); appState.floorLinksStatus = FLOOR_LINKS_IDLE_STATUS
        // The last session's floor-tracking start status describes that session's floor, not this one.
        if (switchingFloor) appState.floorTrackingStatus = null
        if (appState.buildingFloorMaps.values.firstOrNull()?.venue?.id != context.venueId) appState.buildingFloorMaps = emptyMap()
        appState.floorConnectors = runCatching { connectorRepository.connectors(context.venueId) }.getOrDefault(emptyList())
        // Every floor's places for the destination search across floors; a failure only hides other floors.
        appState.buildingPlaces = runCatching { loadBuildingPlaces(context.venueId) }.getOrDefault(emptyList())
        appState.projectStatus = "Opened ${venue.name} · ${floor.name}"
        if (!appState.mapEditorSupported) appState.editorStatus = "Map preserved. Editing multiple walkable regions or a custom origin is not supported yet."
    }

    /**
     * Shows [savedMap] as the open floor: geometry, reference points, anchors, calibration, floor metadata, navigation
     * data and the saved-map baseline. No database access and no live or survey reset, so a live multi-floor session
     * can follow the confirmed floor with it (plan Step 8); [loadProject] adds the resets and refreshes.
     */
    private fun applyFloorDisplay(savedMap: SavedFloorMap, context: ProjectContext) {
        val venue = savedMap.venue
        val floor = savedMap.floor
        val asset = savedMap.asset
        val points = savedMap.points
        val regions = savedMap.regions
        appState.editedRegionId=regions.firstOrNull { it.enabled }?.id
        appState.editedRegionOriginal=regions.firstOrNull {it.enabled}
        appState.editedRegionName=regions.firstOrNull { it.enabled }?.name ?: "Walkable area"
        appState.otherWalkableRegions.clear(); appState.otherWalkableRegions.addAll(regions.filter { it.enabled }.drop(1))
        val walls = savedMap.walls
        val anchors = savedMap.anchors
        val restoredPolygon = regions.firstOrNull { it.enabled }?.let { region ->
            Json.parseToJsonElement(region.polygonJson).jsonArray.map { vertex ->
                Offset(vertex.jsonObject.getValue("x").jsonPrimitive.double.toFloat(),
                    vertex.jsonObject.getValue("y").jsonPrimitive.double.toFloat())
            }
        }
        appState.draftWalkablePolygon.clear()
        appState.draftWalls.clear()
        appState.referencePoints.clear(); appState.suggestedSurveyPoints.clear()
        // Magnetic walk picks name this floor's points; another floor's ids would break the picker.
        appState.magneticWalkFromId = null; appState.magneticWalkToId = null
        appState.magneticWalkStatus = "Choose two survey points along one corridor, stand at the first, then start"
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
        appState.destinationAreas.clear(); appState.destinationAreas.addAll(savedMap.destinationAreas())
        savedNavigationGraph = runCatching { NavigationGraph(savedMap.metricMap().floors.getValue(context.floorId), savedMap.nodes.map { it.core() }, savedMap.edges.map { it.core() }) }.getOrNull()
        appState.mapHasMeasurements = savedMap.hasMeasurements
        appState.mapEditorSupported = floor.originXMetres == 0.0 && floor.originYMetres == 0.0
        appState.markMapSaved()
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

    private var rawRecorder: com.turn.fieldtest.platform.sensors.RawSensorRecorder? = null
    private val rawRecordingFile get() = java.io.File(java.io.File(app.filesDir, "raw-recordings"), "latest.csv")

    /** Starts or stops writing every raw sensor sample and Wi-Fi scan to app storage; only the latest recording is kept. */
    fun toggleRawRecording() {
        rawRecorder?.let { stopRawRecording("Saved ${it.rows} rows · use Save recording to copy the file"); return }
        if (appState.mode != DataMode.REAL_DEVICE || appState.rawRecordingSaving) return
        if (missingMotionPermissions().isNotEmpty()) { onMotionPermissionDenied(); return }
        val file = rawRecordingFile
        rawRecorder = runCatching {
            file.parentFile?.mkdirs()
            com.turn.fieldtest.platform.sensors.RawSensorRecorder(file.bufferedWriter())
        }.getOrElse {
            appState.rawRecordingStatus = "Could not start recording: ${it.message}"
            return
        }
        appState.rawRecording = true
        appState.rawRecordingSaveable = false
        appState.rawRecordingRows = 0
        appState.rawRecordingStatus = "Recording · request Wi-Fi scans as you walk; navigation can run at the same time"
        if (foreground) startRealSources()
    }

    /** Writes a ground-truth marker (TraceMarkers grammar) into the running recording; ignored when none runs. */
    fun addRawMarker(text: String) {
        val recorder = rawRecorder ?: return
        recordRaw { recorder.marker(text, System.currentTimeMillis()) }
        if (rawRecorder === recorder) {
            appState.rawRecordingRows = recorder.rows
            appState.rawRecordingStatus = "Recording · marker added: $text"
        }
    }

    private inline fun recordRaw(block: () -> Unit) {
        try {
            block()
            val rows = rawRecorder?.rows ?: return
            if (rows - appState.rawRecordingRows >= 50) appState.rawRecordingRows = rows
        } catch (error: java.io.IOException) {
            stopRawRecording("Recording stopped: ${error.message ?: "storage error"}")
        }
    }

    private fun stopRawRecording(status: String) {
        val recorder = rawRecorder ?: return
        rawRecorder = null
        appState.rawRecordingRows = recorder.rows
        val closed = runCatching { recorder.close() }.isSuccess
        appState.rawRecording = false
        appState.rawRecordingSaveable = closed && recorder.rows > 0 && rawRecordingFile.exists()
        appState.rawRecordingStatus = if (closed) status else "Recording stopped but the file could not be finished"
    }

    /** Copies the latest finished recording to a document the user picked. */
    fun saveRawRecording(uri: android.net.Uri) {
        if (rawRecorder != null || appState.rawRecordingSaving) return
        // A new recording reuses the same file, so starting one is refused until this copy has finished.
        appState.rawRecordingSaving = true
        appState.rawRecordingStatus = "Saving recording…"
        viewModelScope.launch {
            try {
                appState.rawRecordingStatus = runCatching {
                    // Rows are counted from the file itself: after a process restart the in-memory count is gone.
                    val rows = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        val output = requireNotNull(app.contentResolver.openOutputStream(uri, "wt")) { "Could not open the chosen file" }
                        var lines = 0L
                        output.use { out -> rawRecordingFile.inputStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buffer); if (n < 0) break
                                for (i in 0 until n) if (buffer[i] == '\n'.code.toByte()) lines++
                                out.write(buffer, 0, n)
                            }
                        } }
                        (lines - 1).coerceAtLeast(0) // minus the header
                    }
                    appState.rawRecordingRows = rows
                    "Recording saved ($rows rows)"
                }.getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it; "Could not save recording: ${it.message}" }
            } finally { appState.rawRecordingSaving = false }
        }
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
                // Save only a changed draft: re-saving an unchanged map would also reject floors the editor
                // cannot modify (e.g. a non-zero origin from a backup), so surveys could never start there.
                if (appState.hasUnsavedMapChanges) {
                    persistActiveMap(now)
                    appState.markMapSaved()
                    savedNavigationGraph = runCatching { appState.draftNavigationGraph() }.getOrNull()
                }
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

    /** Begins a magnetic path walk from saved survey point [fromPointId] to [toPointId] in a straight line. */
    fun startMagneticWalk(fromPointId: String, toPointId: String) {
        val sensors = sensorSource.state.value
        val problem = when {
            appState.mode != DataMode.REAL_DEVICE -> "Switch to Real device mode to survey magnetic paths"
            !appState.realMapReady || appState.activeProject == null -> "Open a saved floor first"
            !foreground -> "Keep TURN in the foreground"
            appState.sessionLocked || appState.liveRunning || appState.surveyRunning || activeMagneticWalk != null ->
                "Finish the running session first"
            appState.hasUnsavedMapChanges -> "Save the map before surveying magnetic paths"
            fromPointId == toPointId -> "Choose two different survey points"
            sensors.magnetometerMode == null -> "This phone has no usable magnetometer; magnetic fingerprinting is unavailable"
            sensors.selectedStepSource == null -> "A step detector is required to place magnetic readings along the path"
            else -> null
        }
        if (problem != null) { appState.magneticWalkStatus = problem; return }
        startRealSources()
        magneticWindow.reset()
        activeMagneticWalk = MagneticWalk(fromPointId, toPointId, System.currentTimeMillis())
        appState.magneticWalkFromId = fromPointId; appState.magneticWalkToId = toPointId
        appState.magneticWalkSteps = 0
        appState.sessionLocked = true
        appState.magneticWalkRunning = true
        appState.magneticWalkStatus = "Walk straight to the second point at a steady pace, holding the phone the same way"
    }

    fun cancelMagneticWalk() {
        if (activeMagneticWalk == null) return
        activeMagneticWalk = null
        appState.magneticWalkRunning = false
        appState.sessionLocked = false
        appState.magneticWalkStatus = "Walk cancelled · nothing was saved"
    }

    /** Places the walk's readings along the line and merges them into the floor's magnetic map, or explains why not. */
    fun finishMagneticWalk() {
        val walk = activeMagneticWalk ?: return
        activeMagneticWalk = null
        appState.magneticWalkRunning = false
        val context = activeContext
        viewModelScope.launch {
            try {
                val floor = projects.load(context).metricMap().floors.getValue(context.floorId)
                val from = repositories.floorPlans.referencePoint(walk.fromPointId) ?: error("Start point no longer exists")
                val to = repositories.floorPlans.referencePoint(walk.toPointId) ?: error("End point no longer exists")
                val observations = walk.observations.toList()
                val result = com.turn.fieldtest.core.MagneticPathSurvey.build(floor, MetricPoint(from.xMetres, from.yMetres),
                    MetricPoint(to.xMetres, to.yMetres), observations)
                val now = System.currentTimeMillis()
                val accepted = result as? com.turn.fieldtest.core.MagneticWalkResult.Accepted
                magneticRepository.recordWalk(com.turn.fieldtest.data.local.MagneticWalkEntity(
                    id = "MAG-${UUID.randomUUID()}", floorId = context.floorId,
                    fromReferencePointId = walk.fromPointId, toReferencePointId = walk.toPointId,
                    startedAtEpochMillis = walk.startedAtEpochMillis, endedAtEpochMillis = now,
                    stepCount = observations.size, lengthMetres = MetricPoint(from.xMetres, from.yMetres).distanceTo(MetricPoint(to.xMetres, to.yMetres)),
                    readingCount = observations.sumOf { it?.sampleCount ?: 0 },
                    status = if (accepted != null) "ACCEPTED" else "REJECTED",
                    rejectionReason = (result as? com.turn.fieldtest.core.MagneticWalkResult.Rejected)?.reason,
                    deviceModel = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
                ), accepted?.samples, now)
                refreshMagneticCoverage(context)
                appState.magneticWalkStatus = if (accepted != null)
                    "Saved: %.1f m in %d steps (%.2f m per step) · walk it the other way too for a sturdier map".format(accepted.lengthMetres, observations.size, accepted.strideMetres)
                else "Not saved: ${(result as com.turn.fieldtest.core.MagneticWalkResult.Rejected).reason}"
            } catch (error: Exception) {
                appState.magneticWalkStatus = "Could not save the walk: ${error.message ?: error.javaClass.simpleName}"
            } finally {
                appState.sessionLocked = false
            }
        }
    }

    private suspend fun refreshMagneticCoverage(context: ProjectContext) {
        val map = magneticRepository.map(context.floorId)
        if (appState.activeProject != context) return
        appState.magneticCellSizeMetres = map.cellSizeMetres.toFloat()
        appState.magneticCoverage = map.cells.keys.map { key -> map.centreOf(key).toUiOffset() }
        appState.magneticWalkCount = magneticRepository.walks(context.floorId).count { it.status == "ACCEPTED" }
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
                        floorId = (if (floorCoordinator != null) appState.realLiveFloorId ?: sessionContext?.floorId ?: activeContext.floorId
                            else activeContext.floorId), type = "MANUAL_HEADING_ALIGNMENT",
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
                particleFilter = null; lastLiveMagneticObservation = null
                userRelocalizePending = true
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
                // Tracking off, or no usable link from this floor: only the start floor is loaded, exactly as before (I1).
                val loaded = sessionLoader.load(context, appState.floorTrackingEnabled, appState.useUncheckedFloorLinks)
                val barometerPresent = sensorSource.state.value.availability.any { it.type == TurnSensorType.BAROMETER && it.available }
                // Threshold overrides are snapshotted here: edits in Diagnostics apply from the next session start.
                val detection = com.turn.fieldtest.core.FloorDetectionOverrides.apply(appState.floorDetectionOverrides)
                val coordinator = if (loaded.multiFloor) com.turn.fieldtest.core.FloorTrackingCoordinator.createOrNull(
                    loaded.topology, detection.config, barometerPresent, context.floorId,
                    elevations = loaded.elevations, configOverrides = detection.used) else null
                // No coordinator means the legacy session: never let other floors reach the filter or fingerprints.
                val building = if (coordinator == null) loaded.singleFloor() else loaded
                val saved = building.floorMaps.getValue(context.floorId)
                sessionMap = building.metricMap
                if (chosenStart != null) require(sessionMap!!.floors.getValue(context.floorId).isWalkable(chosenStart)) { "Known start must be inside saved walkable space" }
                sessionGraph = NavigationGraph(sessionMap!!.floors.getValue(context.floorId), saved.nodes.map { it.core() }, saved.edges.map { it.core() })
                sessionMagneticMaps = magneticRepository.map(context.floorId).takeUnless { it.isEmpty }
                    ?.let { mapOf(context.floorId to it) } ?: emptyMap()
                sessionGraphs = mapOf(context.floorId to requireNotNull(sessionGraph))
                if (coordinator != null) {
                    // Other floors' paths and magnetic maps are optional: a broken one must not stop the session.
                    (building.floorIds - context.floorId).forEach { id ->
                        val floorMap = building.floorMaps[id] ?: return@forEach
                        runCatching { NavigationGraph(sessionMap!!.floors.getValue(id), floorMap.nodes.map { it.core() }, floorMap.edges.map { it.core() }) }
                            .onSuccess { sessionGraphs = sessionGraphs + (id to it) }
                        runCatching { magneticRepository.map(id) }.getOrNull()?.takeUnless { it.isEmpty }
                            ?.let { sessionMagneticMaps = sessionMagneticMaps + (id to it) }
                    }
                }
                sessionBuilding = building
                // Directions between floors use the same frozen building, graphs and links as tracking.
                sessionRouter = if (coordinator != null) com.turn.fieldtest.core.MultiFloorRouter(sessionGraphs, building.topology,
                    floors = building.metricMap.floors) else null
                sessionContext = context
                floorCoordinator = coordinator
                lastFloorEvent = null
                floorEventLog.reset()
                coordinator?.let { drainFloorEvents() }
                appState.floorTrackingStatus = floorTrackingStartStatus(building, appState.floorTrackingEnabled, barometerPresent)
                    ?.let { if (coordinator != null && detection.used.isNotEmpty()) "$it · custom thresholds" else it }
                liveWifiEvidence.reset(android.os.SystemClock.elapsedRealtimeNanos() / 1_000L)
                appState.realWifiPosition = null
                appState.realWifiFixEpochMillis = null
                appState.realWifiNeighbourDetails = emptyList()
                appState.realNearestFingerprintIds = emptyList(); appState.realWifiMatchDistance = null
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
                // The session row now exists: write the held CONFIG event (multi-floor sessions only).
                eventMutex.withLock { drainFloorEvents() }
                appState.mapHasMeasurements = true
                if (!foreground || appState.mode != DataMode.REAL_DEVICE || !appState.liveRunning) {
                    eventMutex.withLock { finishActiveLive("Stopped because TURN left the foreground while starting") }
                    return@onSuccess
                }
                particleFilter = null; lastLiveMagneticObservation = null
                rawPdrTracker = null
                liveEstimateSequence = 0L
                magneticWindow.reset(); appState.realMagneticAppliedSteps = 0
                appState.realMagneticStatus = if (sessionMagneticMaps.isEmpty()) "No magnetic map for this floor · survey magnetic paths to enable" else "Magnetic map loaded · waiting for steps"
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
                liveTrailsByFloor.clear(); multiFloorRawPdrTrail = emptyList()
                userRelocalizePending = false; lastFloorTickNanos = Long.MIN_VALUE
                appState.liveFloorTracking = null
                if (floorCoordinator != null) {
                    // Keyed on the start floor, so screen state survives the display following other floors.
                    appState.liveSessionKey = context
                    appState.liveDestination = appState.destinations.firstOrNull { it.id == appState.selectedDestinationId }
                        ?.let { LiveDestination(it.id, context.floorId, it.name) }
                        ?: appState.selectedPlaceOnOtherFloor()?.let { LiveDestination(it.id, it.floorId, it.name) }
                } else { appState.liveSessionKey = null; appState.liveDestination = null }
                if (chosenStart != null) {
                    try { eventMutex.withLock { initializeManual(chosenStart, context.floorId) } }
                    catch (error: Exception) { appState.realLiveStatus = "Known start could not initialize tracking: ${error.message}. Stop and choose another point." }
                }
                startLiveScanLoop()
            }.onFailure { failure ->
                appState.liveRunning = false
                appState.liveStarting = false
                appState.sessionLocked = false
                if (activePositioningSession == null) { clearMultiFloorSession(); appState.floorTrackingStatus = null }
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
        // Count a snapshot only once it is stored, and count it even if re-aggregation then fails;
        // otherwise the counter, the stored rows and the auto-finish target drift apart.
        val entities = runCatching { captureService.record(session, surveyScanSequence, batch) }.getOrElse { failure ->
            appState.surveyRuntimeStatus = "Scan received but Room persistence failed"
            appState.surveySaveStatus = failure.message ?: failure::class.java.simpleName
            return
        }
        surveyScanSequence++
        appState.surveyRawObservationCount += entities.observations.size
        surveyBssids += entities.observations.map { it.bssid }
        appState.surveyDistinctBssidCount = surveyBssids.size
        if (entities.snapshot.isFresh) appState.surveyAcceptedSnapshots += 1
        else appState.surveyCachedIgnored += 1
        runCatching {
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
            appState.surveyRuntimeStatus = if (entities.snapshot.isFresh) {
                "Fresh physical snapshot stored and included in aggregation"
            } else {
                "Cached/stale snapshot stored for lineage and excluded from aggregation"
            }
            appState.surveySaveStatus = "Room contains ${surveyScanSequence} snapshot record(s) for this session"
        }.onFailure { failure ->
            appState.surveyRuntimeStatus = "Snapshot stored, but recomputing the fingerprint failed; it is recomputed when the survey closes"
            appState.surveySaveStatus = failure.message ?: failure::class.java.simpleName
        }
        if (appState.surveyAcceptedSnapshots >= (session.targetSnapshotCount ?: appState.surveyTargetSnapshots)) {
            finishActiveSurvey("Target reached; bounded survey completed automatically")
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
            // The session is already saved; a failed badge refresh must not crash the app from a bare launch.
            runCatching { refreshSurveyProgress(ProjectContext(session.venueId, session.floorId)) }
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
        viewModelScope.launch { try {
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
                    // Magnetic readings are tied to the deleted geometry's coordinates.
                    app.database.magneticDao().clearCells(context.floorId)
                    app.database.magneticDao().clearWalks(context.floorId)
                }
            }.onSuccess {
                appState.clearMapDraft(force = true)
                appState.removeFloorPlanImage(force = true)
                // Deletion already committed; if the reload fails, keep the conservative "has measurements" lock.
                appState.mapHasMeasurements = runCatching { projects.load(context).hasMeasurements }.getOrDefault(true)
                appState.markMapSaved()
                savedNavigationGraph = runCatching { appState.draftNavigationGraph() }.getOrNull()
                appState.editorStatus = "Saved map, linked fingerprints and magnetic paths deleted from this device"
                appState.magneticCoverage = emptyList(); appState.magneticWalkCount = 0
                appState.surveyRuntimeStatus = "Create and save a map before collecting"
            }.onFailure { error ->
                appState.editorStatus = "Delete failed: ${error.message ?: error.javaClass.simpleName}"
            }
        } finally { appState.projectBusy = false } }
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
                val resized = if (appState.draftWalkablePolygon.isEmpty()) {
                    val height = appState.floorWidthMetres * image.pixelHeight.toFloat() / image.pixelWidth.toFloat()
                    appState.resizeFloor(appState.floorWidthMetres, height)
                } else true
                if (resized && appState.canChangeScale) {
                    appState.editorTool = com.turn.fieldtest.ui.model.EditorTool.CALIBRATION
                    appState.editorStatus = "Image imported · tap scale point A, then point B"
                } else {
                    // Scale is locked (recorded sessions exist): say so instead of inviting a calibration that will be refused.
                    appState.editorStatus = "Image imported, but scale is locked by recorded sessions · the drawing keeps the floor's current dimensions"
                }
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
        val allPolygons=listOfNotNull(polygon)+appState.otherWalkableRegions.map { decodePolygon(it.polygonJson).also { p->com.turn.fieldtest.core.validateSimpleWalkablePolygon(p) } }
        require(appState.referencePoints.map { it.id }.distinct().size == appState.referencePoints.size) {
            "Duplicate reference-point IDs"
        }
        appState.referencePoints.forEach { point ->
            require(allPolygons.any { it.contains(MetricPoint(point.metres.x.toDouble(), point.metres.y.toDouble())) }) {
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
        val editedId=appState.editedRegionId ?: "REGION-${context.floorId}"
        val retainedRegionIds=appState.otherWalkableRegions.map {it.id}.toSet()+if(appState.draftWalkablePolygon.isNotEmpty()) setOf(editedId) else emptySet()
        regions.filter {it.id !in retainedRegionIds}.forEach {repositories.floorPlans.delete(it)}
        if (appState.draftWalkablePolygon.isEmpty()) regions.filter { it.id==editedId }.forEach { repositories.floorPlans.delete(it) }
        else repositories.floorPlans.save(WalkableRegionEntity(
            id = editedId, floorId = context.floorId,
            name = appState.editedRegionName,
            polygonJson = regions.firstOrNull { it.id==editedId }?.takeIf { previous ->
                decodePolygon(previous.polygonJson).vertices.map { Offset(it.x.toFloat(), it.y.toFloat()) } == appState.draftWalkablePolygon.toList()
            }?.polygonJson ?: JsonArray(appState.draftWalkablePolygon.map {
                JsonObject(mapOf("x" to JsonPrimitive(it.x.toDouble()), "y" to JsonPrimitive(it.y.toDouble())))
            }).toString(), updatedAtEpochMillis = now,
        ))
        appState.otherWalkableRegions.forEach { require(it.floorId==context.floorId); repositories.floorPlans.save(it) }
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
                ignoreUnknownLiveBssids = true,
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
        // Handle cached broadcasts before matching: an empty match would otherwise blank the neighbour IDs and
        // distance shown, and saved at checkpoints, next to the still-current Wi-Fi position.
        if (freshness != CoreWifiFreshness.FRESH) {
            particleFilter?.correctWithWifi(WifiMatchResult.noEstimate(freshness))
            appState.realWifiEvidenceStatus = "Cached/stale broadcast · correction not applied"
            appState.lastCorrectionType = "cached Wi-Fi result ignored"
            appState.realLiveStatus = "Cached/stale Wi-Fi retained for diagnostics; fusion was not corrected"
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
        val coordinator = floorCoordinator
        val wifiNowNanos = android.os.SystemClock.elapsedRealtimeNanos()
        if (coordinator != null) appendWifiFix(match.estimatedFloorId, match.estimatedPosition.toUiOffset())
        else appState.realWifiFixes = (appState.realWifiFixes + match.estimatedPosition.toUiOffset()).takeLast(MAX_TRAIL_POINTS)
        appState.realWifiEvidenceStatus = "Fresh Wi-Fi match · ${eligibleAps.size} new APs · ${match.neighbours.size} competitive reference points"
        val prior = particleFilter?.summary()
        if (particleFilter == null || particleFilter?.isLost == true) {
            if (match.confidence < 0.55 || match.floorAgreement < 0.7) {
                appState.realLiveStatus = "Wi-Fi fix is ambiguous; waiting for stronger position/floor evidence"
                return
            }
            // Multi-floor: after a lost filter, Wi-Fi may rebuild the cloud on another floor only with confirmation.
            if (coordinator != null && !coordinator.allowWifiInit(match, wifiNowNanos, userRelocalizePending)) {
                drainFloorEvents()
                appState.realLiveStatus = wifiSuggestsFloorText(sessionFloorName(match.estimatedFloorId))
                return
            }
            val filter = ParticleFilter(
                metricMap = currentPilotMap(),
                config = ParticleFilterConfig(particleCount = appState.particleCount),
                seed = positioningSession.startedAtEpochMillis,
                floorTopology = sessionTopology(),
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
            particleFilter = filter; lastLiveMagneticObservation = null
            if (coordinator != null && match.estimatedFloorId in coordinator.topology.levels) {
                coordinator.onAbsoluteFix(match.estimatedFloorId,
                    if (userRelocalizePending) com.turn.fieldtest.core.FloorFixSource.USER_RELOCALIZE
                    else com.turn.fieldtest.core.FloorFixSource.WIFI_INIT, wifiNowNanos)
                drainFloorEvents()
            }
            userRelocalizePending = false
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
                multiFloorRawPdrTrail = appState.realRawPdrTrail
            } else {
                appState.relocalizationCount += 1
            }
            appState.realLiveInitialized = true
            appState.lastCorrectionType = if (appState.relocalizationCount > 0) "globally relocalized" else "first confident Wi-Fi fix"
            appState.realLiveStatus = "Fresh Wi-Fi initialized the particle filter; original raw PDR is retained"
            // Only a session that was waiting for Wi-Fi was initialised by it; a later relocalisation of a
            // manual start is recorded as a correction event and must not rewrite how the session began.
            if (positioningSession.initializationType == "WAITING_FOR_FRESH_WIFI") {
                activePositioningSession = positioningSession.copy(
                    initialFloorId = match.estimatedFloorId,
                    initializationType = "FRESH_WIFI",
                ).also { repositories.positioning.saveSession(it) }
            }
        } else {
            val correction = withContext(Dispatchers.Default) {
                val filter = requireNotNull(particleFilter)
                // Multi-floor: the coordinator gates cross-floor relocalisation and records it as an absolute fix.
                coordinator?.correctWithWifi(filter, match, wifiNowNanos) ?: filter.correctWithWifi(match)
            }
            drainFloorEvents()
            if (correction.kind == WifiCorrectionKind.GLOBAL_RELOCALIZATION) {
                appState.relocalizationCount += 1
            }
            appState.lastCorrectionType = when (correction.kind) {
                WifiCorrectionKind.APPLIED -> "fresh Wi-Fi correction"
                WifiCorrectionKind.GLOBAL_RELOCALIZATION -> "globally relocalized"
                WifiCorrectionKind.FILTER_LOST -> "filter lost"
                WifiCorrectionKind.IGNORED_NO_ESTIMATE -> "Wi-Fi produced no estimate"
                WifiCorrectionKind.IGNORED_NOT_FRESH -> "cached Wi-Fi result ignored"
                // Only a multi-floor filter returns this (single-floor sessions never build one).
                WifiCorrectionKind.DEFERRED_CROSS_FLOOR -> "Wi-Fi suggests another floor · waiting for more evidence"
            }
            appState.realLiveStatus = when (correction.kind) {
                WifiCorrectionKind.APPLIED -> "Fresh Wi-Fi corrected particle weights"
                WifiCorrectionKind.GLOBAL_RELOCALIZATION -> "Strong distant Wi-Fi match globally relocalized the filter"
                WifiCorrectionKind.FILTER_LOST -> "Particle filter lost; request Wi-Fi or re-anchor"
                WifiCorrectionKind.DEFERRED_CROSS_FLOOR -> wifiSuggestsFloorText(sessionFloorName(match.estimatedFloorId))
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
        // The session's start floor, not the displayed one (which follows the confirmed floor in multi-floor sessions).
        val context = sessionContext ?: activeContext
        val floorIds = sessionBuilding?.floorIds ?: setOf(context.floorId)
        val rows = repositories.surveys.matchingFingerprints(context.venueId)
        return rows.groupBy { it.referencePointId }.mapNotNull { (referencePointId, pointRows) ->
            val point = repositories.floorPlans.referencePoint(referencePointId)?.takeIf { it.floorId in floorIds } ?: return@mapNotNull null
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
        // Tracking stopped at the first attempt; a later "Retry saving" must not move the recorded end time.
        val endedAt = liveEndedAtEpochMillis ?: System.currentTimeMillis().also { liveEndedAtEpochMillis = it }
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
        liveEndedAtEpochMillis = null
        appState.liveCloseFailed = false
        appState.clearRoute(); appState.routeStatus = "Navigation stopped"
        appState.realLiveInitialized = false; appState.realLivePosition = null
        activeSensorSession = null
        activePositioningSession = null
        sessionMap = null
        appState.sessionLocked = false
        particleFilter = null; lastLiveMagneticObservation = null
        rawPdrTracker = null
        val followedFloor = appState.activeProject?.takeIf { floorCoordinator != null && it != sessionContext }
        clearMultiFloorSession()
        // The display followed another floor: the start floor's known start and selections do not apply there.
        if (followedFloor != null) appState.clearSelectionsFromOtherFloor()
        // The display followed another floor: its survey badges and magnetic coverage were not loaded on the way.
        followedFloor?.let { floor -> viewModelScope.launch {
            runCatching { refreshSurveyProgress(floor) }
            runCatching { refreshMagneticCoverage(floor) }
        } }
    }

    /** Forgets the frozen session building (also after a failed start); single-floor sessions only reset nulls. */
    private fun clearMultiFloorSession() {
        sessionBuilding = null
        sessionContext = null
        sessionGraph = null
        sessionGraphs = emptyMap()
        sessionMagneticMaps = emptyMap()
        floorCoordinator = null
        floorEventLog.reset()
        sessionRouter = null
        liveTrailsByFloor.clear()
        multiFloorRawPdrTrail = emptyList()
        userRelocalizePending = false
        appState.liveFloorTracking = null
        appState.liveDestination = null
        appState.liveSessionKey = null
    }

    /**
     * Drains the coordinator's events, keeps the latest for diagnostics and persists them to floor_transition_events
     * off the main thread (plan Step 10). Events before the session row exists (CONFIG) are held and written with
     * the first drain after it is saved. A failed write is logged and never stops tracking.
     */
    private fun drainFloorEvents() {
        val events = floorCoordinator?.drainEvents().orEmpty()
        events.lastOrNull()?.let { lastFloorEvent = it }
        if (floorCoordinator == null) return
        val rows = floorEventLog.add(events, activePositioningSession?.id, System.currentTimeMillis(),
            android.os.SystemClock.elapsedRealtimeNanos())
        if (rows.isEmpty()) return
        val dao = app.database.floorTransitionEventDao()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { dao.insert(rows) }
                .onFailure { android.util.Log.w("TURN", "Could not save ${rows.size} floor events", it) }
        }
    }

    private fun sessionFloorName(floorId: String?): String = floorId?.let { id ->
        sessionBuilding?.names?.get(id) ?: appState.projects.firstOrNull { it.floorId == id }?.floorName
    } ?: "another floor"

    /** The topology for a new filter: the session's only when floor tracking is active (I1). */
    private fun sessionTopology(): com.turn.fieldtest.core.FloorTopology =
        floorCoordinator?.let { sessionBuilding?.topology } ?: com.turn.fieldtest.core.FloorTopology.NONE

    /** Adds a Wi-Fi fix to [floorId]'s trail in a multi-floor session; shown when that floor is displayed. */
    private fun appendWifiFix(floorId: String, point: Offset) {
        val trails = liveTrailsByFloor[floorId] ?: FloorTrails()
        val wifi = (trails.wifi + point).takeLast(MAX_TRAIL_POINTS)
        liveTrailsByFloor[floorId] = trails.copy(wifi = wifi)
        if (appState.activeProject?.floorId == floorId) appState.realWifiFixes = wifi
    }

    /**
     * Shows the confirmed floor (plan Step 8, invariant I3): swaps the displayed map to [floorId] from the frozen
     * session building, with no database access and no live reset, then publishes that floor's trails.
     */
    private fun followLiveFloor(floorId: String) {
        val building = sessionBuilding ?: return
        val saved = building.floorMaps[floorId] ?: return
        applyFloorDisplay(saved, ProjectContext(saved.venue.id, floorId))
        val trails = liveTrailsByFloor[floorId] ?: FloorTrails()
        appState.realFusedTrail = trails.fused
        appState.realWifiFixes = trails.wifi
        // Raw PDR has no floor model: it stays in its start floor's frame.
        appState.realRawPdrTrail = if (floorId == rawPdrFloorId) multiFloorRawPdrTrail else emptyList()
    }

    /**
     * Publishes the filter's estimate. [fresh] is false for the 1 Hz floor housekeeping tick of multi-floor sessions,
     * which refreshes the floor decision without counting as a new position estimate.
     */
    private fun updateRealParticleUi(fresh: Boolean = true) {
        val filter = particleFilter ?: return
        floorCoordinator?.let { updateMultiFloorParticleUi(filter, it, fresh); return }
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

    /**
     * Multi-floor branch of [updateRealParticleUi] (plan Step 8). The display shows only the confirmed floor of
     * [FloorTrackingCoordinator.decide] (invariant I3); position, cloud and trail come from that floor's particles.
     */
    private fun updateMultiFloorParticleUi(
        filter: ParticleFilter, coordinator: com.turn.fieldtest.core.FloorTrackingCoordinator, fresh: Boolean,
    ) {
        val decision = coordinator.decide(filter, android.os.SystemClock.elapsedRealtimeNanos())
        drainFloorEvents()
        val summary = filter.summary(decision.floorId) ?: filter.summary()
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
        val nowMillis = System.currentTimeMillis()
        val followed = floorToFollow(appState.activeProject?.floorId, decision.floorId)
        if (followed != null) followLiveFloor(followed)
        val building = sessionBuilding
        val previous = appState.liveFloorTracking
        appState.liveFloorTracking = LiveFloorUi(
            floorId = decision.floorId,
            floorName = sessionFloorName(decision.floorId),
            level = building?.levels?.get(decision.floorId) ?: 0,
            confidence = decision.vote,
            state = decision.state,
            barometer = coordinator.barometerHealth,
            announcedAtEpochMillis = if (followed != null || decision.changedFrom != null) nowMillis else previous?.announcedAtEpochMillis,
            sessionFloors = building?.floorIds.orEmpty().mapNotNull { id ->
                building?.floorMaps?.get(id)?.floor?.let { SessionFloorUi(id, it.name, it.levelNumber, it.widthMetres, it.heightMetres) }
            }.sortedByDescending { it.level },
            startFloorId = sessionContext?.floorId ?: decision.floorId,
        )
        val point = summary.position.toUiOffset()
        appState.realLiveInitialized = true
        appState.realLivePosition = point
        if (fresh) appState.liveEstimateEpochMillis = nowMillis
        appState.realLiveFloorId = summary.floorId
        appState.realLiveConfidence = summary.confidence
        appState.realLiveFloorConfidence = decision.vote
        appState.realLiveUncertaintyMetres = summary.uncertaintyRadiusMetres
        if (fresh) {
            val trails = liveTrailsByFloor[summary.floorId] ?: FloorTrails()
            val fused = appendTrailPoint(trails.fused, point, MAX_TRAIL_POINTS)
            liveTrailsByFloor[summary.floorId] = trails.copy(fused = fused)
            if (appState.activeProject?.floorId == summary.floorId) appState.realFusedTrail = fused
        }
        val onFloor = filter.particles.filter { it.floorId == summary.floorId }
        appState.realParticleCloud = onFloor
            .filterIndexed { index, _ -> index % (onFloor.size / 100).coerceAtLeast(1) == 0 }
            .take(100)
            .map { it.position.toUiOffset() }
        updateRoute()
    }

    fun selectNavigationDestination(id: String?) {
        appState.selectedDestinationId = id
        if (appState.liveRunning && floorCoordinator != null) {
            val context = appState.activeProject
            appState.liveDestination = appState.destinations.firstOrNull { it.id == id }
                ?.let { LiveDestination(it.id, context?.floorId ?: it.floorId, it.name) }
                ?: appState.buildingPlaces.firstOrNull { it.id == id }?.let { LiveDestination(it.id, it.floorId, it.name) }
        }
        appState.clearRoute()
        updateRoute()
    }

    private fun updateRoute() {
        val destination = appState.selectedDestinationId ?: return
        val position = appState.realLivePosition
        if (!appState.liveRunning || !appState.realLiveInitialized || position == null) {
            appState.clearRoute(); appState.routeStatus = "Initialize a position to calculate a route"; return
        }
        val liveDestination = appState.liveDestination?.takeIf { floorCoordinator != null && it.id == destination }
        if (liveDestination != null && liveDestination.floorId != appState.realLiveFloorId) {
            routeToOtherFloor(liveDestination, MetricPoint(position.x.toDouble(), position.y.toDouble())); return
        }
        appState.liveFloorRoute = null
        val graph = liveRouteGraph(floorCoordinator != null, appState.realLiveFloorId, sessionGraphs, sessionGraph, savedNavigationGraph)
        if (graph == null && floorCoordinator != null) {
            appState.clearRoute(); appState.routeStatus = NO_FLOOR_PATHS_TEXT; return
        }
        val node = graph?.nodes?.firstOrNull { it.poiId == destination }
        if (graph == null || node == null) {
            appState.clearRoute()
            // A place picked from another floor in a session that tracks one floor has no directions there.
            val elsewhere = appState.buildingPlaces.firstOrNull { it.id == destination && it.floorId != appState.realLiveFloorId }
                ?.takeIf { floorCoordinator == null && appState.destinations.none { d -> d.id == destination } }
            appState.routeStatus = elsewhere?.let { noLinkedFloorText(it) } ?: "Connect this destination to the saved path graph"; return
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

    /** Phase 3: the live route to [destination] on another floor than the tracked one, from the session's router. */
    private fun routeToOtherFloor(destination: LiveDestination, start: MetricPoint) {
        val fromFloorId = appState.realLiveFloorId
        val building = sessionBuilding
        val floorName = sessionFloorName(destination.floorId)
        val result = if (fromFloorId == null || building == null) MultiFloorRouteResult.Unreachable(RouteFailure.NO_FLOOR_LINK)
            else routeToPlace(sessionRouter, building.floorIds, sessionGraphs, fromFloorId, start, destination.id, destination.floorId,
                appState.stepFreeRouting)
        when (result) {
            is MultiFloorRouteResult.Found -> {
                val route = FloorRouteUi(result.route, destination.id, destination.floorId, destination.name, building!!.names)
                appState.liveFloorRoute = route
                appState.routePoints = floorLeg(route, appState.activeProject?.floorId)?.points.orEmpty().map { it.toUiOffset() }
                appState.remainingDistanceMetres = result.route.walkMetres
                // Arrival is only judged on the destination floor, by the same-floor route.
                appState.destinationReached = false
                appState.routeStatus = crossFloorRouteStatus(route)
            }
            is MultiFloorRouteResult.Unreachable -> {
                appState.clearRoute()
                appState.routeStatus = routeFailureText(result.reason, destination.name, floorName, appState.stepFreeRouting,
                    appState.useUncheckedFloorLinks)
            }
        }
    }

    /** Status for a place on another floor when the session (or preview) has no linked way to that floor. */
    private fun noLinkedFloorText(place: BuildingPlace): String =
        if (!appState.floorTrackingEnabled) floorTrackingOffRouteText(place.floorName)
        else routeFailureText(RouteFailure.NO_FLOOR_LINK, place.name, place.floorName, appState.stepFreeRouting, appState.useUncheckedFloorLinks)

    private class PreviewBuilding(
        val building: SessionBuilding, val graphs: Map<String, NavigationGraph>, val router: com.turn.fieldtest.core.MultiFloorRouter?,
    )

    /** What the route preview between floors depends on; any change re-plans it. */
    private data class CrossFloorPreviewRequest(
        val context: ProjectContext, val place: BuildingPlace, val start: MetricPoint?, val tracking: Boolean, val unchecked: Boolean,
        val stepFree: Boolean, val connectors: List<com.turn.fieldtest.data.repository.FloorConnector>, val openFloorGraph: NavigationGraph?,
    )

    /**
     * Phase 3 route preview without a session: from the start chosen on the open floor to a place on another floor.
     * The building is read like a session start (same links and options), without writing, and kept until the open
     * floor, its saved paths, the links or the link options change.
     */
    private suspend fun planCrossFloorPreview(request: CrossFloorPreviewRequest): CrossFloorPreview {
        val place = request.place
        fun result(route: FloorRouteUi?, problem: String?) = CrossFloorPreview(place.id, request.context.floorId, request.start, route, problem)
        if (!request.tracking) return result(null, floorTrackingOffRouteText(place.floorName))
        val start = request.start ?: return result(null, null)
        val key = listOf(request.context, request.unchecked, request.connectors, request.openFloorGraph)
        val loaded = previewBuilding?.takeIf { it.first == key }?.second ?: withContext(Dispatchers.IO) {
            val building = sessionLoader.load(request.context, true, request.unchecked, persistStartRepairs = false)
            val graphs = building.floorIds.mapNotNull { id ->
                val saved = building.floorMaps[id] ?: return@mapNotNull null
                val floor = building.metricMap.floors[id] ?: return@mapNotNull null
                runCatching { NavigationGraph(floor, saved.nodes.map { it.core() }, saved.edges.map { it.core() }) }.getOrNull()?.let { id to it }
            }.toMap()
            val router = if (building.multiFloor) com.turn.fieldtest.core.MultiFloorRouter(graphs, building.topology, floors = building.metricMap.floors) else null
            PreviewBuilding(building, graphs, router)
        }.also { previewBuilding = key to it }
        val outcome = withContext(Dispatchers.Default) {
            routeToPlace(loaded.router, loaded.building.floorIds.takeIf { loaded.building.multiFloor }.orEmpty(), loaded.graphs,
                request.context.floorId, start, place.id, place.floorId, request.stepFree)
        }
        return when (outcome) {
            is MultiFloorRouteResult.Found -> result(FloorRouteUi(outcome.route, place.id, place.floorId, place.name, loaded.building.names), null)
            is MultiFloorRouteResult.Unreachable -> result(null, routeFailureText(outcome.reason, place.name, place.floorName, request.stepFree, request.unchecked))
        }
    }

    /** Places on every floor of [venueId], for the destination search across floors. Read only. */
    private suspend fun loadBuildingPlaces(venueId: String): List<BuildingPlace> {
        val dao = app.database.navigationDao()
        return appState.projects.filter { it.venueId == venueId }.flatMap { floor ->
            val connected = dao.nodes(floor.floorId).mapNotNull { it.poiId }.toSet()
            dao.destinations(floor.floorId).map { BuildingPlace(it.id, it.name, floor.floorId, floor.floorName, floor.floorLevel, it.id in connected) }
        }
    }

    private suspend fun initializeManual(point: MetricPoint, floorId: String) {
        val session = requireNotNull(activePositioningSession)
        val filter = ParticleFilter(currentPilotMap(), ParticleFilterConfig(particleCount = appState.particleCount), session.startedAtEpochMillis,
            sessionTopology())
        withContext(Dispatchers.Default) { filter.initialize(AbsoluteFix(point, floorId, AbsoluteFixSource.MANUAL), positionStdMetres = 0.5) }
        particleFilter = filter; lastLiveMagneticObservation = null
        floorCoordinator?.takeIf { floorId in it.topology.levels }?.let {
            it.onAbsoluteFix(floorId, com.turn.fieldtest.core.FloorFixSource.MANUAL, android.os.SystemClock.elapsedRealtimeNanos())
            drainFloorEvents()
        }
        val sources = if (sensorSource.state.value.selectedStepSource == TurnSensorType.STEP_DETECTOR) setOf(StepSource.STEP_DETECTOR) else emptySet()
        rawPdrTracker = PdrTracker(point, latestMapHeadingRadians, StepProcessor(sources), StrideModel(appState.strideMetres.toDouble()))
        rawPdrFloorId = floorId
        appState.realRawPdrPosition = point.toUiOffset(); appState.realRawPdrTrail = listOf(point.toUiOffset())
        multiFloorRawPdrTrail = appState.realRawPdrTrail
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
        updateMagneticFeatures(sample)
        if (appState.liveRunning) floorCoordinator?.let { handleFloorSample(it, sample) }
        if (appState.diagnosticWalkRunning && sample.kind == SensorSampleKind.STEP_DETECTED) {
            appState.diagnosticWalkSteps += 1
            appState.realDiagnosticDistanceMetres = appState.diagnosticWalkSteps * appState.strideMetres.toDouble()
        }
        if (sample.kind == SensorSampleKind.STEP_DETECTED) {
            // Always consume the window, so each step gets exactly the readings since the previous step.
            val magnetic = magneticWindow.takeForStep(sample.sensorTimestampNanos)
            activeMagneticWalk?.let { walk ->
                walk.observations += magnetic
                appState.magneticWalkSteps = walk.observations.size
                appState.magneticWalkStatus = "Walking · ${walk.observations.size} steps · keep a steady pace in a straight line"
            }
            if (appState.liveRunning) processLiveStep(sample, magnetic)
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

    /**
     * Multi-floor sessions only: barometer samples feed the coordinator, and on any sample its housekeeping runs at
     * most once per second (barometer health, pending-height expiry, lift stillness, decision holds).
     */
    private suspend fun handleFloorSample(coordinator: com.turn.fieldtest.core.FloorTrackingCoordinator, sample: SensorSample) {
        val ts = sample.sensorTimestampNanos
        // Vertical evidence moves and resamples particles, and the tick scans every particle against the portals:
        // filter work runs off the main thread like every other filter update (the caller holds eventMutex).
        val filter = particleFilter
        if (sample.kind == SensorSampleKind.PRESSURE) sample.values.firstOrNull()?.takeIf { it.isFinite() }?.let { hpa ->
            val result = withContext(Dispatchers.Default) { coordinator.onPressure(ts, hpa.toDouble(), filter) }
            drainFloorEvents()
            if (result != null) updateRealParticleUi()
        }
        if (lastFloorTickNanos == Long.MIN_VALUE || ts - lastFloorTickNanos >= 1_000_000_000L) {
            lastFloorTickNanos = ts
            withContext(Dispatchers.Default) { coordinator.onTick(ts, filter) }
            drainFloorEvents()
            if (particleFilter?.isLost == false) updateRealParticleUi(fresh = false)
        }
    }

    /**
     * Turns magnetometer readings into orientation-invariant features using the latest gravity direction.
     * Uncalibrated readings are corrected with Android's bias estimate; a change in that estimate, an
     * accuracy change or unreliable data marks a discontinuity so step-to-step comparison is not misled.
     */
    private fun updateMagneticFeatures(sample: SensorSample) {
        when (sample.kind) {
            SensorSampleKind.GRAVITY -> if (sample.values.size >= 3) {
                latestGravity = DoubleArray(3) { sample.values[it].toDouble() }; latestGravityNanos = sample.sensorTimestampNanos
            }
            SensorSampleKind.ACCELERATION -> if (sample.values.size >= 3 &&
                sensorSource.state.value.availability.none { it.type == TurnSensorType.GRAVITY && it.available }) {
                // Low-pass the accelerometer into a gravity estimate on phones without a gravity sensor.
                val previous = latestGravity
                latestGravity = DoubleArray(3) { i -> (previous?.get(i) ?: sample.values[i].toDouble()) * 0.8 + sample.values[i] * 0.2 }
                latestGravityNanos = sample.sensorTimestampNanos
            }
            SensorSampleKind.ACCURACY_CHANGED -> if (sample.type == TurnSensorType.MAGNETOMETER) {
                magneticWindow.markDiscontinuity(sample.sensorTimestampNanos)
                appState.magneticSensorStatus = when (sample.accuracy) {
                    android.hardware.SensorManager.SENSOR_STATUS_UNRELIABLE -> "Magnetometer unreliable · move the phone in a figure-eight to recalibrate"
                    android.hardware.SensorManager.SENSOR_STATUS_ACCURACY_LOW -> "Magnetometer accuracy low"
                    else -> "Magnetometer ready (${sensorSource.state.value.magnetometerMode ?: "unknown"})"
                }
            }
            SensorSampleKind.MAGNETIC_FIELD -> {
                if (sample.values.size < 3 || sample.accuracy == android.hardware.SensorManager.SENSOR_STATUS_UNRELIABLE) {
                    magneticWindow.markDiscontinuity(sample.sensorTimestampNanos); return
                }
                val raw = DoubleArray(3) { sample.values[it].toDouble() }
                val field = if (sample.values.size >= 6) {
                    val bias = DoubleArray(3) { sample.values[it + 3].toDouble() }
                    val previousBias = lastMagneticBias
                    if (previousBias != null && (0..2).any { kotlin.math.abs(bias[it] - previousBias[it]) > 0.5 })
                        magneticWindow.markDiscontinuity(sample.sensorTimestampNanos)
                    lastMagneticBias = bias
                    DoubleArray(3) { raw[it] - bias[it] }
                } else raw
                val gravityAge = latestGravityNanos?.let { sample.sensorTimestampNanos - it }
                val gravity = latestGravity?.takeIf { gravityAge != null && gravityAge in -500_000_000L..500_000_000L } ?: return
                com.turn.fieldtest.core.MagneticFeature.from(field, gravity)?.let { magneticWindow.record(sample.sensorTimestampNanos, it) }
            }
            else -> Unit
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

    private suspend fun processLiveStep(sample: SensorSample, magnetic: com.turn.fieldtest.core.MagneticObservation?) {
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
            // The filter did not move with this step, so the next magnetic change cannot be compared with it.
            lastLiveMagneticObservation = null
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
                // Multi-floor sessions only; null (as before) without a floor coordinator.
                pressureHpa = floorCoordinator?.latestPressureHpa,
            ),
        )
        if (!event.decision.acceptedForMovement) {
            appState.realLiveStepStatus = "Step ignored: ${event.decision.reason}"
            appState.realLiveRecordingStatus = "Rejected step saved"
            lastLiveMagneticObservation = null
            return
        }

        val coordinator = floorCoordinator
        val prediction = withContext(Dispatchers.Default) {
            if (coordinator != null) filter?.predictStep(event.strideMetres, heading, stairsActive = coordinator.stairsActive)
            else filter?.predictStep(event.strideMetres, heading)
        }
        // Magnetic evidence after the motion update: it judges where each particle has just moved to.
        val magneticCorrection = if (prediction?.accepted == true && filter != null && magnetic != null &&
            appState.magneticCorrectionsEnabled && sessionMagneticMaps.isNotEmpty()) {
            withContext(Dispatchers.Default) { filter.correctWithMagnetic(sessionMagneticMaps, magnetic, lastLiveMagneticObservation) }
        } else null
        lastLiveMagneticObservation = magnetic.takeIf { prediction?.accepted == true }
        if (coordinator != null) {
            // Step timing for the vertical detector, and (without a healthy barometer) the stairs/lift dwell cues.
            withContext(Dispatchers.Default) { coordinator.onStep(sample.sensorTimestampNanos, filter) }
            drainFloorEvents()
        }
        appState.realMagneticStatus = when {
            !appState.magneticCorrectionsEnabled -> "Magnetic corrections off"
            sessionMagneticMaps.isEmpty() -> "No magnetic map for this floor · survey magnetic paths to enable"
            magnetic == null -> "No magnetometer reading for this step"
            magneticCorrection == null -> "Waiting for tracking before using magnetic evidence"
            else -> when (magneticCorrection.kind) {
                com.turn.fieldtest.core.MagneticCorrectionKind.APPLIED -> {
                    appState.realMagneticAppliedSteps += 1
                    "Magnetic evidence applied on ${appState.realMagneticAppliedSteps} steps"
                }
                com.turn.fieldtest.core.MagneticCorrectionKind.IGNORED_UNMAPPED_AREA -> "Outside surveyed magnetic paths · not applied"
                com.turn.fieldtest.core.MagneticCorrectionKind.IGNORED_DISTURBED -> "Strong magnetic interference · step not used"
                else -> "Magnetic evidence not applied"
            }
        }
        appState.realLiveStepStatus = if (prediction?.accepted == true) "Step accepted · position updated"
            else "Step recorded · fused position lost; request a fresh Wi-Fi fix"
        appState.realLiveStepCount = event.stateAfter.acceptedStepCount.toLong()
        appState.realLiveDistanceMetres = event.stateAfter.estimatedDistanceMetres
        appState.realRawPdrPosition = event.stateAfter.position.toUiOffset()
        if (coordinator != null) {
            multiFloorRawPdrTrail = (multiFloorRawPdrTrail + event.stateAfter.position.toUiOffset()).takeLast(MAX_TRAIL_POINTS)
            if (appState.activeProject?.floorId == rawPdrFloorId) appState.realRawPdrTrail = multiFloorRawPdrTrail
        } else {
            appState.realRawPdrTrail = (appState.realRawPdrTrail + event.stateAfter.position.toUiOffset()).takeLast(MAX_TRAIL_POINTS)
        }
        appState.realLiveMapRejectedCount += prediction?.rejectedParticleCount ?: 0
        appState.lastCorrectionType = (if ((prediction?.rejectedParticleCount ?: 0) > 0) {
            "map constraint applied"
        } else {
            "PDR prediction"
        }) + if (magneticCorrection?.kind == com.turn.fieldtest.core.MagneticCorrectionKind.APPLIED) " + magnetic" else ""
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
        (magneticCorrection?.summary ?: prediction?.summary)?.let { summary ->
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
        runCatching { rawRecorder?.close() }
        rawRecorder = null
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
