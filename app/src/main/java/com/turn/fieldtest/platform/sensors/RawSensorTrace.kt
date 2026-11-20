package com.turn.fieldtest.platform.sensors

import com.turn.fieldtest.core.ConnectorKind
import com.turn.fieldtest.core.FloorTruth
import java.io.Reader
import java.io.StringReader

/**
 * One parsed row of a [RawSensorRecorder] CSV. [timeNanos] is on the sensor (elapsed-realtime) clock so rows can be
 * replayed in order; [epochMillis] is the wall-clock receipt time the recorder wrote.
 */
sealed interface TraceRow {
    val timeNanos: Long
    val epochMillis: Long

    /** A motion, pressure or magnetic sample, exactly as recorded. */
    data class Sensor(val type: TurnSensorType, val kind: SensorSampleKind, val values: List<Float>, val sensorNanos: Long,
                      override val epochMillis: Long, val accuracy: Int) : TraceRow {
        override val timeNanos get() = sensorNanos
        fun toSample() = SensorSample(type, kind, values, sensorNanos, epochMillis, accuracy, simulated = false)
    }

    /**
     * One Wi-Fi batch: consecutive `wifi` rows with the same receipt time and freshness. [timeNanos] is the receipt
     * time moved onto the sensor clock (see [RawSensorTrace]); each access point keeps its own scan time.
     */
    data class WifiScan(override val timeNanos: Long, override val epochMillis: Long, val fresh: Boolean,
                        val accessPoints: List<TraceAccessPoint>) : TraceRow

    /** A note from the walk; [marker] is its [TraceMarkers] meaning, or null for free text. */
    data class Marker(override val timeNanos: Long, override val epochMillis: Long, val text: String) : TraceRow {
        val marker: TraceMarker? get() = TraceMarkers.parse(text)
    }
}

/** A recorded access point. Network names are never recorded. */
data class TraceAccessPoint(val bssid: String, val rssiDbm: Int, val frequencyMhz: Int, val scanNanos: Long)

/** Ground-truth notes understood by [TraceMarkers]. */
sealed interface TraceMarker {
    data class VerticalStart(val kind: ConnectorKind, val up: Boolean) : TraceMarker
    data class Arrived(val floorId: String) : TraceMarker
    data object Still : TraceMarker
}

/**
 * Marker grammar written through [RawSensorRecorder.marker]: `VERTICAL_START <STAIRS|LIFT|ESCALATOR|RAMP> <UP|DOWN>`,
 * `ARRIVED <floorId>` and `STILL`. Keywords are case-insensitive; other notes are free text.
 */
object TraceMarkers {
    const val STILL = "STILL"
    private val whitespace = Regex("\\s+")

    fun verticalStart(kind: ConnectorKind, up: Boolean) = "VERTICAL_START ${kind.name} ${if (up) "UP" else "DOWN"}"

    fun arrived(floorId: String): String {
        require(floorId.isNotBlank() && floorId == floorId.trim()) { "floorId must be non-blank without outer spaces" }
        require(floorId.none { it in ",;\r\n" }) { "floorId must not contain , ; or line breaks (the recorder strips them)" }
        return "ARRIVED $floorId"
    }

    fun parse(text: String): TraceMarker? {
        val trimmed = text.trim()
        val tokens = trimmed.split(whitespace)
        return when (tokens.first().uppercase()) {
            "VERTICAL_START" -> {
                if (tokens.size != 3) return null
                val kind = ConnectorKind.parse(tokens[1]) ?: return null
                when (tokens[2].uppercase()) { "UP" -> TraceMarker.VerticalStart(kind, true); "DOWN" -> TraceMarker.VerticalStart(kind, false); else -> null }
            }
            "ARRIVED" -> trimmed.substringAfter(tokens.first()).trim().takeIf { it.isNotEmpty() }?.let { TraceMarker.Arrived(it) }
            STILL -> if (tokens.size == 1) TraceMarker.Still else null
            else -> null
        }
    }

    /**
     * Floor truth from the markers: `ARRIVED` starts a stretch on that floor, `VERTICAL_START` starts a transit
     * (null floor) until the next `ARRIVED`. `STILL`, repeats and free text change nothing.
     */
    fun truth(rows: List<TraceRow>): List<FloorTruth> {
        val out = mutableListOf<FloorTruth>()
        rows.filterIsInstance<TraceRow.Marker>().sortedBy { it.timeNanos }.forEach { row ->
            val last = out.lastOrNull()
            when (val m = row.marker) {
                is TraceMarker.Arrived -> if (last?.floorId != m.floorId) out += FloorTruth(row.timeNanos, m.floorId)
                is TraceMarker.VerticalStart -> if (last == null || last.floorId != null) out += FloorTruth(row.timeNanos, null, m.kind, m.up)
                TraceMarker.Still, null -> Unit
            }
        }
        return out
    }
}

/**
 * A parsed [RawSensorRecorder] CSV. Pure JVM so replay tests and field analysis share one reader.
 *
 * - A file whose first line is not [RawSensorRecorder.HEADER] is rejected.
 * - Rows with an unknown source, sensor type or kind, and malformed rows (such as a line cut off when the app was
 *   killed), are skipped and counted in [skippedRows].
 * - Wi-Fi and marker rows carry only wall-clock receipt time, so they are placed on the sensor clock with the
 *   offset of the nearest earlier sensor row (else the first sensor row; with no sensor rows, epoch time in nanos).
 */
class RawSensorTrace(val rows: List<TraceRow>, val skippedRows: Int) {
    fun truth(): List<FloorTruth> = TraceMarkers.truth(rows)

    companion object {
        private sealed interface Parsed {
            val epochMillis: Long
            class Sensor(val row: TraceRow.Sensor) : Parsed { override val epochMillis get() = row.epochMillis }
            class Wifi(override val epochMillis: Long, val fresh: Boolean, val ap: TraceAccessPoint) : Parsed
            class Marker(override val epochMillis: Long, val text: String) : Parsed
        }

        fun truth(rows: List<TraceRow>): List<FloorTruth> = TraceMarkers.truth(rows)

        fun parse(text: String): RawSensorTrace = parse(StringReader(text))

        fun parse(reader: Reader): RawSensorTrace = reader.buffered().use { input ->
            val header = input.readLine()?.trimEnd('\r')
            require(header == RawSensorRecorder.HEADER.trimEnd('\n')) { "Not a raw sensor recording (header was: $header)" }
            var skipped = 0
            val parsed = mutableListOf<Parsed>()
            input.lineSequence().map { it.trimEnd('\r') }.filter { it.isNotEmpty() }.forEach { line ->
                val row = parseLine(line)
                if (row == null) skipped++ else parsed += row
            }
            RawSensorTrace(assemble(parsed), skipped)
        }

        private fun parseLine(line: String): Parsed? {
            val f = line.split(',', limit = 6)
            if (f.size != 6) return null
            val (source, kind, sensorTime, epochText, accuracy) = f
            val values = f[5]
            val epoch = epochText.toLongOrNull() ?: return null
            return when {
                source.startsWith("sensor:") -> {
                    val type = TurnSensorType.entries.firstOrNull { it.name == source.removePrefix("sensor:") } ?: return null
                    val sampleKind = SensorSampleKind.entries.firstOrNull { it.name == kind } ?: return null
                    val floats = if (values.isEmpty()) emptyList() else values.split(';').map { it.toFloatOrNull() ?: return null }
                    Parsed.Sensor(TraceRow.Sensor(type, sampleKind, floats, sensorTime.toLongOrNull() ?: return null, epoch,
                        accuracy.toIntOrNull() ?: return null))
                }
                source == "wifi" -> {
                    val v = values.split(';')
                    if (kind != "SCAN_RESULT" || v.size != 4 || v[3] !in setOf("0", "1")) return null
                    Parsed.Wifi(epoch, v[3] == "1", TraceAccessPoint(v[0], v[1].toIntOrNull() ?: return null,
                        v[2].toIntOrNull() ?: return null, sensorTime.toLongOrNull() ?: return null))
                }
                source == "marker" -> if (kind == "NOTE") Parsed.Marker(epoch, values) else null
                else -> null
            }
        }

        private fun assemble(parsed: List<Parsed>): List<TraceRow> {
            fun offsetOf(s: TraceRow.Sensor) = s.sensorNanos - s.epochMillis * 1_000_000
            var offset = parsed.firstNotNullOfOrNull { (it as? Parsed.Sensor)?.row }?.let(::offsetOf) ?: 0L
            val rows = mutableListOf<TraceRow>()
            var batch: TraceRow.WifiScan? = null
            fun flush() { batch?.let { rows += it }; batch = null }
            for (p in parsed) {
                when (p) {
                    is Parsed.Sensor -> { flush(); offset = offsetOf(p.row); rows += p.row }
                    is Parsed.Marker -> { flush(); rows += TraceRow.Marker(p.epochMillis * 1_000_000 + offset, p.epochMillis, p.text) }
                    is Parsed.Wifi -> {
                        val open = batch
                        batch = if (open != null && open.epochMillis == p.epochMillis && open.fresh == p.fresh) open.copy(accessPoints = open.accessPoints + p.ap)
                            else { flush(); TraceRow.WifiScan(p.epochMillis * 1_000_000 + offset, p.epochMillis, p.fresh, listOf(p.ap)) }
                    }
                }
            }
            flush()
            return rows
        }
    }
}
