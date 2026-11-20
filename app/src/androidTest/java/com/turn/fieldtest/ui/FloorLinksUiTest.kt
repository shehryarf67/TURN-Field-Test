package com.turn.fieldtest.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.turn.fieldtest.core.ConnectorDraft
import com.turn.fieldtest.core.ConnectorKind
import com.turn.fieldtest.core.MetricPoint
import com.turn.fieldtest.data.local.FloorEntity
import com.turn.fieldtest.data.local.VenueEntity
import com.turn.fieldtest.data.local.VerticalTransitionEntity
import com.turn.fieldtest.data.local.WalkableRegionEntity
import com.turn.fieldtest.data.repository.FloorConnector
import com.turn.fieldtest.data.repository.ProjectContext
import com.turn.fieldtest.data.repository.ProjectSummary
import com.turn.fieldtest.data.repository.SavedFloorMap
import com.turn.fieldtest.ui.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FloorLinksUiTest {
    @get:Rule val compose = createComposeRule()

    private fun summary(floor: String, level: Int, venue: String = "uni") =
        ProjectSummary(venue, "f-$floor", "University", floor, 0, 0, 0, 0, floorLevel = level)

    private val square = """[{"x":0,"y":0},{"x":20,"y":0},{"x":20,"y":20},{"x":0,"y":20}]"""
    private fun saved(floor: String, level: Int) = SavedFloorMap(VenueEntity("uni", "University", null, 1, 1),
        FloorEntity("f-$floor", "uni", floor, level, 20.0, 20.0, createdAtEpochMillis = 1, updatedAtEpochMillis = 1), null,
        listOf(WalkableRegionEntity("r-$floor", "f-$floor", "Walkable", square, true, 1)), emptyList(), emptyList(), emptyList(), false)

    private fun link(verified: Boolean) = FloorConnector(VerticalTransitionEntity(id = "LINK-1", fromFloorId = "f-C-F3", toFloorId = "f-C-F4",
        kind = "LIFT", fromXMetres = 5.0, fromYMetres = 5.0, toXMetres = 6.0, toYMetres = 6.0, label = "Main lift", travelTimeSeconds = 25.0,
        accessible = true, verifiedOnSite = verified), ConnectorKind.LIFT, "C-F3", 3, "C-F4", 4)

    private fun state(floors: Int = 2) = TurnAppState().apply {
        mode = DataMode.REAL_DEVICE; realMapReady = true
        projects = listOf(summary("C-F3", 3), summary("C-F4", 4)).take(floors)
        activeProject = ProjectContext("uni", if (floors == 2) "f-C-F4" else "f-C-F3"); venueName = "University"; floorName = "C-F4"
        clearMapDraft(force = true); floorWidthMetres = 20f; floorHeightMetres = 20f
        draftWalkablePolygon.addAll(listOf(Offset.Zero, Offset(20f, 0f), Offset(20f, 20f), Offset(0f, 20f)))
        markMapSaved()
        buildingFloorMaps = mapOf("f-C-F3" to saved("C-F3", 3), "f-C-F4" to saved("C-F4", 4))
        destination = TurnDestination.FLOOR_LINKS
    }

    @Test fun savedLinksShowStatusAndCanBeMarkedCheckedOrRemoved() {
        val s = state().apply { floorConnectors = listOf(link(verified = false)) }
        var verified: Pair<String, Boolean>? = null; var removed: String? = null; var refreshed = 0
        compose.setContent { TurnApp(s, TurnAppActions(onConnectorVerified = { id, v -> verified = id to v },
            onConnectorDeleted = { removed = it }, onFloorLinksRefresh = { refreshed++ })) }
        compose.onNodeWithText("Main lift").assertExists()
        compose.onNodeWithText("Lift · 25 s · step-free").assertExists()
        compose.onNodeWithText("Not yet checked on site").assertExists()
        compose.onNodeWithText("Mark checked on site").performScrollTo().performClick()
        compose.onNodeWithText("Remove").performScrollTo().performClick()
        compose.onNodeWithText("Remove Main lift?").assertExists()
        compose.onAllNodesWithText("Remove").filterToOne(hasAnyAncestor(isDialog())).performClick()
        compose.runOnIdle { assertEquals("LINK-1" to true, verified); assertEquals("LINK-1", removed); assertTrue(refreshed >= 1) }
    }

    @Test fun newLinkNeedsAPointOnBothFloorsThenSavesWithStairDefaults() {
        val s = state()
        var saved: ConnectorSave? = null
        compose.setContent { TurnApp(s, TurnAppActions(onConnectorSaved = { saved = it })) }
        compose.onNodeWithText("New link").performClick()
        compose.onNodeWithText("Name, e.g. Stairs 1 or Main lift").performTextInput("Stairs east")
        compose.onNodeWithText("Tap the link's position on both floors").assertExists()
        compose.onNodeWithText("Save link").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithContentDescription("From floor map").performScrollTo().performTouchInput { click(center) }
        compose.onNodeWithContentDescription("To floor map").performScrollTo().performTouchInput { click(center) }
        compose.onNodeWithText("Save link").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle {
            val save = requireNotNull(saved)
            assertEquals("f-C-F3" to "f-C-F4", save.draft.fromFloorId to save.draft.toFloorId)
            assertEquals(ConnectorKind.STAIRS, save.draft.kind)
            assertEquals("Stairs east", save.draft.label)
            assertEquals(15.0, save.draft.travelTimeSeconds, 0.0)
            assertEquals(10.0, save.draft.from.x, 1.0); assertEquals(10.0, save.draft.from.y, 1.0)
            assertFalse(save.accessible); assertTrue(save.bidirectional); assertFalse(save.verifiedOnSite)
            assertNull(save.replacingId)
        }
    }

    @Test fun movingAPointKeepsEverythingElseInTheEditor() {
        val s = state()
        var saved: ConnectorSave? = null
        compose.setContent { TurnApp(s, TurnAppActions(onConnectorSaved = { saved = it })) }
        compose.onNodeWithText("New link").performClick()
        compose.onNodeWithText("Name, e.g. Stairs 1 or Main lift").performTextInput("Lift A")
        compose.onNodeWithContentDescription("From floor map").performScrollTo().performTouchInput { click(center) }
        compose.onNodeWithContentDescription("To floor map").performScrollTo().performTouchInput { click(center) }
        compose.onNodeWithText("Lift").performScrollTo().performClick()
        // Moving the first point again must not undo the second point, the name or the kind.
        compose.onNodeWithContentDescription("From floor map").performScrollTo().performTouchInput { click(Offset(width * 0.25f, height * 0.5f)) }
        compose.onNodeWithText("Save link").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle {
            val save = requireNotNull(saved)
            assertEquals(ConnectorKind.LIFT, save.draft.kind); assertTrue(save.accessible)
            assertEquals("Lift A", save.draft.label)
            assertEquals(25.0, save.draft.travelTimeSeconds, 0.0)
            assertTrue("moved from point", save.draft.from.x < 8.0)
            assertEquals(10.0, save.draft.to.x, 1.0)
        }
    }

    @Test fun choosingALiftMakesItStepFreeAndSuggestionsOpenForReview() {
        val s = state().apply { connectorSuggestions = listOf(ConnectorDraft(ConnectorKind.STAIRS, "f-C-F3", MetricPoint(5.0, 5.0),
            "f-C-F4", MetricPoint(5.0, 5.0), "Stairs 1", 15.0)) }
        var saved: ConnectorSave? = null; var suggested = false
        compose.setContent { TurnApp(s, TurnAppActions(onConnectorSaved = { saved = it }, onConnectorSuggestionsRequested = { suggested = true })) }
        compose.onNodeWithText("Suggest stair links").performClick()
        compose.onNodeWithText("Suggested from stair landings").assertExists()
        compose.onNodeWithText("Review & add").performScrollTo().performClick()
        compose.onNodeWithText("Lift").performClick()
        compose.onNodeWithText("Save link").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle {
            assertTrue(suggested)
            val save = requireNotNull(saved)
            assertEquals(ConnectorKind.LIFT, save.draft.kind)
            assertEquals("Stairs 1", save.draft.label)
            assertTrue(save.accessible) // lifts default to step-free
            assertEquals(25.0, save.draft.travelTimeSeconds, 0.0) // default follows the kind until typed
            assertFalse(save.verifiedOnSite)
        }
    }

    @Test fun aSingleFloorBuildingExplainsHowToAddAnother() {
        compose.setContent { TurnApp(state(floors = 1)) }
        compose.onNodeWithText("This building has one floor").assertExists()
        compose.onNodeWithText("New link").assertDoesNotExist()
    }
}
