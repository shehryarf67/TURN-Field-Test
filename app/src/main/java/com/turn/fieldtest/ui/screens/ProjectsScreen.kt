package com.turn.fieldtest.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
    val available = !state.projectBusy && !state.startupLoading && !state.sessionLocked && !state.realEvaluationBusy
    fun open(context: ProjectContext) {
        if (state.hasUnsavedMapChanges) pendingOpen = context else actions.onProjectOpened(context, false)
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
                actions.onProjectOpened(context, true); pendingOpen = null
            }) { Text("Discard draft and open") } },
            dismissButton = { TextButton(onClick = { pendingOpen = null }) { Text("Keep editing") } },
        )
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(PagePadding),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("Your projects", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Set up a floor, collect fingerprints, then start live positioning.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(enabled = available && !state.hasUnsavedMapChanges, onClick = { creating = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Create project")
        }
        if (state.projectBusy || state.startupLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(state.projectStatus, style = MaterialTheme.typography.bodyMedium)
        if (state.hasUnsavedMapChanges) Text("Your map has unsaved changes. Save in Map setup, or open a saved project to discard the draft.")
        if (state.sessionLocked) Text("Project selection is locked until the physical session finishes saving.")
        if (state.projects.isEmpty() && !state.startupLoading) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Start with your university floor", style = MaterialTheme.typography.titleMedium)
                    Text("Create a project, import a plan and calibrate its size before collecting measurements.")
                }
            }
        }
        state.projects.forEach { project ->
            val selected = state.activeProject == project.context && state.realMapReady
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
                containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(project.venueName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(project.floorName, style = MaterialTheme.typography.bodyLarge)
                    Text("${project.referencePointCount} reference points · ${project.fingerprintPointCount} with saved fingerprints",
                        style = MaterialTheme.typography.bodyMedium)
                    Text("${project.surveySessionCount} survey sessions · ${project.positioningSessionCount} positioning sessions",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (selected) {
                        Text("Active project", color = MaterialTheme.colorScheme.primary)
                        Button(onClick = { state.selectDestination(TurnDestination.FLOOR_EDITOR) }, enabled = available) { Text("Map setup") }
                        OutlinedButton(onClick = { open(project.context) }, enabled = available) { Text("Reload saved map") }
                    } else Button(onClick = { open(project.context) }, enabled = available) { Text("Open project") }
                }
            }
        }
    }
}
