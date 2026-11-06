package com.turn.fieldtest.mapper

import androidx.compose.ui.geometry.Offset
import com.turn.fieldtest.ui.TurnAppState
import com.turn.fieldtest.ui.model.DataMode
import com.turn.fieldtest.data.local.*
import com.turn.fieldtest.data.repository.ProjectContext
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MultiRegionEditorTest {
    private val first=WalkableRegionEntity("a","f","Bedroom","""[{"x":0.123456789,"y":0},{"x":4,"y":0},{"x":4,"y":4},{"x":0,"y":4}]""",updatedAtEpochMillis=1)
    private val second=WalkableRegionEntity("b","f","Hallway","""[{"x":4,"y":0},{"x":8,"y":0},{"x":8,"y":4},{"x":4,"y":4}]""",updatedAtEpochMillis=1)
    private fun state()=TurnAppState().apply {
        mode=DataMode.REAL_DEVICE; realMapReady=true; clearMapDraft(); activeProject=ProjectContext("v","f")
        editedRegionId=first.id; editedRegionName=first.name; editedRegionOriginal=first
        draftWalkablePolygon.addAll(com.turn.fieldtest.data.repository.decodePolygon(first.polygonJson).vertices.map {Offset(it.x.toFloat(),it.y.toFloat())})
        otherWalkableRegions.add(second); markMapSaved()
    }
    @Test fun switchingRoomsDoesNotChangeGeometryOrMarkMapDirty() {
        val s=state(); s.selectWalkableRegion("b")
        assertFalse(s.hasUnsavedMapChanges); assertEquals(first,s.otherWalkableRegions.single())
        s.selectWalkableRegion("a"); assertFalse(s.hasUnsavedMapChanges); assertEquals(second,s.otherWalkableRegions.single())
    }
    @Test fun graphChecksAllRoomsAndKeepsRoomEditsOnSelectionChange() {
        val s=state(); s.navigationNodes.add(NavigationNodeEntity("node","f",6.0,2.0))
        assertTrue(s.draftNavigationGraph().validationErrors().isEmpty())
        s.draftWalkablePolygon[0]=Offset(.5f,.5f); s.selectWalkableRegion("b"); s.selectWalkableRegion("a")
        assertEquals(Offset(.5f,.5f),s.draftWalkablePolygon[0]); assertTrue(s.hasUnsavedMapChanges)
    }
    @Test fun demoResetCannotRetainPrivateScannedRooms() {
        val s=state(); s.loadDemoMap(); assertTrue(s.otherWalkableRegions.isEmpty()); assertNull(s.editedRegionOriginal)
    }
}
