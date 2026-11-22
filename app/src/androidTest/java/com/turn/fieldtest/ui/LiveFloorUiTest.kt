package com.turn.fieldtest.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.turn.fieldtest.core.BarometerHealth
import com.turn.fieldtest.core.FloorState
import com.turn.fieldtest.data.local.NavigationEdgeEntity
import com.turn.fieldtest.data.local.NavigationNodeEntity
import com.turn.fieldtest.data.local.PointOfInterestEntity
import com.turn.fieldtest.data.repository.ProjectContext
import com.turn.fieldtest.ui.model.*
import com.turn.fieldtest.ui.screens.FullscreenNavigationScreen
import com.turn.fieldtest.ui.screens.RealEvaluateScreen
import com.turn.fieldtest.ui.theme.TurnTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Multi-floor live UI (plan Step 9), with state set directly like CanvasWorkflowTest. No real sensors run. */
@RunWith(AndroidJUnit4::class)
class LiveFloorUiTest {
    @get:Rule val compose = createComposeRule()

    private fun fixture() = TurnAppState().apply {
        mode = DataMode.REAL_DEVICE; realMapReady = true; activeProject = ProjectContext("canvas-v", "canvas-f")
        destination = TurnDestination.LIVE_LOCATE; venueName = "Test university"; floorName = "Floor 4"
        clearMapDraft(force = true); floorWidthMetres = 20f; floorHeightMetres = 20f
        draftWalkablePolygon.addAll(listOf(Offset.Zero, Offset(20f, 0f), Offset(20f, 20f), Offset(0f, 20f)))
        destinations.add(PointOfInterestEntity("lab", "canvas-f", "Lab 1", "DESTINATION", 15.0, 3.0))
        navigationNodes.addAll(listOf(NavigationNodeEntity("start", "canvas-f", 3.0, 3.0), NavigationNodeEntity("end", "canvas-f", 15.0, 3.0, "lab")))
        navigationEdges.add(NavigationEdgeEntity("path", "canvas-f", "start", "end", 12.0))
        markMapSaved()
    }

    private fun tracking(state: FloorState = FloorState.CONFIRMED, confidence: Double = 0.92, announcedAt: Long? = null) = LiveFloorUi(
        floorId = "canvas-f", floorName = "Floor 4", level = 4, confidence = confidence, state = state,
        barometer = BarometerHealth.OK, announcedAtEpochMillis = announcedAt,
        sessionFloors = listOf(SessionFloorUi("canvas-f", "Floor 4", 4, 20.0, 20.0), SessionFloorUi("canvas-f3", "Floor 3", 3, 30.0, 10.0)),
        startFloorId = "canvas-f3",
    )

    private fun live(floor: LiveFloorUi?) = fixture().apply {
        liveRunning = true; sessionLocked = true; realLiveInitialized = true; realLivePosition = Offset(5f, 3f)
        realLiveFloorId = "canvas-f"; liveFloorTracking = floor
        if (floor != null) liveSessionKey = ProjectContext("canvas-v", "canvas-f3")
    }

    @Test fun confirmedFloorShowsChipWithoutBanner() {
        val state = live(tracking())
        compose.setContent { TurnApp(state) }
        compose.onNodeWithText("Floor 4 · 92%").assertExists()
        compose.onNodeWithText("You're on Floor 4").assertDoesNotExist()
    }

    @Test fun recentChangeIsAnnounced() {
        val state = live(tracking(confidence = 0.9, announcedAt = System.currentTimeMillis()))
        compose.setContent { TurnApp(state) }
        compose.onNodeWithText("You're on Floor 4").assertExists()
        compose.onNodeWithText("Relocalize").assertDoesNotExist()
    }

    @Test fun uncertainFloorKeepsBannerWithRelocalize() {
        var relocalized = 0
        val state = live(tracking(FloorState.UNCERTAIN, confidence = 0.4))
        compose.setContent { TurnApp(state, TurnAppActions(onRealLiveRelocalizationRequested = { relocalized++ })) }
        compose.onAllNodesWithText("Floor uncertain", substring = true).onFirst().assertExists()
        compose.onNodeWithText("Not sure which floor").assertExists()
        compose.onNodeWithText("Relocalize").performClick()
        compose.runOnIdle { assertEquals(1, relocalized) }
    }

    @Test fun singleFloorSessionShowsNoFloorUi() {
        val state = live(null)
        compose.setContent { TurnApp(state) }
        compose.onNodeWithText("Floor 4 · 92%").assertDoesNotExist()
        compose.onNodeWithText("Floor uncertain", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Relocalize").assertDoesNotExist()
        compose.onNodeWithText("End navigation").assertIsDisplayed()
    }

    @Test fun floorOptionsAreLockedWhileLive() {
        val state = live(tracking()).apply { floorTrackingStatus = "Multi-floor tracking · 2 floors · 1 link · barometer ready" }
        compose.setContent { TurnApp(state) }
        compose.onNodeWithContentDescription("Map options").performClick()
        compose.onNodeWithText("Multi-floor tracking").performScrollTo().assertExists()
        compose.onNodeWithText("Use unchecked floor links").assertExists()
        compose.onNodeWithText("Barometer: ready").assertExists()
        compose.onNodeWithText("Multi-floor tracking · 2 floors · 1 link · barometer ready").assertExists()
        // Magnetic corrections plus both floor switches are locked; dark mode and debug overlays stay available.
        compose.onAllNodes(isToggleable() and isNotEnabled()).assertCountEquals(3)
    }

    @Test fun floorOptionsToggleWhenStopped() {
        val state = fixture()
        compose.setContent { TurnApp(state) }
        compose.onNodeWithContentDescription("Map options").performClick()
        compose.onNodeWithText("Use unchecked floor links").performScrollTo()
        compose.onAllNodes(isToggleable() and isNotEnabled()).assertCountEquals(0)
        val toggles = compose.onAllNodes(isToggleable())
        val count = toggles.fetchSemanticsNodes().size
        toggles[count - 1].performClick()
        compose.runOnIdle { assertTrue(state.useUncheckedFloorLinks) }
        compose.onAllNodes(isToggleable())[count - 2].performClick()
        compose.runOnIdle { assertFalse(state.floorTrackingEnabled) }
        // Without floor tracking, the unchecked-links switch has nothing to change.
        compose.onAllNodes(isToggleable() and isNotEnabled()).assertCountEquals(1)
    }

    @Test fun followingAnotherFloorKeepsTheOptionsSheetOpen() {
        val state = live(tracking())
        compose.setContent { TurnApp(state) }
        compose.onNodeWithContentDescription("Map options").performClick()
        compose.onNodeWithText("Multi-floor tracking").performScrollTo().assertExists()
        compose.runOnIdle { state.activeProject = ProjectContext("canvas-v", "canvas-f3"); state.floorName = "Floor 3" }
        compose.onNodeWithText("Multi-floor tracking").assertExists()
    }

    @Test fun fullscreenHeaderShowsFloorConfidence() {
        val state = live(tracking())
        compose.setContent { TurnTheme { Surface(Modifier.fillMaxSize()) { FullscreenNavigationScreen(state) } } }
        compose.onNodeWithText("Floor 4 · floor 92%").assertExists()
    }

    @Test fun checkpointFloorChipsDefaultToTheStartFloor() {
        val captured = mutableListOf<com.turn.fieldtest.ui.screens.CheckpointInput>()
        val state = live(tracking()).apply { destination = TurnDestination.EVALUATE }
        compose.setContent { TurnTheme { Surface(Modifier.fillMaxSize()) { RealEvaluateScreen(state, true) { captured += it } } } }
        compose.onNodeWithText("Checkpoint floor").assertExists()
        compose.onNodeWithText("Floor 3 · metric coordinates").assertExists()
        compose.onNodeWithText("Floor 4").performClick()
        compose.onNodeWithText("Floor 4 · metric coordinates").assertExists()
    }

    @Test fun singleFloorEvaluationHasNoFloorChips() {
        val state = live(null).apply { destination = TurnDestination.EVALUATE }
        compose.setContent { TurnTheme { Surface(Modifier.fillMaxSize()) { RealEvaluateScreen(state, true) {} } } }
        compose.onNodeWithText("Checkpoint floor").assertDoesNotExist()
        compose.onNodeWithText("Floor 4 · metric coordinates").assertExists()
    }
}
