package com.turn.fieldtest.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.turn.fieldtest.core.FloorAlignment
import com.turn.fieldtest.data.local.FloorEntity
import com.turn.fieldtest.data.local.VenueEntity
import com.turn.fieldtest.data.local.WalkableRegionEntity
import com.turn.fieldtest.data.repository.ProjectContext
import com.turn.fieldtest.data.repository.ProjectSummary
import com.turn.fieldtest.data.repository.SavedFloorMap
import com.turn.fieldtest.ui.model.*
import com.turn.fieldtest.ui.screens.DiagnosticsScreen
import com.turn.fieldtest.ui.theme.TurnTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Home testing tools: floor heights and floor alignment (Link floors), and the Diagnostics threshold editor. */
@RunWith(AndroidJUnit4::class)
class HomeTestingToolsUiTest {
    @get:Rule val compose = createComposeRule()

    private fun summary(floor: String, level: Int) = ProjectSummary("home", "f-$floor", "Home", floor, 0, 0, 0, 0, floorLevel = level)
    private val square = """[{"x":0,"y":0},{"x":20,"y":0},{"x":20,"y":20},{"x":0,"y":20}]"""
    private fun saved(floor: String, level: Int) = SavedFloorMap(VenueEntity("home", "Home", null, 1, 1),
        FloorEntity("f-$floor", "home", floor, level, 20.0, 20.0, createdAtEpochMillis = 1, updatedAtEpochMillis = 1), null,
        listOf(WalkableRegionEntity("r-$floor", "f-$floor", "Walkable", square, true, 1)), emptyList(), emptyList(), emptyList(), false)

    private fun house() = TurnAppState().apply {
        mode = DataMode.REAL_DEVICE; realMapReady = true
        projects = listOf(summary("Ground", 0), summary("Upstairs", 1))
        activeProject = ProjectContext("home", "f-Ground"); venueName = "Home"; floorName = "Ground"
        clearMapDraft(force = true); floorWidthMetres = 20f; floorHeightMetres = 20f
        draftWalkablePolygon.addAll(listOf(Offset.Zero, Offset(20f, 0f), Offset(20f, 20f), Offset(0f, 20f)))
        markMapSaved()
        buildingFloorMaps = mapOf("f-Ground" to saved("Ground", 0), "f-Upstairs" to saved("Upstairs", 1))
        destination = TurnDestination.FLOOR_LINKS
    }

    @Test fun storeyHeightFillsEveryFloorAndSaves() {
        var heights: Map<String, Double?>? = null
        compose.setContent { TurnApp(house(), TurnAppActions(onFloorElevationsSaved = { heights = it })) }
        compose.onNodeWithText("Not set · standard 3.5 m storeys").performScrollTo().assertExists()
        compose.onNodeWithText("Save heights").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Fill all floors").performScrollTo().performClick() // default 2.7 m
        compose.onNodeWithText("Save heights").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(mapOf("f-Ground" to 0.0, "f-Upstairs" to 2.7), heights) }
    }

    @Test fun twoPointsOnEachFloorAlignThem() {
        var saved: Pair<String, FloorAlignment?>? = null
        compose.setContent { TurnApp(house(), TurnAppActions(onFloorAlignmentSaved = { id, a -> saved = id to a })) }
        compose.onNodeWithText("Align two floors").performScrollTo().performClick()
        listOf("Reference floor map", "Floor to align map").forEach { map ->
            compose.onNodeWithContentDescription(map).performScrollTo().performTouchInput { click(Offset(width * 0.25f, height * 0.5f)) }
            compose.onNodeWithContentDescription(map).performScrollTo().performTouchInput { click(Offset(width * 0.75f, height * 0.5f)) }
        }
        compose.onNodeWithText("Save alignment").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle {
            val (floor, alignment) = requireNotNull(saved)
            assertEquals("f-Upstairs", floor)
            assertEquals("f-Ground", alignment!!.toFloorId)
            assertEquals(0.0, alignment.transform.rotationDegrees, 1.0)
            assertTrue(alignment.maxResidualMetres < 0.2)
        }
    }

    @Test fun thresholdOverridesAreValidatedSavedAndReset() {
        val s = TurnAppState().apply { mode = DataMode.REAL_DEVICE }
        compose.setContent { TurnTheme { DiagnosticsScreen(s, compact = true) } }
        compose.onNodeWithText("Show thresholds").performScrollTo().performClick()
        val field = compose.onNodeWithText("Nominal storey height (when floor heights are not set)")
        field.performScrollTo().performTextInput("9")
        compose.onNodeWithText("Allowed 2–6 m").performScrollTo().assertExists()
        field.performTextClearance(); field.performTextInput("2.8")
        compose.onAllNodesWithText("Use")[0].performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(mapOf("vertical.floorHeightMetres" to 2.8), s.floorDetectionOverrides) }
        compose.onNodeWithText("Reset to defaults").performScrollTo().performClick()
        compose.onNodeWithText("Reset").performClick() // confirm in the dialog
        compose.runOnIdle { assertTrue(s.floorDetectionOverrides.isEmpty()) }
    }
}
