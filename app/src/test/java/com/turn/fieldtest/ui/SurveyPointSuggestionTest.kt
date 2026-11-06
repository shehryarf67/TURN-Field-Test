package com.turn.fieldtest.ui

import androidx.compose.ui.geometry.Offset
import com.turn.fieldtest.core.*
import com.turn.fieldtest.data.local.NavigationEdgeEntity
import com.turn.fieldtest.data.local.NavigationNodeEntity
import com.turn.fieldtest.data.local.PointOfInterestEntity
import com.turn.fieldtest.data.repository.ProjectContext
import com.turn.fieldtest.ui.model.DataMode
import com.turn.fieldtest.ui.model.EditorTool
import kotlin.test.*

class SurveyPointSuggestionTest {
    private val square = MapFloor("F", listOf(MetricPolygon(listOf(MetricPoint(0.0, 0.0), MetricPoint(20.0, 0.0),
        MetricPoint(20.0, 20.0), MetricPoint(0.0, 20.0)))))

    @Test fun junctionsAndEntrancesComeFirstAndExistingPointsAreRespected() {
        // An L-shaped path: end → corner → entrance of a room.
        val nodes = listOf(NavigationNode("end", "F", MetricPoint(2.0, 2.0)), NavigationNode("corner", "F", MetricPoint(14.0, 2.0)),
            NavigationNode("door", "F", MetricPoint(14.0, 14.0), poiId = "lab"))
        val edges = listOf(NavigationEdge("e1", "end", "corner", 12.0), NavigationEdge("e2", "corner", "door", 12.0))
        val existing = listOf(MetricPoint(8.0, 2.0))
        val points = SurveyPointPlanner.suggest(square, nodes, edges, existing, spacingMetres = 3.0)
        // The 90° corner leads; the path's centre line follows before its dead end and the room entrance.
        assertEquals(SuggestedSurveyPoint(MetricPoint(14.0, 2.0), SurveyPointReason.JUNCTION), points[0])
        assertTrue(points.any { it.reason == SurveyPointReason.JUNCTION && it.position == MetricPoint(2.0, 2.0) })
        assertTrue(points.any { it.reason == SurveyPointReason.ENTRANCE && it.position == MetricPoint(14.0, 14.0) })
        assertTrue(points.indexOfFirst { it.reason == SurveyPointReason.CORRIDOR } < points.indexOfFirst { it.reason == SurveyPointReason.ENTRANCE })
        assertTrue(points.none { it.position.distanceTo(existing[0]) < 3.0 }, "suggestions must keep clear of saved points")
        assertTrue(points.all { square.isWalkable(it.position) })
    }

    @Test fun withoutPathsAGridStillCoversOpenSpace() {
        val points = SurveyPointPlanner.suggest(square, emptyList(), emptyList(), emptyList(), spacingMetres = 5.0)
        assertEquals(16, points.size)
        assertTrue(points.all { it.reason == SurveyPointReason.OPEN_AREA })
    }

    private fun editableState() = TurnAppState().apply {
        mode = DataMode.REAL_DEVICE; realMapReady = true; activeProject = ProjectContext("v", "f")
        clearMapDraft(force = true); floorWidthMetres = 20f; floorHeightMetres = 20f
        draftWalkablePolygon.addAll(listOf(Offset.Zero, Offset(20f, 0f), Offset(20f, 20f), Offset(0f, 20f)))
        destinations.add(PointOfInterestEntity("lab", "f", "Lab 1", "DESTINATION", 15.0, 3.0))
        navigationNodes.addAll(listOf(NavigationNodeEntity("a", "f", 3.0, 3.0), NavigationNodeEntity("b", "f", 15.0, 3.0, "lab")))
        navigationEdges.add(NavigationEdgeEntity("ab", "f", "a", "b", 12.0))
        markMapSaved(); editorTool = EditorTool.REFERENCE_POINT
    }

    @Test fun tappingNearASuggestionAcceptsItAndAddAllLabelsTheRest() {
        val state = editableState()
        state.suggestSurveyPoints(3.0)
        val total = state.suggestedSurveyPoints.size
        assertTrue(total > 3)
        val first = state.suggestedSurveyPoints.first()
        state.addMapPoint(first + Offset(0.5f, 0.3f))
        assertEquals(listOf(first), state.referencePoints.map { it.metres }, "a near tap adopts the exact suggestion")
        assertEquals(total - 1, state.suggestedSurveyPoints.size)
        state.acceptAllSuggestedSurveyPoints()
        assertTrue(state.suggestedSurveyPoints.isEmpty())
        assertEquals(total, state.referencePoints.size)
        assertEquals((1..total).map { "RP-%02d".format(it) }, state.referencePoints.map { it.label })
        assertEquals(state.referencePoints[1].id, state.selectedSurveyReferencePointId, "surveying starts at the first newly added point")
        assertTrue(state.hasUnsavedMapChanges)
    }

    @Test fun tapAwayFromSuggestionsAddsAManualPointAndWarnsOutsideWalkableSpace() {
        val state = editableState().apply { draftWalkablePolygon.clear()
            draftWalkablePolygon.addAll(listOf(Offset.Zero, Offset(20f, 0f), Offset(20f, 6f), Offset(0f, 6f))) }
        state.addMapPoint(Offset(10f, 15f))
        assertEquals(1, state.referencePoints.size)
        assertTrue(state.editorStatus.contains("outside walkable space"))
    }
}
