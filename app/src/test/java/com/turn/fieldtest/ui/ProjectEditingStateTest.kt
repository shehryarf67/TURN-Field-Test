package com.turn.fieldtest.ui

import androidx.compose.ui.geometry.Offset
import com.turn.fieldtest.ui.model.DataMode
import com.turn.fieldtest.ui.model.EditorTool
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ProjectEditingStateTest {
    private fun physical() = TurnAppState().apply {
        mode = DataMode.REAL_DEVICE; realMapReady = true; clearMapDraft(); markMapSaved()
    }
    @Test fun sessionLockProtectsEveryCoordinateMutation() {
        val state = physical()
        state.addReferencePointAt("RP-01", 2f, 3f)
        state.editorTool = EditorTool.WALKABLE
        state.addMapPoint(Offset(1f, 1f))
        state.sessionLocked = true
        val points = state.referencePoints.toList()
        val polygon = state.draftWalkablePolygon.toList()
        state.clearMapDraft()
        state.undoCurrentTool()
        state.addReferencePointAt("RP-02", 4f, 5f)
        state.removeReferencePoint(points.single().id)
        state.addMapPoint(Offset(8f, 8f))
        assertFalse(state.resizeFloor(20f, 20f))
        assertEquals(points, state.referencePoints.toList())
        assertEquals(polygon, state.draftWalkablePolygon.toList())
    }
    @Test fun recordedCoordinatesPreventRescaleButAllowAddingNewSurveyPoints() {
        val state = physical().apply { mapHasMeasurements = true }
        assertFalse(state.resizeFloor(20f, 20f))
        assertFalse(state.applyCalibration(3.0))
        state.addReferencePointAt("RP-01", 2f, 3f)
        assertEquals(1, state.referencePoints.size)
    }
    @Test fun sameVisiblePointNameInDifferentProjectsHasDifferentStorageIds() {
        val first = physical(); val second = physical()
        first.addReferencePointAt("RP-01", 2f, 3f)
        second.addReferencePointAt("RP-01", 2f, 3f)
        assertEquals("RP-01", first.referencePoints.single().label)
        assertNotEquals(first.referencePoints.single().id, second.referencePoints.single().id)
    }
    @Test fun dirtyTrackingIncludesGeometryNamesAndImageAndResetsOnSave() {
        val state = physical()
        assertFalse(state.hasUnsavedMapChanges)
        state.venueName = "University"
        assertTrue(state.hasUnsavedMapChanges)
        state.markMapSaved()
        state.floorPlanContentUri = "content://test/plan"
        assertTrue(state.hasUnsavedMapChanges)
        state.markMapSaved()
        state.addReferencePointAt("RP-01", 2f, 3f)
        assertTrue(state.hasUnsavedMapChanges)
        state.markMapSaved()
        assertFalse(state.hasUnsavedMapChanges)
    }
    @Test fun invalidCalibrationCannotReportSuccessOrChangeDimensions() {
        val state = physical()
        state.calibrationPoints.addAll(listOf(Offset(0f, 0f), Offset(1f, 0f)))
        assertFalse(state.applyCalibration(10000.0))
        assertEquals(42f, state.floorWidthMetres)
    }
    @Test fun startupAndProjectLoadingDisableEditing() {
        val state = physical().apply { projectBusy = true }
        state.addReferencePointAt("RP-01", 2f, 3f)
        assertTrue(state.referencePoints.isEmpty())
        state.projectBusy = false; state.startupLoading = true
        assertFalse(state.canEditMap)
    }
}
