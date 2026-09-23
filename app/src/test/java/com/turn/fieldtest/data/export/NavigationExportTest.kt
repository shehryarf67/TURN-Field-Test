package com.turn.fieldtest.data.export

import com.turn.fieldtest.data.local.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NavigationExportTest {
    private val export = TurnDatabaseExport(exportedAtEpochMillis = 1,
        venues = listOf(VenueEntity("v", "University", createdAtEpochMillis = 1, updatedAtEpochMillis = 1)),
        floors = listOf(FloorEntity("f", "v", "Ground", 0, 20.0, 20.0, createdAtEpochMillis = 1, updatedAtEpochMillis = 1)),
        pointsOfInterest = listOf(PointOfInterestEntity("lab", "f", "Lab 1", "ROOM", 12.0, 2.0)),
        navigationNodes = listOf(NavigationNodeEntity("a", "f", 2.0, 2.0), NavigationNodeEntity("b", "f", 12.0, 2.0, "lab")),
        navigationEdges = listOf(NavigationEdgeEntity("ab", "f", "a", "b", 10.0)))
    @Test fun graphAndDestinationsSurviveJsonRoundTrip() {
        val decoded = Json.decodeFromString<TurnDatabaseExport>(Json.encodeToString(export))
        assertEquals(export, decoded)
        assertTrue(decoded.validationErrors().isEmpty())
    }
    @Test fun oldExportIsAcceptedWithoutInventingGraphData() {
        val decoded = Json.decodeFromString<TurnDatabaseExport>("""{"schemaVersion":1,"exportedAtEpochMillis":1}""")
        assertTrue(decoded.validationErrors().isEmpty())
        assertTrue(decoded.navigationNodes.isEmpty())
    }
    @Test fun orphanOrCrossFloorPathIsRejected() {
        assertTrue(export.copy(navigationEdges = listOf(export.navigationEdges.single().copy(toNodeId = "missing"))).validationErrors().isNotEmpty())
        assertTrue(export.copy(navigationNodes = listOf(export.navigationNodes.first().copy(floorId = "other"), export.navigationNodes.last())).validationErrors().isNotEmpty())
    }
}
