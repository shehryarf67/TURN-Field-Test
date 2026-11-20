package com.turn.fieldtest.platform.sensors

import com.turn.fieldtest.core.ConnectorKind
import com.turn.fieldtest.core.FloorTruth
import com.turn.fieldtest.platform.wifi.WifiAccessPoint
import com.turn.fieldtest.platform.wifi.WifiFreshness
import com.turn.fieldtest.platform.wifi.WifiScanBatch
import java.io.StringWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class RawSensorTraceTest {
    private fun sample(type: TurnSensorType, kind: SensorSampleKind, values: List<Float>, nanos: Long, epoch: Long, simulated: Boolean = false) =
        SensorSample(type, kind, values, nanos, epoch, 3, simulated)

    private fun batch(epoch: Long, fresh: Boolean, vararg aps: WifiAccessPoint) = WifiScanBatch(epoch, 50L, true, fresh,
        if (fresh) WifiFreshness.FRESH else WifiFreshness.CACHED_OR_STALE, aps.toList(), 0L, null)

    private fun ap(bssid: String, rssi: Int, micros: Long) = WifiAccessPoint(bssid, "Secret, Net", rssi, 2412, 1, micros, 0L)

    @Test
    fun roundTripsSamplesWifiBatchesAndMarkersThroughTheRealRecorder() {
        val out = StringWriter()
        val samples = listOf(
            sample(TurnSensorType.BAROMETER, SensorSampleKind.PRESSURE, listOf(1013.25f), 5_000_000_000L, 1_000L),
            sample(TurnSensorType.ACCELEROMETER, SensorSampleKind.ACCELERATION, listOf(0.1f, -9.81f, 1.0E-5f), 5_010_000_000L, 1_010L),
            sample(TurnSensorType.STEP_DETECTOR, SensorSampleKind.STEP_DETECTED, emptyList(), 5_020_000_000L, 1_020L),
        )
        RawSensorRecorder(out, flushEvery = 2).use { r ->
            samples.forEach(r::sensor)
            r.sensor(sample(TurnSensorType.BAROMETER, SensorSampleKind.PRESSURE, listOf(1.0f), 5_030_000_000L, 1_030L, simulated = true))
            r.marker(TraceMarkers.arrived("F1"), 1_100L)
            r.wifi(batch(2_000L, true, ap("aa:bb", -61, 5_900_000L), ap("cc:dd", -75, 5_950_000L)))
            r.wifi(batch(2_000L, false, ap("ee:ff", -80, 4_000_000L)))
            r.marker(TraceMarkers.verticalStart(ConnectorKind.STAIRS, up = true), 3_000L)
            r.marker("free note,with comma", 3_500L)
            r.marker(TraceMarkers.arrived("F2"), 18_000L)
        }
        val trace = RawSensorTrace.parse(out.toString())
        assertEquals(0, trace.skippedRows)
        assertEquals(9, trace.rows.size)
        assertEquals(samples, trace.rows.take(3).map { (it as TraceRow.Sensor).toSample() })

        // Receipt times move onto the sensor clock with the last sensor row's offset (5.02 s - 1.02 s = 4 s).
        val offset = 4_000_000_000L
        val arrived = assertIs<TraceRow.Marker>(trace.rows[3])
        assertEquals(1_100_000_000L + offset, arrived.timeNanos)
        assertEquals(TraceMarker.Arrived("F1"), arrived.marker)

        val fresh = assertIs<TraceRow.WifiScan>(trace.rows[4])
        assertEquals(TraceRow.WifiScan(2_000_000_000L + offset, 2_000L, true, listOf(
            TraceAccessPoint("aa:bb", -61, 2412, 5_900_000_000L), TraceAccessPoint("cc:dd", -75, 2412, 5_950_000_000L))), fresh)
        val cached = assertIs<TraceRow.WifiScan>(trace.rows[5])
        assertEquals(false, cached.fresh)
        assertEquals(listOf("ee:ff"), cached.accessPoints.map { it.bssid })

        assertEquals(TraceMarker.VerticalStart(ConnectorKind.STAIRS, true), (trace.rows[6] as TraceRow.Marker).marker)
        assertEquals("free note with comma", (trace.rows[7] as TraceRow.Marker).text)
        assertNull((trace.rows[7] as TraceRow.Marker).marker)

        assertEquals(listOf(
            FloorTruth(1_100_000_000L + offset, "F1"),
            FloorTruth(3_000_000_000L + offset, null, ConnectorKind.STAIRS, up = true),
            FloorTruth(18_000_000_000L + offset, "F2"),
        ), trace.truth())
        assertEquals(trace.truth(), RawSensorTrace.truth(trace.rows))
    }

    @Test
    fun rejectsAWrongOrMissingHeader() {
        assertFailsWith<IllegalArgumentException> { RawSensorTrace.parse("") }
        assertFailsWith<IllegalArgumentException> { RawSensorTrace.parse("source,kind,time\nmarker,NOTE,,1,,STILL\n") }
        assertFailsWith<IllegalArgumentException> { RawSensorTrace.parse("marker,NOTE,,1,,STILL\n") }
        assertEquals(1, RawSensorTrace.parse(RawSensorRecorder.HEADER.replace("\n", "\r\n") + "marker,NOTE,,1,,STILL\r\n").rows.size)
    }

    @Test
    fun skipsUnknownSourcesTypesAndMalformedRows() {
        val csv = RawSensorRecorder.HEADER +
            "ble,ADVERT,1,1,,aa;-50\n" +
            "sensor:THERMOMETER,TEMPERATURE,1,1,3,21.5\n" +
            "sensor:BAROMETER,HUMIDITY,1,1,3,50\n" +
            "sensor:BAROMETER,PRESSURE,1000,1,3,1013.2\n" +
            "wifi,SCAN_RESULT,1,2,,aa:bb;-60\n" +
            "marker,NOTE,,3,,STILL\n" +
            "sensor:BAROMETER,PRESSURE,2000,4,3,10" // cut off mid-number is still a number; the next one is not
        val trace = RawSensorTrace.parse(csv + "\nsensor:BAROMETER,PRESS")
        assertEquals(5, trace.skippedRows)
        assertEquals(3, trace.rows.size)
        assertEquals(listOf(1013.2f), (trace.rows[0] as TraceRow.Sensor).values)
        assertEquals(TraceMarker.Still, (trace.rows[1] as TraceRow.Marker).marker)
        assertEquals(1000L - 1_000_000L + 3_000_000L, trace.rows[1].timeNanos)
    }

    @Test
    fun markerGrammar() {
        assertEquals(TraceMarker.VerticalStart(ConnectorKind.LIFT, false), TraceMarkers.parse("vertical_start lift down"))
        assertEquals(TraceMarker.VerticalStart(ConnectorKind.ESCALATOR, true), TraceMarkers.parse("VERTICAL_START ESCALATOR UP"))
        assertEquals(TraceMarker.VerticalStart(ConnectorKind.RAMP, false), TraceMarkers.parse(TraceMarkers.verticalStart(ConnectorKind.RAMP, false)))
        assertEquals(TraceMarker.Arrived("Level 2"), TraceMarkers.parse("ARRIVED Level 2"))
        assertEquals(TraceMarker.Still, TraceMarkers.parse(" STILL "))
        listOf("", "VERTICAL_START STAIRS", "VERTICAL_START TUNNEL UP", "VERTICAL_START STAIRS SIDEWAYS", "ARRIVED", "STILL now", "start stairs")
            .forEach { assertNull(TraceMarkers.parse(it), it) }
        assertFailsWith<IllegalArgumentException> { TraceMarkers.arrived("F,1") }
        assertFailsWith<IllegalArgumentException> { TraceMarkers.arrived(" ") }
    }

    @Test
    fun truthIgnoresRepeatsStillAndDoubleStarts() {
        fun m(sec: Long, text: String) = TraceRow.Marker(sec * 1_000_000_000L, sec * 1000, text)
        val rows = listOf(m(0, "ARRIVED F1"), m(5, "STILL"), m(8, "ARRIVED F1"), m(10, "VERTICAL_START LIFT UP"),
            m(12, "VERTICAL_START LIFT UP"), m(30, "ARRIVED F3"), m(40, "note"))
        assertEquals(listOf(
            FloorTruth(0, "F1"), FloorTruth(10_000_000_000L, null, ConnectorKind.LIFT, true), FloorTruth(30_000_000_000L, "F3"),
        ), TraceMarkers.truth(rows))
    }
}
