package com.turn.fieldtest.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.turn.fieldtest.data.local.PointOfInterestEntity
import com.turn.fieldtest.data.local.NavigationNodeEntity
import com.turn.fieldtest.data.local.NavigationEdgeEntity
import com.turn.fieldtest.data.repository.ProjectContext
import com.turn.fieldtest.ui.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.turn.fieldtest.ui.screens.NavigationMap
import com.turn.fieldtest.ui.theme.TurnTheme

/** UI-only fixtures. No synthetic radio, movement or AR evidence is passed to the real runtime. */
@RunWith(AndroidJUnit4::class)
class CanvasWorkflowTest {
    @get:Rule val compose = createComposeRule()
    private fun fixture() = TurnAppState().apply {
        mode = DataMode.REAL_DEVICE; realMapReady = true; activeProject = ProjectContext("canvas-v", "canvas-f")
        destination = TurnDestination.LIVE_LOCATE; venueName = "Test university"; floorName = "Floor 4"
        clearMapDraft(force = true); floorWidthMetres = 20f; floorHeightMetres = 20f
        draftWalkablePolygon.addAll(listOf(Offset.Zero, Offset(20f, 0f), Offset(20f, 20f), Offset(0f, 20f)))
        referencePoints.add(MapPointUi("rp", floorName, Offset(3f, 3f), "RP-01"))
        destinations.add(PointOfInterestEntity("lab", "canvas-f", "Lab 1", "DESTINATION", 15.0, 3.0))
        navigationNodes.addAll(listOf(NavigationNodeEntity("start","canvas-f",3.0,3.0),NavigationNodeEntity("end","canvas-f",15.0,3.0,"lab")))
        navigationEdges.add(NavigationEdgeEntity("path","canvas-f","start","end",12.0))
        markMapSaved()
    }

    private fun waitForIme() {
        // Android's IME animation runs outside Compose's clock; wait before tapping the result.
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().let {
            it.waitForIdleSync(); it.uiAutomation.waitForIdle(200L,5_000L)
        }
        compose.waitForIdle()
    }

    @Test fun cancellingAheadPointReturnsToDirectionWithoutDiscardingRoute() {
        val state=fixture().apply {selectedDestinationId="lab";chooseKnownStart(Offset(3f,3f))}
        compose.setContent {TurnApp(state)}
        compose.onNodeWithText("Start navigation").performClick()
        compose.onNodeWithText("Tap a point ahead on the map").performScrollTo().performClick()
        compose.runOnIdle {assertTrue(state.pickingHeading)}
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Which way are you facing?").assertExists()
        compose.runOnIdle {assertFalse(state.pickingHeading);assertEquals("lab",state.selectedDestinationId);assertEquals(Offset(3f,3f),state.manualStartPoint)}
    }

    @Test fun destinationFirstSearchThenPromptsForStartAndDirection() {
        val state = fixture(); var starts = 0
        compose.setContent { TurnApp(state, TurnAppActions(
            onDestinationSelected = { state.selectedDestinationId = it }, onRealLiveToggled = { starts++ })) }
        compose.onNodeWithText("Search places").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Lab")
        waitForIme()
        compose.onAllNodesWithText("Lab 1").onLast().performClick()
        compose.runOnIdle { assertEquals("lab", state.selectedDestinationId) }
        // Choosing a destination without a start asks where you are next.
        compose.onNodeWithText("Where are you now?").assertExists()
        compose.onNodeWithText("Show survey points (1)").performScrollTo().performClick()
        compose.onNodeWithText("RP-01").performScrollTo().performClick()
        compose.onNodeWithText("12 m").assertExists()
        compose.onNodeWithText("Walking distance").assertExists()
        compose.onNodeWithText("Start navigation").performClick()
        compose.onNodeWithText("I'm facing this way · Go").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("← Left").performScrollTo().performClick()
        compose.onNodeWithText("I'm facing this way · Go").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, starts); assertEquals(Offset(3f, 3f), state.manualStartPoint)
            assertEquals(180.0, state.initialHeadingDegrees, 0.001); assertTrue(state.headingChoiceConfirmed)
            assertEquals(1, state.destinations.size); assertFalse(state.hasUnsavedMapChanges)
        }
    }

    @Test fun rotatedLongFloorConvertsScreenDirectionsToMapHeadings() {
        // A 40 × 10 m floor in a portrait window is drawn turned a quarter clockwise.
        val state = fixture().apply {
            clearMapDraft(force = true); floorWidthMetres = 40f; floorHeightMetres = 10f
            draftWalkablePolygon.addAll(listOf(Offset.Zero, Offset(40f, 0f), Offset(40f, 10f), Offset(0f, 10f)))
            destinations.add(PointOfInterestEntity("lab", "canvas-f", "Lab 1", "DESTINATION", 15.0, 3.0))
            navigationNodes.addAll(listOf(NavigationNodeEntity("start","canvas-f",3.0,3.0),NavigationNodeEntity("end","canvas-f",15.0,3.0,"lab")))
            navigationEdges.add(NavigationEdgeEntity("path","canvas-f","start","end",12.0))
            selectedDestinationId = "lab"; chooseKnownStart(Offset(3f, 3f)); markMapSaved()
        }
        compose.setContent { androidx.compose.foundation.layout.Box(Modifier.size(360.dp, 760.dp)) { TurnApp(state) } }
        compose.runOnIdle { assertEquals(1, state.mapQuarterTurns) }
        compose.onNodeWithText("Start navigation").performClick()
        compose.onNodeWithText("↑ Up").performScrollTo().performClick()
        // Screen up on a clockwise-turned map is map −x, i.e. 180°; screen right is map +y, i.e. 90°.
        compose.runOnIdle { assertEquals(180.0, state.initialHeadingDegrees, 0.001) }
        compose.onNodeWithText("Right →").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(90.0, state.initialHeadingDegrees, 0.001) }
    }

    @Test fun liveNavigationShowsTurnByTurnBannerAndStats() {
        val state = fixture().apply {
            liveRunning = true; realLiveInitialized = true; realLivePosition = Offset(5f, 3f); selectedDestinationId = "lab"
            routePoints = listOf(Offset(5f, 3f), Offset(15f, 3f)); remainingDistanceMetres = 10.0; realLiveUncertaintyMetres = 1.8
        }
        compose.setContent { TurnApp(state) }
        compose.onNodeWithText("Continue 10 m").assertIsDisplayed()
        compose.onNodeWithText("then arrive at Lab 1").assertIsDisplayed()
        compose.onNodeWithText("±1.8 m").assertExists()
        compose.onNodeWithText("Change destination").assertExists()
        compose.onNodeWithText("End navigation").assertIsDisplayed()
    }

    @Test fun toolsRetainMapperAndAllResearchDestinations() {
        val state = fixture(); var scans = 0
        compose.setContent { TurnApp(state, TurnAppActions(onWalkMapperRequested = { scans++ })) }
        compose.onNodeWithText("Tools", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Create a map with your phone").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, scans) }
        compose.onNodeWithText("Radio diagnostics").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(TurnDestination.RADIO_DIAGNOSTICS, state.destination) }
        compose.onNodeWithContentDescription("Back to tools").performClick()
        compose.onNodeWithText("Data / export").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(TurnDestination.DATA_EXPORT, state.destination) }
    }

    @Test fun knownStartShowsRouteBeforeTrackingWithoutCreatingLivePosition() {
        val state=fixture().apply { selectedDestinationId="lab"; chooseKnownStart(Offset(3f,3f)) }
        compose.setContent { TurnApp(state) }
        compose.onNodeWithText("12 m").assertExists()
        compose.onNodeWithText("Walking distance").assertExists()
        compose.onNodeWithText("Start navigation").assertIsEnabled()
        compose.runOnIdle { assertFalse(state.liveRunning); assertNull(state.realLivePosition) }
    }

    @Test fun startFirstPromptsForDestinationThenRouteAndDirectionBeforeTracking() {
        val state=fixture();var starts=0
        compose.setContent {TurnApp(state,TurnAppActions(onDestinationSelected={state.selectedDestinationId=it},onRealLiveToggled={starts++}))}
        compose.onNodeWithText("Where are you?").performClick()
        compose.onNodeWithText("RP-01").assertDoesNotExist()
        compose.onNodeWithText("Show survey points (1)").performScrollTo().performClick()
        compose.onNodeWithText("RP-01").performScrollTo().performClick()
        compose.onNodeWithText("Where to?").assertExists()
        compose.onNodeWithText("Start set. Now choose where you want to go.").assertExists()
        compose.onAllNodesWithText("Lab 1").onLast().performClick()
        compose.onNodeWithText("12 m").assertExists()
        compose.onNodeWithText("RP-01").assertExists()
        compose.onNodeWithText("Start navigation").performClick()
        compose.onNodeWithText("I'm facing this way · Go").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Right →").performScrollTo().performClick()
        compose.onNodeWithText("I'm facing this way · Go").performScrollTo().performClick()
        compose.runOnIdle {assertEquals(1,starts);assertEquals("lab",state.selectedDestinationId);assertEquals(Offset(3f,3f),state.manualStartPoint)}
    }

    @Test fun wifiStartAlsoPromptsForDestinationAndDoesNotLoopBackToStartSelection() {
        val state=fixture()
        compose.setContent {TurnApp(state,TurnAppActions(onDestinationSelected={state.selectedDestinationId=it}))}
        compose.onNodeWithText("Where are you?").performClick()
        compose.onNodeWithText("Detect with Wi-Fi").performScrollTo().performClick()
        compose.onNodeWithText("Where to?").assertExists()
        compose.onAllNodesWithText("Lab 1").onLast().performClick()
        compose.onNodeWithText("The route appears once Wi-Fi finds you.").assertExists()
        compose.onNodeWithText("Start navigation").performClick()
        compose.onNodeWithText("Which way are you facing?").assertExists()
        compose.runOnIdle {assertNull(state.manualStartPoint);assertNull(state.realLivePosition);assertFalse(state.liveRunning)}
    }

    @Test fun trackingWithoutDestinationStaysAvailable() {
        val state=fixture();var starts=0
        compose.setContent {TurnApp(state,TurnAppActions(onDestinationSelected={state.selectedDestinationId=it},onRealLiveToggled={starts++}))}
        compose.onNodeWithText("Choose a destination").performClick()
        compose.onNodeWithText("Just show my position").performScrollTo().performClick()
        compose.onNodeWithText("Where are you now?").assertExists()
        compose.onNodeWithText("Show survey points (1)").performScrollTo().performClick()
        compose.onNodeWithText("RP-01").performScrollTo().performClick()
        compose.onNodeWithText("Just my position").assertExists()
        compose.onNodeWithText("Start navigation").performClick()
        compose.onNodeWithText("↓ Down").performScrollTo().performClick()
        compose.onNodeWithText("I'm facing this way · Go").performScrollTo().performClick()
        compose.runOnIdle {assertEquals(1,starts);assertNull(state.selectedDestinationId);assertEquals(270.0,state.initialHeadingDegrees,0.001)}
    }

    @Test fun disconnectedDestinationExplainsWhyNavigationIsUnavailable() {
        val state=fixture().apply { selectedDestinationId="lab"; navigationNodes.clear(); markMapSaved() }
        compose.setContent { TurnApp(state) }
        compose.onNodeWithText("Lab 1 has no connected path yet. Connect its entrance in Map setup.").assertExists()
        compose.onNodeWithText("Start navigation").assertIsNotEnabled()
        compose.onNodeWithText("Open Map setup").assertExists()
    }

    @Test fun currentLocationListsRoomsAndUsesTheirEntranceWithoutInventingUnmappedStarts() {
        val state=fixture().apply {
            selectedDestinationId="lab"
            destinations.add(PointOfInterestEntity("library","canvas-f","Library","ROOM",10.0,15.0))
            markMapSaved()
        }
        compose.setContent {TurnApp(state)}
        // With a destination but no start, the main button asks where you are.
        compose.onNodeWithText("Start navigation").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Library")
        compose.onNodeWithText("Entrance not connected · pick on map instead").assertExists()
        compose.onAllNodesWithText("Library").onLast().performScrollTo().performClick()
        compose.runOnIdle {assertNull(state.manualStartPoint); assertFalse(state.liveRunning)}
        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.onNode(hasSetTextAction()).performTextInput("Lab")
        compose.onAllNodesWithText("Lab 1").onLast().performScrollTo().performClick()
        compose.runOnIdle {assertEquals(Offset(15f,3f),state.manualStartPoint); assertFalse(state.liveRunning)}
        compose.onNodeWithText("Lab 1 entrance").assertExists()
    }

    @Test fun labelledRoomWithoutEntranceIsSearchableAndExplainsMissingRoute() {
        val state=fixture().apply {destinations.add(PointOfInterestEntity("library","canvas-f","Library","ROOM",10.0,15.0)); markMapSaved()}
        compose.setContent {TurnApp(state,TurnAppActions(onDestinationSelected={state.selectedDestinationId=it}))}
        compose.onNodeWithText("Search places").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Library")
        waitForIme()
        compose.onAllNodesWithText("Library").onLast().performClick()
        // No start yet: the start sheet opens; dismiss it to read the route card.
        compose.onNodeWithText("Where are you now?").assertExists()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        compose.onNodeWithText("Library has no connected path yet. Connect its entrance in Map setup.").assertExists()
        compose.onNodeWithText("Start navigation").assertIsNotEnabled()
    }

    @Test fun existingRoomCanBeLinkedToVerifiedEntranceWithoutCreatingDuplicate() {
        val state=fixture().apply {
            destinations.add(PointOfInterestEntity("library","canvas-f","Library","ROOM",10.0,15.0))
            mapSetupSection="Paths"; selectedNodeId="start"; markMapSaved()
        }
        compose.setContent {TurnTheme {com.turn.fieldtest.ui.screens.MapSetupScreen(state,TurnAppActions())}}
        compose.onNodeWithText("Link an existing room").performScrollTo().performClick()
        compose.onNodeWithText("Library").performClick()
        compose.runOnIdle {
            assertEquals(2,state.destinations.size)
            assertEquals("library",state.navigationNodes.single {it.id=="start"}.poiId)
            assertEquals(3.0,state.destinations.single {it.id=="library"}.xMetres,0.0)
            assertTrue(state.hasUnsavedMapChanges)
            state.selectedNodeId="end";state.linkSelectedDestination("library")
            assertEquals("lab",state.navigationNodes.single {it.id=="end"}.poiId)
            assertEquals("library",state.navigationNodes.single {it.id=="start"}.poiId)
        }
    }

    @Test fun roomInteriorSelectsDestinationAwayFromItsMarker() {
        val state=fixture()
        state.destinationAreas.add(com.turn.fieldtest.data.repository.DestinationArea("lab",
            com.turn.fieldtest.core.MetricPolygon(listOf(com.turn.fieldtest.core.MetricPoint(0.0,0.0),
                com.turn.fieldtest.core.MetricPoint(20.0,0.0),com.turn.fieldtest.core.MetricPoint(20.0,20.0),com.turn.fieldtest.core.MetricPoint(0.0,20.0)))))
        var chosen: String?=null
        compose.setContent { TurnTheme { NavigationMap(state,Modifier.fillMaxSize(),onDestinationTapped={chosen=it}) } }
        compose.onNodeWithContentDescription("Floor map").performTouchInput { click(center) }
        compose.runOnIdle { assertEquals("lab",chosen) }
    }

    @Test fun followingCameraTracksFusedPositionWithoutMovingIt() {
        val state=fixture().apply {liveRunning=true;realLiveInitialized=true;realLivePosition=Offset(3f,3f)}
        var tapped: Offset?=null
        compose.setContent { TurnTheme { NavigationMap(state,Modifier.fillMaxSize(),showPosition=true,onTap={p,_->tapped=p}) } }
        compose.onNodeWithContentDescription("Following my position").assertExists()
        compose.runOnIdle { state.realLivePosition=Offset(6f,3f) }
        compose.onNodeWithContentDescription("Floor map").performTouchInput { click(center) }
        compose.runOnIdle {
            assertNotNull(tapped);assertEquals(6f,tapped!!.x,.05f);assertEquals(3f,tapped!!.y,.05f)
            assertEquals(Offset(6f,3f),state.realLivePosition)
        }
    }

    @Test fun draggingLiveMapAccumulatesMovementAndPausesFollowUntilRecentered() {
        val state=fixture().apply {liveRunning=true;realLiveInitialized=true;realLivePosition=Offset(10f,10f)}
        var tapped: Offset?=null
        compose.setContent {TurnTheme {NavigationMap(state,Modifier.fillMaxSize(),showPosition=true,onTap={p,_->tapped=p})}}
        compose.onNodeWithContentDescription("Following my position").assertExists()
        compose.onNodeWithContentDescription("Floor map").performTouchInput {
            down(center)
            repeat(24) {advanceEventTime(16); moveBy(Offset(5f,0f))}
            up()
        }
        compose.onNodeWithContentDescription("Center on my position").assertExists()
        compose.onNodeWithContentDescription("Floor map").performTouchInput {click(center)}
        compose.runOnIdle {
            // A frame-feedback bug loses almost all of these small deltas.
            assertNotNull(tapped); assertTrue("Drag must accumulate",tapped!!.x<9f)
            assertEquals(10f,tapped!!.y,.1f)
            state.realLivePosition=Offset(11f,10f)
        }
        val afterDrag=tapped
        compose.onNodeWithContentDescription("Floor map").performTouchInput {click(center)}
        compose.runOnIdle {assertEquals(afterDrag!!.x,tapped!!.x,.1f)}
        compose.onNodeWithContentDescription("Center on my position").performClick()
        compose.onNodeWithContentDescription("Floor map").performTouchInput {click(center)}
        compose.runOnIdle {assertEquals(11f,tapped!!.x,.1f); assertEquals(Offset(11f,10f),state.realLivePosition)}
    }

    @Test fun liveNavigationFocusRestoresAppTabsWhenStopped() {
        val state=fixture().apply {liveRunning=true;realLiveInitialized=true;realLivePosition=Offset(3f,3f)}
        compose.setContent { TurnApp(state) }
        compose.onNodeWithText("Buildings").assertDoesNotExist()
        compose.onNodeWithText("End navigation").assertIsDisplayed()
        compose.runOnIdle {state.liveRunning=false}
        compose.onNodeWithText("Buildings").assertIsDisplayed()
    }

    @Test fun appearanceAndDebugControlsPreserveLivePositionAndSession() {
        val state = fixture().apply {
            liveRunning = true; sessionLocked = true; realLiveInitialized = true
            realLivePosition = Offset(5f, 3f); routePoints = listOf(Offset(5f, 3f), Offset(15f, 3f))
        }
        val route = state.routePoints
        compose.setContent { TurnApp(state) }
        compose.onNodeWithContentDescription("Map options").performClick()
        compose.onAllNodes(isToggleable())[0].performClick()
        compose.onAllNodes(isToggleable())[1].performClick()
        compose.onNodeWithText("Particles").performScrollTo().assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(state.darkTheme); assertTrue(state.debugOverlays); assertTrue(state.liveRunning)
            assertTrue(state.sessionLocked); assertEquals(Offset(5f, 3f), state.realLivePosition); assertEquals(route, state.routePoints)
        }
    }

    @Test fun unsavedMapCannotStartOrLaunchAr() {
        val state = fixture().apply { draftWalkablePolygon[0] = Offset(1f, 1f) }
        compose.setContent { TurnApp(state) }
        compose.onNodeWithText("Start navigation").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("Tools", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Create a map with your phone").performScrollTo().assertIsNotEnabled()
    }

    @Test fun recenterAndTapsUseVisibleMapAreaBesideTheFloatingCard() {
        val state = fixture().apply { liveRunning = true; realLivePosition = Offset(2f, 3f) }
        var tapped: Offset? = null
        compose.setContent { TurnTheme { NavigationMap(state, Modifier.fillMaxSize(), showPosition = true,
            viewportPadding = PaddingValues(start = 100.dp, top = 50.dp, bottom = 120.dp),
            onTap = { point, _ -> tapped = point }) } }
        compose.onNodeWithContentDescription("Center on my position").performClick()
        val left = with(compose.density) { 100.dp.toPx() }
        val top = with(compose.density) { 50.dp.toPx() }
        val bottom = with(compose.density) { 120.dp.toPx() }
        compose.onNodeWithContentDescription("Floor map").performTouchInput {
            click(Offset((width + left) / 2f, (height + top - bottom) / 2f))
        }
        compose.runOnIdle {
            assertNotNull(tapped); assertEquals(2f, tapped!!.x, .02f); assertEquals(3f, tapped!!.y, .02f)
            assertEquals(Offset(2f, 3f), state.realLivePosition)
        }
    }

    @Test fun blankFloorOffersSetupInsteadOfStartingUnusableTracking() {
        val state = fixture().apply { draftWalkablePolygon.clear(); markMapSaved() }
        compose.setContent { TurnApp(state) }
        compose.onNodeWithText("Start navigation").assertDoesNotExist()
        compose.onNodeWithText("Prepare this floor").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(TurnDestination.FLOOR_EDITOR, state.destination) }
    }
}
