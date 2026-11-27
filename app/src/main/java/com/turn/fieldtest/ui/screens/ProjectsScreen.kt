package com.turn.fieldtest.ui.screens

import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.turn.fieldtest.data.repository.ProjectSummary
import com.turn.fieldtest.ui.TurnAppActions
import com.turn.fieldtest.ui.TurnAppState
import com.turn.fieldtest.ui.components.AdvancedSection
import com.turn.fieldtest.ui.components.EmptyState
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
    /** Building the imported floor joins; null means a new building. */
    var importInto by remember { mutableStateOf<String?>(null) }
    var addFloorTo by remember { mutableStateOf<String?>(null) }
    val available = !state.projectBusy && !state.startupLoading && !state.sessionLocked && !state.realEvaluationBusy
    val buildings = state.projects.groupBy { it.venueId }.values.toList()
    fun startImport(f: String) {
        floorPackage = f
        // Default to the building that already holds this block's floors (e.g. C-F3 joins the building with C-F4).
        importInto = buildings.firstOrNull { b -> canJoin(b, f) && b.any { it.floorName.substringBefore('-') == f.substringBefore('-') } }
            ?.first()?.venueId
        mapperImport = true
    }
    if(mapperImport) AlertDialog(onDismissRequest={mapperImport=false}, title={Text("Add a university floor")}, text={
        Column(Modifier.heightIn(max=460.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("Adds the floor's size, walkable areas, rooms and paths. Wi-Fi recordings are not included — you'll record those yourself.")
            Box {
                OutlinedButton(onClick={floorChoices=true}) {Text("Floor: $floorPackage · Change")}
                DropdownMenu(floorChoices,{floorChoices=false}) {
                    bundledMapperFloors.forEach { f ->
                        DropdownMenuItem(text={Text(f)},onClick={startImport(f);floorChoices=false})
                    }
                }
            }
            if (buildings.isNotEmpty()) {
                Text("Where should it go?", style = MaterialTheme.typography.labelLarge)
                ChoiceRow("New building", importInto == null) { importInto = null }
                buildings.forEach { b ->
                    val why = when {
                        b.any { it.floorName.equals(floorPackage, ignoreCase = true) } -> " (already has $floorPackage)"
                        b.any { it.floorLevel == bundledLevel(floorPackage) } -> " (level ${levelBadge(bundledLevel(floorPackage))} is taken)"
                        else -> ""
                    }
                    ChoiceRow("Add to ${b.first().venueName}$why", importInto == b.first().venueId, enabled = why.isEmpty()) {
                        importInto = b.first().venueId
                    }
                }
            }
            if (importInto == null) {
                Row(verticalAlignment=Alignment.CenterVertically) {Checkbox(replaceOld,{replaceOld=it});Text("Replace all existing maps and fingerprints")}
                if(replaceOld) Text("This also deletes linked survey/positioning recordings and AR drafts on this device. Export anything you want to keep first.",color=MaterialTheme.colorScheme.error)
            } else Text("Other floors, fingerprints and recordings are kept. Floors stay separate until stairs and lifts are linked.",
                style = MaterialTheme.typography.bodySmall)
            Text("Room and door names were added automatically — check them on site. Some floors' sizes are estimates; C-F4 was checked against measured rooms.",
                style = MaterialTheme.typography.bodySmall)
        }
    },confirmButton={TextButton(enabled = importInto == null || buildings.any { b -> b.first().venueId == importInto && canJoin(b, floorPackage) }, onClick={
        mapperImport=false
        val into = importInto
        if (into == null) actions.onBundledMapperImport(floorPackage,replaceOld) else actions.onBundledFloorAddedToBuilding(floorPackage, into)
    }) {Text(if (importInto == null) "Import $floorPackage" else "Add $floorPackage")}},
      dismissButton={TextButton(onClick={mapperImport=false}) {Text("Cancel")}})
    addFloorTo?.let { venueId ->
        var name by remember(venueId) { mutableStateOf("") }
        var level by remember(venueId) { mutableStateOf("") }
        val siblings = state.projects.filter { it.venueId == venueId }
        val clash = siblings.any { it.floorName.equals(name.trim(), ignoreCase = true) }
        val levelTaken = level.toIntOrNull()?.let { l -> siblings.any { it.floorLevel == l } } == true
        AlertDialog(onDismissRequest = { addFloorTo = null }, title = { Text("Add floor") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Adds an empty floor to ${siblings.firstOrNull()?.venueName ?: "this building"}. Then map it with your phone (Tools → Map a floor with your phone) or import a plan in Map setup.")
                OutlinedTextField(name, { name = it.take(120) }, label = { Text("Floor name") }, singleLine = true, isError = clash,
                    supportingText = { if (clash) Text("This building already has a floor with that name") })
                OutlinedTextField(level, { level = it.filter { c -> c.isDigit() || c == '-' }.take(3) }, label = { Text("Level (0 = ground, -1 = basement)") },
                    singleLine = true, isError = levelTaken, supportingText = { if (levelTaken) Text("Another floor already uses this level") })
            }
        }, confirmButton = { Button(enabled = available && name.isNotBlank() && !clash && !levelTaken && level.toIntOrNull() != null, onClick = {
            actions.onFloorAdded(venueId, name.trim(), level.toInt()); addFloorTo = null
        }) { Text("Add floor") } }, dismissButton = { TextButton(onClick = { addFloorTo = null }) { Text("Cancel") } })
    }
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
        title = { Text("Add a building") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Start with one floor (for example your ground floor). You can add more floors later. Everything stays on this phone.")
                OutlinedTextField(venue, { venue = it.take(120) }, label = { Text("Building name") }, placeholder = { Text("e.g. Home") }, singleLine = true)
                OutlinedTextField(floor, { floor = it.take(120) }, label = { Text("Floor name") }, placeholder = { Text("e.g. Ground floor") }, singleLine = true)
            }
        },
        confirmButton = { Button(enabled = available && venue.isNotBlank() && floor.isNotBlank(), onClick = {
            actions.onProjectCreated(ProjectInput(venue, floor)); creating = false
        }) { Text("Add building") } },
        dismissButton = { TextButton(onClick = { creating = false }) { Text("Cancel") } },
    )
    pendingOpen?.let { context ->
        AlertDialog(
            onDismissRequest = { pendingOpen = null },
            title = { Text("Discard unsaved changes?") },
            text = { Text("You have map changes that aren't saved yet. Opening this floor throws them away. Everything you already saved (maps and Wi-Fi recordings) stays on this phone.") },
            confirmButton = { Button(enabled = available, onClick = {
                opening = context; actions.onProjectOpened(context, true); pendingOpen = null
            }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Discard changes and open") } },
            dismissButton = { TextButton(onClick = { pendingOpen = null }) { Text("Keep editing") } },
        )
    }
    val importAllowed = available && !state.hasUnsavedMapChanges && state.mode == com.turn.fieldtest.ui.model.DataMode.REAL_DEVICE
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(PagePadding),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Your buildings and their floors. Open a floor to set it up or to navigate on it.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.projectBusy || state.startupLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(state.projectStatus, style = MaterialTheme.typography.bodyMedium)
        if (state.hasUnsavedMapChanges) Text("Your map has unsaved changes. Save them in Map setup — opening another floor would throw them away.")
        if (state.sessionLocked) Text("You can switch floors once the current recording has finished saving.")
        if (state.projects.isEmpty() && !state.startupLoading) {
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                EmptyState("No buildings yet", "Tap Add a building to start. Then map each floor with your phone (Tools → Map a floor with your phone), " +
                    "or import a floor plan picture in Map setup.") {
                    Button(onClick = { creating = true }, enabled = available && !state.hasUnsavedMapChanges) { Text("Add a building") }
                }
            }
        }
        if (buildings.isNotEmpty()) SectionTitle("Your buildings")
        buildings.forEach { floors ->
            val first = floors.first()
            val current = floors.firstOrNull { state.activeProject == it.context && state.realMapReady }
            OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.outlinedCardColors(
                containerColor = if (current != null) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(if (current != null) 2.dp else 1.dp,
                    if (current != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Surface(Modifier.size(52.dp), shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                            Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Apartment, null, tint = MaterialTheme.colorScheme.primary) }
                        }
                        Column(Modifier.weight(1f)) {
                            Text(first.venueName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(if (floors.size == 1) "1 floor" else "${floors.size} floors", style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (current != null) Icon(Icons.Outlined.CheckCircle, "Current building", tint = MaterialTheme.colorScheme.primary)
                    }
                    // Highest floor first, as in a lift panel.
                    floors.sortedByDescending { it.floorLevel }.forEach { f ->
                        FloorRow(f, selected = f == current, enabled = available) { open(f.context) }
                    }
                    if (current != null) {
                        // Setup progress for the open floor: map, connected places, then completed Wi-Fi survey points.
                        val completed = state.surveyPointProgress.values.count { it.status == "COMPLETED" }
                        val steps = listOf(state.draftWalkablePolygon.size >= 3, state.destinations.isNotEmpty() && state.navigationNodes.isNotEmpty(),
                            completed > 0 && completed >= state.referencePoints.size)
                        Text("${current.floorName} setup", style = MaterialTheme.typography.labelLarge)
                        LinearProgressIndicator({ steps.count { it } / steps.size.toFloat() }, Modifier.fillMaxWidth().height(6.dp),
                            strokeCap = androidx.compose.ui.graphics.StrokeCap.Round, gapSize = 0.dp, drawStopIndicator = {})
                        Text(when {
                            !steps[0] -> "Next: map this floor (Map setup, or Tools → Map a floor with your phone)"
                            !steps[1] -> "Next: add places and connect paths in Map setup"
                            !steps[2] -> "Next: record Wi-Fi · $completed of ${state.referencePoints.size} points done"
                            else -> "Ready to navigate · $completed points recorded"
                        }, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Button(onClick = { state.selectDestination(if (state.draftWalkablePolygon.size >= 3) TurnDestination.LIVE_LOCATE else TurnDestination.FLOOR_EDITOR) },
                            enabled = available, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                            Text(if (state.draftWalkablePolygon.size >= 3) "Explore floor" else "Prepare this floor")
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { state.selectDestination(TurnDestination.FLOOR_EDITOR) }, enabled = available,
                                modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) { Text("Map setup") }
                            OutlinedButton(onClick = { open(current.context) }, enabled = available,
                                modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) { Text("Reload saved") }
                        }
                        if (floors.size > 1) OutlinedButton(onClick = { state.selectDestination(TurnDestination.FLOOR_LINKS) }, enabled = available,
                            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text("Link floors · stairs and lifts") }
                    }
                    TextButton(onClick = { addFloorTo = first.venueId }, enabled = available && !state.hasUnsavedMapChanges &&
                        state.mode == com.turn.fieldtest.ui.model.DataMode.REAL_DEVICE) {
                        Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(6.dp)); Text("Add floor")
                    }
                }
            }
        }
        SectionTitle("Other ways to add a floor")
        OutlinedButton(enabled = available && !state.hasUnsavedMapChanges, onClick = { creating = true }, modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp)) { Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text("Add a building") }
        OutlinedButton(onClick = actions.onMapperImportRequested, enabled = importAllowed, modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp)) { Icon(Icons.Outlined.FileOpen, null); Spacer(Modifier.width(8.dp)); Text("Import a TURN-Mapper file") }
        AdvancedSection(title = UNIVERSITY_FLOORS, subtitle = "Ready-made FAST-NUCES floors with rooms, walls and paths (no Wi-Fi recordings)") {
        OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
            bundledMapperFloors.forEachIndexed { index, f ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                val added = state.projects.any { it.floorName == f }
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${f.substringBefore('-')} Block", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.width(76.dp))
                    Text(floorLabel(f), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    if (added) Text("Added", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp))
                    TextButton(onClick = { startImport(f) }, enabled = importAllowed) { Text(if (added) "Re-import" else "Add") }
                }
            }
        }
        }
    }
}

/** Title of the collapsed section holding the bundled university floors (tests expand it by this name). */
internal const val UNIVERSITY_FLOORS = "University floors"

@Composable
private fun FloorRow(floor: ProjectSummary, selected: Boolean, enabled: Boolean, onOpen: () -> Unit) {
    Surface(shape = RoundedCornerShape(12.dp), color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(levelBadge(floor.floorLevel), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(40.dp))
            Column(Modifier.weight(1f)) {
                Text(floor.floorName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text("${floor.fingerprintPointCount} Wi-Fi points recorded", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (selected) Text("Open now", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 12.dp))
            else TextButton(onClick = onOpen, enabled = enabled) { Text("Open") }
        }
    }
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, enabled: Boolean = true, onSelect: () -> Unit) {
    // One selectable radio item, so screen readers (and tests) see the label and its selected state together.
    Row(Modifier.fillMaxWidth().selectable(selected, enabled = enabled, role = androidx.compose.ui.semantics.Role.RadioButton, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = null, enabled = enabled); Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/** A bundled floor can join a building that lacks both its name and its level (levels are unique per building). */
private fun canJoin(building: List<ProjectSummary>, floorId: String) =
    building.none { it.floorName.equals(floorId, ignoreCase = true) || it.floorLevel == bundledLevel(floorId) }

/** Level of a bundled mapper floor from its id: GF is 0, Bn is -n, Fn is n. */
private fun bundledLevel(floorId: String) = when (val l = floorId.substringAfter('-')) {
    "GF" -> 0
    else -> if (l.startsWith("B")) -(l.drop(1).toIntOrNull() ?: 1) else l.removePrefix("F").toIntOrNull() ?: 0
}

/** Short lift-style level label: G for 0, B1 for -1, otherwise the number. */
internal fun levelBadge(level: Int) = when {
    level == 0 -> "G"
    level < 0 -> "B${-level}"
    else -> level.toString()
}

/** Floors bundled under assets/mapper, in the order the import picker has always offered them. */
private val bundledMapperFloors = listOf("C-F4","C-F3","B-GF","B-F1","C-F2","C-F5","C-B1","C-GF","C-F1","A-GF","A-F1","A-F2","A-F3","B-F2")

private fun floorLabel(id: String) = when (val level = id.substringAfter('-')) {
    "GF" -> "Ground floor"
    "B1" -> "Basement"
    else -> "Floor ${level.removePrefix("F")}"
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
}
