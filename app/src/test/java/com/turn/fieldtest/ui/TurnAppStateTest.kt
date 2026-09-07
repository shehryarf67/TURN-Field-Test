package com.turn.fieldtest.ui

import androidx.compose.ui.geometry.Offset
import com.turn.fieldtest.ui.model.DataMode
import com.turn.fieldtest.ui.model.EditorTool
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TurnAppStateTest {
    @Test
    fun blankPhysicalDraftDoesNotRetainExampleGeometry() {
        val state = TurnAppState().apply {
            mode = DataMode.REAL_DEVICE
            realMapReady = true
        }

        state.clearMapDraft()
        state.editorTool = EditorTool.WALKABLE
        state.addMapPoint(Offset(1f, 1f))

        assertEquals(listOf(Offset(1f, 1f)), state.draftWalkablePolygon)
        assertTrue(state.draftWalls.isEmpty())
        assertTrue(state.referencePoints.isEmpty())
    }

    @Test
    fun twoPointCalibrationRescalesFloorAndGeometryUniformly() {
        val state = TurnAppState().apply {
            mode = DataMode.REAL_DEVICE
            realMapReady = true
            clearMapDraft()
            floorWidthMetres = 40f
            floorHeightMetres = 20f
            editorTool = EditorTool.CALIBRATION
        }
        state.addMapPoint(Offset(5f, 5f))
        state.addMapPoint(Offset(15f, 5f))
        state.editorTool = EditorTool.WALKABLE
        state.addMapPoint(Offset(10f, 10f))

        assertTrue(state.applyCalibration(5.0))
        assertEquals(20f, state.floorWidthMetres, 0.001f)
        assertEquals(10f, state.floorHeightMetres, 0.001f)
        assertEquals(Offset(5f, 5f), state.draftWalkablePolygon.single())
    }

    @Test
    fun exactReferencePointRejectsCoordinatesOutsideFloor() {
        val state = TurnAppState().apply {
            mode = DataMode.REAL_DEVICE
            realMapReady = true
            clearMapDraft()
            floorWidthMetres = 10f
            floorHeightMetres = 5f
        }

        state.addReferencePointAt("RP-01", 11f, 2f)

        assertTrue(state.referencePoints.isEmpty())
        assertTrue(state.editorStatus.contains("inside the floor"))
    }
}
