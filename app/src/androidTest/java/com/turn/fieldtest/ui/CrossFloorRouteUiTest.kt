package com.turn.fieldtest.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.turn.fieldtest.core.BarometerHealth
import com.turn.fieldtest.core.ConnectorKind
import com.turn.fieldtest.core.ConnectorLeg
import com.turn.fieldtest.core.FloorPortal
import com.turn.fieldtest.core.FloorState
import com.turn.fieldtest.core.MetricPoint
import com.turn.fieldtest.core.MultiFloorRoute
import com.turn.fieldtest.core.WalkLeg
import com.turn.fieldtest.data.local.NavigationEdgeEntity
import com.turn.fieldtest.data.local.NavigationNodeEntity
import com.turn.fieldtest.data.local.PointOfInterestEntity
import com.turn.fieldtest.data.repository.ProjectContext
import com.turn.fieldtest.ui.model.*
import com.turn.fieldtest.ui.screens.FullscreenNavigationScreen
import com.turn.fieldtest.ui.theme.TurnTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Phase 3 directions between floors, with state set directly like LiveFloorUiTest. No real sensors or routing run. */
@RunWith(AndroidJUnit4::class)
class CrossFloorRouteUiTest {
    @get:Rule val compose = createComposeRule()

    private val floor4 = "canvas-f"
    private val floor5 = "canvas-f5"
    private val names = mapOf(floor4 to "Floor 4", floor5 to "Floor 5")

    private fun fixture() = TurnAppState().apply {
        mode = DataMode.REAL_DEVICE; realMapReady = true; activeProject = ProjectContext("canvas-v", floor4)
        destination = TurnDestination.LIVE_LOCATE; venueName = "Test university"; floorName = "Floor 4"
        clearMapDraft(force = true); floorWidthMetres = 20f; floorHeightMetres = 20f
        draftWalkablePolygon.addAll(listOf(Offset.Zero, Offset(20f, 0f), Offset(20f, 20f), Offset(0f, 20f)))
        destinations.add(PointOfInterestEntity("lab", floor4, "Lab 1", "DESTINATION", 15.0, 3.0))
        navigationNodes.addAll(listOf(NavigationNodeEntity("start", floor4, 3.0, 3.0), NavigationNodeEntity("end", floor4, 15.0, 3.0, "lab")))
        navigationEdges.add(NavigationEdgeEntity("path", floor4, "start", "end", 12.0))
        markMapSaved()
    }

    private fun withOtherFloors(state: TurnAppState) = state.apply {
        buildingPlaces = listOf(
            BuildingPlace("lab", "Lab 1", floor4, "Floor 4", 4, true),
            BuildingPlace("lab7", "Lab 7", floor5, "Floor 5", 5, true),
            BuildingPlace("office", "Office 3", "canvas-f3", "Floor 3", 3, false),
        )
    }

    /** Walk 10 m to Stairs 1 on Floor 4, up one floor, walk 4 m to Lab 7. */
    private fun route(): FloorRouteUi {
        val portal = FloorPortal(0, "link-1", ConnectorKind.STAIRS, floor4, MetricPoint(15.0, 3.0), floor5, MetricPoint(15.0, 3.0),
            1.5, 1, 15.0, true, "Stairs 1")
        val legs = listOf(
            WalkLeg(floor4, listOf(MetricPoint(5.0, 3.0), MetricPoint(15.0, 3.0)), 10.0, 8.0, "Stairs 1"),
            ConnectorLeg(listOf(portal), ConnectorKind.STAIRS, "Stairs 1", floor4, floor5, 1, MetricPoint(15.0, 3.0), MetricPoint(15.0, 3.0), 15.0),
            WalkLeg(floor5, listOf(MetricPoint(15.0, 3.0), MetricPoint(15.0, 7.0)), 4.0, 3.2, null),
        )
        return FloorRouteUi(MultiFloorRoute(legs, 14.0, 26.2), "lab7", floor5, "Lab 7", names)
    }

    private fun live(position: Offset) = withOtherFloors(fixture()).apply {
        liveRunning = true; sessionLocked = true; realLiveInitialized = true; realLivePosition = position
        realLiveFloorId = floor4; liveSessionKey = ProjectContext("canvas-v", floor4)
        liveFloorTracking = LiveFloorUi(floor4, "Floor 4", 4, 0.92, FloorState.CONFIRMED, BarometerHealth.OK, null,
            listOf(SessionFloorUi(floor4, "Floor 4", 4, 20.0, 20.0), SessionFloorUi(floor5, "Floor 5", 5, 20.0, 20.0)), floor4)
        selectedDestinationId = "lab7"; liveDestination = LiveDestination("lab7", floor5, "Lab 7")
        liveFloorRoute = route(); routePoints = listOf(Offset(5f, 3f), Offset(15f, 3f)); remainingDistanceMetres = 14.0
        routeStatus = crossFloorRouteStatus(route())
    }

    @Test fun searchListsOtherFloorsGroupedByFloor() {
        val state = withOtherFloors(fixture())
        compose.setContent { TurnApp(state, TurnAppActions(onDestinationSelected = { state.selectedDestinationId = it })) }
        compose.onNodeWithText("Search places").performClick()
        compose.onNodeWithText("Floor 4 · this floor").assertExists()
        compose.onNodeWithText("5 · Floor 5").assertExists()
        compose.onNodeWithText("3 · Floor 3").performScrollTo().assertExists()
        compose.onNodeWithText("On Floor 3 · entrance not connected").performScrollTo().assertExists()
        compose.onNodeWithText("Lab 7").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("lab7", state.selectedDestinationId); assertEquals("lab7", state.selectedPlaceOnOtherFloor()?.id) }
        compose.onNodeWithText("Lab 7 · Floor 5").assertExists()
    }

    @Test fun oneFloorBuildingSearchIsUnchanged() {
        val state = fixture()
        compose.setContent { TurnApp(state) }
        compose.onNodeWithText("Search places").performClick()
        compose.onNodeWithText("Lab 1").assertExists()
        compose.onNodeWithText("Floor 4 · this floor").assertDoesNotExist()
        compose.onNodeWithText("directions between floors", substring = true).assertDoesNotExist()
    }

    @Test fun previewShowsTheLegsOfARouteToAnotherFloor() {
        val state = withOtherFloors(fixture()).apply {
            selectedDestinationId = "lab7"; manualStartPoint = Offset(5f, 3f)
            crossFloorPreview = CrossFloorPreview("lab7", floor4, MetricPoint(5.0, 3.0), route(), null)
        }
        compose.setContent { TurnApp(state) }
        compose.onNodeWithText("Lab 7 · Floor 5").assertExists()
        compose.onNodeWithText("With stairs or lifts").assertExists()
        compose.onNodeWithText("Walk 10 m to Stairs 1").performScrollTo().assertExists()
        compose.onNodeWithText("Go up 1 floor to Floor 5 by Stairs 1").performScrollTo().assertExists()
        compose.onNodeWithText("Start navigation").performScrollTo().assertIsEnabled()
        compose.runOnIdle { assertEquals("link-1", state.routeConnectorLinkId()) }
    }

    @Test fun previewProblemBlocksStart() {
        val state = withOtherFloors(fixture()).apply {
            selectedDestinationId = "lab7"; manualStartPoint = Offset(5f, 3f)
            crossFloorPreview = CrossFloorPreview("lab7", floor4, MetricPoint(5.0, 3.0), null, "No step-free way to Floor 5")
        }
        compose.setContent { TurnApp(state) }
        compose.onNodeWithText("No step-free way to Floor 5").performScrollTo().assertExists()
        compose.onNodeWithText("Start navigation").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertNull(state.routeConnectorLinkId()) }
    }

    @Test fun stepFreeSwitchTogglesTheOption() {
        val state = fixture()
        compose.setContent { TurnApp(state) }
        compose.onNodeWithContentDescription("Map options").performClick()
        compose.onNodeWithText("Step-free routes").performScrollTo().assertExists()
        // Dark mode, step-free, then (Advanced) debug overlays, magnetic corrections, multi-floor tracking, unchecked links.
        val toggles = compose.onAllNodes(isToggleable())
        val count = toggles.fetchSemanticsNodes().size
        assertEquals(6, count)
        toggles[1].performClick()
        compose.runOnIdle { assertTrue(state.stepFreeRouting); assertTrue(state.floorTrackingEnabled); assertFalse(state.useUncheckedFloorLinks) }
    }

    @Test fun stepFreeSwitchStaysAvailableWhileLive() {
        val state = live(Offset(5f, 3f))
        compose.setContent { TurnApp(state) }
        compose.onNodeWithContentDescription("Map options").performClick()
        compose.onNodeWithText("Step-free routes").performScrollTo().assertExists()
        // Only magnetic corrections and the two floor-tracking switches lock while tracking.
        compose.onAllNodes(isToggleable() and isNotEnabled()).assertCountEquals(3)
    }

    @Test fun bannerAppearsNearTheLinkWithTheLegList() {
        val state = live(Offset(13f, 3f))
        compose.setContent { TurnApp(state) }
        compose.onAllNodesWithText("Go up 1 floor to Floor 5 by Stairs 1").onFirst().assertExists()
        compose.onNodeWithText("Walk 10 m to Stairs 1").performScrollTo().assertExists()
        compose.onNodeWithText("then arrive at Stairs 1").assertExists()
        compose.runOnIdle { assertEquals("link-1", state.routeConnectorLinkId()) }
    }

    @Test fun noBannerFarFromTheLink() {
        val state = live(Offset(3f, 3f)).apply {
            // Re-planned from farther away: 12 m left to the stairs.
            liveFloorRoute = route().let { r -> r.copy(route = r.route.copy(legs = listOf(
                WalkLeg(floor4, listOf(MetricPoint(3.0, 3.0), MetricPoint(15.0, 3.0)), 12.0, 9.6, "Stairs 1")) + r.route.legs.drop(1))) }
        }
        compose.setContent { TurnApp(state) }
        compose.onNodeWithText("Go up 1 floor to Floor 5 by Stairs 1").performScrollTo().assertExists() // leg list row only
        compose.onAllNodesWithText("Go up 1 floor to Floor 5 by Stairs 1").assertCountEquals(1)
    }

    @Test fun nextLegTakesOverOnTheNewFloor() {
        val state = live(Offset(13f, 3f))
        compose.setContent { TurnApp(state) }
        compose.runOnIdle {
            // Phase 2 confirmed Floor 5 and the display followed it: the route now starts on Floor 5.
            state.activeProject = ProjectContext("canvas-v", floor5); state.floorName = "Floor 5"; state.realLiveFloorId = floor5
            state.liveFloorRoute = route().let { r -> r.copy(route = r.route.copy(legs = r.route.legs.takeLast(1), walkMetres = 4.0)) }
        }
        compose.onAllNodesWithText("Go up 1 floor to Floor 5 by Stairs 1").assertCountEquals(0)
        compose.onNodeWithText("Walk 4 m to Lab 7").performScrollTo().assertExists()
        compose.runOnIdle { assertNull(state.routeConnectorLinkId()) }
    }

    @Test fun fullscreenShowsTheSameBanner() {
        val state = live(Offset(14f, 3f))
        compose.setContent { TurnTheme { Surface(Modifier.fillMaxSize()) { FullscreenNavigationScreen(state) } } }
        compose.onNodeWithText("Go up 1 floor to Floor 5 by Stairs 1").assertExists()
    }
}
