package com.turn.fieldtest.core

import com.turn.fieldtest.platform.sensors.RawSensorRecorder
import com.turn.fieldtest.platform.sensors.SensorSample
import com.turn.fieldtest.platform.sensors.SensorSampleKind
import com.turn.fieldtest.platform.sensors.TurnSensorType
import com.turn.fieldtest.platform.wifi.WifiAccessPoint
import com.turn.fieldtest.platform.wifi.WifiFreshness
import com.turn.fieldtest.platform.wifi.WifiScanBatch
import java.io.StringWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FloorConnectorsTest {
    private fun floor(id: String) = MapFloor(id, listOf(MetricPolygon(listOf(
        MetricPoint(0.0, 0.0), MetricPoint(20.0, 0.0), MetricPoint(20.0, 10.0), MetricPoint(0.0, 10.0)))))

    private fun draft(from: MetricPoint = MetricPoint(2.0, 2.0), to: MetricPoint = MetricPoint(2.0, 2.0), label: String = "Stairs 1",
        seconds: Double = 15.0, toFloor: String = "F2") = ConnectorDraft(ConnectorKind.STAIRS, "F1", from, toFloor, to, label, seconds)

    @Test
    fun validLinkHasNoProblems() {
        assertEquals(emptyList(), ConnectorRules.problems(draft(), floor("F1"), floor("F2"), 0, 1))
    }

    @Test
    fun rejectsSameFloorSameLevelOutsidePointsAndBadValues() {
        assertTrue(ConnectorRules.problems(draft(toFloor = "F1"), floor("F1"), floor("F1"), 0, 0).any { "different floors" in it })
        assertTrue(ConnectorRules.problems(draft(), floor("F1"), floor("F2"), 1, 1).any { "same level" in it })
        assertTrue(ConnectorRules.problems(draft(from = MetricPoint(30.0, 2.0)), floor("F1"), floor("F2"), 0, 1).any { "first floor" in it })
        assertTrue(ConnectorRules.problems(draft(to = MetricPoint(2.0, -1.0)), floor("F1"), floor("F2"), 0, 1).any { "second floor" in it })
        assertTrue(ConnectorRules.problems(draft(), null, floor("F2"), 0, 1).any { "saved walkable map" in it })
        assertTrue(ConnectorRules.problems(draft(label = "  "), floor("F1"), floor("F2"), 0, 1).any { "name" in it })
        assertTrue(ConnectorRules.problems(draft(label = "x".repeat(61)), floor("F1"), floor("F2"), 0, 1).any { "name" in it })
        assertTrue(ConnectorRules.problems(draft(seconds = Double.NaN), floor("F1"), floor("F2"), 0, 1).any { "Travel time" in it })
        assertTrue(ConnectorRules.problems(draft(seconds = 0.5), floor("F1"), floor("F2"), 0, 1).any { "Travel time" in it })
        assertTrue(ConnectorRules.problems(draft().copy(activationRadiusMetres = 9.0), floor("F1"), floor("F2"), 0, 1).any { "radius" in it })
    }

    @Test
    fun defaultTravelTimeScalesWithFloorsCrossed() {
        assertEquals(15.0, ConnectorRules.defaultTravelSeconds(ConnectorKind.STAIRS, 0, 1))
        assertEquals(30.0, ConnectorRules.defaultTravelSeconds(ConnectorKind.STAIRS, 3, 1))
        assertEquals(25.0, ConnectorRules.defaultTravelSeconds(ConnectorKind.LIFT, 2, 2))
        assertEquals(ConnectorKind.LIFT, ConnectorKind.parse("elevator"))
        assertEquals(ConnectorKind.RAMP, ConnectorKind.parse("RAMP"))
        assertNull(ConnectorKind.parse("TELEPORTER"))
    }

    @Test
    fun readsLandingNumbersOnlyFromStairLandingNames() {
        assertEquals(2, StairLandingMatcher.landingNumber("Stairs landing, C-F4 (2)"))
        assertEquals(12, StairLandingMatcher.landingNumber("  stairs landing, near lab (12) "))
        assertNull(StairLandingMatcher.landingNumber("Corridor, C-F4 (2)"))
        assertNull(StairLandingMatcher.landingNumber("Stairs landing, C-F4"))
    }

    @Test
    fun suggestsOnlyAdjacentLevelsWithMatchingNumbersAndSkipsExistingLinks() {
        val ground = FloorLandings("G", 0, mapOf(1 to MetricPoint(1.0, 1.0), 2 to MetricPoint(9.0, 1.0)))
        val first = FloorLandings("F1", 1, mapOf(1 to MetricPoint(1.2, 1.1), 3 to MetricPoint(5.0, 5.0)))
        val third = FloorLandings("F3", 3, mapOf(1 to MetricPoint(1.0, 1.0)))
        val suggestions = StairLandingMatcher.suggest(listOf(third, first, ground), emptyList())
        assertEquals(1, suggestions.size) // G↔F1 share landing 1 only; F1↔F3 are not adjacent levels.
        with(suggestions.single()) {
            assertEquals("G", fromFloorId); assertEquals("F1", toFloorId); assertEquals("Stairs 1", label)
            assertEquals(ConnectorKind.STAIRS, kind); assertEquals(15.0, travelTimeSeconds)
        }
        // An existing link drawn in the opposite direction at the same spots counts as the same link.
        val reverse = ConnectorDraft(ConnectorKind.STAIRS, "F1", MetricPoint(1.0, 1.5), "G", MetricPoint(1.5, 1.0), "Main stairs", 15.0)
        assertEquals(emptyList(), StairLandingMatcher.suggest(listOf(ground, first), listOf(reverse)))
        // A link elsewhere does not hide the suggestion.
        val elsewhere = reverse.copy(from = MetricPoint(15.0, 8.0))
        assertEquals(1, StairLandingMatcher.suggest(listOf(ground, first), listOf(elsewhere)).size)
    }

    @Test
    fun sameEndsMatchesEitherDirectionButNotOtherFloorsOrSpots() {
        val link = draft()
        assertTrue(link.sameEndsAs(link.copy(label = "Renamed", kind = ConnectorKind.LIFT)))
        assertTrue(link.sameEndsAs(ConnectorDraft(ConnectorKind.STAIRS, "F2", MetricPoint(2.5, 2.0), "F1", MetricPoint(2.0, 2.5), "Back", 15.0)))
        assertTrue(!link.sameEndsAs(link.copy(to = MetricPoint(9.0, 2.0))))
        assertTrue(!link.sameEndsAs(link.copy(toFloorId = "F3")))
        val reversed = ConnectorDraft(ConnectorKind.ESCALATOR, "F2", MetricPoint(2.0, 2.0), "F1", MetricPoint(2.0, 2.0), "Down", 12.0)
        assertTrue(!link.sameEndsAs(reversed, allowReversed = false))
        assertTrue(link.sameEndsAs(link, allowReversed = false))
    }

    @Test
    fun rawRecorderWritesSensorAndWifiRowsWithoutNetworkNames() {
        val out = StringWriter()
        val recorder = RawSensorRecorder(out, flushEvery = 2)
        recorder.sensor(SensorSample(TurnSensorType.BAROMETER, SensorSampleKind.PRESSURE, listOf(1013.25f), 42L, 1_000L, 3, simulated = false))
        recorder.sensor(SensorSample(TurnSensorType.BAROMETER, SensorSampleKind.PRESSURE, listOf(1.0f), 43L, 1_001L, 3, simulated = true))
        recorder.wifi(WifiScanBatch(2_000L, 50L, true, true, WifiFreshness.FRESH, listOf(
            WifiAccessPoint("aa:bb", "Secret, Network", -61, 2412, 1, 7L, 0L)), 0L, null))
        recorder.marker("start stairs,\nfloor 1", 3_000L)
        recorder.close()
        recorder.sensor(SensorSample(TurnSensorType.BAROMETER, SensorSampleKind.PRESSURE, listOf(1.0f), 44L, 1_002L, 3, simulated = false))
        val lines = out.toString().trim().lines()
        assertEquals(RawSensorRecorder.HEADER.trim(), lines[0])
        assertEquals("sensor:BAROMETER,PRESSURE,42,1000,3,1013.25", lines[1])
        assertEquals("wifi,SCAN_RESULT,7000,2000,,aa:bb;-61;2412;1", lines[2])
        assertEquals("marker,NOTE,,3000,,start stairs floor 1", lines[3])
        assertEquals(4, lines.size) // simulated and post-close samples are dropped
        assertEquals(3L, recorder.rows)
        assertTrue("Secret" !in out.toString())
    }
}
