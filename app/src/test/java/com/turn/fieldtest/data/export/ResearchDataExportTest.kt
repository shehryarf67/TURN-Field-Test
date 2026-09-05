package com.turn.fieldtest.data.export

import com.turn.fieldtest.data.local.WifiObservationEntity
import com.turn.fieldtest.data.local.SensorSessionEntity
import com.turn.fieldtest.platform.storage.CsvTableCodec
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ResearchDataExportTest {
    @Test
    fun rawCsvPreservesCachedRowsQuotesNewlinesAndNullableFields() {
        val observation = WifiObservationEntity("obs", "snap", "session", "rp", "aa:bb:cc:dd:ee:ff",
            "Lab, \"A\"\nFloor", -62, 2412, null, 1000, 2000, false)
        val table = ResearchDataExport.csv(TurnDatabaseExport(exportedAtEpochMillis = 3000,
            wifiObservations = listOf(observation)), ResearchDataset.WIFI_OBSERVATIONS)
        val bytes = ByteArrayOutputStream()
        CsvTableCodec.encode(table, bytes)
        val decoded = CsvTableCodec.decode(bytes.toByteArray().inputStream())
        val row = decoded.headers.zip(decoded.rows.single()).toMap()
        assertEquals(observation.ssid, row["ssid"])
        assertEquals("false", row["isFresh"])
        assertEquals("", row["channel"])
        assertEquals("session", row["surveySessionId"])
        assertEquals("-62", row["rssiDbm"])
    }

    @Test
    fun emptyDatasetsRetainAStableFullHeader() {
        ResearchDataset.entries.filter { it.table != null }.forEach { dataset ->
            val table = ResearchDataExport.csv(TurnDatabaseExport(exportedAtEpochMillis = 0), dataset)
            assertTrue("id" in table.headers)
            assertTrue(table.rows.isEmpty())
        }
    }

    @Test
    fun realExportRefusesSimulatedSensorSessions() {
        val value = TurnDatabaseExport(exportedAtEpochMillis = 0, sensorSessions = listOf(
            SensorSessionEntity("demo", "DEMO", "STEP_DETECTOR", "GAME_ROTATION_VECTOR", 0.73,
                startedAtEpochMillis = 0)))
        assertFailsWith<IllegalArgumentException> { ResearchDataExport.requirePhysicalData(value) }
    }
}
