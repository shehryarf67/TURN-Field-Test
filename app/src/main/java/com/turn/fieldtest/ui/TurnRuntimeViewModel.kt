package com.turn.fieldtest.ui

import android.app.Application
import android.hardware.SensorManager
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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

    init {
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
                appState.surveyRuntimeStatus = "Could not create survey session"
                appState.surveySaveStatus = failure.message ?: failure::class.java.simpleName
            }
        }
    }

    fun finishRealSurvey() {
        if (activeSurvey == null) return
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
        rawPdrTracker = null
        appState.realLiveInitialized = false
        appState.realLiveStatus = "Global relocalization armed; waiting for a fresh Wi-Fi fix"
        requestLiveScan()
    }

    private fun beginRealLive() {
        if (activeSurvey != null || appState.surveyRunning) {
            appState.realLiveStatus = "Finish the active fingerprint survey before positioning"
            return
        }
        if (!realWifiReady("Live locate")) return
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

    private suspend fun ensurePilotContext(now: Long) {
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
        appState.realWifiPosition = match.estimatedPosition?.toUiOffset()
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
            rawPdrTracker = PdrTracker(
                initialPosition = match.estimatedPosition,
                initialHeadingRadians = 0.0,
                stepProcessor = StepProcessor(availableSteps),
                strideModel = StrideModel(appState.strideMetres.toDouble()),
            )
            liveYawBaselineRadians = latestDeviceYawRadians
            latestMapHeadingRadians = 0.0
            appState.realRawPdrPosition = match.estimatedPosition.toUiOffset()
            appState.realRawPdrTrail = listOf(match.estimatedPosition.toUiOffset())
            appState.realLiveInitialized = true
            appState.lastCorrectionType = "first confident Wi-Fi fix"
            appState.realLiveStatus = "Initialized from fresh Wi-Fi; current phone direction is map +x"
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
        appState.realLastWifiCorrectionEpochMillis = batch.receivedAtEpochMillis
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
        val neighboursJson = neighbourIds.joinToString(prefix = "[\"", separator = "\",\"", postfix = "\"]")
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
                accepted = true,
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
            appState.realLiveStatus = "Filter lost; request a fresh Wi-Fi fix"
            return
        }
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
                    latestMapHeadingRadians = normalizeRadians(latestMapHeadingRadians - z * deltaSeconds)
                }
            }
            else -> Unit
        }
        appState.realLiveHeadingDegrees = latestMapHeadingRadians * 180.0 / PI
    }

    private suspend fun processLiveStep(sample: SensorSample) {
        val tracker = rawPdrTracker ?: return
        val filter = particleFilter ?: return
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

        val prediction = filter.predictStep(event.strideMetres, latestMapHeadingRadians)
        appState.realLiveStepCount = event.stateAfter.acceptedStepCount.toLong()
        appState.realLiveDistanceMetres = event.stateAfter.estimatedDistanceMetres
        appState.realRawPdrPosition = event.stateAfter.position.toUiOffset()
        appState.realRawPdrTrail = (appState.realRawPdrTrail + event.stateAfter.position.toUiOffset()).takeLast(MAX_TRAIL_POINTS)
        appState.realLiveMapRejectedCount += prediction.rejectedParticleCount
        appState.lastCorrectionType = if (prediction.rejectedParticleCount > 0) {
            "map constraint applied"
        } else {
            "PDR prediction"
        }
        appState.realLiveStatus = if (prediction.accepted) {
            "Step ${event.stateAfter.acceptedStepCount}: ${appState.lastCorrectionType}"
        } else {
            "Filter lost because every particle violated the metric map"
        }
        updateRealParticleUi()

        val sequence = liveEstimateSequence++
        repositories.positioning.saveEstimate(
            PositionEstimateEntity(
                id = "EST-${positioningSession.id}-$sequence-RAW",
                positioningSessionId = positioningSession.id,
                sequence = sequence,
                method = "RAW_PDR",
                floorId = appState.realLiveFloorId,
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
        prediction.summary?.let { summary ->
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
