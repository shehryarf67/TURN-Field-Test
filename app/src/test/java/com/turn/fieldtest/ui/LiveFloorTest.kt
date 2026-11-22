package com.turn.fieldtest.ui

import androidx.compose.ui.geometry.Offset
import com.turn.fieldtest.core.BarometerHealth
import com.turn.fieldtest.core.FloorState
import com.turn.fieldtest.data.repository.ProjectContext
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LiveFloorTest {
    private fun floor(state: FloorState = FloorState.CONFIRMED, confidence: Double = 0.92, announcedAt: Long? = null) = LiveFloorUi(
        floorId = "f-4", floorName = "Floor 4", level = 4, confidence = confidence, state = state,
        barometer = BarometerHealth.OK, announcedAtEpochMillis = announcedAt,
        sessionFloors = listOf(SessionFloorUi("f-4", "Floor 4", 4, 20.0, 20.0), SessionFloorUi("f-3", "Floor 3", 3, 30.0, 10.0)),
        startFloorId = "f-3",
    )

    @Test fun chipTextFollowsPlanWording() {
        assertEquals("Floor 4 · 92%", floorChipText(floor()))
        assertEquals("Changing floor…", floorChipText(floor(FloorState.CHANGING)))
        assertEquals("Floor uncertain", floorChipText(floor(FloorState.UNCERTAIN)))
    }

    @Test fun bannerTextAnnouncesOnlyConfidentFloors() {
        assertEquals("You're on Floor 4", floorBannerText(floor(confidence = 0.8)))
        assertFalse(floorBannerOffersRelocalize(floor(confidence = 0.8)))
        assertEquals("Probably on Floor 4 · Not right?", floorBannerText(floor(confidence = 0.79)))
        assertTrue(floorBannerOffersRelocalize(floor(confidence = 0.79)))
        assertEquals("Not sure which floor", floorBannerText(floor(FloorState.UNCERTAIN)))
        assertTrue(floorBannerOffersRelocalize(floor(FloorState.UNCERTAIN)))
    }

    @Test fun bannerShowsSixSecondsAfterAChangeAndStaysWhileUncertain() {
        assertFalse(floorBannerVisible(null, 0))
        assertFalse(floorBannerVisible(floor(), 1_000))
        assertTrue(floorBannerVisible(floor(announcedAt = 1_000), 1_000))
        assertTrue(floorBannerVisible(floor(announcedAt = 1_000), 1_000 + FLOOR_BANNER_MILLIS - 1))
        assertFalse(floorBannerVisible(floor(announcedAt = 1_000), 1_000 + FLOOR_BANNER_MILLIS))
        assertTrue(floorBannerVisible(floor(FloorState.UNCERTAIN), 99_999))
    }

    @Test fun fullscreenSuffixAndOtherTexts() {
        assertEquals("", fullscreenFloorSuffix(null))
        assertEquals(" · floor 92%", fullscreenFloorSuffix(floor()))
        assertEquals("Barometer: ready", barometerStatusText(BarometerHealth.OK))
        assertEquals("Barometer: not on this phone", barometerStatusText(BarometerHealth.UNAVAILABLE))
        assertEquals("Barometer: no recent readings", barometerStatusText(BarometerHealth.STALE))
        assertEquals("Destination is on Floor 3 · turn on Multi-floor tracking in Map options for directions between floors",
            floorTrackingOffRouteText("Floor 3"))
        assertEquals("Wi-Fi suggests Floor 5 · waiting for more evidence", wifiSuggestsFloorText("Floor 5"))
    }

    @Test fun displayFollowsOnlyAChangedConfirmedFloor() {
        assertNull(floorToFollow("f-3", "f-3"))
        assertEquals("f-4", floorToFollow("f-3", "f-4"))
        assertEquals("f-4", floorToFollow(null, "f-4"))
    }

    @Test fun dotIsHiddenOnlyWhileAnotherFloorIsShownInAMultiFloorSession() {
        assertTrue(positionOnDisplayedFloor(null, "f-4", "f-3"))
        assertTrue(positionOnDisplayedFloor(floor(), null, "f-3"))
        assertTrue(positionOnDisplayedFloor(floor(), "f-4", "f-4"))
        assertFalse(positionOnDisplayedFloor(floor(), "f-4", "f-3"))
    }

    @Test fun checkpointFloorDefaultsToPreviousThenStartNeverDisplayed() {
        val ids = listOf("f-3", "f-4")
        assertEquals("f-3", defaultCheckpointFloor(null, ids, "f-3"))
        assertEquals("f-4", defaultCheckpointFloor("f-4", ids, "f-3"))
        assertEquals("f-3", defaultCheckpointFloor("f-9", ids, "f-3"))
    }

    @Test fun trailsSkipRepeatsAndStayBounded() {
        val trail = appendTrailPoint(appendTrailPoint(emptyList(), Offset(1f, 1f), 2), Offset(1f, 1f), 2)
        assertEquals(listOf(Offset(1f, 1f)), trail)
        assertEquals(listOf(Offset(2f, 2f), Offset(3f, 3f)), appendTrailPoint(appendTrailPoint(trail, Offset(2f, 2f), 2), Offset(3f, 3f), 2))
    }

    @Test fun screenStateKeyIsTheOpenFloorUnlessAMultiFloorSessionRuns() {
        val state = TurnAppState()
        state.activeProject = ProjectContext("uni", "f-4")
        assertEquals(ProjectContext("uni", "f-4"), state.screenStateKey)
        state.liveSessionKey = ProjectContext("uni", "f-3")
        assertEquals(ProjectContext("uni", "f-3"), state.screenStateKey)
        assertNull(state.liveFloorTracking)
        assertTrue(state.floorTrackingEnabled)
        assertFalse(state.useUncheckedFloorLinks)
    }

    // A session started on Floor 3 from a known start, then the display followed Floor 4. The known start is in Floor 3's
    // frame; left set, the next session would start from those coordinates on Floor 4.
    @Test fun selectionsFromTheStartFloorAreDroppedAfterTheDisplayFollowedAnotherFloor() {
        val state = TurnAppState().apply {
            destinations.add(com.turn.fieldtest.data.local.PointOfInterestEntity("lab-4", "f-4", "Lab 4", "ROOM", 2.0, 2.0))
            manualStartPoint = Offset(25f, 8f); pickingStart = true; selectedNodeId = "node-3"
            selectedDestinationId = "cafe-3"
        }
        state.clearSelectionsFromOtherFloor()
        assertNull(state.manualStartPoint)
        assertFalse(state.pickingStart)
        assertNull(state.selectedNodeId)
        assertNull(state.selectedDestinationId)

        state.selectedDestinationId = "lab-4"
        state.clearSelectionsFromOtherFloor()
        assertEquals("lab-4", state.selectedDestinationId, "a destination on the open floor is kept")
    }
}
