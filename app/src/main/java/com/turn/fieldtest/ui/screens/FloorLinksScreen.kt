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
    val locked = state.floorLinksBusy || state.sessionLocked
    editing?.let { e -> LinkEditorPanel(state, floors, e, { editing = it }, onCancel = { editing = null }) { save -> actions.onConnectorSaved(save); editing = null }; return }
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
        state.floorConnectors.forEach { c -> LinkCard(c, linkProblems(state, c), !locked,
            onVerify = { actions.onConnectorVerified(c.entity.id, !c.entity.verifiedOnSite) }, onEdit = { editing = c.toEditor() }, onDelete = { deleting = c }) }
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
private fun LinkCard(c: FloorConnector, problems: List<String>, enabled: Boolean, onVerify: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
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
            Row {
                TextButton(onClick = onVerify, enabled = enabled) { Text(if (c.entity.verifiedOnSite) "Mark not checked" else "Mark checked on site") }
                TextButton(onClick = onEdit, enabled = enabled) { Text("Edit") }
                TextButton(onClick = onDelete, enabled = enabled) { Text("Remove") }
            }
        }
    }
}

@Composable
private fun LinkEditorPanel(state: TurnAppState, floors: List<ProjectSummary>, e: LinkEditor, update: (LinkEditor) -> Unit,
    onCancel: () -> Unit, onSave: (ConnectorSave) -> Unit) {
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
internal fun FloorPickMap(saved: SavedFloorMap, marker: MetricPoint?, connectors: List<FloorConnector>, onPick: (MetricPoint) -> Unit, modifier: Modifier = Modifier) {
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
        marker?.let { val q = project(it); drawCircle(Color.White, 11f * density, q); drawCircle(palette.primary, 8f * density, q)
            drawCircle(palette.primary, 16f * density, q, style = Stroke(2f * density)) }
    }
}
