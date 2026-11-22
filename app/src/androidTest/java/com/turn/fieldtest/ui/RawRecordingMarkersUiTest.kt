package com.turn.fieldtest.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.turn.fieldtest.data.repository.ProjectContext
import com.turn.fieldtest.data.repository.ProjectSummary
import com.turn.fieldtest.ui.model.DataMode
import com.turn.fieldtest.ui.screens.DiagnosticsScreen
import com.turn.fieldtest.ui.theme.TurnTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Plan Step 11: Diagnostics marker buttons write the TraceMarkers grammar, only while recording. */
@RunWith(AndroidJUnit4::class)
class RawRecordingMarkersUiTest {
    @get:Rule val compose = createComposeRule()

    private fun summary(venue: String, floor: String, name: String, level: Int) = ProjectSummary(venue, floor, "Building $venue", name,
        referencePointCount = 0, fingerprintPointCount = 0, surveySessionCount = 0, positioningSessionCount = 0, floorLevel = level)

    private fun state(recording: Boolean) = TurnAppState().apply {
        mode = DataMode.REAL_DEVICE
        activeProject = ProjectContext("v", "f3")
        projects = listOf(summary("v", "f4", "Floor 4", 4), summary("v", "f3", "Floor 3", 3), summary("other", "x", "Library", 0))
        rawRecording = recording
    }

    @Test fun markersAreDisabledUntilRecording() {
        compose.setContent { TurnTheme { DiagnosticsScreen(state(recording = false), compact = true) } }
        listOf("Start stairs up", "Start stairs down", "Enter lift up", "Enter lift down", "Standing still", "Floor 3", "Floor 4")
            .forEach { compose.onNodeWithText(it).performScrollTo().assertIsNotEnabled() }
        compose.onNodeWithText("Start recording to add markers").performScrollTo().assertIsDisplayed()
    }

    @Test fun buttonsWriteTheGrammarAndOnlyThisBuildingsFloorsAreOffered() {
        val written = mutableListOf<String>()
        compose.setContent { TurnTheme { DiagnosticsScreen(state(recording = true), compact = true, onRawMarker = { written += it }) } }
        listOf("Start stairs up", "Start stairs down", "Enter lift up", "Enter lift down", "Standing still", "Floor 4")
            .forEach { compose.onNodeWithText(it).performScrollTo().performClick() }
        assertEquals(listOf("VERTICAL_START STAIRS UP", "VERTICAL_START STAIRS DOWN", "VERTICAL_START LIFT UP",
            "VERTICAL_START LIFT DOWN", "STILL", "ARRIVED f4"), written)
        compose.onNodeWithText("Arrived on…").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Library").assertCountEquals(0)
    }
}
