package com.turn.fieldtest.data.repository

import com.turn.fieldtest.data.local.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ProjectRepositoryTest {
    private val legacy = ProjectSummary("VEN-CS-01", "FL-G", "University", "Ground", 4, 3, 7, 2)
    private val another = legacy.copy(venueId = "another", floorId = "other-floor")

    @Test fun existingSingleProjectCanReopenWithoutNewPreferences() {
        assertEquals(ProjectContext("VEN-CS-01", "FL-G"), resolveSavedProject(listOf(legacy), null, null))
    }
    @Test fun savedSelectionWinsOverOrdering() {
        assertEquals(another.context, resolveSavedProject(listOf(legacy, another), "another", "other-floor"))
    }
    @Test fun missingOrMismatchedSavedSelectionNeverFallsBackToAnotherFloor() {
        assertNull(resolveSavedProject(listOf(legacy), "missing", "FL-G"))
        assertNull(resolveSavedProject(listOf(legacy, another), "another", "FL-G"))
        assertNull(resolveSavedProject(listOf(legacy), null, "FL-G"))
    }
    @Test fun ambiguousOrEmptyProjectsRequireExplicitSelection() {
        assertNull(resolveSavedProject(listOf(legacy, another), null, null))
        assertNull(resolveSavedProject(emptyList(), null, null))
    }
    @Test fun projectNamesAreTrimmedAndRequired() {
        assertEquals(ProjectInput("University", "Ground"), ProjectInput(" University ", " Ground ").validated())
        assertThrows(IllegalArgumentException::class.java) { ProjectInput(" ", "Ground").validated() }
        assertThrows(IllegalArgumentException::class.java) { ProjectInput("University", "x".repeat(121)).validated() }
    }
    private fun map(regions: List<WalkableRegionEntity>) = SavedFloorMap(
        VenueEntity("v", "University", createdAtEpochMillis = 1, updatedAtEpochMillis = 1),
        FloorEntity("f", "v", "Ground", 0, 10.0, 10.0, createdAtEpochMillis = 1, updatedAtEpochMillis = 1),
        null, regions, emptyList(), emptyList(), emptyList(), false)

    @Test fun blankSavedProjectCannotSupplyLiveConstraints() {
        assertThrows(IllegalArgumentException::class.java) { map(emptyList()).metricMap() }
    }
    @Test fun savedConstraintMapIgnoresDisabledRegionsAndKeepsItsOwnFloor() {
        val region = WalkableRegionEntity("r", "f", "Room", """[{"x":0,"y":0},{"x":10,"y":0},{"x":10,"y":10},{"x":0,"y":10}]""", updatedAtEpochMillis = 1)
        val saved = map(listOf(region, region.copy(id = "disabled", enabled = false, polygonJson = "invalid")))
        assertNotNull(saved.metricMap())
        assertEquals(ProjectContext("v", "f"), saved.context)
    }
}
