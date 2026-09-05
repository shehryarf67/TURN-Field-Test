package com.turn.fieldtest.data.export

import com.turn.fieldtest.platform.storage.CsvTable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

enum class ResearchDataset(val label: String, val table: String?) {
    BACKUP("Complete database JSON", null),
    FINGERPRINTS("Aggregated Wi-Fi fingerprints", "aggregatedWifiFingerprints"),
    WIFI_OBSERVATIONS("Raw Wi-Fi observations", "wifiObservations"),
    WIFI_SNAPSHOTS("Wi-Fi scan snapshots", "wifiScanSnapshots"),
    SURVEY_SESSIONS("Survey sessions and device metadata", "surveySessions"),
    PDR_EVENTS("PDR events", "pdrEvents"),
    POSITIONS("Position estimates", "positionEstimates"),
    CORRECTIONS("Correction events", "correctionEvents"),
    TEST_SAMPLES("Independent test samples", "testSamples"),
}

/** All entity fields are exported, including nullable fields and lineage IDs. */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
object ResearchDataExport {
    private val json = Json { encodeDefaults = true }

    fun requirePhysicalData(value: TurnDatabaseExport) {
        require(value.surveySessions.none { it.dataMode != "REAL_DEVICE" } &&
            value.positioningSessions.none { it.dataMode != "REAL_DEVICE" } &&
            value.sensorSessions.none { it.dataMode != "REAL_DEVICE" } &&
            value.bleObservations.none { it.simulated }) {
            "Database contains simulated sessions; physical export rejected"
        }
    }

    fun csv(value: TurnDatabaseExport, dataset: ResearchDataset): CsvTable {
        requirePhysicalData(value)
        val name = requireNotNull(dataset.table) { "Use JSON for a complete database backup" }
        val root = json.encodeToJsonElement(TurnDatabaseExport.serializer(), value).jsonObject
        val rows = (root.getValue(name) as JsonArray).map { it.jsonObject }
        // An empty dataset still exports its full schema so tools can read the file consistently.
        val descriptor = TurnDatabaseExport.serializer().descriptor
        val listDescriptor = descriptor.getElementDescriptor(descriptor.getElementIndex(name))
        val rowDescriptor = listDescriptor.getElementDescriptor(0)
        val headers = (0 until rowDescriptor.elementsCount).map(rowDescriptor::getElementName)
        return CsvTable(headers, rows.map { row -> headers.map { column -> cell(row[column]) } })
    }

    private fun cell(value: kotlinx.serialization.json.JsonElement?): String? = when (value) {
        null, JsonNull -> null
        is JsonPrimitive -> value.content
        is JsonObject, is JsonArray -> value.toString()
    }
}
