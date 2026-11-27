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
import com.turn.fieldtest.ui.components.ConfirmDialog
import com.turn.fieldtest.ui.components.HelpTip
import com.turn.fieldtest.ui.model.EditorTool
import com.turn.fieldtest.ui.model.TurnDestination

@Composable
fun MapSetupScreen(state: TurnAppState, actions: TurnAppActions) {
    var section by remember(state.activeProject) { mutableStateOf(state.mapSetupSection) }
    LaunchedEffect(section) { state.mapSetupSection = section }
    var width by remember(state.activeProject, state.floorWidthMetres) { mutableStateOf(state.floorWidthMetres.toString()) }
    var height by remember(state.activeProject, state.floorHeightMetres) { mutableStateOf(state.floorHeightMetres.toString()) }
    var measured by remember { mutableStateOf("") }
    var poiName by remember(state.selectedNodeId) { mutableStateOf(state.destinations.firstOrNull { poi -> state.navigationNodes.any { it.id == state.selectedNodeId && it.poiId == poi.id } }?.name.orEmpty()) }
    var advanced by remember { mutableStateOf(false) }
    if (advanced) {
        Column {
            TextButton(onClick = { advanced = false }) { Text("Back to Map setup") }
            FloorEditorScreen(state, true, actions.onMapSaved, actions.onFloorPlanImportRequested, actions.onMapDeleted)
        }; return
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val shortWindow = maxHeight < 420.dp
    val panelHeight = (maxHeight * 0.42f).coerceAtMost(280.dp)
    Column(Modifier.fillMaxSize().then(if (shortWindow) Modifier.verticalScroll(rememberScrollState()) else Modifier)
        .padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(state.venueName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    FloorSwitcher(state, actions)
                }
                Text(if (state.hasUnsavedMapChanges) "Unsaved changes — tap Save map" else "All changes saved", style = MaterialTheme.typography.labelMedium,
                    color = if (state.hasUnsavedMapChanges) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
            Button(onClick = actions.onMapSaved, enabled = state.canEditMap) { Text(if (state.projectBusy) "Saving…" else "Save map") }
        }
        Text(when (section) {
            "Scale" -> "Step 1 of 4 · Floor plan and its real size"
            "Layout" -> "Step 2 of 4 · Where people can walk"
            "Paths" -> "Step 3 of 4 · Walking paths and named places"
            else -> "Step 4 of 4 · Spots to record Wi-Fi"
        }, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        NavigationMap(state, Modifier.fillMaxWidth().then(if (shortWindow) Modifier.height(230.dp) else Modifier.weight(1f).heightIn(min = 100.dp)), showGraph = section == "Paths", showReferences = section == "Points", showCalibration = section == "Scale", roomLabels = section == "Points",
            onTap = { point, tolerance ->
                if (section == "Paths") state.graphTap(point, tolerance)
                // Arriving here from the survey screen skips the chip that normally selects the point tool.
                else if (section == "Points") { state.editorTool = EditorTool.REFERENCE_POINT; state.addMapPoint(point) }
                else if (section == "Points" || section == "Layout" || state.editorTool == EditorTool.CALIBRATION) state.addMapPoint(point)
            })
        Text(state.editorStatus, style = MaterialTheme.typography.bodySmall, maxLines = 3)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Scale", "Layout", "Paths", "Points").forEach { title ->
                FilterChip(selected = section == title, onClick = {
                    section = title; state.editorTool = when (title) { "Points" -> EditorTool.REFERENCE_POINT; "Layout" -> EditorTool.WALKABLE; else -> EditorTool.SELECT }
                    state.selectedNodeId = null
                }, label = { Text(sectionLabel(title)) })
            }
        }
        // Short windows use the page's scroll container; nesting another vertical viewport
        // here can leave controls outside the visible window after scrolling them into view.
        Column(Modifier.fillMaxWidth().then(if (shortWindow) Modifier else Modifier.height(panelHeight).verticalScroll(rememberScrollState()))
            .padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (section) {
                "Scale" -> {
                    Text("Have a picture of the floor plan? Import it. Otherwise map the floor with your phone's camera.", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick=actions.onWalkMapperRequested,enabled=state.canEditMap && state.mode==com.turn.fieldtest.ui.model.DataMode.REAL_DEVICE && state.activeProject!=null && !state.hasUnsavedMapChanges) { Text(MAP_WITH_PHONE) }
                    OutlinedButton(onClick = actions.onFloorPlanImportRequested, enabled = state.canEditMap) { Text(if (state.floorPlanContentUri == null) "Import floor plan picture" else "Replace floor plan picture") }
                    Text("The phone mapper walks you through it step by step and saves a new floor. Save any changes here first.",style=MaterialTheme.typography.bodySmall)
                    Text("Real size of the plan", style = MaterialTheme.typography.labelLarge)
                    HelpTip("Type the real width and height the whole plan covers, or set the scale from one measurement below.",
                        "The size tells TURN how many metres the plan covers, so distances and directions are right. " +
                            "If you don't know the overall size, tap 'Tap two points to measure', tap two points on the plan you can measure in real life " +
                            "(for example the two ends of a wall), measure that distance with a tape, type it in and tap Set scale.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(width, { width = it }, label = { Text("Width (m)") }, enabled = state.canChangeScale, modifier = Modifier.weight(1f), singleLine = true)
                        OutlinedTextField(height, { height = it }, label = { Text("Height (m)") }, enabled = state.canChangeScale, modifier = Modifier.weight(1f), singleLine = true)
                    }
                    OutlinedButton(onClick = { state.resizeFloor(width.toFloatOrNull() ?: 0f, height.toFloatOrNull() ?: 0f) }, enabled = state.canChangeScale) { Text("Set size") }
                    OutlinedButton(onClick = { state.editorTool = EditorTool.CALIBRATION; state.calibrationPoints.clear(); state.editorStatus = "Tap the first point (A), then the second (B)" }, enabled = state.canChangeScale) { Text("Tap two points to measure") }
                    OutlinedTextField(measured, { measured = it }, label = { Text("Real distance between A and B (m)") }, singleLine = true)
                    Button(onClick = { state.applyCalibration(measured.toDoubleOrNull() ?: 0.0) }, enabled = state.canChangeScale && state.calibrationPoints.size == 2) { Text("Set scale") }
                    if (!state.canChangeScale) Text("The size can't be changed now: it's locked once Wi-Fi has been recorded or paths have been added, and while something is saving.", style = MaterialTheme.typography.bodySmall)
                }
                "Layout" -> {
                    if(state.otherWalkableRegions.isNotEmpty()) {
                        Text("Room: ${state.editedRegionName}",style=MaterialTheme.typography.labelLarge)
                        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            state.otherWalkableRegions.toList().forEach { region->OutlinedButton(onClick={state.selectWalkableRegion(region.id)},enabled=state.canEditMap) {Text("Edit ${region.name}")} }
                        }
                    }
                    Text("Tap the corners of the area people can walk in, then Close outline. Add walls where needed and leave gaps at doors.", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(EditorTool.WALKABLE, EditorTool.WALL).forEach { tool -> FilterChip(state.editorTool == tool,
                            { state.editorTool = tool; state.pendingWallStart = null }, label = { Text(tool.label) }, enabled = state.canEditMap) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = state::undoCurrentTool, enabled = state.canEditMap) { Text("Undo") }
                        Button(onClick = state::finishWalkablePolygon, enabled = state.canEditMap && state.editorTool == EditorTool.WALKABLE) { Text("Close outline") }
                    }
                    var redraw by remember { mutableStateOf(false) }
                    if (redraw) ConfirmDialog("Redraw the outline?", "The current walkable outline and any suggested Wi-Fi spots are cleared so you can draw it again. Nothing is saved until you tap Save map.",
                        "Clear and redraw", onConfirm = { state.draftWalkablePolygon.clear(); state.suggestedSurveyPoints.clear(); state.editorTool = EditorTool.WALKABLE; state.editorStatus = "Tap each corner of the new outline" },
                        onDismiss = { redraw = false })
                    OutlinedButton(onClick = { redraw = true }, enabled = state.canEditMap) { Text("Redraw outline…") }
                }
                "Paths" -> {
                    HelpTip("Add path points along hallways and at every turn, connect them, then name the points at room doors.",
                        "Choose 'Add path point' and tap the map to place points. Choose 'Connect' and tap two points to join them with a walking path. " +
                            "Choose 'Select' and tap a point at a room's doorway to give that place a name (or link a room that's already on the map). " +
                            "Navigation follows these paths, so keep them away from walls and through doorways.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Inspect", "Node", "Connect").forEach { tool -> FilterChip(state.graphTool == tool, {
                            state.graphTool = tool; state.selectedNodeId = null
                        }, label = { Text(pathToolLabel(tool)) }, enabled = state.canEditMap) }
                    }
                    Text("${state.navigationNodes.size} path points · ${state.navigationEdges.size} connections · ${state.destinations.size} places", style = MaterialTheme.typography.labelMedium)
                    val unlinked=state.destinations.filter {place->state.navigationNodes.none {it.poiId==place.id}}
                    if(unlinked.isNotEmpty()) Text("${unlinked.size} ${if (unlinked.size == 1) "place isn't" else "places aren't"} connected yet. Select the path point at its doorway and link it.",style=MaterialTheme.typography.bodySmall)
                    if (state.selectedNodeId != null) {
                        if(unlinked.isNotEmpty() && state.navigationNodes.firstOrNull {it.id==state.selectedNodeId}?.poiId==null) {
                            var roomMenu by remember(state.selectedNodeId) {mutableStateOf(false)}
                            Box {
                                OutlinedButton(onClick={roomMenu=true},enabled=state.canEditMap) {Text("Link an existing room")}
                                DropdownMenu(roomMenu,{roomMenu=false}) {
                                    unlinked.forEach {room -> DropdownMenuItem(text={Text(room.name)},onClick={
                                        state.linkSelectedDestination(room.id);poiName=room.name;roomMenu=false
                                    })}
                                }
                            }
                        }
                        OutlinedTextField(poiName, { poiName = it }, label = { Text("Place name, e.g. Kitchen") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        Button(onClick = { state.nameSelectedDestination(poiName) }, enabled = state.canEditMap && poiName.isNotBlank()) { Text("Name this place") }
                        OutlinedButton(onClick = state::removeSelectedNode, enabled = state.canEditMap) { Text("Remove this path point") }
                        state.navigationEdges.filter { it.fromNodeId == state.selectedNodeId || it.toNodeId == state.selectedNodeId }.forEach { edge ->
                            OutlinedButton(onClick = { state.navigationEdges.remove(edge) }, enabled = state.canEditMap) { Text("Remove connection · %.1f m".format(edge.distanceMetres)) }
                        }
                    }
                }
                "Points" -> {
                    HelpTip("Mark the spots where you'll stand to record Wi-Fi. Tap the map, or let TURN suggest spots along your paths.",
                        "TURN recognises where you are by comparing Wi-Fi with recordings made at these spots, so cover every room and hallway. " +
                            "Put a removable sticker on the floor at each spot so you can find it again when recording (Tools → Record Wi-Fi).")
                    Text("Distance between suggested spots", style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(2.0, 3.0, 5.0).forEach { s ->
                            FilterChip(selected = state.surveySpacingMetres == s, onClick = { state.surveySpacingMetres = s },
                                label = { Text("%.0f m".format(s) + if (s == 3.0) " · default" else "") })
                        }
                    }
                    Button(onClick = { state.suggestSurveyPoints(state.surveySpacingMetres) }, enabled = state.canEditMap,
                        modifier = Modifier.fillMaxWidth()) { Text(if (state.suggestedSurveyPoints.isEmpty()) "Suggest spots" else "Suggest again") }
                    if (state.suggestedSurveyPoints.isNotEmpty()) {
                        Text("${state.suggestedSurveyPoints.size} suggestions shown as dashed circles. Tap one to add it, or add them all.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = state::acceptAllSuggestedSurveyPoints, enabled = state.canEditMap,
                                modifier = Modifier.weight(1f)) { Text("Add all ${state.suggestedSurveyPoints.size}") }
                            OutlinedButton(onClick = { state.suggestedSurveyPoints.clear() }, modifier = Modifier.weight(1f)) { Text("Clear suggestions") }
                        }
                    }
                    val completed = state.referencePoints.count { state.surveyPointProgress[it.id]?.status in setOf("COMPLETED", "LEGACY") }
                    if (state.referencePoints.isEmpty()) Text("No Wi-Fi spots yet — tap the map or Suggest spots to add some.", style = MaterialTheme.typography.bodyMedium)
                    else Text("${state.referencePoints.size} Wi-Fi spots · $completed recorded", style = MaterialTheme.typography.labelLarge)
                    state.referencePoints.forEach { point -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        val status = state.surveyPointProgress[point.id]?.status
                        Text("${point.label} · %.1f, %.1f m".format(point.metres.x, point.metres.y) +
                            if (status == "COMPLETED" || status == "LEGACY") " · recorded" else "", modifier = Modifier.weight(1f))
                        TextButton(onClick = { state.removeReferencePoint(point.id) }, enabled = state.canEditMap && state.canRemoveReferencePoint(point.id)) { Text("Remove") }
                    } }
                }
            }
            if (section != "Points") Button(enabled = state.canEditMap && when (section) {
                "Layout" -> state.draftWalkablePolygon.size >= 3
                "Paths" -> state.destinations.isNotEmpty() && state.navigationEdges.isNotEmpty()
                else -> true
            }, onClick = {
                section = when (section) { "Scale" -> "Layout"; "Layout" -> "Paths"; else -> "Points" }
                state.editorTool = when (section) { "Layout" -> EditorTool.WALKABLE; "Points" -> EditorTool.REFERENCE_POINT; else -> EditorTool.SELECT }
                state.selectedNodeId = null
            }, modifier = Modifier.fillMaxWidth()) { Text(when (section) { "Scale" -> "Next: where people can walk"; "Layout" -> "Next: paths and places"; else -> "Next: Wi-Fi spots" }) }
            else {
                Button(onClick = { state.selectDestination(TurnDestination.SURVEY) },
                    enabled = !state.hasUnsavedMapChanges && state.referencePoints.isNotEmpty() && !state.projectBusy,
                    modifier = Modifier.fillMaxWidth()) { Text("Next: record Wi-Fi") }
                if (state.hasUnsavedMapChanges) Text("Save your map above to continue.", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = { advanced = true }) { Text("Advanced map editor (all tools)") }
        }
    }
    }
}

/** Plain chip names for the four Map setup steps (their ids stay Scale/Layout/Paths/Points). */
private fun sectionLabel(id: String) = when (id) { "Scale" -> "Plan & size"; "Layout" -> "Walkable area"; "Points" -> "Wi-Fi spots"; else -> id }
/** Plain names for the path tools (their ids stay Inspect/Node/Connect). */
internal fun pathToolLabel(id: String) = when (id) { "Inspect" -> "Select"; "Node" -> "Add path point"; else -> id }
