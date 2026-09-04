package com.turn.fieldtest.platform.wifi

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.turn.fieldtest.platform.PlatformClock
import com.turn.fieldtest.platform.SystemPlatformClock
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.max

enum class WifiFreshness { FRESH, CACHED_OR_STALE }

enum class WifiScanIssue {
    WIFI_DISABLED,
    LOCATION_SERVICES_DISABLED,
    PERMISSION_DENIED,
    SCANNER_NOT_STARTED,
    REQUEST_REJECTED_OR_THROTTLED,
    CLIENT_RATE_LIMITED,
    SECURITY_EXCEPTION,
    HARDWARE_OR_API_FAILURE,
    NO_ACCESS_POINTS,
}

data class WifiAccessPoint(
    val bssid: String,
    val ssid: String?,
    val rssiDbm: Int,
    val frequencyMhz: Int,
    val channel: Int?,
    /** Android scan-result timestamp, microseconds since boot. */
    val scanTimestampMicros: Long,
    val ageMillisAtReceipt: Long,
)

data class WifiScanBatch(
    val receivedAtEpochMillis: Long,
    val receivedAtElapsedMillis: Long,
    val requestAccepted: Boolean?,
    val resultsUpdated: Boolean,
    val freshness: WifiFreshness,
    val accessPoints: List<WifiAccessPoint>,
    val newestResultAgeMillis: Long?,
    val scanThrottlingEnabled: Boolean?,
    val nextPermittedRequestAtEpochMillis: Long? = null,
    val simulated: Boolean = false,
    val issue: WifiScanIssue? = null,
) {
    val isIndependentFreshScan: Boolean
        get() = freshness == WifiFreshness.FRESH && resultsUpdated
}

data class WifiScannerState(
    val monitoring: Boolean = false,
    val requestInFlight: Boolean = false,
    val lastRequestAtEpochMillis: Long? = null,
    val lastRequestAccepted: Boolean? = null,
    val lastBatchAtEpochMillis: Long? = null,
    val lastResultsUpdated: Boolean? = null,
    val lastFreshness: WifiFreshness? = null,
    val lastIssue: WifiScanIssue? = null,
    val scanThrottlingEnabled: Boolean? = null,
    val nextPermittedRequestAtEpochMillis: Long? = null,
)

sealed interface WifiScanRequestResult {
    data class Accepted(val requestedAtEpochMillis: Long) : WifiScanRequestResult
    data class Rejected(val issue: WifiScanIssue, val requestedAtEpochMillis: Long) : WifiScanRequestResult
}

interface WifiScanner {
    val batches: SharedFlow<WifiScanBatch>
    val state: StateFlow<WifiScannerState>

    /** Register the foreground receiver. Call from a visible lifecycle owner. */
    fun start()

    /** Unregister immediately when the field-testing screen/activity stops. */
    fun stop()

    suspend fun requestScan(): WifiScanRequestResult
}

/**
 * Foreground-only WifiManager adapter. A broadcast with EXTRA_RESULTS_UPDATED=false, old scan
 * timestamps, or the same newest scan timestamp as the previous delivery is never labelled fresh.
 */
class AndroidWifiScanner(
    context: Context,
    private val wifiManager: WifiManager = context.applicationContext.getSystemService(WifiManager::class.java),
    private val locationManager: LocationManager = context.applicationContext.getSystemService(LocationManager::class.java),
    private val clock: PlatformClock = SystemPlatformClock,
    private val freshnessWindowMillis: Long = DEFAULT_FRESHNESS_WINDOW_MILLIS,
    private val throttlePolicy: WifiScanThrottlePolicy = WifiScanThrottlePolicy(),
) : WifiScanner {
    private val appContext = context.applicationContext
    private val mutableBatches = MutableSharedFlow<WifiScanBatch>(extraBufferCapacity = 8)
    private val mutableState = MutableStateFlow(WifiScannerState())
    private var receiverRegistered = false
    private var lastNewestTimestampMicros: Long? = null

    override val batches: SharedFlow<WifiScanBatch> = mutableBatches.asSharedFlow()
    override val state: StateFlow<WifiScannerState> = mutableState.asStateFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) return
            val resultsUpdated = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
            emitCurrentResults(resultsUpdated)
        }
    }

    override fun start() {
        if (receiverRegistered) return
        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            appContext.registerReceiver(receiver, filter)
        }
        receiverRegistered = true
        mutableState.value = mutableState.value.copy(
            monitoring = true,
            scanThrottlingEnabled = readThrottleSetting(),
            lastIssue = null,
        )
    }

    override fun stop() {
        if (!receiverRegistered) return
        runCatching { appContext.unregisterReceiver(receiver) }
        receiverRegistered = false
        mutableState.value = mutableState.value.copy(monitoring = false, requestInFlight = false)
    }

    @SuppressLint("MissingPermission")
    override suspend fun requestScan(): WifiScanRequestResult {
        val now = clock.epochMillis()
        val preflightIssue = preflightIssue()
        if (preflightIssue != null) return reject(preflightIssue, now)
        val nextPermitted = mutableState.value.nextPermittedRequestAtEpochMillis
        if (nextPermitted != null && now < nextPermitted) {
            return reject(WifiScanIssue.CLIENT_RATE_LIMITED, now)
        }

        mutableState.value = mutableState.value.copy(
            requestInFlight = true,
            lastRequestAtEpochMillis = now,
            lastIssue = null,
        )
        val accepted = try {
            @Suppress("DEPRECATION")
            wifiManager.startScan()
        } catch (_: SecurityException) {
            return reject(WifiScanIssue.SECURITY_EXCEPTION, now)
        } catch (_: RuntimeException) {
            return reject(WifiScanIssue.HARDWARE_OR_API_FAILURE, now)
        }

        mutableState.value = mutableState.value.copy(
            requestInFlight = accepted,
            lastRequestAccepted = accepted,
            lastIssue = if (accepted) null else WifiScanIssue.REQUEST_REJECTED_OR_THROTTLED,
            scanThrottlingEnabled = readThrottleSetting(),
            nextPermittedRequestAtEpochMillis = throttlePolicy.nextPermittedEpochMillis(now),
        )
        return if (accepted) {
            WifiScanRequestResult.Accepted(now)
        } else {
            WifiScanRequestResult.Rejected(WifiScanIssue.REQUEST_REJECTED_OR_THROTTLED, now)
        }
    }

    private fun reject(issue: WifiScanIssue, now: Long): WifiScanRequestResult.Rejected {
        mutableState.value = mutableState.value.copy(
            requestInFlight = false,
            lastRequestAtEpochMillis = now,
            lastRequestAccepted = false,
            lastIssue = issue,
            scanThrottlingEnabled = readThrottleSetting(),
        )
        return WifiScanRequestResult.Rejected(issue, now)
    }

    private fun preflightIssue(): WifiScanIssue? {
        if (!receiverRegistered) return WifiScanIssue.SCANNER_NOT_STARTED
        if (!wifiManager.isWifiEnabled) return WifiScanIssue.WIFI_DISABLED
        if (!hasRuntimePermissions()) return WifiScanIssue.PERMISSION_DENIED
        if (!isLocationServiceEnabled()) return WifiScanIssue.LOCATION_SERVICES_DISABLED
        return null
    }

    private fun hasRuntimePermissions(): Boolean {
        val fineLocationGranted = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val nearbyGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.NEARBY_WIFI_DEVICES,
            ) == PackageManager.PERMISSION_GRANTED

        // TURN derives physical position, so it intentionally requires location permission and does
        // not use the manifest neverForLocation assertion.
        return fineLocationGranted && nearbyGranted
    }

    private fun isLocationServiceEnabled(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        locationManager.isLocationEnabled
    } else {
        @Suppress("DEPRECATION")
        locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    @SuppressLint("MissingPermission")
    private fun emitCurrentResults(resultsUpdated: Boolean) {
        val epochNow = clock.epochMillis()
        val elapsedMicros = clock.elapsedRealtimeNanos() / 1_000L
        val scanResults = try {
            wifiManager.scanResults.orEmpty()
        } catch (_: SecurityException) {
            emitFailureBatch(epochNow, WifiScanIssue.SECURITY_EXCEPTION, resultsUpdated)
            return
        } catch (_: RuntimeException) {
            emitFailureBatch(epochNow, WifiScanIssue.HARDWARE_OR_API_FAILURE, resultsUpdated)
            return
        }

        val points = scanResults
            .asSequence()
            .filter { it.BSSID.isNotBlank() }
            .map { it.toAccessPoint(elapsedMicros) }
            .groupBy { it.bssid.lowercase() }
            .values
            .map { duplicateRows ->
                duplicateRows.maxWith(
                    compareBy<WifiAccessPoint> { it.rssiDbm }
                        .thenBy { it.scanTimestampMicros },
                )
            }
            .sortedByDescending(WifiAccessPoint::rssiDbm)
            .toList()
        val newestTimestamp = points.maxOfOrNull(WifiAccessPoint::scanTimestampMicros)
        val newestAge = points.minOfOrNull(WifiAccessPoint::ageMillisAtReceipt)
        val freshnessDecision = WifiFreshnessEvaluator.evaluate(
            resultsUpdated = resultsUpdated,
            newestTimestampMicros = newestTimestamp,
            newestResultAgeMillis = newestAge,
            previousNewestTimestampMicros = lastNewestTimestampMicros,
            freshnessWindowMillis = freshnessWindowMillis,
        )
        val isFresh = freshnessDecision == WifiFreshness.FRESH
        if (isFresh) lastNewestTimestampMicros = newestTimestamp

        val issue = if (points.isEmpty()) WifiScanIssue.NO_ACCESS_POINTS else null
        val batch = WifiScanBatch(
            receivedAtEpochMillis = epochNow,
            receivedAtElapsedMillis = clock.elapsedRealtimeMillis(),
            requestAccepted = mutableState.value.lastRequestAccepted.takeIf { mutableState.value.requestInFlight },
            resultsUpdated = resultsUpdated,
            freshness = if (isFresh) WifiFreshness.FRESH else WifiFreshness.CACHED_OR_STALE,
            accessPoints = points,
            newestResultAgeMillis = newestAge,
            scanThrottlingEnabled = readThrottleSetting(),
            nextPermittedRequestAtEpochMillis = mutableState.value.nextPermittedRequestAtEpochMillis,
            issue = issue,
        )
        mutableBatches.tryEmit(batch)
        mutableState.value = mutableState.value.copy(
            requestInFlight = false,
            lastBatchAtEpochMillis = epochNow,
            lastResultsUpdated = resultsUpdated,
            lastFreshness = batch.freshness,
            lastIssue = issue,
            scanThrottlingEnabled = batch.scanThrottlingEnabled,
        )
    }

    private fun emitFailureBatch(now: Long, issue: WifiScanIssue, resultsUpdated: Boolean) {
        mutableBatches.tryEmit(
            WifiScanBatch(
                receivedAtEpochMillis = now,
                receivedAtElapsedMillis = clock.elapsedRealtimeMillis(),
                requestAccepted = mutableState.value.lastRequestAccepted,
                resultsUpdated = resultsUpdated,
                freshness = WifiFreshness.CACHED_OR_STALE,
                accessPoints = emptyList(),
                newestResultAgeMillis = null,
                scanThrottlingEnabled = readThrottleSetting(),
                nextPermittedRequestAtEpochMillis = mutableState.value.nextPermittedRequestAtEpochMillis,
                issue = issue,
            ),
        )
        mutableState.value = mutableState.value.copy(
            requestInFlight = false,
            lastBatchAtEpochMillis = now,
            lastResultsUpdated = resultsUpdated,
            lastFreshness = WifiFreshness.CACHED_OR_STALE,
            lastIssue = issue,
        )
    }

    @Suppress("DEPRECATION")
    private fun ScanResult.toAccessPoint(elapsedMicros: Long): WifiAccessPoint {
        val timestamp = timestamp
        return WifiAccessPoint(
            bssid = BSSID,
            ssid = SSID.takeIf(String::isNotBlank),
            rssiDbm = level,
            frequencyMhz = frequency,
            channel = WifiChannels.fromFrequencyMhz(frequency),
            scanTimestampMicros = timestamp,
            ageMillisAtReceipt = max(0L, (elapsedMicros - timestamp) / 1_000L),
        )
    }

    private fun readThrottleSetting(): Boolean? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        runCatching { wifiManager.isScanThrottleEnabled }.getOrNull()
    } else {
        null
    }

    companion object {
        const val DEFAULT_FRESHNESS_WINDOW_MILLIS = 15_000L
    }
}

object WifiChannels {
    fun fromFrequencyMhz(frequencyMhz: Int): Int? = when {
        frequencyMhz == 2_484 -> 14
        frequencyMhz in 2_412..2_472 && (frequencyMhz - 2_407) % 5 == 0 ->
            (frequencyMhz - 2_407) / 5
        frequencyMhz in 5_000..5_895 && (frequencyMhz - 5_000) % 5 == 0 ->
            (frequencyMhz - 5_000) / 5
        frequencyMhz == 5_935 -> 2
        frequencyMhz in 5_955..7_115 && (frequencyMhz - 5_950) % 5 == 0 ->
            (frequencyMhz - 5_950) / 5
        else -> null
    }
}

data class WifiScanThrottlePolicy(
    /** Conservative foreground pacing aligned with Android's documented 4 requests / 2 minutes. */
    val minimumIntervalMillis: Long = 30_000L,
) {
    init {
        require(minimumIntervalMillis >= 0L)
    }

    fun nextPermittedEpochMillis(requestedAtEpochMillis: Long): Long =
        requestedAtEpochMillis + minimumIntervalMillis
}

object WifiFreshnessEvaluator {
    fun evaluate(
        resultsUpdated: Boolean,
        newestTimestampMicros: Long?,
        newestResultAgeMillis: Long?,
        previousNewestTimestampMicros: Long?,
        freshnessWindowMillis: Long = AndroidWifiScanner.DEFAULT_FRESHNESS_WINDOW_MILLIS,
    ): WifiFreshness {
        if (!resultsUpdated || newestTimestampMicros == null || newestResultAgeMillis == null) {
            return WifiFreshness.CACHED_OR_STALE
        }
        if (newestResultAgeMillis !in 0L..freshnessWindowMillis) {
            return WifiFreshness.CACHED_OR_STALE
        }
        if (previousNewestTimestampMicros != null && newestTimestampMicros <= previousNewestTimestampMicros) {
            return WifiFreshness.CACHED_OR_STALE
        }
        return WifiFreshness.FRESH
    }
}

/** Deterministic scanner used only when the application explicitly selects DEMO mode. */
class ReplayWifiScanner(
    private val scope: CoroutineScope,
    trace: List<WifiScanBatch>,
    private val clock: PlatformClock = SystemPlatformClock,
) : WifiScanner {
    private val replay = trace.toList()
    private var index = 0
    private val mutableBatches = MutableSharedFlow<WifiScanBatch>(extraBufferCapacity = 8)
    private val mutableState = MutableStateFlow(WifiScannerState())

    override val batches: SharedFlow<WifiScanBatch> = mutableBatches.asSharedFlow()
    override val state: StateFlow<WifiScannerState> = mutableState.asStateFlow()

    override fun start() {
        mutableState.value = mutableState.value.copy(monitoring = true, lastIssue = null)
    }

    override fun stop() {
        mutableState.value = mutableState.value.copy(monitoring = false, requestInFlight = false)
    }

    override suspend fun requestScan(): WifiScanRequestResult {
        val now = clock.epochMillis()
        if (!mutableState.value.monitoring) {
            val issue = WifiScanIssue.SCANNER_NOT_STARTED
            mutableState.value = mutableState.value.copy(lastIssue = issue, lastRequestAtEpochMillis = now)
            return WifiScanRequestResult.Rejected(issue, now)
        }
        if (replay.isEmpty()) {
            val issue = WifiScanIssue.NO_ACCESS_POINTS
            mutableState.value = mutableState.value.copy(lastIssue = issue, lastRequestAtEpochMillis = now)
            return WifiScanRequestResult.Rejected(issue, now)
        }
        val batch = replay[index % replay.size].copy(
            receivedAtEpochMillis = now,
            receivedAtElapsedMillis = clock.elapsedRealtimeMillis(),
            requestAccepted = true,
            simulated = true,
        )
        index++
        mutableState.value = mutableState.value.copy(
            requestInFlight = true,
            lastRequestAtEpochMillis = now,
            lastRequestAccepted = true,
            lastIssue = null,
        )
        scope.launch {
            mutableBatches.emit(batch)
            mutableState.value = mutableState.value.copy(
                requestInFlight = false,
                lastBatchAtEpochMillis = now,
                lastResultsUpdated = batch.resultsUpdated,
                lastFreshness = batch.freshness,
                lastIssue = batch.issue,
            )
        }
        return WifiScanRequestResult.Accepted(now)
    }
}
