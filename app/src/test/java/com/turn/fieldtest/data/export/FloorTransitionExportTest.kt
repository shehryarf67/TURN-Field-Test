package com.turn.fieldtest.data.export

import com.turn.fieldtest.core.BarometerHealth
import com.turn.fieldtest.core.FloorTrackingEvent
import com.turn.fieldtest.core.FloorTrackingEventType
import com.turn.fieldtest.data.local.FloorEntity
import com.turn.fieldtest.data.local.FloorTransitionEventEntity
import com.turn.fieldtest.data.local.PositioningSessionEntity
import com.turn.fieldtest.data.local.VenueEntity
import com.turn.fieldtest.data.local.VerticalTransitionEntity
import com.turn.fieldtest.data.repository.FloorEventLog
import com.turn.fieldtest.data.repository.toEntity
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Plan Step 10: coordinator events map onto floor_transition_events rows and travel in export schema 4. */
class FloorTransitionExportTest {
    private val venue = VenueEntity("v", "Home", createdAtEpochMillis = 1, updatedAtEpochMillis = 1)
    private val floors = listOf("f3" to 3, "f4" to 4).map { (id, level) ->
        FloorEntity(id, "v", id, level, 20.0, 20.0, createdAtEpochMillis = 1, updatedAtEpochMillis = 1)
    }
    private val link = VerticalTransitionEntity("l", "f3", "f4", "STAIRS", 1.0, 1.0, 1.0, 1.0)
    private val session = PositioningSessionEntity("POS-1", "v", initialFloorId = "f3", dataMode = "REAL_DEVICE",
        particleCount = 500, startedAtEpochMillis = 1_000)

    private fun event(type: FloorTrackingEventType, atNanos: Long, from: String? = null, to: String? = null) =
        FloorTrackingEvent(type, atNanos, from, to, movedShare = 0.7, floorVoteAfter = 0.9, heightDeltaMetres = 3.4,
            pressureHpa = 1001.5, barometerHealth = BarometerHealth.OK, detail = "d")

    private fun row(sequence: Long, from: String? = "f3", to: String? = "f4", link: String? = null) =
        FloorTransitionEventEntity("FTE-POS-1-$sequence", "POS-1", sequence, "APPLIED", from, to, link, 0.7, 0.9, 3.4,
            1001.5, "OK", 5_000_000_000, 10_000, "")

    private fun export(vararg rows: FloorTransitionEventEntity) = TurnDatabaseExport(exportedAtEpochMillis = 0,
        venues = listOf(venue), floors = floors, verticalTransitions = listOf(link), positioningSessions = listOf(session),
        floorTransitionEvents = rows.toList())

    @Test fun eventMapsOneToOneOntoTheRowWithWallClockFromTheDrainTime() {
        val row = event(FloorTrackingEventType.APPLIED, atNanos = 8_000_000_000, from = "f3", to = "f4")
            .toEntity("POS-1", 4, nowEpochMillis = 100_000, nowElapsedNanos = 10_000_000_000)
        assertEquals(FloorTransitionEventEntity("FTE-POS-1-4", "POS-1", 4, "APPLIED", "f3", "f4", null, 0.7, 0.9, 3.4,
            1001.5, "OK", 8_000_000_000, 98_000, "d"), row)
    }

    @Test fun configHasNoSensorTimeAndHeldEventsAreNumberedFromZeroOnceTheSessionExists() {
        val log = FloorEventLog()
        val config = FloorTrackingEvent(FloorTrackingEventType.CONFIG, 0L, barometerHealth = BarometerHealth.UNAVAILABLE,
            detail = "barometerPresent=false; config={}")
        assertTrue(log.add(listOf(config), sessionId = null, nowEpochMillis = 50, nowElapsedNanos = 1).isEmpty())
        val rows = log.add(listOf(event(FloorTrackingEventType.ABSOLUTE_FIX, 2_000_000_000, to = "f3")), "POS-1",
            nowEpochMillis = 3_000, nowElapsedNanos = 3_000_000_000)
        assertEquals(listOf(0L, 1L), rows.map { it.sequence })
        assertEquals(listOf("FTE-POS-1-0", "FTE-POS-1-1"), rows.map { it.id })
        assertTrue(rows.all { it.positioningSessionId == "POS-1" })
        assertEquals("CONFIG", rows[0].type); assertNull(rows[0].sensorTimestampNanos); assertEquals(50, rows[0].timestampEpochMillis)
        assertEquals("UNAVAILABLE", rows[0].barometerHealth)
        assertEquals(2_000, rows[1].timestampEpochMillis)
        assertTrue(log.add(emptyList(), "POS-1", 0, 0).isEmpty(), "nothing is written twice")
        assertEquals(2L, log.add(listOf(event(FloorTrackingEventType.CONFIRMED, 4)), "POS-1", 0, 0).single().sequence)
        log.reset()
        assertEquals(0L, log.add(listOf(config), "POS-2", 0, 0).single().sequence)
    }

    @Test fun schemaFourCarriesTheEventsAndOlderExportsReadWithAnEmptyList() {
        assertEquals(4, TurnDatabaseExport.CURRENT_EXPORT_SCHEMA)
        val value = export(row(0, from = null, to = null), row(1, link = "l"))
        assertTrue(value.validationErrors().isEmpty(), value.validationErrors().toString())
        val decoded = Json.decodeFromString<TurnDatabaseExport>(Json.encodeToString(value))
        assertEquals(value.floorTransitionEvents, decoded.floorTransitionEvents)

        val schema3 = Json.decodeFromString<TurnDatabaseExport>("""{"schemaVersion":3,"exportedAtEpochMillis":1}""")
        assertTrue(schema3.floorTransitionEvents.isEmpty())
        assertTrue(schema3.validationErrors().isEmpty())
        assertTrue(TurnDatabaseExport(schemaVersion = 5, exportedAtEpochMillis = 0).validationErrors().isNotEmpty())
    }

    @Test fun unknownSessionFloorOrLinkAndDuplicateSequencesAreRejected() {
        listOf(
            row(1).copy(positioningSessionId = "POS-X"),
            row(1, from = "gone"),
            row(1, to = "gone"),
            row(1, link = "gone"),
        ).forEach { bad -> assertTrue(export(bad).validationErrors().any { "Floor transition event" in it }, bad.toString()) }
        assertTrue(export(row(1), row(1).copy(id = "other")).validationErrors().any { "Duplicate floor transition event" in it })
    }

    @Test fun researchCsvHasTheFloorChangeLogDataset() {
        val table = ResearchDataExport.csv(export(row(1)), ResearchDataset.FLOOR_TRANSITIONS)
        val values = table.headers.zip(table.rows.single()).toMap()
        assertEquals("APPLIED", values["type"]); assertEquals("f4", values["toFloorId"]); assertEquals(null, values["linkId"])
        assertEquals("5000000000", values["sensorTimestampNanos"])
    }
}
