package com.turn.fieldtest.core.replay

import com.turn.fieldtest.data.export.TurnDatabaseExport
import com.turn.fieldtest.data.local.FloorEntity
import com.turn.fieldtest.data.local.VerticalTransitionEntity
import com.turn.fieldtest.data.local.WalkableRegionEntity
import com.turn.fieldtest.platform.sensors.TraceRow
import com.turn.fieldtest.platform.sensors.TurnSensorType
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 4 field replay entry point. Drop recordings into app/src/test/resources/field/ (a raw sensor CSV and a JSON
 * per walk, see docs/FIELD_TEST_PROTOCOL.md) and run
 * `./gradlew.bat testDebugUnitTest --tests "com.turn.fieldtest.core.replay.FieldReplayTest"`.
 * The report is printed and written to app/build/reports/field-replay/report.txt. With no recordings the test is
 * skipped. It measures, it does not judge: targets are printed, never asserted.
 */
class FieldReplayTest {
    @Test
    fun replayFieldRecordings() {
        val dir = File(FIELD_DIR)
        val cases = FieldReplay.loadCases(dir)
        assumeTrue(cases.isNotEmpty(), "No field recordings under ${dir.absolutePath}; nothing to replay")
        val report = FieldReplay.report(cases.flatMap { FieldReplay.run(it) })
        File(REPORT).apply { parentFile.mkdirs() }.writeText(report)
        println(report)
        println("Written to ${File(REPORT).absolutePath}")
    }

    /** The tool itself, on a synthetic stairs walk written in the field layout (CSV + JSON) to a temporary folder. */
    @Test
    fun toolReadsTheFieldLayoutAndScoresAWalk(@TempDir dir: File) {
        val walk = SyntheticWalk(11L).arrived("F1").walk(19.8, 0.0).stairs(1, "F2", headingA = 0.0).walk(20.0, PI).build()
        File(dir, "stairs-up.csv").writeText(walk.csv)
        File(dir, "stairs-up.json").writeText(
            """
            {
              "startFloorId": "F1", "startX": 12.0, "startY": 5.0, "startStdMetres": 0.5, "seeds": [11, 23],
              "floors": [
                {"id": "F1", "level": 0, "walkable": [[[0, 0], [50, 0], [50, 10], [0, 10]]]},
                {"id": "F2", "level": 1, "walkable": [[[100, 0], [140, 0], [140, 10], [100, 10]]]}
              ],
              "links": [{"id": "stairs-12", "kind": "STAIRS", "fromFloorId": "F1", "fromX": 37, "fromY": 5,
                         "toFloorId": "F2", "toX": 135, "toY": 5, "travelTimeSeconds": 15}],
              "wifiAnchors": [{"bssid": "02:00:00:00:02:01", "floorId": "F2", "x": 120, "y": 5}],
              "comment": "unknown keys are ignored"
            }
            """.trimIndent(),
        )
        File(dir, "stairs-up-no-barometer.json").writeText("""{"trace": "stairs-up.csv", "dropBarometer": true, "startFloorId": "F1", "startX": 12, "startY": 5, "seeds": [11]}""")
        File(dir, "notes.txt").writeText("not a recording")
        val (dropped, case) = FieldReplay.loadCases(dir).also { assertEquals(2, it.size) } // sorted by file name
        assertEquals("stairs-up-no-barometer", dropped.name)
        assertTrue(dropped.trace.rows.none { it is TraceRow.Sensor && it.type == TurnSensorType.BAROMETER })
        assertTrue(case.trace.rows.any { it is TraceRow.Sensor && it.type == TurnSensorType.BAROMETER })
        assertEquals("stairs-up", case.name)
        val runs = FieldReplay.run(case, headingOverride = walk::headingAt)
        assertEquals(listOf(11L, 23L), runs.map { it.seed })
        runs.forEach { r ->
            assertTrue(r.barometer)
            assertEquals("F2", r.result.finalFloor, r.result.describe())
            assertEquals(0, r.result.metrics.falseChanges)
            assertEquals(0, r.result.metrics.missedChanges)
        }
        val report = FieldReplay.report(runs)
        assertTrue(report.lines().any { it.startsWith("stairs-up | 11 | yes |") }, report)
        assertTrue(FieldReplay.loadCases(File(dir, "missing")).isEmpty())
        // No gyro rows in a synthetic walk: the gyro heading holds the start heading.
        assertEquals(1.0, FieldReplay.gyroHeading(case.trace, 1.0)(walk.endNanos), 1e-12)
    }

    /** The same walk with floors, walls and links read from a complete database JSON; an unchecked link stays out. */
    @Test
    fun toolReadsFloorsAndLinksFromADatabaseExport(@TempDir dir: File) {
        val walk = SyntheticWalk(23L).arrived("F1").walk(19.8, 0.0).stairs(1, "F2", headingA = 0.0).walk(20.0, PI).build()
        fun floor(id: String, level: Int) = FloorEntity(id, "V1", id, level, 140.0, 60.0, createdAtEpochMillis = 0L, updatedAtEpochMillis = 0L)
        fun region(floorId: String, l: Int, r: Int) = WalkableRegionEntity("R-$floorId", floorId, "hall",
            """[{"x":$l,"y":0},{"x":$r,"y":0},{"x":$r,"y":10},{"x":$l,"y":10}]""", updatedAtEpochMillis = 0L)
        fun link(id: String, fromX: Double, toX: Double, checked: Boolean) = VerticalTransitionEntity(id, "F1", "F2", "STAIRS",
            fromX, 5.0, toX, 5.0, label = id, verifiedOnSite = checked)
        val export = TurnDatabaseExport(exportedAtEpochMillis = 0L,
            floors = listOf(floor("F1", 0), floor("F2", 1), FloorEntity("X", "V2", "Other venue", 5, 1.0, 1.0, createdAtEpochMillis = 0L, updatedAtEpochMillis = 0L)),
            walkableRegions = listOf(region("F1", 0, 50), region("F2", 100, 140)),
            verticalTransitions = listOf(link("stairs-12", 37.0, 135.0, checked = true), link("unchecked", 10.0, 110.0, checked = false)))
        File(dir, "c-block.json").writeText(Json.encodeToString(TurnDatabaseExport.serializer(), export))
        File(dir, "walk.csv").writeText(walk.csv)
        File(dir, "walk.json").writeText("""{"database": "c-block.json", "startFloorId": "F1", "startX": 12, "startY": 5, "startStdMetres": 0.5, "seeds": [23]}""")
        val run = FieldReplay.run(FieldReplay.loadCases(dir).single(), headingOverride = walk::headingAt).single()
        assertEquals("F2", run.result.finalFloor, run.result.describe())
        assertEquals(0, run.result.metrics.falseChanges)
        assertEquals(1, run.result.metrics.latenciesSeconds.size)
    }

    @Test
    fun azimuthOfAQuarterTurnAboutTheVertical() {
        // A rotation of +90 degrees about z (counter-clockwise seen from above) is an azimuth of -90 degrees.
        val s = kotlin.math.sin(PI / 4).toFloat(); val c = kotlin.math.cos(PI / 4).toFloat()
        assertEquals(-PI / 2, FieldReplay.azimuth(listOf(0f, 0f, s, c)), 1e-6)
        assertEquals(0.0, FieldReplay.azimuth(listOf(0f, 0f, 0f)), 1e-9)
    }

    private companion object {
        /** Gradle runs unit tests from the app module folder. */
        const val FIELD_DIR = "src/test/resources/field"
        const val REPORT = "build/reports/field-replay/report.txt"
    }
}
