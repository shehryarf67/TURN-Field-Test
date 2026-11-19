package com.turn.fieldtest.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.turn.fieldtest.data.repository.ProjectContext
import com.turn.fieldtest.data.repository.ProjectSummary
import com.turn.fieldtest.ui.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BuildingFloorsUiTest {
    @get:Rule val compose = createComposeRule()

    private fun summary(floor: String, level: Int, venue: String = "uni", venueName: String = "University") =
        ProjectSummary(venue, "f-$floor", venueName, floor, 0, 0, 0, 0, floorLevel = level)

    /** An open C-F4 inside a building that also holds C-F3, plus an unrelated single-floor building. */
    private fun state() = TurnAppState().apply {
        mode = DataMode.REAL_DEVICE; realMapReady = true
        projects = listOf(summary("C-F3", 3), summary("C-F4", 4), summary("Ground", 0, venue = "lib", venueName = "Library"))
        activeProject = ProjectContext("uni", "f-C-F4"); venueName = "University"; floorName = "C-F4"
        clearMapDraft(force = true); floorWidthMetres = 20f; floorHeightMetres = 20f
        draftWalkablePolygon.addAll(listOf(Offset.Zero, Offset(20f, 0f), Offset(20f, 20f), Offset(0f, 20f)))
        markMapSaved()
    }

    @Test fun buildingsGroupFloorsAndAddAnEmptyFloor() {
        val s = state().apply { destination = TurnDestination.VENUES }
        var added: Triple<String, String, Int>? = null
        compose.setContent { TurnApp(s, TurnAppActions(onFloorAdded = { v, n, l -> added = Triple(v, n, l) })) }
        compose.onNodeWithText("Your buildings").assertExists()
        compose.onNodeWithText("2 floors").assertExists()
        compose.onNodeWithText("1 floor").assertExists()
        compose.onNodeWithText("Open now").assertExists() // C-F4 is the open floor
        compose.onAllNodesWithText("Add floor").onFirst().performScrollTo().performClick()
        compose.onNodeWithText("Floor name").performTextInput("c-f3") // clashes, case-insensitively
        compose.onNodeWithText("This building already has a floor with that name").assertExists()
        compose.onNodeWithText("Floor name").performTextClearance()
        compose.onNodeWithText("Floor name").performTextInput("C-F5")
        compose.onNodeWithText("Level (0 = ground, -1 = basement)").performTextInput("5")
        compose.onAllNodesWithText("Add floor").filterToOne(hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        compose.runOnIdle { assertEquals(Triple("uni", "C-F5", 5), added) }
    }

    @Test fun importingABlockFloorDefaultsToItsBuildingAndNeverReplaces() {
        val s = state().apply { destination = TurnDestination.VENUES }
        var intoBuilding: Pair<String, String>? = null; var replaced = false
        compose.setContent { TurnApp(s, TurnAppActions(onBundledFloorAddedToBuilding = { f, v -> intoBuilding = f to v },
            onBundledMapperImport = { _, _ -> replaced = true })) }
        // Bundled order is C-F4, C-F3, B-GF, B-F1, C-F2, C-F5…; the first two are already added ("Re-import").
        compose.onAllNodesWithText("Add")[3].performScrollTo().performClick()
        compose.onNodeWithText("Floor: C-F5 · Change").assertExists()
        compose.onNodeWithText("Add to University").assertIsSelected()
        compose.onNodeWithText("Replace all existing maps and fingerprints").assertDoesNotExist()
        compose.onNodeWithText("Add C-F5").performClick()
        compose.runOnIdle { assertEquals("C-F5" to "uni", intoBuilding); assertFalse(replaced) }
    }

    @Test fun reimportingAFloorTheBuildingHasDefaultsToANewBuilding() {
        val s = state().apply { destination = TurnDestination.VENUES }
        var intoBuilding: Pair<String, String>? = null; var imported: String? = null
        compose.setContent { TurnApp(s, TurnAppActions(onBundledFloorAddedToBuilding = { f, v -> intoBuilding = f to v },
            onBundledMapperImport = { f, _ -> imported = f })) }
        compose.onAllNodesWithText("Re-import").onFirst().performScrollTo().performClick() // C-F4
        compose.onNodeWithText("New building").assertIsSelected()
        compose.onNodeWithText("Add to University (already has C-F4)").assertIsNotEnabled()
        compose.onNodeWithText("Import C-F4").performClick()
        compose.runOnIdle { assertEquals("C-F4", imported); assertNull(intoBuilding) }
    }

    @Test fun addFloorFlagsATakenLevel() {
        val s = state().apply { destination = TurnDestination.VENUES }
        compose.setContent { TurnApp(s) }
        compose.onAllNodesWithText("Add floor").onFirst().performScrollTo().performClick()
        compose.onNodeWithText("Floor name").performTextInput("Mezzanine")
        compose.onNodeWithText("Level (0 = ground, -1 = basement)").performTextInput("3")
        compose.onNodeWithText("Another floor already uses this level").assertExists()
        compose.onAllNodesWithText("Add floor").filterToOne(hasClickAction() and hasAnyAncestor(isDialog())).assertIsNotEnabled()
    }

    @Test fun floorSwitcherOpensAnotherFloorOfTheSameBuilding() {
        val s = state().apply { destination = TurnDestination.LIVE_LOCATE }
        var opened: ProjectContext? = null
        compose.setContent { TurnApp(s, TurnAppActions(onProjectOpened = { c, _ -> opened = c })) }
        compose.onNodeWithContentDescription("Floor C-F4, change floor").performClick()
        compose.onNodeWithText("Ground", substring = true).assertDoesNotExist() // other buildings' floors are not offered
        compose.onNodeWithText("4 · C-F4").assertIsNotEnabled()
        compose.onNodeWithText("3 · C-F3").performClick()
        compose.runOnIdle { assertEquals(ProjectContext("uni", "f-C-F3"), opened) }
    }

    @Test fun floorSwitcherProtectsUnsavedEditsAndRunningSessions() {
        val s = state().apply { destination = TurnDestination.LIVE_LOCATE; draftWalkablePolygon[0] = Offset(1f, 1f) }
        var opened: ProjectContext? = null
        compose.setContent { TurnApp(s, TurnAppActions(onProjectOpened = { c, _ -> opened = c })) }
        compose.onNodeWithContentDescription("Floor C-F4, change floor").performClick()
        compose.onNodeWithText("Save map changes before switching floors").assertExists()
        compose.onNodeWithText("3 · C-F3").assertIsNotEnabled()
        compose.runOnIdle { assertNull(opened) }
    }

    @Test fun floorSwitcherIsLockedWhileASessionIsRecording() {
        val s = state().apply { sessionLocked = true; surveyRunning = true }
        compose.setContent { com.turn.fieldtest.ui.theme.TurnTheme { com.turn.fieldtest.ui.screens.FloorSwitcher(s, TurnAppActions()) } }
        compose.onNodeWithContentDescription("Floor C-F4, change floor").assertIsNotEnabled()
        compose.runOnIdle { s.sessionLocked = false; s.surveyRunning = false }
        compose.onNodeWithContentDescription("Floor C-F4, change floor").assertIsEnabled()
    }
}
