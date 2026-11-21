package com.turn.fieldtest.core.replay

import com.turn.fieldtest.core.AbsoluteFix
import com.turn.fieldtest.core.AbsoluteFixSource
import com.turn.fieldtest.core.ConfirmedFloor
import com.turn.fieldtest.core.FloorDecisionResult
import com.turn.fieldtest.core.FloorDetectionConfig
import com.turn.fieldtest.core.FloorFixSource
import com.turn.fieldtest.core.FloorTopology
import com.turn.fieldtest.core.FloorTrackEvaluator
import com.turn.fieldtest.core.FloorTrackMetrics
import com.turn.fieldtest.core.FloorTrackingCoordinator
import com.turn.fieldtest.core.FloorTrackingEvent
import com.turn.fieldtest.core.MetricMap
import com.turn.fieldtest.core.MetricPoint
import com.turn.fieldtest.core.ParticleFilter
import com.turn.fieldtest.core.ParticleFilterConfig
import com.turn.fieldtest.core.WifiCorrectionKind
import com.turn.fieldtest.core.WifiMatchResult
import com.turn.fieldtest.platform.sensors.RawSensorTrace
import com.turn.fieldtest.platform.sensors.SensorSampleKind
import com.turn.fieldtest.platform.sensors.TraceRow
import com.turn.fieldtest.platform.sensors.TurnSensorType

/** Where and how the replayed session starts (an absolute fix at the first row). */
data class ReplayStart(val floorId: String, val position: MetricPoint, val stdMetres: Double = 0.5,
                       val source: FloorFixSource = FloorFixSource.MANUAL)

data class ReplayResult(
    val confirmed: List<ConfirmedFloor>,
    val decisions: List<Pair<Long, FloorDecisionResult>>,
    val events: List<FloorTrackingEvent>,
    val wifiResults: List<WifiCorrectionKind>,
    val metrics: FloorTrackMetrics,
    val filter: ParticleFilter,
    val endNanos: Long,
) {
    val finalFloor: String get() = confirmed.last().floorId
}

/**
 * Replays a [RawSensorTrace] (a field recording, or a [SyntheticTrace]) in sensor-time order through a multi-floor
 * [ParticleFilter] and a [FloorTrackingCoordinator], exactly as the live session will call them (Step 7):
 * pressure -> onPressure; step -> predictStep(stairsActive) then onStep; fresh Wi-Fi -> coordinator.correctWithWifi;
 * onTick at 1 Hz; decide after each update. CONFIRMED floors are scored with [FloorTrackEvaluator] against the
 * trace's markers. Heading and stride are supplied by the caller (the scenario script, or a PDR pipeline later).
 */
class FloorReplayHarness(
    private val map: MetricMap,
    private val topology: FloorTopology,
    private val filterConfig: ParticleFilterConfig = ParticleFilterConfig(particleCount = 300),
    private val detectionConfig: FloorDetectionConfig = FloorDetectionConfig(),
) {
    fun replay(
        trace: RawSensorTrace,
        start: ReplayStart,
        seed: Long,
        headingAt: (Long) -> Double,
        wifiMatcher: (TraceRow.WifiScan) -> WifiMatchResult? = { null },
        strideMetres: Double = 0.7,
        barometerPresent: Boolean = trace.rows.any { it is TraceRow.Sensor && it.type == TurnSensorType.BAROMETER },
    ): ReplayResult {
        val rows = trace.rows.sortedBy { it.timeNanos }
        require(rows.isNotEmpty()) { "empty trace" }
        val t0 = rows.first().timeNanos
        val filter = ParticleFilter(map, filterConfig, seed, topology)
        val coordinator = requireNotNull(FloorTrackingCoordinator.createOrNull(topology, detectionConfig, barometerPresent, start.floorId))
        val fixSource = when (start.source) {
            FloorFixSource.QR -> AbsoluteFixSource.QR
            FloorFixSource.WIFI_INIT, FloorFixSource.USER_RELOCALIZE, FloorFixSource.WIFI_CROSS_FLOOR -> AbsoluteFixSource.WIFI
            FloorFixSource.MANUAL -> AbsoluteFixSource.MANUAL
        }
        filter.initialize(AbsoluteFix(start.position, start.floorId, fixSource), start.stdMetres)
        coordinator.onAbsoluteFix(start.floorId, start.source, t0)

        val confirmed = mutableListOf<ConfirmedFloor>()
        val decisions = mutableListOf<Pair<Long, FloorDecisionResult>>()
        val events = mutableListOf<FloorTrackingEvent>()
        val wifi = mutableListOf<WifiCorrectionKind>()
        fun decide(t: Long) {
            val d = coordinator.decide(filter, t)
            decisions += t to d
            if (confirmed.lastOrNull()?.floorId != d.floorId) confirmed += ConfirmedFloor(t, d.floorId)
        }
        decide(t0)
        var nextTick = t0 + TICK
        for (row in rows) {
            val t = row.timeNanos
            while (nextTick <= t) {
                coordinator.onTick(nextTick, filter)
                decide(nextTick)
                nextTick += TICK
            }
            when (row) {
                is TraceRow.Sensor -> when {
                    row.type == TurnSensorType.BAROMETER && row.kind == SensorSampleKind.PRESSURE && row.values.isNotEmpty() ->
                        if (coordinator.onPressure(t, row.values[0].toDouble(), filter) != null) decide(t)
                    row.type == TurnSensorType.STEP_DETECTOR && row.kind == SensorSampleKind.STEP_DETECTED -> {
                        if (!filter.isLost) filter.predictStep(strideMetres, headingAt(t), coordinator.stairsActive)
                        coordinator.onStep(t, filter)
                        decide(t)
                    }
                    else -> Unit
                }
                is TraceRow.WifiScan -> if (row.fresh) {
                    wifiMatcher(row)?.let { match ->
                        wifi += coordinator.correctWithWifi(filter, match, t).kind
                        decide(t)
                    }
                }
                is TraceRow.Marker -> Unit
            }
            events += coordinator.drainEvents()
        }
        val end = rows.last().timeNanos
        events += coordinator.drainEvents()
        val metrics = FloorTrackEvaluator.evaluate(trace.truth(), confirmed, end)
        return ReplayResult(confirmed, decisions, events, wifi, metrics, filter, end)
    }

    private companion object {
        const val TICK = 1_000_000_000L
    }
}

/** Compact event log for assertion messages. */
fun ReplayResult.describe(): String = events.filter { it.type != com.turn.fieldtest.core.FloorTrackingEventType.CONFIG }
    .joinToString("; ") { "%.1fs %s %s".format(java.util.Locale.ROOT, it.atNanos / 1e9, it.type, it.detail) } +
    " | votes=${filter.floorVotes()} | confirmed=$confirmed"
