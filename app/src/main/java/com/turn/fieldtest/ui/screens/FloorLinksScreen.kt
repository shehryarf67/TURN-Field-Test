package com.turn.fieldtest.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.turn.fieldtest.core.*
import com.turn.fieldtest.data.repository.FloorConnector
import com.turn.fieldtest.data.repository.ProjectSummary
import com.turn.fieldtest.data.repository.SavedFloorMap
import com.turn.fieldtest.data.repository.decodePolygon
import com.turn.fieldtest.data.repository.destinationAreas
import com.turn.fieldtest.ui.ConnectorSave
import com.turn.fieldtest.ui.TurnAppActions
import com.turn.fieldtest.ui.TurnAppState
import com.turn.fieldtest.ui.model.TurnDestination
import com.turn.fieldtest.ui.theme.TurnAmber
import com.turn.fieldtest.ui.theme.TurnMint
import com.turn.fieldtest.ui.theme.TurnRed

/** What the editor is working on; [replacingId] is set when editing a saved link. */
private data class LinkEditor(
    val kind: ConnectorKind = ConnectorKind.STAIRS, val label: String = "",
    val fromFloorId: String? = null, val from: MetricPoint? = null,
    val toFloorId: String? = null, val to: MetricPoint? = null,
    val travel: String = "", val travelEdited: Boolean = false,
    val accessible: Boolean = ConnectorKind.STAIRS.stepFree, val bidirectional: Boolean = true,
    val verified: Boolean = false, val replacingId: String? = null,
)

/** Seconds as typed: locale-independent digits, no trailing ".0". */
private fun seconds(value: Double) = if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

private fun FloorConnector.toEditor() = LinkEditor(kind, entity.label, entity.fromFloorId, MetricPoint(entity.fromXMetres, entity.fromYMetres),
    entity.toFloorId, MetricPoint(entity.toXMetres, entity.toYMetres), seconds(entity.travelTimeSeconds), true,
    entity.accessible, entity.bidirectional, entity.verifiedOnSite, entity.id)

private fun ConnectorDraft.toEditor() = LinkEditor(kind, label, fromFloorId, from, toFloorId, to, seconds(travelTimeSeconds), false,
    kind.stepFree, true, false)

@Composable
fun FloorLinksScreen(state: TurnAppState, actions: TurnAppActions) {
    LaunchedEffect(state.activeProject?.venueId) { actions.onFloorLinksRefresh() }
    var editing by remember { mutableStateOf<LinkEditor?>(null) }
    var deleting by remember { mutableStateOf<FloorConnector?>(null) }
    val floors = state.projects.filter { it.venueId == state.activeProject?.venueId }.sortedByDescending { it.floorLevel }
    var aligning by remember { mutableStateOf(false) }
    val locked = state.floorLinksBusy || state.sessionLocked
    val alignments = buildingAlignments(state)
    editing?.let { e -> LinkEditorPanel(state, floors, e, alignments, { editing = it }, onCancel = { editing = null }) { save -> actions.onConnectorSaved(save); editing = null }; return }
    if (aligning) { AlignFloorsPanel(state, floors, alignments, locked, onClose = { aligning = false }) { id, a -> actions.onFloorAlignmentSaved(id, a); aligning = false }; return }
    deleting?.let { c ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Remove ${c.entity.label}?") },
            text = { Text("Routes and floor changes will no longer use this link. You can add it again later.") },
            confirmButton = { TextButton(onClick = { actions.onConnectorDeleted(c.entity.id); deleting = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Keep") } })
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(PagePadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(state.venueName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Floors stay separate until you link the places where people move between them: stairs, lifts, escalators and ramps.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(state.floorLinksStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        if (floors.size < 2) {
            OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("This building has one floor", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text("Add another floor in Buildings, then link them here.", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { state.selectDestination(TurnDestination.VENUES) }) { Text("Go to Buildings") }
                }
            }
            return@Column
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { editing = LinkEditor(fromFloorId = floors.getOrNull(1)?.floorId, toFloorId = floors.firstOrNull()?.floorId) },
                enabled = !locked, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) {
                Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(6.dp)); Text("New link")
            }
            OutlinedButton(onClick = actions.onConnectorSuggestionsRequested, enabled = !locked, modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp)) { Text("Suggest stair links") }
        }
        if (state.connectorSuggestions.isNotEmpty()) {
            Text("Suggested from stair landings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Landing numbers come from automatic labels and may not be the same staircase on every floor. Review each one, then check it on site.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.connectorSuggestions.forEach { s ->
                val a = floors.firstOrNull { it.floorId == s.fromFloorId }; val b = floors.firstOrNull { it.floorId == s.toFloorId }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text("${a?.floorName ?: "?"} ↔ ${b?.floorName ?: "?"}", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { editing = s.toEditor() }, enabled = !locked) { Text("Review & add") }
                }
            }
        }
        Text("Links (${state.floorConnectors.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (state.floorConnectors.isEmpty()) Text("No links yet.", style = MaterialTheme.typography.bodySmall)
        state.floorConnectors.forEach { c -> LinkCard(c, linkProblems(state, c), linkAlignmentNote(c, alignments), !locked,
            onVerify = { actions.onConnectorVerified(c.entity.id, !c.entity.verifiedOnSite) }, onEdit = { editing = c.toEditor() }, onDelete = { deleting = c }) }
        FloorHeightsCard(state, floors, !locked, actions.onFloorElevationsSaved)
        AlignFloorsCard(floors, alignments, !locked) { aligning = true }
    }
}

/** Every floor's saved alignment in the open building (floor id -> alignment onto another floor). */
private fun buildingAlignments(state: TurnAppState): Map<String, FloorAlignment> =
    state.buildingFloorMaps.mapNotNull { (id, saved) -> FloorAlignment.fromJsonOrNull(saved.floor.frameAlignmentJson)?.let { id to it } }.toMap()

/** What the saved alignments say about a link's two ends, or null when its floors are not aligned. */
private fun linkAlignmentNote(c: FloorConnector, alignments: Map<String, FloorAlignment>): LinkAlignmentCheck? {
    val t = FloorAlignmentSolver.transformBetween(c.entity.fromFloorId, c.entity.toFloorId, alignments) ?: return null
    return FloorLinkAlignment.check(c.kind, MetricPoint(c.entity.fromXMetres, c.entity.fromYMetres), MetricPoint(c.entity.toXMetres, c.entity.toYMetres), t)
}

/** Fixed-format metres for text fields: locale-independent, no trailing zeros beyond two decimals. */
private fun metres(value: Double) = String.format(java.util.Locale.ROOT, "%.2f", value)

/**
 * Floor heights (schema 8): each floor's height above the ground floor, so stairs and lift rides are judged against
 * the building's real storeys (a 2.7 m house storey is one floor, not 0.77 of a standard 3.5 m one).
 */
@Composable
private fun FloorHeightsCard(state: TurnAppState, floors: List<ProjectSummary>, enabled: Boolean, onSave: (Map<String, Double?>) -> Unit) {
    val saved = floors.associate { it.floorId to state.buildingFloorMaps[it.floorId]?.floor?.elevationMetres }
    var storey by remember { mutableStateOf("2.7") }
    var inputs by remember(saved) { mutableStateOf(saved.mapValues { (_, v) -> v?.let(::metres).orEmpty() }) }
    val levels = floors.associate { it.floorId to it.floorLevel }
    val names = floors.associate { it.floorId to it.floorName }
    val parsed = inputs.mapValues { (_, v) -> v.trim().toDoubleOrNull() }
    val anyTyped = inputs.values.any { it.isNotBlank() }
    val error = when {
        !anyTyped -> null
        inputs.any { (_, v) -> v.isNotBlank() && v.trim().toDoubleOrNull() == null } -> "Enter heights as numbers in metres, e.g. 2.7"
        else -> FloorElevations.validationError(levels, parsed, names)
    }
    OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Floor heights", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("How high each floor is above the ground floor. Stairs and lift rides are then judged against your building's real storeys; unset uses a standard 3.5 m storey.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (saved.values.all { it == null }) "Not set · standard 3.5 m storeys"
                else "Set · " + floors.sortedBy { it.floorLevel }.joinToString(" · ") { "${levelBadge(it.floorLevel)} ${saved[it.floorId]?.let(::metres) ?: "?"} m" },
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(storey, { storey = it.filter { c -> c.isDigit() || c == '.' }.take(5) }, label = { Text("Storey height (m)") },
                    singleLine = true, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = {
                    storey.toDoubleOrNull()?.let { h -> runCatching { FloorElevations.fromStoreyHeight(levels, h) }.getOrNull() }
                        ?.let { e -> inputs = e.mapValues { (_, v) -> metres(v) } }
                }, enabled = enabled && storey.toDoubleOrNull()?.let { it in FloorElevations.MIN_STOREY_METRES..FloorElevations.MAX_STOREY_METRES } == true) {
                    Text("Fill all floors")
                }
            }
            floors.sortedByDescending { it.floorLevel }.forEach { f ->
                OutlinedTextField(inputs[f.floorId].orEmpty(), { v -> inputs = inputs + (f.floorId to v.filter { c -> c.isDigit() || c == '.' || c == '-' }.take(7)) },
                    label = { Text("${levelBadge(f.floorLevel)} · ${f.floorName} height (m)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onSave(parsed) }, enabled = enabled && anyTyped && error == null && parsed != saved,
                    modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) { Text("Save heights") }
                OutlinedButton(onClick = { onSave(saved.mapValues { null }) }, enabled = enabled && saved.values.any { it != null },
                    modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) { Text("Clear heights") }
            }
        }
    }
}

@Composable
private fun AlignFloorsCard(floors: List<ProjectSummary>, alignments: Map<String, FloorAlignment>, enabled: Boolean, onAlign: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Align floors at the stairs", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Each mapped floor has its own origin and direction. Pick the same two spots on two floors (e.g. two stairwell corners) to line the floors up; link ends can then be placed and checked against each other. Tracking does not need it.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val name = floors.associate { it.floorId to "${levelBadge(it.floorLevel)} ${it.floorName}" }
            if (alignments.isEmpty()) Text("No floors aligned yet", style = MaterialTheme.typography.labelMedium)
            alignments.forEach { (id, a) ->
                Text(String.format(java.util.Locale.ROOT, "%s → %s · turned %.1f° · fit within %.2f m%s", name[id] ?: id, name[a.toFloorId] ?: a.toFloorId,
                    a.transform.rotationDegrees, a.maxResidualMetres, if (a.scaleWarning) " · scales differ" else ""),
                    style = MaterialTheme.typography.labelMedium, color = if (a.scaleWarning) TurnAmber else TurnMint, fontWeight = FontWeight.Bold)
            }
            OutlinedButton(onClick = onAlign, enabled = enabled, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text("Align two floors") }
        }
    }
}

/**
 * Pick two matching points on each of two floors; shows the fitted rotation and translation, the residual error, any
 * scale disagreement, and what the alignment says about the links between the two floors.
 */
@Composable
private fun AlignFloorsPanel(state: TurnAppState, floors: List<ProjectSummary>, alignments: Map<String, FloorAlignment>, locked: Boolean,
    onClose: () -> Unit, onSave: (String, FloorAlignment?) -> Unit) {
    var reference by remember { mutableStateOf(floors.minByOrNull { it.floorLevel }?.floorId) }
    var moving by remember { mutableStateOf(floors.sortedBy { it.floorLevel }.getOrNull(1)?.floorId) }
    var here by remember(moving) { mutableStateOf(listOf<MetricPoint>()) }
    var there by remember(reference) { mutableStateOf(listOf<MetricPoint>()) }
    fun pick(list: List<MetricPoint>, p: MetricPoint) = if (list.size < 2) list + p else listOf(list[1], p)
    val solved = if (reference != null && moving != null && reference != moving && here.size == 2 && there.size == 2)
        runCatching { FloorAlignmentSolver.solve(reference!!, here, there, System.currentTimeMillis()) } else null
    BackHandlerCompat(onClose)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(PagePadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Align two floors", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = onClose) { Text("Close") }
        }
        Text("Tap the same two physical spots on both floors, in the same order: point 1, then point 2 (e.g. the two corners at the foot of the stairwell). They should be at least 0.5 m apart; further apart is more accurate.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        AlignPointPicker("Reference floor", floors, reference, there, state, { reference = it; there = emptyList() }) { there = pick(there, it) }
        AlignPointPicker("Floor to align", floors.filter { it.floorId != reference }, moving, here, state, { moving = it; here = emptyList() }) { here = pick(here, it) }
        TextButton(onClick = { here = emptyList(); there = emptyList() }) { Text("Clear points") }
        when {
            solved == null -> Text("Pick ${2 - there.size.coerceAtMost(2)} more on the reference floor and ${2 - here.size.coerceAtMost(2)} more on the floor to align.",
                style = MaterialTheme.typography.bodySmall)
            solved.isFailure -> Text(solved.exceptionOrNull()?.message ?: "Cannot align these points", color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
            else -> {
                val r = solved.getOrThrow()
                val a = r.alignment
                Text(String.format(java.util.Locale.ROOT, "Turn %.1f° · shift %.2f, %.2f m · residual %.2f m (rms %.2f m) · distance ratio %.3f",
                    a.transform.rotationDegrees, a.transform.tx, a.transform.ty, a.maxResidualMetres, a.rmsResidualMetres, a.scaleRatio),
                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                r.warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = TurnAmber) }
                val links = state.floorConnectors.filter { setOf(it.entity.fromFloorId, it.entity.toFloorId) == setOf(moving, reference) }
                val trial = alignments.filterKeys { it != moving && it != reference } + (moving!! to a)
                links.forEach { c -> linkAlignmentNote(c, trial)?.let { check ->
                    Text("${c.entity.label}: ${check.message}", style = MaterialTheme.typography.bodySmall,
                        color = if (check.consistent) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                } }
                Button(onClick = { onSave(moving!!, a) }, enabled = !locked, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    shape = RoundedCornerShape(16.dp)) { Text("Save alignment") }
            }
        }
        val existing = moving?.let { alignments[it] }
        if (existing != null) OutlinedButton(onClick = { onSave(moving!!, null) }, enabled = !locked, modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)) { Text("Remove this floor's alignment") }
    }
}

@Composable
private fun AlignPointPicker(title: String, floors: List<ProjectSummary>, floorId: String?, points: List<MetricPoint>, state: TurnAppState,
    onFloor: (String) -> Unit, onPoint: (MetricPoint) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val floor = floors.firstOrNull { it.floorId == floorId }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        Box {
            OutlinedButton(onClick = { open = true }) { Text(floor?.let { "${levelBadge(it.floorLevel)} · ${it.floorName}" } ?: "Choose floor") }
            DropdownMenu(open, { open = false }) {
                floors.forEach { f -> DropdownMenuItem(text = { Text("${levelBadge(f.floorLevel)} · ${f.floorName}") }, onClick = { open = false; onFloor(f.floorId) }) }
            }
        }
        Text("${points.size} of 2 points", style = MaterialTheme.typography.bodySmall)
    }
    val saved = floorId?.let { state.buildingFloorMaps[it] }
    when {
        floorId == null -> Unit
        saved == null -> Text("Loading this floor's map…", style = MaterialTheme.typography.bodySmall)
        else -> FloorPickMap(saved, null, state.floorConnectors, onPoint,
            Modifier.fillMaxWidth().height(230.dp).semantics { contentDescription = "$title map" }, numbered = points)
    }
}

/** Problems with a saved link against the floors' current saved maps (a later map edit can strand a point). */
private fun linkProblems(state: TurnAppState, c: FloorConnector): List<String> {
    val a = state.buildingFloorMaps[c.entity.fromFloorId]; val b = state.buildingFloorMaps[c.entity.toFloorId]
    if (a == null || b == null) return emptyList() // still loading
    return ConnectorRules.problems(ConnectorDraft(c.kind, c.entity.fromFloorId, MetricPoint(c.entity.fromXMetres, c.entity.fromYMetres),
        c.entity.toFloorId, MetricPoint(c.entity.toXMetres, c.entity.toYMetres), c.entity.label, c.entity.travelTimeSeconds, c.entity.activationRadiusMetres),
        a.walkableFloor(), b.walkableFloor(), a.floor.levelNumber, b.floor.levelNumber)
}

private fun SavedFloorMap.walkableFloor(): MapFloor? = runCatching { metricMap().floors[floor.id] }.getOrNull()

@Composable
private fun LinkCard(c: FloorConnector, problems: List<String>, alignment: LinkAlignmentCheck?, enabled: Boolean,
    onVerify: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(Modifier.size(36.dp), shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Box(contentAlignment = Alignment.Center) { Text(c.kind.badge, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary) }
                }
                Column(Modifier.weight(1f)) {
                    Text(c.entity.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text("${levelBadge(c.fromLevel)} ${c.fromFloorName} ${if (c.entity.bidirectional) "↔" else "→"} ${levelBadge(c.toLevel)} ${c.toFloorName}",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            Text("${c.kind.label} · %.0f s · ${if (c.entity.accessible) "step-free" else "has steps"}".format(c.entity.travelTimeSeconds),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (c.entity.verifiedOnSite) "Checked on site" else "Not yet checked on site", style = MaterialTheme.typography.labelMedium,
                color = if (c.entity.verifiedOnSite) TurnMint else TurnAmber, fontWeight = FontWeight.Bold)
            problems.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            alignment?.let { Text(it.message, style = MaterialTheme.typography.bodySmall,
                color = if (it.consistent) MaterialTheme.colorScheme.onSurfaceVariant else TurnAmber) }
            Row {
                TextButton(onClick = onVerify, enabled = enabled) { Text(if (c.entity.verifiedOnSite) "Mark not checked" else "Mark checked on site") }
                TextButton(onClick = onEdit, enabled = enabled) { Text("Edit") }
                TextButton(onClick = onDelete, enabled = enabled) { Text("Remove") }
            }
        }
    }
}

@Composable
private fun LinkEditorPanel(state: TurnAppState, floors: List<ProjectSummary>, e: LinkEditor, alignments: Map<String, FloorAlignment>,
    update: (LinkEditor) -> Unit, onCancel: () -> Unit, onSave: (ConnectorSave) -> Unit) {
    // With aligned floors, the far end can be placed straight above or below the first one, and the pair checked.
    val frame = if (e.fromFloorId != null && e.toFloorId != null && e.fromFloorId != e.toFloorId)
        FloorAlignmentSolver.transformBetween(e.fromFloorId, e.toFloorId, alignments) else null
    val fromLevel = floors.firstOrNull { it.floorId == e.fromFloorId }?.floorLevel
    val toLevel = floors.firstOrNull { it.floorId == e.toFloorId }?.floorLevel
    // Travel time follows kind and floor gap until the user types their own measured value.
    LaunchedEffect(e.kind, fromLevel, toLevel) {
        if (!e.travelEdited && fromLevel != null && toLevel != null)
            update(e.copy(travel = seconds(ConnectorRules.defaultTravelSeconds(e.kind, fromLevel, toLevel))))
    }
    val draft = if (e.fromFloorId != null && e.toFloorId != null && e.from != null && e.to != null)
        ConnectorDraft(e.kind, e.fromFloorId, e.from, e.toFloorId, e.to, e.label.trim(), e.travel.toDoubleOrNull() ?: Double.NaN) else null
    val problems = buildList {
        if (e.from == null || e.to == null) add("Tap the link's position on both floors")
        if (draft != null) addAll(ConnectorRules.problems(draft, state.buildingFloorMaps[draft.fromFloorId]?.walkableFloor(),
            state.buildingFloorMaps[draft.toFloorId]?.walkableFloor(), fromLevel, toLevel))
    }
    BackHandlerCompat(onCancel)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(PagePadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (e.replacingId == null) "New floor link" else "Edit floor link", style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ConnectorKind.entries.forEach { k ->
                FilterChip(e.kind == k, { update(e.copy(kind = k, accessible = k.stepFree)) }, label = { Text(k.label) })
            }
        }
        OutlinedTextField(e.label, { update(e.copy(label = it.take(60))) }, label = { Text("Name, e.g. Stairs 1 or Main lift") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        FloorPointPicker("From", floors, e.fromFloorId, e.from, state.buildingFloorMaps, state.floorConnectors, e.replacingId,
            { update(e.copy(fromFloorId = it, from = null)) }) { update(e.copy(from = it)) }
        FloorPointPicker("To", floors, e.toFloorId, e.to, state.buildingFloorMaps, state.floorConnectors, e.replacingId,
            { update(e.copy(toFloorId = it, to = null)) }) { update(e.copy(to = it)) }
        if (frame != null) {
            if (e.from != null) {
                val placed = FloorLinkAlignment.placeOtherEnd(e.from, frame)
                val inside = placed.x in 0.0..(state.buildingFloorMaps[e.toFloorId]?.floor?.widthMetres ?: 0.0) &&
                    placed.y in 0.0..(state.buildingFloorMaps[e.toFloorId]?.floor?.heightMetres ?: 0.0)
                OutlinedButton(onClick = { update(e.copy(to = placed)) }, enabled = inside, modifier = Modifier.fillMaxWidth()) {
                    Text(if (inside) "Place To directly above/below From (aligned floors)" else "From lies outside the other floor's map")
                }
            }
            if (e.from != null && e.to != null) {
                val check = FloorLinkAlignment.check(e.kind, e.from, e.to, frame)
                Text(check.message, style = MaterialTheme.typography.bodySmall,
                    color = if (check.consistent) MaterialTheme.colorScheme.onSurfaceVariant else TurnAmber)
            }
        }
        OutlinedTextField(e.travel, { update(e.copy(travel = it.filter { c -> c.isDigit() || c == '.' }.take(5), travelEdited = true)) },
            label = { Text("Travel time (seconds)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        SwitchRow("Step-free (usable without steps)", e.accessible) { update(e.copy(accessible = it)) }
        SwitchRow("Two-way", e.bidirectional) { update(e.copy(bidirectional = it)) }
        SwitchRow("Checked on site: these two points really connect", e.verified) { update(e.copy(verified = it)) }
        problems.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        Button(onClick = { draft?.let { onSave(ConnectorSave(it, e.bidirectional, e.accessible, e.verified, e.replacingId)) } },
            enabled = problems.isEmpty() && !state.floorLinksBusy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            shape = RoundedCornerShape(16.dp)) { Text("Save link") }
    }
}

@Composable
private fun BackHandlerCompat(onBack: () -> Unit) = androidx.activity.compose.BackHandler(onBack = onBack)

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium); Switch(checked, onChange)
    }
}

@Composable
private fun FloorPointPicker(title: String, floors: List<ProjectSummary>, floorId: String?, point: MetricPoint?,
    maps: Map<String, SavedFloorMap>, connectors: List<FloorConnector>, editingId: String?,
    onFloor: (String) -> Unit, onPoint: (MetricPoint) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val floor = floors.firstOrNull { it.floorId == floorId }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        Box {
            OutlinedButton(onClick = { open = true }) { Text(floor?.let { "${levelBadge(it.floorLevel)} · ${it.floorName}" } ?: "Choose floor") }
            DropdownMenu(open, { open = false }) {
                floors.forEach { f -> DropdownMenuItem(text = { Text("${levelBadge(f.floorLevel)} · ${f.floorName}") }, onClick = { open = false; onFloor(f.floorId) }) }
            }
        }
        if (point != null) Text("%.1f, %.1f m".format(point.x, point.y), style = MaterialTheme.typography.bodySmall)
    }
    val saved = floorId?.let { maps[it] }
    when {
        floorId == null -> Unit
        saved == null -> Text("Loading this floor's map…", style = MaterialTheme.typography.bodySmall)
        else -> FloorPickMap(saved, point, connectors.filter { it.entity.id != editingId }, onPoint,
            Modifier.fillMaxWidth().height(230.dp).semantics { contentDescription = "$title floor map" })
    }
}

/**
 * Tap-to-pick view of any saved floor of the building: walkable space, rooms, walls, numbered stair landings
 * (amber) and other links (mint), so points can be placed on floors other than the one that is open.
 */
@Composable
internal fun FloorPickMap(saved: SavedFloorMap, marker: MetricPoint?, connectors: List<FloorConnector>, onPick: (MetricPoint) -> Unit, modifier: Modifier = Modifier,
    /** Numbered picks (1, 2, …), e.g. the matching points of a floor alignment. */
    numbered: List<MetricPoint> = emptyList()) {
    val regions = remember(saved) { saved.regions.filter { it.enabled }.mapNotNull { runCatching { decodePolygon(it.polygonJson) }.getOrNull() } }
    val rooms = remember(saved) { runCatching { saved.destinationAreas() }.getOrDefault(emptyList()) }
    val landings = remember(saved) { saved.points.mapNotNull { p -> StairLandingMatcher.landingNumber(p.name)?.let { it to MetricPoint(p.xMetres, p.yMetres) } } }
    val links = connectors.mapNotNull { c -> when (saved.floor.id) {
        c.entity.fromFloorId -> c.kind.badge to MetricPoint(c.entity.fromXMetres, c.entity.fromYMetres)
        c.entity.toFloorId -> c.kind.badge to MetricPoint(c.entity.toXMetres, c.entity.toYMetres)
        else -> null } }
    val palette = MaterialTheme.colorScheme
    val density = LocalDensity.current.density
    val w = saved.floor.widthMetres; val h = saved.floor.heightMetres
    var size by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }
    fun scale() = minOf(size.width / w, size.height / h).toFloat() * 0.94f
    fun origin() = Offset((size.width - (w * scale()).toFloat()) / 2f, (size.height - (h * scale()).toFloat()) / 2f)
    fun project(p: MetricPoint) = origin() + Offset((p.x * scale()).toFloat(), ((h - p.y) * scale()).toFloat())
    // The tap loop outlives recompositions; always call the latest callback, which carries the current editor state.
    val pick by rememberUpdatedState(onPick)
    Canvas(modifier.pointerInput(saved) {
        detectTapGestures { tap ->
            val s = scale(); if (s <= 0f) return@detectTapGestures
            val o = origin()
            val p = MetricPoint(((tap.x - o.x) / s).toDouble(), h - ((tap.y - o.y) / s).toDouble())
            if (p.x in 0.0..w && p.y in 0.0..h) pick(p)
        }
    }) {
        size = this.size
        drawRect(palette.surfaceContainerLow)
        fun poly(vertices: List<MetricPoint>) = Path().apply {
            vertices.forEachIndexed { i, v -> val q = project(v); if (i == 0) moveTo(q.x, q.y) else lineTo(q.x, q.y) }; close()
        }
        regions.forEach { drawPath(poly(it.vertices), palette.primary.copy(alpha = 0.10f)) }
        rooms.forEach { drawPath(poly(it.polygon.vertices), palette.surface); drawPath(poly(it.polygon.vertices), palette.outlineVariant, style = Stroke(1f)) }
        saved.walls.filter { it.enabled }.forEach { drawLine(palette.outline, project(MetricPoint(it.startXMetres, it.startYMetres)),
            project(MetricPoint(it.endXMetres, it.endYMetres)), 1.5f * density) }
        val text = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = android.graphics.Paint.Align.CENTER; isFakeBoldText = true; textSize = 10f * density; color = Color.White.toArgb()
        }
        fun badge(p: MetricPoint, label: String, color: Color) {
            val q = project(p); drawCircle(color, 8f * density, q)
            drawContext.canvas.nativeCanvas.drawText(label, q.x, q.y + 3.5f * density, text)
        }
        landings.forEach { (n, p) -> badge(p, n.toString(), TurnAmber) }
        links.forEach { (b, p) -> badge(p, b, TurnMint) }
        numbered.forEachIndexed { i, p -> badge(p, (i + 1).toString(), palette.primary) }
        marker?.let { val q = project(it); drawCircle(Color.White, 11f * density, q); drawCircle(palette.primary, 8f * density, q)
            drawCircle(palette.primary, 16f * density, q, style = Stroke(2f * density)) }
    }
}
