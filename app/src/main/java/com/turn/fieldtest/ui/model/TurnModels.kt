package com.turn.fieldtest.ui.model

import androidx.compose.ui.geometry.Offset

enum class TurnDestination(val title: String, val shortLabel: String, val description: String) {
    VENUES("Venues", "VE", "Venue and floor setup"),
    FLOOR_EDITOR("Floor-plan editor", "FP", "Metric floor geometry editor"),
    RADIO_DIAGNOSTICS("Radio diagnostics", "RD", "Wi-Fi and motion sensor health"),
    SURVEY("Survey", "SV", "Wi-Fi fingerprint collection"),
    LIVE_LOCATE("Live locate", "LL", "Fused Wi-Fi and PDR positioning"),
    EVALUATE("Evaluate", "EV", "Independent checkpoint testing"),
    DATA_EXPORT("Data / export", "DX", "Import, export and backups"),
    SETTINGS("Settings", "ST", "Modes, algorithms and hardware")
}

enum class DataMode(val label: String, val detail: String) {
    DEMO("DEMO / SIMULATED", "Prerecorded Wi-Fi and motion traces"),
    REAL_DEVICE("REAL DEVICE", "Physical radios and Android sensors")
}

enum class EditorTool(val label: String, val code: String) {
    SELECT("Select", "SE"),
    WALKABLE("Walkable polygon", "WA"),
    WALL("Wall", "WL"),
    DOOR("Door", "DR"),
    JUNCTION("Junction", "JN"),
    STAIRS("Stairs", "ST"),
    LIFT("Lift", "LF"),
    ESCALATOR("Escalator", "ES"),
    POI("Room / POI", "PO"),
    QR_ANCHOR("QR anchor", "QR"),
    REFERENCE_POINT("Survey point", "RP")
}

data class FloorSummary(
    val id: String,
    val name: String,
    val level: Int,
    val widthMetres: Double,
    val heightMetres: Double,
    val referencePoints: Int,
    val fingerprintCoveragePercent: Int
)

data class VenueSummary(
    val id: String,
    val name: String,
    val address: String,
    val floors: List<FloorSummary>,
    val lastEdited: String
)

data class WifiAccessPointUi(
    val bssid: String,
    val ssid: String,
    val rssiDbm: Int,
    val frequencyMhz: Int,
    val channel: Int?,
    val ageSeconds: Int,
    val fresh: Boolean
)

data class SensorReadingUi(
    val name: String,
    val value: String,
    val available: Boolean,
    val quality: String
)

data class FingerprintAggregateUi(
    val bssid: String,
    val medianDbm: Double,
    val meanDbm: Double,
    val standardDeviation: Double,
    val range: IntRange,
    val observations: Int,
    val detectionRatePercent: Int,
    val stable: Boolean
)

data class MapPointUi(
    val id: String,
    val floor: String,
    val metres: Offset,
    val label: String = id
)

data class PositionSampleUi(
    val metres: Offset,
    val floor: Int = 0,
    val confidenceMetres: Float = 2.4f
)

data class CorrectionEventUi(
    val time: String,
    val title: String,
    val detail: String,
    val severity: EventSeverity = EventSeverity.INFO
)

enum class EventSeverity { INFO, GOOD, WARNING, ERROR }

data class EvaluationSampleUi(
    val checkpointId: String,
    val wifiErrorMetres: Double?,
    val pdrErrorMetres: Double?,
    val fusedErrorMetres: Double?,
    val constrainedErrorMetres: Double?,
    val floorCorrect: Boolean,
    val confidenceContainedTruth: Boolean,
    val timestamp: String
)

data class ExportItemUi(
    val title: String,
    val description: String,
    val format: String,
    val recordCount: Int
)

object TurnDemoData {
    val venues = listOf(
        VenueSummary(
            id = "VEN-CS-01",
            name = "Computing Block Pilot",
            address = "Research pilot · manually measured",
            floors = listOf(
                FloorSummary("FL-G", "Ground floor", 0, 42.0, 28.0, 14, 86),
                FloorSummary("FL-1", "First floor", 1, 42.0, 28.0, 11, 64)
            ),
            lastEdited = "Today, 14:32"
        ),
        VenueSummary(
            id = "VEN-LAB-02",
            name = "Navigation Lab",
            address = "Calibration space",
            floors = listOf(
                FloorSummary("FL-LAB", "Lab", 0, 18.0, 12.0, 8, 100)
            ),
            lastEdited = "Yesterday, 17:05"
        )
    )

    val wifi = listOf(
        WifiAccessPointUi("9C:3D:CF:11:02:A0", "CAMPUS-WIFI", -48, 5180, 36, 1, true),
        WifiAccessPointUi("9C:3D:CF:11:02:A1", "CAMPUS-WIFI", -57, 5200, 40, 1, true),
        WifiAccessPointUi("54:A6:5C:8E:10:4B", "CS-LABS", -64, 2412, 1, 2, true),
        WifiAccessPointUi("B8:27:EB:44:19:02", "eduroam", -71, 2462, 11, 8, false),
        WifiAccessPointUi("2C:3A:E8:05:76:D1", "IoT-Guest", -79, 5745, 149, 8, false)
    )

    val sensors = listOf(
        SensorReadingUi("Step detector", "event ready", true, "preferred"),
        SensorReadingUi("Step counter", "12,481 total", true, "validation"),
        SensorReadingUi("Game rotation vector", "+31.4° relative", true, "stable"),
        SensorReadingUi("Rotation vector", "heading aid", true, "magnetic drift"),
        SensorReadingUi("Gyroscope", "0.03 rad/s", true, "stable"),
        SensorReadingUi("Accelerometer", "9.79 m/s²", true, "fallback off"),
        SensorReadingUi("Barometer", "1008.4 hPa", true, "optional"),
        SensorReadingUi("Magnetometer", "unavailable", false, "not required")
    )

    val fingerprintAggregates = listOf(
        FingerprintAggregateUi("9C:3D:CF:11:02:A0", -49.0, -49.6, 2.1, -54..-46, 8, 100, true),
        FingerprintAggregateUi("9C:3D:CF:11:02:A1", -58.0, -57.8, 2.8, -63..-53, 8, 100, true),
        FingerprintAggregateUi("54:A6:5C:8E:10:4B", -65.0, -65.9, 3.2, -72..-61, 7, 88, true),
        FingerprintAggregateUi("B8:27:EB:44:19:02", -73.0, -71.4, 8.9, -86..-58, 5, 63, false)
    )

    val rawPdrTrail = listOf(
        Offset(6.0f, 20.5f), Offset(6.8f, 20.2f), Offset(7.6f, 19.9f),
        Offset(8.4f, 19.5f), Offset(9.2f, 19.2f), Offset(10.0f, 18.8f),
        Offset(10.7f, 18.2f), Offset(11.1f, 17.5f), Offset(11.3f, 16.7f),
        Offset(11.6f, 15.9f), Offset(12.0f, 15.1f), Offset(12.5f, 14.4f),
        Offset(13.0f, 13.6f), Offset(13.7f, 13.0f), Offset(14.5f, 12.5f)
    )

    val fusedTrail = listOf(
        Offset(6.0f, 20.5f), Offset(6.8f, 20.4f), Offset(7.7f, 20.2f),
        Offset(8.5f, 20.0f), Offset(9.4f, 19.9f), Offset(10.2f, 19.8f),
        Offset(10.9f, 19.2f), Offset(11.0f, 18.3f), Offset(11.0f, 17.4f),
        Offset(11.2f, 16.6f), Offset(11.6f, 15.8f), Offset(12.1f, 15.1f),
        Offset(12.8f, 14.5f), Offset(13.6f, 14.1f), Offset(14.5f, 14.0f)
    )

    val wifiFixes = listOf(
        PositionSampleUi(Offset(6.2f, 20.1f), confidenceMetres = 3.6f),
        PositionSampleUi(Offset(10.6f, 19.6f), confidenceMetres = 2.8f),
        PositionSampleUi(Offset(14.8f, 14.2f), confidenceMetres = 2.2f)
    )

    val events = listOf(
        CorrectionEventUi("14:42:16", "Fresh Wi-Fi correction", "8 APs · RP-G-07 nearest", EventSeverity.GOOD),
        CorrectionEventUi("14:42:14", "Map constraint applied", "Wall-crossing proposal rejected", EventSeverity.WARNING),
        CorrectionEventUi("14:42:12", "PDR prediction", "Step 38 · 0.73 m", EventSeverity.INFO),
        CorrectionEventUi("14:42:09", "Cached Wi-Fi ignored", "Results unchanged · age 11 s", EventSeverity.WARNING),
        CorrectionEventUi("14:41:58", "QR correction", "Anchor QR-G-ENTRANCE", EventSeverity.GOOD)
    )

    val evaluationSamples = listOf(
        EvaluationSampleUi("CP-G-01", 3.8, 5.7, 1.9, 1.7, true, true, "14:20:11"),
        EvaluationSampleUi("CP-G-04", 4.4, 4.1, 2.3, 2.0, true, true, "14:24:35"),
        EvaluationSampleUi("CP-G-07", 2.6, 6.9, 2.8, 2.5, true, false, "14:29:02"),
        EvaluationSampleUi("CP-1-02", 5.9, null, 3.4, 3.1, false, true, "14:34:46")
    )

    val exportItems = listOf(
        ExportItemUi("Complete venue", "Map assets, geometry, anchors and metadata", "JSON", 1),
        ExportItemUi("Fingerprint database", "Aggregates plus raw snapshot lineage", "JSON / CSV", 112),
        ExportItemUi("Raw Wi-Fi observations", "Every BSSID observation and freshness flag", "CSV", 2_846),
        ExportItemUi("PDR events", "Steps, headings, strides and rejected moves", "CSV", 418),
        ExportItemUi("Position estimates", "Raw PDR, Wi-Fi-only and fused trajectories", "CSV", 526),
        ExportItemUi("Independent test results", "Ground truth and method comparison", "CSV", 24),
        ExportItemUi("Database backup", "Complete local Room database", "BACKUP", 1)
    )
}
