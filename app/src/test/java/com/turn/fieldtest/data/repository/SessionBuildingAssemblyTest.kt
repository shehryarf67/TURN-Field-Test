package com.turn.fieldtest.data.repository

import com.turn.fieldtest.core.ConnectorKind
import com.turn.fieldtest.core.ExclusionReason
import com.turn.fieldtest.core.FloorTopology
import com.turn.fieldtest.data.local.FloorEntity
import com.turn.fieldtest.data.local.VenueEntity
import com.turn.fieldtest.data.local.VerticalTransitionEntity
import com.turn.fieldtest.data.local.WalkableRegionEntity
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SessionBuildingAssemblyTest {
    private val square = """[{"x":0,"y":0},{"x":20,"y":0},{"x":20,"y":20},{"x":0,"y":20}]"""

    private fun saved(floor: String, level: Int, walkable: Boolean = true) = SavedFloorMap(VenueEntity("uni", "University", null, 1, 1),
        FloorEntity("f-$floor", "uni", floor, level, 20.0, 20.0, createdAtEpochMillis = 1, updatedAtEpochMillis = 1), null,
        if (walkable) listOf(WalkableRegionEntity("r-$floor", "f-$floor", "Walkable", square, true, 1)) else emptyList(),
        emptyList(), emptyList(), emptyList(), false)

    private fun link(id: String = "LINK-1", from: String = "F3", to: String = "F4", verified: Boolean = true, enabled: Boolean = true) =
        FloorConnector(VerticalTransitionEntity(id = id, fromFloorId = "f-$from", toFloorId = "f-$to",
            kind = "STAIRS", fromXMetres = 5.0, fromYMetres = 5.0, toXMetres = 6.0, toYMetres = 6.0, label = "Stairs A",
            travelTimeSeconds = 15.0, accessible = false, verifiedOnSite = verified, enabled = enabled),
            ConnectorKind.STAIRS, from, 3, to, 4)

    private val f3 = saved("F3", 3)
    private val f4 = saved("F4", 4)

    private fun assertLegacy(building: SessionBuilding) {
        assertFalse(building.multiFloor)
        assertEquals(FloorTopology.NONE, building.topology)
        assertEquals(f3.metricMap(), building.metricMap)
        assertEquals(setOf("f-F3"), building.floorIds)
        assertEquals(mapOf("f-F3" to f3), building.floorMaps)
    }

    @Test fun oneFloorWithoutLinksIsTodaysSessionMap() =
        assertLegacy(assembleSessionBuilding(f3, emptyList(), emptyList(), floorTrackingEnabled = true, useUnverifiedLinks = false))

    @Test fun trackingOffIgnoresLinksAndOtherFloors() {
        val building = assembleSessionBuilding(f3, listOf(f4), listOf(link()), floorTrackingEnabled = false, useUnverifiedLinks = true)
        assertLegacy(building)
        assertNull(floorTrackingStartStatus(building, floorTrackingEnabled = false, barometerPresent = true))
    }

    @Test fun uncheckedLinkWithFlagOffStaysSingleFloorAndSaysWhy() {
        val building = assembleSessionBuilding(f3, listOf(f4), listOf(link(verified = false)), true, useUnverifiedLinks = false)
        assertLegacy(building)
        assertTrue(building.excluded.any { it.reason == ExclusionReason.UNVERIFIED })
        assertEquals("1 unchecked link ignored · check it on site or allow unchecked links",
            floorTrackingStartStatus(building, true, barometerPresent = true))
    }

    @Test fun disabledLinkStaysSingleFloor() {
        val building = assembleSessionBuilding(f3, listOf(f4), listOf(link(enabled = false)), true, true)
        assertLegacy(building)
        assertEquals("Floor tracking off: no checked links from this floor", floorTrackingStartStatus(building, true, true))
    }

    @Test fun uncheckedLinkWithFlagOnGivesMultiFloor() {
        val building = assembleSessionBuilding(f3, listOf(f4), listOf(link(verified = false)), true, useUnverifiedLinks = true)
        assertTrue(building.multiFloor)
        assertEquals(setOf("f-F3", "f-F4"), building.floorIds)
    }

    @Test fun checkedLinkGivesTwoFloorSessionWithStatus() {
        val building = assembleSessionBuilding(f3, listOf(f4), listOf(link()), true, false)
        assertTrue(building.multiFloor)
        assertEquals(setOf("f-F3", "f-F4"), building.metricMap.floors.keys)
        assertEquals(mapOf("f-F3" to "F3", "f-F4" to "F4"), building.names)
        assertEquals(mapOf("f-F3" to 3, "f-F4" to 4), building.levels)
        assertEquals("Multi-floor tracking · 2 floors · 1 link · barometer ready", floorTrackingStartStatus(building, true, true))
        assertEquals("Multi-floor tracking · 2 floors · 1 link · no barometer: floor from Wi-Fi and stairs/lift",
            floorTrackingStartStatus(building, true, false))
    }

    @Test fun singleFloorReductionMatchesTheLegacyBuilding() {
        val building = assembleSessionBuilding(f3, listOf(f4), listOf(link()), true, false).singleFloor()
        assertLegacy(building)
    }

    @Test fun floorWithoutWalkableSpaceIsSkippedWithItsLinks() {
        val broken = saved("F4", 4, walkable = false)
        val building = assembleSessionBuilding(f3, listOf(broken), listOf(link()), true, false)
        assertLegacy(building)
        assertEquals(listOf("f-F4"), building.skippedFloors.map { it.first })
    }

    @Test fun skippedFloorDoesNotStopTheOtherLinks() {
        val f2 = saved("F2", 2, walkable = false)
        val building = assembleSessionBuilding(f3, listOf(f4, f2), listOf(link(), link("LINK-2", from = "F2", to = "F3")), true, false)
        assertTrue(building.multiFloor)
        assertEquals(setOf("f-F3", "f-F4"), building.floorIds)
        assertEquals(listOf("f-F2"), building.skippedFloors.map { it.first })
    }

    @Test fun unusableStartFloorStillFailsAsBefore() {
        assertThrows(IllegalArgumentException::class.java) {
            assembleSessionBuilding(saved("F3", 3, walkable = false), listOf(f4), listOf(link()), true, false)
        }
    }
}

/** Schema 8 floor elevations reach the session only when every session floor has one. */
class SessionBuildingElevationsTest {
    private val square = """[{"x":0,"y":0},{"x":20,"y":0},{"x":20,"y":20},{"x":0,"y":20}]"""

    private fun saved(floor: String, level: Int, elevation: Double?) = SavedFloorMap(VenueEntity("home", "Home", null, 1, 1),
        FloorEntity("f-$floor", "home", floor, level, 20.0, 20.0, createdAtEpochMillis = 1, updatedAtEpochMillis = 1, elevationMetres = elevation), null,
        listOf(WalkableRegionEntity("r-$floor", "f-$floor", "Walkable", square, true, 1)), emptyList(), emptyList(), emptyList(), false)

    private val link = FloorConnector(VerticalTransitionEntity(id = "L", fromFloorId = "f-G", toFloorId = "f-F1",
        kind = "STAIRS", fromXMetres = 5.0, fromYMetres = 5.0, toXMetres = 6.0, toYMetres = 6.0, label = "Stairs",
        travelTimeSeconds = 12.0, verifiedOnSite = true), ConnectorKind.STAIRS, "G", 0, "F1", 1)

    @Test fun unsetOrPartialElevationsKeepTheNominalStorey() {
        val unset = assembleSessionBuilding(saved("G", 0, null), listOf(saved("F1", 1, null)), listOf(link), true, false)
        assertTrue(unset.multiFloor)
        assertNull(unset.elevations)
        assertEquals("Multi-floor tracking · 2 floors · 1 link · barometer ready", floorTrackingStartStatus(unset, true, true))
        val partial = assembleSessionBuilding(saved("G", 0, 0.0), listOf(saved("F1", 1, null)), listOf(link), true, false)
        assertNull(partial.elevations)
        assertNull(partial.singleFloor().elevations)
    }

    @Test fun completeElevationsReachTheSession() {
        val building = assembleSessionBuilding(saved("G", 0, 0.0), listOf(saved("F1", 1, 2.7)), listOf(link), true, false)
        assertEquals(mapOf("f-G" to 0.0, "f-F1" to 2.7), building.elevations?.elevationMetres)
        assertEquals("Multi-floor tracking · 2 floors · 1 link · barometer ready · floor heights set", floorTrackingStartStatus(building, true, true))
        assertNull(building.singleFloor().elevations, "the single-floor reduction is the legacy session")
        val trackingOff = assembleSessionBuilding(saved("G", 0, 0.0), listOf(saved("F1", 1, 2.7)), listOf(link), false, false)
        assertNull(trackingOff.elevations)
    }
}
