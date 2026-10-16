package com.turn.fieldtest.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.turn.fieldtest.ui.*
import com.turn.fieldtest.ui.model.EditorTool

@Composable
fun MapSetupScreen(state: TurnAppState, actions: TurnAppActions) {
    var section by remember(state.activeProject) { mutableStateOf("Scale") }
    var width by remember(state.activeProject, state.floorWidthMetres) { mutableStateOf(state.floorWidthMetres.toString()) }
    var height by remember(state.activeProject, state.floorHeightMetres) { mutableStateOf(state.floorHeightMetres.toString()) }
    var measured by remember { mutableStateOf("") }
    var poiName by remember(state.selectedNodeId) { mutableStateOf(state.destinations.firstOrNull { poi -> state.navigationNodes.any { it.id == state.selectedNodeId && it.poiId == poi.id } }?.name.orEmpty()) }
    var advanced by remember { mutableStateOf(false) }
    if (advanced) {
        Column {
            TextButton(onClick = { advanced = false }) { Text("Back to map setup") }
            FloorEditorScreen(state, true, actions.onMapSaved, actions.onFloorPlanImportRequested, actions.onMapDeleted)
        }; return
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(state.floorName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(if (state.hasUnsavedMapChanges) "Unsaved changes" else "Saved map", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
            }
            Button(onClick = actions.onMapSaved, enabled = state.canEditMap) { Text(if (state.projectBusy) "Saving…" else "Save map") }
        }
        NavigationMap(state, Modifier.fillMaxWidth().weight(1f).heightIn(min = 230.dp), showGraph = section == "Paths", showReferences = section == "Points",
            onTap = { point, tolerance ->
                if (section == "Paths") state.graphTap(point, tolerance)
                else if (section == "Points" || section == "Layout" || state.editorTool == EditorTool.CALIBRATION) state.addMapPoint(point)
            })
        Text(state.editorStatus, style = MaterialTheme.typography.bodySmall, maxLines = 3)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Scale", "Layout", "Paths", "Points").forEach { title ->
                FilterChip(selected = section == title, onClick = {
                    section = title; state.editorTool = when (title) { "Points" -> EditorTool.REFERENCE_POINT; "Layout" -> EditorTool.WALKABLE; else -> EditorTool.SELECT }
                    state.selectedNodeId = null
                }, label = { Text(title) })
            }
        }
        Column(Modifier.fillMaxWidth().height(240.dp).verticalScroll(rememberScrollState()).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (section) {
                "Scale" -> {
                    Text("Import a plan, then set its measured size or calibrate two points.", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = actions.onFloorPlanImportRequested, enabled = state.canEditMap) { Text(if (state.floorPlanContentUri == null) "Import floor plan" else "Replace floor plan") }
                    OutlinedButton(onClick=actions.onWalkMapperRequested,enabled=state.canEditMap && state.mode==com.turn.fieldtest.ui.model.DataMode.REAL_DEVICE && state.activeProject!=null && !state.hasUnsavedMapChanges) { Text("Scan building with phone") }
                    Text("No plan? Create one with guided AR or measured drawing. Save current edits before opening the mapper.",style=MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(width, { width = it }, label = { Text("Width (m)") }, enabled = state.canChangeScale, modifier = Modifier.weight(1f), singleLine = true)
                        OutlinedTextField(height, { height = it }, label = { Text("Height (m)") }, enabled = state.canChangeScale, modifier = Modifier.weight(1f), singleLine = true)
                    }
                    OutlinedButton(onClick = { state.resizeFloor(width.toFloatOrNull() ?: 0f, height.toFloatOrNull() ?: 0f) }, enabled = state.canChangeScale) { Text("Apply dimensions") }
                    OutlinedButton(onClick = { state.editorTool = EditorTool.CALIBRATION; state.calibrationPoints.clear(); state.editorStatus = "Tap scale point A, then B" }, enabled = state.canChangeScale) { Text("Pick scale points A–B") }
                    OutlinedTextField(measured, { measured = it }, label = { Text("Measured A–B distance (m)") }, singleLine = true)
                    Button(onClick = { state.applyCalibration(measured.toDoubleOrNull() ?: 0.0) }, enabled = state.canChangeScale && state.calibrationPoints.size == 2) { Text("Calibrate") }
                    if (!state.canChangeScale) Text("Scale is locked while busy, after measurements, or after path nodes are added.", style = MaterialTheme.typography.bodySmall)
                }
                "Layout" -> {
                    if(state.otherWalkableRegions.isNotEmpty()) {
                        Text("Room: ${state.editedRegionName}",style=MaterialTheme.typography.labelLarge)
                        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            state.otherWalkableRegions.toList().forEach { region->OutlinedButton(onClick={state.selectWalkableRegion(region.id)},enabled=state.canEditMap) {Text("Edit ${region.name}")} }
                        }
                    }
                    Text("Draw corridor boundaries and walls. Leave openings at doors.", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(EditorTool.WALKABLE, EditorTool.WALL).forEach { tool -> FilterChip(state.editorTool == tool,
                            { state.editorTool = tool; state.pendingWallStart = null }, label = { Text(tool.label) }, enabled = state.canEditMap) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = state::undoCurrentTool, enabled = state.canEditMap) { Text("Undo") }
                        Button(onClick = state::finishWalkablePolygon, enabled = state.canEditMap && state.editorTool == EditorTool.WALKABLE) { Text("Close outline") }
                    }
                    OutlinedButton(onClick = { state.draftWalkablePolygon.clear(); state.editorTool = EditorTool.WALKABLE; state.editorStatus = "Tap each corner of the new outline" }, enabled = state.canEditMap) { Text("Redraw outline") }
                }
                "Paths" -> {
                    Text("Place nodes along corridors and at turns. Connect them, then name destination nodes.", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Inspect", "Node", "Connect").forEach { tool -> FilterChip(state.graphTool == tool, {
                            state.graphTool = tool; state.selectedNodeId = null
                        }, label = { Text(tool) }, enabled = state.canEditMap) }
                    }
                    Text("${state.navigationNodes.size} nodes · ${state.navigationEdges.size} paths · ${state.destinations.size} destinations", style = MaterialTheme.typography.labelMedium)
                    if (state.selectedNodeId != null) {
                        OutlinedTextField(poiName, { poiName = it }, label = { Text("Destination name, e.g. Lab 1") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        Button(onClick = { state.nameSelectedDestination(poiName) }, enabled = state.canEditMap && poiName.isNotBlank()) { Text("Save destination in draft") }
                        OutlinedButton(onClick = state::removeSelectedNode, enabled = state.canEditMap) { Text("Remove selected node") }
                        state.navigationEdges.filter { it.fromNodeId == state.selectedNodeId || it.toNodeId == state.selectedNodeId }.forEach { edge ->
                            OutlinedButton(onClick = { state.navigationEdges.remove(edge) }, enabled = state.canEditMap) { Text("Remove connection · %.1f m".format(edge.distanceMetres)) }
                        }
                    }
                }
                "Points" -> {
                    Text("Tap known physical markers to add survey points. Calibrate before collecting.", style = MaterialTheme.typography.bodySmall)
                    state.referencePoints.forEach { point -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${point.label} · %.1f, %.1f m".format(point.metres.x, point.metres.y))
                        TextButton(onClick = { state.removeReferencePoint(point.id) }, enabled = state.canEditMap) { Text("Remove") }
                    } }
                }
            }
            TextButton(onClick = { advanced = true }) { Text("Advanced map tools") }
        }
    }
}
