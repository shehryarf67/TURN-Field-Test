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
        compose.runOnIdle {
            assertEquals("lab", state.selectedDestinationId)
            assertEquals(Offset(2f, 2f), state.manualStartPoint)
            assertFalse(state.realLiveInitialized)
            assertNull(state.realLivePosition)
        }
    }
}
