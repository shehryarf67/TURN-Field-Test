package com.turn.fieldtest.platform.sensors

import com.turn.fieldtest.platform.wifi.WifiScanBatch
import java.io.Closeable
import java.io.Writer

/**
 * Writes every raw motion, pressure and magnetic sample and every Wi-Fi scan result to one CSV, so stair
 * and lift walks can be analysed offline (barometer floor-change thresholds, step and turn patterns).
 *
 * Columns: source, kind, sensor_time_ns, received_epoch_ms, accuracy, values (semicolon-separated).
 * Wi-Fi rows hold bssid;rssi_dbm;frequency_mhz;fresh. Network names are deliberately not recorded.
 */
class RawSensorRecorder(private val out: Writer, private val flushEvery: Int = 500) : Closeable {
    var rows = 0L
        private set
    private var closed = false

    init { out.write(HEADER) }

    fun sensor(sample: SensorSample) {
        if (sample.simulated) return
        row("sensor:${sample.type.name}", sample.kind.name, sample.sensorTimestampNanos.toString(), sample.receivedAtEpochMillis,
            sample.accuracy.toString(), sample.values.joinToString(";"))
    }

    fun wifi(batch: WifiScanBatch) {
        if (batch.simulated) return
        val fresh = if (batch.isIndependentFreshScan) "1" else "0"
        batch.accessPoints.forEach { ap ->
            row("wifi", "SCAN_RESULT", (ap.scanTimestampMicros * 1000).toString(), batch.receivedAtEpochMillis, "",
                "${ap.bssid};${ap.rssiDbm};${ap.frequencyMhz};$fresh")
        }
    }

    /** A note typed or triggered during the walk, e.g. "start stairs" — commas and newlines are stripped. */
    fun marker(text: String, epochMillis: Long) =
        row("marker", "NOTE", "", epochMillis, "", text.replace(Regex("[,;\\r\\n]+"), " ").trim())

    private fun row(source: String, kind: String, sensorTime: String, epochMillis: Long, accuracy: String, values: String) {
        if (closed) return
        out.write("$source,$kind,$sensorTime,$epochMillis,$accuracy,$values\n")
        rows++
        if (rows % flushEvery == 0L) out.flush()
    }

    override fun close() {
        if (closed) return
        closed = true
        out.flush(); out.close()
    }

    companion object {
        const val HEADER = "source,kind,sensor_time_ns,received_epoch_ms,accuracy,values\n"
    }
}
