package com.turn.fieldtest.core.replay

import com.turn.fieldtest.core.ConnectorKind
import com.turn.fieldtest.core.FloorLinkInput
import com.turn.fieldtest.core.FloorTopologyBuilder
import com.turn.fieldtest.core.MapFloor
import com.turn.fieldtest.core.MapHeading
import com.turn.fieldtest.core.MetricMap
import com.turn.fieldtest.core.MetricPoint
import com.turn.fieldtest.core.MetricPolygon
import com.turn.fieldtest.core.WifiMatchResult
import com.turn.fieldtest.core.WifiNeighbour
import com.turn.fieldtest.core.WallSegment
import com.turn.fieldtest.core.WifiScanFreshness
import com.turn.fieldtest.data.export.TurnDatabaseExport
import com.turn.fieldtest.data.repository.decodePolygon
import com.turn.fieldtest.platform.sensors.RawSensorTrace
import com.turn.fieldtest.platform.sensors.SensorSampleKind
import com.turn.fieldtest.platform.sensors.TraceRow
import com.turn.fieldtest.platform.sensors.TurnSensorType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Phase 4 field replay: one recording is a raw sensor CSV from *Diagnostics → Raw sensor recording* plus a small JSON
 * describing the building around it. Floors are walkable polygons in each floor's own map frame (metres, as Map setup
 * shows them); links are the floor links (positions in each end's frame); Wi-Fi anchors are optional BSSIDs that
 * stand for "this access point is strongest here". See docs/FIELD_TEST_PROTOCOL.md for the layout and how to run it.
 */
@Serializable
data class FieldCase(
    /** The CSV next to the JSON; defaults to the JSON's name with .csv. */
    val trace: String? = null,
    val startFloorId: String,
    val startX: Double,
    val startY: Double,
    val startStdMetres: Double = 1.0,
    /** Map heading (radians, 0 = +x, PI/2 = +y) the phone faces when the recording starts; turns come from the gyro. */
    val startHeadingRadians: Double = 0.0,
    val strideMetres: Double = 0.7,
    /** Replay as if the phone had no barometer (pressure rows dropped), to compare both paths on one walk. */
    val dropBarometer: Boolean = false,
    val seeds: List<Long> = listOf(11L, 23L, 37L),
    /**
     * A complete database JSON (Data & export → Save complete database JSON) next to this file: its floors, walkable
     * polygons, walls and floor links (as saved: enabled, checked) are used, plus any [floors] and [links] written here.
     */
    val database: String? = null,
    /** For links from [database] only: also use links not yet checked on site (the app's option, default off). */
    val useUncheckedLinks: Boolean = false,
    val floors: List<FieldFloor> = emptyList(),
    val links: List<FieldLink> = emptyList(),
    val wifiAnchors: List<FieldWifiAnchor> = emptyList(),
    /** Floor elevations in metres (floor id -> height), on top of the database's floors.elevationMetres (schema 8). */
    val elevations: Map<String, Double> = emptyMap(),
    /** Threshold overrides as the Diagnostics editor stores them (key -> value), e.g. copied from a CONFIG event. */
    val thresholds: Map<String, Double> = emptyMap(),
)

/** One floor: its id (as in ARRIVED markers), level number and walkable polygons as [[x, y], ...]. */
@Serializable
data class FieldFloor(val id: String, val level: Int, val walkable: List<List<List<Double>>>)

@Serializable
data class FieldLink(
    val id: String, val kind: String,
    val fromFloorId: String, val fromX: Double, val fromY: Double,
    val toFloorId: String, val toX: Double, val toY: Double,
    val radiusMetres: Double = 2.0, val travelTimeSeconds: Double = 15.0, val bidirectional: Boolean = true,
)

@Serializable
data class FieldWifiAnchor(val bssid: String, val floorId: String, val x: Double, val y: Double)

/** One recording ready to replay. */
class LoadedFieldCase(val name: String, val case: FieldCase, val trace: RawSensorTrace, val database: TurnDatabaseExport? = null)

/** One replay of one recording with one seed. */
data class FieldRun(val name: String, val seed: Long, val barometer: Boolean, val result: ReplayResult)

object FieldReplay {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Every `*.json` in [dir] that describes a recording (it has `startFloorId`), with its CSV; other JSON files (a
     * database export) are skipped. An empty list when the folder is missing or holds no recordings.
     */
    fun loadCases(dir: File): List<LoadedFieldCase> =
        dir.listFiles { f -> f.isFile && f.extension.equals("json", ignoreCase = true) }.orEmpty().sortedBy { it.name }.mapNotNull { file ->
            val element = json.parseToJsonElement(file.readText())
            if ((element as? JsonObject)?.containsKey("startFloorId") != true) return@mapNotNull null
            val case = json.decodeFromJsonElement(FieldCase.serializer(), element)
            val csv = File(dir, case.trace ?: (file.nameWithoutExtension + ".csv"))
            require(csv.isFile) { "${file.name}: recording ${csv.name} not found next to it" }
            val database = case.database?.let { name ->
                val db = File(dir, name)
                require(db.isFile) { "${file.name}: database $name not found next to it" }
                json.decodeFromString(TurnDatabaseExport.serializer(), db.readText())
            }
            val parsed = csv.reader().use { RawSensorTrace.parse(it) }
            val trace = if (!case.dropBarometer) parsed else RawSensorTrace(
                parsed.rows.filterNot { it is TraceRow.Sensor && it.type == TurnSensorType.BAROMETER }, parsed.skippedRows)
            LoadedFieldCase(file.nameWithoutExtension, case, trace, database)
        }

    /** Replays [loaded] once per seed. [headingOverride] replaces the gyro heading (self-test with a scripted walk). */
    fun run(loaded: LoadedFieldCase, headingOverride: ((Long) -> Double)? = null): List<FieldRun> {
        val case = loaded.case
        val db = loaded.database
        val venueId = db?.floors?.firstOrNull { it.id == case.startFloorId }?.venueId
        val dbFloors = db?.floors.orEmpty().filter { it.venueId == venueId }.mapNotNull { floor ->
            val polygons = db!!.walkableRegions.filter { it.floorId == floor.id && it.enabled }.map { decodePolygon(it.polygonJson) }
            if (polygons.isEmpty()) return@mapNotNull null
            val walls = db.wallSegments.filter { it.floorId == floor.id && it.enabled }
                .map { WallSegment(it.id, MetricPoint(it.startXMetres, it.startYMetres), MetricPoint(it.endXMetres, it.endYMetres)) }
            Triple(floor.id, floor.levelNumber, MapFloor(floor.id, polygons, walls))
        }
        val written = case.floors.map { f ->
            Triple(f.id, f.level, MapFloor(f.id, f.walkable.map { poly -> MetricPolygon(poly.map { MetricPoint(it[0], it[1]) }) }))
        }
        val allFloors = (dbFloors + written).associateBy { it.first } // a floor written here replaces the database's
        val floors = allFloors.mapValues { it.value.third }
        val map = MetricMap(floors)
        val dbLinks = db?.verticalTransitions.orEmpty().filter { it.fromFloorId in floors && it.toFloorId in floors }.mapNotNull { t ->
            val kind = ConnectorKind.parse(t.kind) ?: return@mapNotNull null
            FloorLinkInput(t.id, kind, t.fromFloorId, MetricPoint(t.fromXMetres, t.fromYMetres), t.toFloorId, MetricPoint(t.toXMetres, t.toYMetres),
                t.activationRadiusMetres, t.bidirectional, t.enabled, t.verifiedOnSite, t.travelTimeSeconds, t.label.ifBlank { t.id }, t.accessible)
        }
        val links = dbLinks + case.links.map { l ->
            val kind = requireNotNull(ConnectorKind.parse(l.kind)) { "${loaded.name}: link ${l.id} has unknown kind ${l.kind}" }
            FloorLinkInput(l.id, kind, l.fromFloorId, MetricPoint(l.fromX, l.fromY), l.toFloorId, MetricPoint(l.toX, l.toY),
                l.radiusMetres, l.bidirectional, enabled = true, verifiedOnSite = true, travelTimeSeconds = l.travelTimeSeconds, label = l.id)
        }
        val build = FloorTopologyBuilder.build(case.startFloorId, floors, allFloors.mapValues { it.value.second }, links, case.useUncheckedLinks)
        require(!build.topology.isEmpty) { "${loaded.name}: no usable floor link from ${case.startFloorId} (${build.excluded})" }
        require(loaded.trace.truth().isNotEmpty()) { "${loaded.name}: no ARRIVED marker; tap 'Arrived on…' at the start" }
        val heading = headingOverride ?: gyroHeading(loaded.trace, case.startHeadingRadians)
        val matcher = anchorMatcher(case.wifiAnchors)
        val barometer = loaded.trace.rows.any { it is TraceRow.Sensor && it.type == TurnSensorType.BAROMETER }
        val dbElevations = db?.floors.orEmpty().filter { it.venueId == venueId }.associate { it.id to it.elevationMetres }
        val elevations = com.turn.fieldtest.core.FloorElevations.of(build.topology.levels,
            build.topology.levels.keys.associateWith { case.elevations[it] ?: dbElevations[it] })
        val detection = com.turn.fieldtest.core.FloorDetectionOverrides.apply(case.thresholds)
        require(detection.rejected.isEmpty()) { "${loaded.name}: thresholds not usable: ${detection.rejected}" }
        val harness = FloorReplayHarness(map, build.topology, detectionConfig = detection.config, elevations = elevations)
        val start = ReplayStart(case.startFloorId, MetricPoint(case.startX, case.startY), case.startStdMetres)
        return case.seeds.map { seed ->
            FieldRun(loaded.name, seed, barometer, harness.replay(loaded.trace, start, seed, heading, matcher, case.strideMetres))
        }
    }

    /**
     * Map heading from the game rotation vector (else the rotation vector): the change in Android azimuth since the
     * first sample, turned into the map frame from [startHeading]. Portrait, phone held in front; no samples gives
     * [startHeading] throughout.
     */
    fun gyroHeading(trace: RawSensorTrace, startHeading: Double): (Long) -> Double {
        val sensors = trace.rows.filterIsInstance<TraceRow.Sensor>().filter { it.values.size >= 3 }
        val chosen = sensors.filter { it.type == TurnSensorType.GAME_ROTATION_VECTOR && it.kind == SensorSampleKind.RELATIVE_ROTATION_VECTOR }
            .ifEmpty { sensors.filter { it.type == TurnSensorType.ROTATION_VECTOR && it.kind == SensorSampleKind.ROTATION_VECTOR } }
            .sortedBy { it.timeNanos }
        if (chosen.isEmpty()) return { startHeading }
        val times = LongArray(chosen.size) { chosen[it].timeNanos }
        val azimuths = DoubleArray(chosen.size) { azimuth(chosen[it].values) }
        return { nanos ->
            val i = times.binarySearch(nanos).let { if (it >= 0) it else -it - 2 }.coerceAtLeast(0)
            MapHeading.fromRelativeAzimuth(startHeading, azimuths[0], azimuths[i])
        }
    }

    /** Android azimuth (clockwise from the device's reference) of a rotation-vector sample x, y, z[, w]. */
    fun azimuth(v: List<Float>): Double {
        val x = v[0].toDouble(); val y = v[1].toDouble(); val z = v[2].toDouble()
        val w = if (v.size >= 4) v[3].toDouble() else sqrt((1.0 - x * x - y * y - z * z).coerceAtLeast(0.0))
        return atan2(2.0 * (x * y - z * w), 1.0 - 2.0 * (x * x + z * z))
    }

    /** The strongest anchored access point in a scan stands for its anchor; RSSI -40 dBm is a confident match. */
    fun anchorMatcher(anchors: List<FieldWifiAnchor>): (TraceRow.WifiScan) -> WifiMatchResult? {
        val byBssid = anchors.associateBy { it.bssid.lowercase(Locale.ROOT) }
        if (byBssid.isEmpty()) return { null }
        return { scan ->
            scan.accessPoints.filter { it.bssid.lowercase(Locale.ROOT) in byBssid }.maxByOrNull { it.rssiDbm }?.let { ap ->
                val a = byBssid.getValue(ap.bssid.lowercase(Locale.ROOT))
                val point = MetricPoint(a.x, a.y)
                WifiMatchResult(point, a.floorId, listOf(WifiNeighbour("anchor-${a.bssid}", a.floorId, point, 1.0, 1.0, 0.0, 1)),
                    ((ap.rssiDbm + 90) / 50.0).coerceIn(0.0, 1.0), 2.0, floorAgreement = 1.0, weightedMatchDistance = 1.0,
                    freshness = WifiScanFreshness.FRESH, unlikeDatabase = false)
            }
        }
    }

    /** A plain-text table: one line per run, then the targets from docs/FIELD_TEST_PROTOCOL.md. */
    fun report(runs: List<FieldRun>): String = buildString {
        fun f(v: Double) = if (v.isNaN()) "-" else "%.2f".format(Locale.ROOT, v)
        appendLine("TURN field replay (${runs.size} run(s))")
        appendLine("recording | seed | barometer | accuracy | latencies s | false changes (/h) | reverts | missed | final floor")
        runs.forEach { r ->
            val m = r.result.metrics
            appendLine(listOf(r.name, r.seed, if (r.barometer) "yes" else "no", f(m.accuracy),
                m.latenciesSeconds.joinToString(" ") { f(it) }.ifEmpty { "-" }, "${m.falseChanges} (${f(m.falseChangesPerHour)})",
                m.reverts, m.missedChanges, r.result.finalFloor).joinToString(" | "))
        }
        appendLine("Targets: accuracy >= 0.95, every latency <= 6 s, 0 false changes, 0 reverts, 0 missed.")
    }
}
