package com.turn.fieldtest.core.replay

import com.turn.fieldtest.core.ConnectorKind
import com.turn.fieldtest.platform.sensors.RawSensorRecorder
import com.turn.fieldtest.platform.sensors.RawSensorTrace
import com.turn.fieldtest.platform.sensors.SensorSample
import com.turn.fieldtest.platform.sensors.SensorSampleKind
import com.turn.fieldtest.platform.sensors.TraceMarkers
import com.turn.fieldtest.platform.sensors.TurnSensorType
import com.turn.fieldtest.platform.wifi.WifiAccessPoint
import com.turn.fieldtest.platform.wifi.WifiFreshness
import com.turn.fieldtest.platform.wifi.WifiScanBatch
import java.io.StringWriter
import kotlin.math.PI
import kotlin.math.abs

/**
 * A finished synthetic walk: the CSV exactly as [RawSensorRecorder] wrote it, parsed back through [RawSensorTrace],
 * plus the scripted heading (map frame of the floor the walker is on), so the replay never sees the script itself.
 */
class SyntheticTrace(val csv: String, private val headings: List<Pair<Long, Double>>, val endNanos: Long) {
    val trace: RawSensorTrace = RawSensorTrace.parse(csv)

    fun headingAt(nanos: Long): Double = headings.lastOrNull { it.first <= nanos }?.second ?: 0.0
}

/**
 * Seeded multi-sensor walk built on [SyntheticPressure] (5 Hz barometer, σ 0.03 hPa): steps with a scripted heading,
 * barometer samples (optionally dropped), Wi-Fi scans and ground-truth markers, all written through
 * [RawSensorRecorder]. Headings are radians in the map frame (0 = +x, PI/2 = +y).
 */
class SyntheticWalk(seed: Long, private val barometer: Boolean = true, private val epochBaseMillis: Long = 1_700_000_000_000L) {
    private val pressure = SyntheticPressure(seed)
    private val headings = mutableListOf<Pair<Long, Double>>()
    private val markers = mutableListOf<Pair<Long, String>>()
    private val scans = mutableListOf<Pair<Long, List<Pair<String, Int>>>>()

    val nowNanos: Long get() = pressure.nowNanos

    private fun heading(radians: Double) = apply { headings += pressure.nowNanos to radians }

    fun arrived(floorId: String) = apply { markers += pressure.nowNanos to TraceMarkers.arrived(floorId) }

    fun walk(seconds: Double, headingRadians: Double) = heading(headingRadians).also { pressure.walk(seconds) }

    fun stand(seconds: Double) = apply { pressure.stand(seconds) }

    fun drift(hpaPerHour: Double) = apply { pressure.drift(hpaPerHour) }

    fun pressureStep(deltaHpa: Double) = apply { pressure.pressureStep(deltaHpa) }

    /** A door/HVAC pulse while walking on at [headingRadians]: the walker does not stop for it. */
    fun pulseWhileWalking(deltaHpa: Double, seconds: Double, headingRadians: Double) = apply {
        pressure.pressureStep(deltaHpa)
        walk(seconds, headingRadians)
        pressure.pressureStep(-deltaHpa)
    }

    /**
     * Switchback stairs by whole [floors] (negative = down), [secondsPerFloor] each, stepping at 1.5 Hz. The walker
     * turns between [headingA] and [headingA] + PI every [flightSeconds], so the track stays inside the stairwell.
     * Ends with ARRIVED [arrivalFloorId].
     */
    fun stairs(floors: Int, arrivalFloorId: String, headingA: Double, secondsPerFloor: Double = 12.0, flightSeconds: Double = 3.0) = apply {
        markers += pressure.nowNanos to TraceMarkers.verticalStart(ConnectorKind.STAIRS, floors > 0)
        val total = abs(floors) * secondsPerFloor
        val parts = (total / flightSeconds).toInt().coerceAtLeast(1)
        repeat(parts) { i ->
            heading(if (i % 2 == 0) headingA else headingA + PI)
            pressure.climb(floors * 3.5 / parts, total / parts)
        }
        arrived(arrivalFloorId)
    }

    /** A step-free lift ride; ends with ARRIVED [arrivalFloorId] when the doors open. */
    fun lift(floors: Int, arrivalFloorId: String, speedMps: Double = 1.0) = apply {
        markers += pressure.nowNanos to TraceMarkers.verticalStart(ConnectorKind.LIFT, floors > 0)
        pressure.lift(floors, speedMps)
        arrived(arrivalFloorId)
    }

    /** A fresh Wi-Fi scan now: (bssid, rssi) pairs. */
    fun wifi(vararg accessPoints: Pair<String, Int>) = apply { scans += pressure.nowNanos to accessPoints.toList() }

    fun build(): SyntheticTrace {
        val built = pressure.build()
        val out = StringWriter()
        val recorder = RawSensorRecorder(out)
        // Sorted by sensor time; at equal times sensor rows first so markers and scans take their clock offset.
        data class Row(val nanos: Long, val order: Int, val write: () -> Unit)
        fun epoch(nanos: Long) = epochBaseMillis + nanos / 1_000_000
        val rows = mutableListOf<Row>()
        if (barometer) built.samples.forEach { s ->
            rows += Row(s.timestampNanos, 0) {
                recorder.sensor(SensorSample(TurnSensorType.BAROMETER, SensorSampleKind.PRESSURE, listOf(s.hpa.toFloat()),
                    s.timestampNanos, epoch(s.timestampNanos), 3, simulated = false))
            }
        }
        built.stepNanos.forEach { t ->
            rows += Row(t, 0) {
                recorder.sensor(SensorSample(TurnSensorType.STEP_DETECTOR, SensorSampleKind.STEP_DETECTED, listOf(1f), t, epoch(t), 3,
                    simulated = false))
            }
        }
        markers.forEach { (t, text) -> rows += Row(t, 1) { recorder.marker(text, epoch(t)) } }
        scans.forEach { (t, aps) ->
            rows += Row(t, 1) {
                recorder.wifi(WifiScanBatch(
                    receivedAtEpochMillis = epoch(t), receivedAtElapsedMillis = t / 1_000_000, requestAccepted = true,
                    resultsUpdated = true, freshness = WifiFreshness.FRESH,
                    accessPoints = aps.map { (bssid, rssi) -> WifiAccessPoint(bssid, null, rssi, 2437, 6, t / 1000, 0) },
                    newestResultAgeMillis = 0, scanThrottlingEnabled = false,
                ))
            }
        }
        rows.sortedWith(compareBy<Row>({ it.nanos }, { it.order })).forEach { it.write() }
        recorder.close()
        return SyntheticTrace(out.toString(), headings.toList(), built.samples.lastOrNull()?.timestampNanos?.coerceAtLeast(pressure.nowNanos) ?: pressure.nowNanos)
    }
}
