package com.turn.fieldtest.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.turn.fieldtest.data.local.*
import com.turn.fieldtest.data.repository.ProjectContext
import com.turn.fieldtest.ui.model.*
import com.turn.fieldtest.ui.screens.*
import com.turn.fieldtest.ui.theme.TurnTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** UI fixtures only: these tests never feed simulated evidence into the physical runtime. */
@RunWith(AndroidJUnit4::class)
class PrototypeWorkflowTest {
    @get:Rule val compose = createComposeRule()
    private fun fixture() = TurnAppState().apply {
        mode = DataMode.REAL_DEVICE; activeProject = ProjectContext("v", "f"); realMapReady = true
        clearMapDraft(force = true); floorWidthMetres = 20f; floorHeightMetres = 20f
        floorName = "University floor"; venueName = "Test venue"
        draftWalkablePolygon.addAll(listOf(Offset(0f, 0f), Offset(20f, 0f), Offset(20f, 20f), Offset(0f, 20f)))
        markMapSaved()
    }
    @Test fun mapHitTestingStaysAlignedWhenViewportHeightChanges() {
        val state = fixture()
        val height = mutableStateOf(300.dp)
        var point: Offset? = null
        compose.setContent { TurnTheme {
            NavigationMap(state, Modifier.height(height.value), onTap = { p, _ -> point = p })
        } }
        compose.onNodeWithContentDescription("Floor map").performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(10f, point!!.y, 0.01f); height.value = 500.dp }
        compose.onNodeWithContentDescription("Floor map").performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(10f, point!!.x, 0.01f); assertEquals(10f, point!!.y, 0.01f) }
    }
    @Test fun mapTapPlacesNodeAndDestinationAndSaveActionKeepsDraft() {
        val state = fixture()
        var saved = false
        compose.setContent { TurnTheme { Surface(Modifier.fillMaxSize()) {
            MapSetupScreen(state, TurnAppActions(onMapSaved = { saved = true; state.markMapSaved() }))
        } } }
        compose.onNodeWithText("Paths", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Node", useUnmergedTree = true).performClick()
        compose.onNodeWithContentDescription("Floor map").performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(1, state.navigationNodes.size); assertTrue(state.hasUnsavedMapChanges) }
        compose.onNodeWithText("Destination name, e.g. Lab 1").performScrollTo().performTextInput("Lab 1")
        compose.onNodeWithText("Save destination in draft").performScrollTo().performClick()
        compose.onNodeWithText("Save map").performClick()
        compose.runOnIdle { assertTrue(saved); assertEquals("Lab 1", state.destinations.single().name); assertFalse(state.hasUnsavedMapChanges) }
    }
    @Test fun surveyPointSelectionShowsItsOwnPersistedProgress() {
        val state = fixture().apply {
            referencePoints.addAll(listOf(MapPointUi("a", "f", Offset(2f, 2f), "RP-01"), MapPointUi("b", "f", Offset(8f, 2f), "RP-02")))
            selectedSurveyReferencePointId = "a"; surveyTargetSnapshots = 8; surveyAcceptedSnapshots = 8
            surveyPointProgress = mapOf("a" to SurveyPointProgress("COMPLETED", 8, 8, 6))
        }
        compose.setContent { TurnTheme { Surface(Modifier.fillMaxSize()) { FieldSurveyScreen(state, TurnAppActions()) } } }
        compose.onNodeWithText("8 / 8 fresh snapshots · 6 APs").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("RP-02 · Not surveyed", useUnmergedTree = true).performClick()
        compose.onNodeWithText("0 / 8 fresh snapshots · 0 APs").performScrollTo().assertIsDisplayed()
    }
    @Test fun destinationAndDeclaredStartCanBeChosenWithoutInventingAPosition() {
        val state = fixture().apply {
            referencePoints.add(MapPointUi("a", "f", Offset(2f, 2f), "RP-01"))
            destinations.add(PointOfInterestEntity("lab", "f", "Lab 1", "ROOM", 10.0, 2.0))
        }
        compose.setContent { TurnTheme { Surface(Modifier.fillMaxSize()) {
            NavigationScreen(state, TurnAppActions(onDestinationSelected = { state.selectedDestinationId = it }))
        } } }
        compose.onNodeWithText("Choose destination").performClick()
        compose.onNodeWithText("Lab 1").performClick()
        compose.onNodeWithText("At a reference point").performScrollTo().performClick()
        compose.onNodeWithText("I am at RP-01").performClick()
        compose.onNodeWithText("Up", useUnmergedTree = true).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals("lab", state.selectedDestinationId)
            assertEquals(Offset(2f, 2f), state.manualStartPoint)
            assertFalse(state.realLiveInitialized)
            assertNull(state.realLivePosition)
            assertEquals(90.0, state.initialHeadingDegrees, 0.0)
        }
    }

    @Test fun surveyDoesNotInventCompassDirectionOrRequireAFacingNote() {
        val state = fixture().apply {
            referencePoints.add(MapPointUi("a", "f", Offset(2f, 2f), "RP-01"))
            selectedSurveyReferencePointId = "a"
        }
        var metadata: SurveyCaptureMetadata? = null
        compose.setContent { TurnTheme { Surface(Modifier.fillMaxSize()) {
            SurveyScreen(state, true, onStartRealSurvey = { metadata = it })
        } } }
        compose.onNodeWithText("North-facing").assertDoesNotExist()
        compose.onNodeWithText("Begin collection").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals("a", metadata!!.referencePointId)
            assertNull(metadata!!.orientationLabel)
            assertEquals("", metadata!!.researcherNotes)
        }
    }

    @Test fun freshWifiRequestRemainsAvailableWhileFusedTrackingIsInitialized() {
        val state = fixture().apply {
            liveRunning = true; realLiveInitialized = true
            realWifiEvidenceStatus = "Scan not accepted: Android throttled · no new requested fix"
        }
        var requested = false
        compose.setContent { TurnTheme { Surface(Modifier.fillMaxSize()) {
            NavigationScreen(state, TurnAppActions(onRealLiveScanRequested = { requested = true }))
        } } }
        compose.onNodeWithText("Request fresh Wi-Fi fix").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(requested) }
        compose.onNodeWithText(state.realWifiEvidenceStatus).performScrollTo().assertIsDisplayed()
    }
    @Test fun fullscreenExitKeepsPositionAndRoute() {
        val state = fixture().apply {
            destination = TurnDestination.LIVE_LOCATE
            realLivePosition = Offset(5f, 5f)
            routePoints = listOf(Offset(5f, 5f), Offset(15f, 5f))
        }
        val position = state.realLivePosition
        val route = state.routePoints
        compose.setContent { TurnApp(state, TurnAppActions()) }
        compose.onNodeWithContentDescription("Full screen map").performClick()
        compose.onNodeWithText("Landscape").performClick()
        compose.runOnIdle { assertEquals(NavigationViewOrientation.LANDSCAPE, state.navigationViewOrientation) }
        compose.onNodeWithText("Exit full screen").performClick()
        compose.onNodeWithContentDescription("Full screen map").assertIsDisplayed()
        compose.runOnIdle {
            assertFalse(state.navigationFullscreen)
            assertEquals(position, state.realLivePosition)
            assertEquals(route, state.routePoints)
        }
    }

    @Test fun startingRequiresExplicitHeadingAndPointAheadCalculatesLeft() {
        val state = fixture().apply { manualStartPoint = Offset(10f, 10f) }
        compose.setContent { TurnTheme { Surface(Modifier.fillMaxSize()) {
            NavigationScreen(state, TurnAppActions())
        } } }
        compose.onNodeWithText("Start navigation").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Point ahead on map").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Floor map").performTouchInput {
            click(Offset(center.x - width * 0.23f, center.y))
        }
        compose.runOnIdle {
            assertTrue(state.headingChoiceConfirmed)
            assertEquals(180.0, state.initialHeadingDegrees, 0.01)
            assertEquals(Offset(10f, 10f), state.manualStartPoint)
            assertFalse(state.pickingHeading)
        }
        compose.onNodeWithText("Start navigation").performScrollTo().assertIsEnabled()
    }

    @Test fun liveHeadingControlsDeclareLeftWithoutMovingOrClearingTrails() {
        val state = fixture().apply {
            liveRunning = true; sessionLocked = true; realLiveInitialized = true
            realLivePosition = Offset(18f, 5f); realLiveHeadingDegrees = 0.0
            realRawPdrTrail = listOf(Offset(17f, 5f), Offset(18f, 5f))
            realFusedTrail = realRawPdrTrail
        }
        var declared: Double? = null
        compose.setContent { TurnTheme { Surface(Modifier.fillMaxSize()) {
            NavigationScreen(state, TurnAppActions(onRealHeadingAligned = { declared = it }))
        } } }
        compose.onNodeWithText("Correct walking direction").performScrollTo().performClick()
        compose.onNodeWithText("Which way are you walking on the map?").performScrollTo()
        compose.onNodeWithText("Left", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Align to this direction").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(180.0, declared!!, 0.0)
            assertEquals(Offset(18f, 5f), state.realLivePosition)
            assertEquals(2, state.realRawPdrTrail.size)
            assertEquals(2, state.realFusedTrail.size)
        }
        compose.onNodeWithText("Reverse heading (180°)").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(180.0, declared!!, 0.0) }
    }
}
