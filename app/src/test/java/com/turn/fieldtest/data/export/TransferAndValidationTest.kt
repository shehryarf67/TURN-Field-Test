package com.turn.fieldtest.data.export

import com.turn.fieldtest.data.local.FloorEntity
import com.turn.fieldtest.platform.storage.CsvTable
import com.turn.fieldtest.platform.storage.CsvTableCodec
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TransferAndValidationTest {
    @Test
    fun csvRoundTripPreservesCommasQuotesAndNewlines() {
        val expected = CsvTable(
            headers = listOf("id", "notes"),
            rows = listOf(listOf("rp-1", "crowded, \"busy\"\nsecond line")),
        )
        val output = ByteArrayOutputStream()
        CsvTableCodec.encode(expected, output)
        val actual = CsvTableCodec.decode(ByteArrayInputStream(output.toByteArray()))
        assertEquals(expected, actual)
    }

    @Test
    fun completeExportValidationRejectsOrphanFloor() {
        val export = TurnDatabaseExport(
            exportedAtEpochMillis = 1,
            floors = listOf(
                FloorEntity(
                    id = "floor",
                    venueId = "missing",
                    name = "Ground",
                    levelNumber = 0,
                    widthMetres = 10.0,
                    heightMetres = 8.0,
                    createdAtEpochMillis = 1,
                    updatedAtEpochMillis = 1,
                ),
            ),
        )
        assertTrue(export.validationErrors().any { "unknown venue" in it })
    }
}
