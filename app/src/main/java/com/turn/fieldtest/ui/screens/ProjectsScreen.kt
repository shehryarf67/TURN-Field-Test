package com.turn.fieldtest.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.Alignment
import com.turn.fieldtest.data.repository.ProjectContext
import com.turn.fieldtest.data.repository.ProjectInput
import com.turn.fieldtest.ui.TurnAppActions
import com.turn.fieldtest.ui.TurnAppState
import com.turn.fieldtest.ui.model.TurnDestination

@Composable
fun ProjectsScreen(state: TurnAppState, actions: TurnAppActions) {
    var creating by remember { mutableStateOf(false) }
    var venue by remember { mutableStateOf("") }
    var floor by remember { mutableStateOf("") }
    var pendingOpen by remember { mutableStateOf<ProjectContext?>(null) }
    var opening by remember { mutableStateOf<ProjectContext?>(null) }
    var mapperImport by remember { mutableStateOf(false) }
    var floorPackage by remember { mutableStateOf("C-F4") }
    var replaceOld by remember { mutableStateOf(true) }
    var floorChoices by remember { mutableStateOf(false) }
    val available = !state.projectBusy && !state.startupLoading && !state.sessionLocked && !state.realEvaluationBusy
    if(mapperImport) AlertDialog(onDismissRequest={mapperImport=false}, title={Text("Import mapper floor")}, text={
        Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("Use the package's dimensions, walkable geometry, destinations and paths. No fingerprints are included.")
            Row(verticalAlignment=Alignment.CenterVertically) {Checkbox(replaceOld,{replaceOld=it});Text("Replace all existing maps and fingerprints")}
            if(replaceOld) Text("This also deletes linked survey/positioning recordings and AR drafts on this device. Export anything you want to keep first.",color=MaterialTheme.colorScheme.error)
            Box {
                OutlinedButton(onClick={floorChoices=true}) {Text("Floor: $floorPackage · Change")}
                DropdownMenu(floorChoices,{floorChoices=false}) {
                    listOf("C-F4","C-F3","B-GF","B-F1","C-F2","C-F5","C-B1","C-GF","C-F1","A-GF","A-F1","A-F2","A-F3","B-F2").forEach { f ->
                        DropdownMenuItem(text={Text(f)},onClick={floorPackage=f;floorChoices=false})
                    }
                }
            }
            Text("Automatic room/door labels need review on site. Some floors have estimated scale; C-F4 uses measured-room calibration.")
        }
    },confirmButton={TextButton(onClick={mapperImport=false;actions.onBundledMapperImport(floorPackage,replaceOld)}) {Text("Import $floorPackage")}},
      dismissButton={TextButton(onClick={mapperImport=false}) {Text("Cancel")}})
    fun open(context: ProjectContext) {
        if (state.hasUnsavedMapChanges) pendingOpen = context else { opening = context; actions.onProjectOpened(context, false) }
    }
    LaunchedEffect(state.projectBusy, state.activeProject, opening) {
        if (!state.projectBusy && opening != null && state.activeProject == opening && state.projectStatus.startsWith("Opened ")) {
            opening = null
            state.selectDestination(if (state.draftWalkablePolygon.size >= 3) TurnDestination.LIVE_LOCATE else TurnDestination.FLOOR_EDITOR)
        }
    }
    if (creating) AlertDialog(
        onDismissRequest = { creating = false },
        title = { Text("Create project") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Start with one floor. Your map and measurements stay on this device.")
                OutlinedTextField(venue, { venue = it.take(120) }, label = { Text("Venue name") }, singleLine = true)
                OutlinedTextField(floor, { floor = it.take(120) }, label = { Text("Floor name") }, singleLine = true)
            }
        },
        confirmButton = { Button(enabled = available && venue.isNotBlank() && floor.isNotBlank(), onClick = {
            actions.onProjectCreated(ProjectInput(venue, floor)); creating = false
        }) { Text("Create project") } },
        dismissButton = { TextButton(onClick = { creating = false }) { Text("Cancel") } },
    )
    pendingOpen?.let { context ->
        AlertDialog(
            onDismissRequest = { pendingOpen = null },
            title = { Text("Reload saved map?") },
            text = { Text("Unsaved drawing changes will be discarded. Saved maps, fingerprints and sessions remain on this device.") },
            confirmButton = { Button(enabled = available, onClick = {
                opening = context; actions.onProjectOpened(context, true); pendingOpen = null
            }) { Text("Discard draft and open") } },
            dismissButton = { TextButton(onClick = { pendingOpen = null }) { Text("Keep editing") } },
        )
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(PagePadding),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("Your places, indoors.", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Open a saved floor or prepare a new building.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick={mapperImport=true},enabled=available && !state.hasUnsavedMapChanges && state.mode==com.turn.fieldtest.ui.model.DataMode.REAL_DEVICE,modifier=Modifier.fillMaxWidth()) {Text("Import university mapper floor")}
        TextButton(onClick=actions.onMapperImportRequested,enabled=available && !state.hasUnsavedMapChanges && state.mode==com.turn.fieldtest.ui.model.DataMode.REAL_DEVICE) {Text("Import another mapper file")}
        Button(enabled = available && !state.hasUnsavedMapChanges, onClick = { creating = true }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text("Add a building")
        }
        if (state.projectBusy || state.startupLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(state.projectStatus, style = MaterialTheme.typography.bodyMedium)
        if (state.hasUnsavedMapChanges) Text("Your map has unsaved changes. Save in Map setup, or open a saved project to discard the draft.")
        if (state.sessionLocked) Text("Project selection is locked until the physical session finishes saving.")
        if (state.projects.isEmpty() && !state.startupLoading) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.Apartment, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                    Text("Bring your first floor into TURN", style = MaterialTheme.typography.titleMedium)
                    Text("Use an existing floor plan, or create one with guided AR mapping. Then add places and survey points.")
                }
            }
        }
        state.projects.forEach { project ->
            val selected = state.activeProject == project.context && state.realMapReady
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
                containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Outlined.Apartment, null, tint = MaterialTheme.colorScheme.primary)
                        Text(project.venueName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        if (selected) Icon(Icons.Outlined.CheckCircle, "Current building", tint = MaterialTheme.colorScheme.primary)
                    }
                    Text(project.floorName, style = MaterialTheme.typography.bodyLarge)
                    Text("Saved offline · ${project.fingerprintPointCount} surveyed points",
                        style = MaterialTheme.typography.bodyMedium)
                    Text("${project.surveySessionCount} survey sessions · ${project.positioningSessionCount} positioning sessions",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (selected) {
                        Button(onClick = { state.selectDestination(if (state.draftWalkablePolygon.size >= 3) TurnDestination.LIVE_LOCATE else TurnDestination.FLOOR_EDITOR) },
                            enabled = available, modifier = Modifier.fillMaxWidth()) { Text(if (state.draftWalkablePolygon.size >= 3) "Explore floor" else "Prepare this floor") }
                        OutlinedButton(onClick = { state.selectDestination(TurnDestination.FLOOR_EDITOR) }, enabled = available, modifier = Modifier.fillMaxWidth()) { Text("Map setup") }
                        OutlinedButton(onClick = { open(project.context) }, enabled = available) { Text("Reload saved map") }
                    } else Button(onClick = { open(project.context) }, enabled = available) { Text("Open project") }
                }
            }
        }
    }
}
