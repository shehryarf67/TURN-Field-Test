package com.turn.fieldtest.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.turn.fieldtest.core.MapHeading
import com.turn.fieldtest.core.MetricPoint
import com.turn.fieldtest.ui.TurnAppActions
import com.turn.fieldtest.ui.TurnAppState
import com.turn.fieldtest.ui.model.TurnDestination
import com.turn.fieldtest.ui.theme.TurnAmber
import com.turn.fieldtest.ui.theme.TurnMint
import com.turn.fieldtest.ui.theme.TurnRed
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Room categories offered as search shortcuts; a category only appears when a place matches it. */
private val placeCategories = listOf(
    "Classrooms" to listOf("class"), "Labs" to listOf("lab"), "Washrooms" to listOf("wash", "toilet", "wr"),
    "Stairs" to listOf("stair"), "Offices" to listOf("office"),
)

private fun inCategory(name: String, category: String?): Boolean =
    category == null || placeCategories.first { it.first == category }.second.any { name.lowercase().contains(it) }

/** Next guidance line from a metric route (+x right, +y up). Pure, so turn wording never depends on the screen. */
internal data class RouteInstruction(val mapHeadingDegrees: Double, val distanceMetres: Double, val then: String)

internal fun nextInstruction(route: List<Offset>, destinationName: String): RouteInstruction? {
    val legs = route.zipWithNext().filter { (a, b) -> (b - a).getDistance() > 0.3f }
    if (legs.isEmpty()) return null
    fun heading(leg: Pair<Offset, Offset>) = Math.toDegrees(atan2((leg.second.y - leg.first.y).toDouble(), (leg.second.x - leg.first.x).toDouble()))
    fun turn(from: Double, to: Double) = ((to - from + 540.0) % 360.0) - 180.0
    val first = heading(legs.first())
    var distance = 0.0
    var index = 0
    while (index < legs.size && abs(turn(first, heading(legs[index]))) < 30.0) {
        distance += (legs[index].second - legs[index].first).getDistance(); index++
    }
    val then = if (index >= legs.size) "then arrive at $destinationName"
        else if (turn(first, heading(legs[index])) > 0) "then turn left" else "then turn right"
    return RouteInstruction(first, distance, then)
}

private fun walkingTime(metres: Double): String {
    val seconds = (metres / 1.2).roundToInt()
    return if (seconds < 60) "~${seconds.coerceAtLeast(1)} s" else "~${(seconds + 30) / 60} min"
}

/** Canvas is a presentation of the existing real-device session, never a second positioning engine. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CanvasNavigationScreen(state: TurnAppState, actions: TurnAppActions) {
    var sheet by rememberSaveable(state.activeProject) { mutableStateOf<String?>(null) }
    var query by rememberSaveable(state.activeProject) { mutableStateOf("") }
    var category by rememberSaveable(state.activeProject) { mutableStateOf<String?>(null) }
    var startQuery by rememberSaveable(state.activeProject) { mutableStateOf("") }
    var showReferences by rememberSaveable(state.activeProject) { mutableStateOf(false) }
    var wifiStartChosen by rememberSaveable(state.activeProject) { mutableStateOf(false) }
    var startLabel by rememberSaveable(state.activeProject) { mutableStateOf<String?>(null) }
    var startPlaceId by rememberSaveable(state.activeProject) { mutableStateOf<String?>(null) }
    var positionOnly by rememberSaveable(state.activeProject) { mutableStateOf(false) }
    var details by rememberSaveable { mutableStateOf(false) }
    var startAttempted by rememberSaveable(state.activeProject) { mutableStateOf(false) }
    var cardHeightPx by remember { mutableIntStateOf(0) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.liveRunning) { while (state.liveRunning) { now = System.currentTimeMillis(); delay(1000) } }
    val age = state.liveEstimateEpochMillis?.let { (now - it).coerceAtLeast(0) / 1000 }
    val confidence = navigationConfidence(state, age, now)
    val selected = state.destinations.firstOrNull { it.id == state.selectedDestinationId }
    LaunchedEffect(selected?.id) { if (selected != null) positionOnly = false }
    // A start picked on the map no longer matches the label it was given when it changes elsewhere.
    LaunchedEffect(state.manualStartPoint) { if (state.manualStartPoint == null && !wifiStartChosen) { startLabel = null; startPlaceId = null } }
    val hasWalkableMap = state.allWalkablePolygons().any { it.size >= 3 }
    val busy = state.projectBusy || state.liveStarting || state.surveyRunning || state.surveyStarting || state.realHeadingAlignmentBusy
    val previewGraph = remember(state.activeProject, state.navigationPreviewGraph, state.hasUnsavedMapChanges, state.navigationNodes.toList(),
        state.navigationEdges.toList(), state.draftWalkablePolygon.toList(), state.draftWalls.toList(), state.otherWalkableRegions.toList()) {
        if(state.hasUnsavedMapChanges) null else state.navigationPreviewGraph ?: runCatching { state.draftNavigationGraph() }.getOrNull()
    }
    val previewStart = state.manualStartPoint
    val startChosen = previewStart != null || wifiStartChosen
    val destinationNode = previewGraph?.nodes?.firstOrNull { it.poiId == selected?.id && selected != null }
    val routePreview = remember(previewGraph, previewStart, selected?.id, state.liveRunning) {
        if(state.liveRunning || previewStart==null || destinationNode==null) null else
            runCatching { previewGraph?.route(MetricPoint(previewStart.x.toDouble(),previewStart.y.toDouble()),destinationNode.id) }.getOrNull()
    }
    val placeDistances = remember(previewGraph, previewStart) {
        if (previewGraph == null || previewStart == null) emptyMap() else previewGraph.nodes.filter { it.poiId != null }.mapNotNull { node ->
            runCatching { previewGraph.route(MetricPoint(previewStart.x.toDouble(), previewStart.y.toDouble()), node.id) }.getOrNull()
                ?.let { node.poiId!! to it.distanceMetres }
        }.toMap()
    }
    val routeProblem = when {
        state.hasUnsavedMapChanges -> "Save map changes in Map setup to preview directions."
        selected == null -> null
        destinationNode == null -> "${selected.name} has no connected path yet. Connect its entrance in Map setup."
        previewStart == null && wifiStartChosen -> "The route appears once Wi-Fi finds you."
        previewStart != null && routePreview == null -> "No walkable route from this start. Choose another start or check paths in Map setup."
        else -> null
    }
    val canStart = !busy && !state.sessionLocked && !state.hasUnsavedMapChanges &&
        (if (selected == null) positionOnly else destinationNode != null && (previewStart == null || routePreview != null))
    // Screen directions as drawn on this map, converted to and from the map frame used for headings.
    fun screenToMap(screenDegrees: Double) = (screenDegrees + 90.0 * state.mapQuarterTurns + 360.0) % 360.0
    fun mapToScreen(mapDegrees: Double) = (mapDegrees - 90.0 * state.mapQuarterTurns + 360.0) % 360.0
    fun openStart() { startQuery = ""; sheet = "start" }
    fun openDestinations(withCategory: String? = null) { query = ""; category = withCategory; sheet = "destinations" }
    fun openDirection() {
        state.headingChoiceConfirmed = false
        state.realHeadingStatus = "Stand still and choose the direction you are facing for this session"
        sheet = "direction"
    }
    fun primaryAction() = when {
        state.liveRunning || state.liveCloseFailed -> actions.onRealLiveToggled()
        !startChosen -> openStart()
        selected == null && !positionOnly -> openDestinations()
        else -> openDirection()
    }
    fun selectStart(point: Offset, label: String, placeId: String? = null) {
        wifiStartChosen = false
        state.chooseKnownStart(point)
        startLabel = label; startPlaceId = placeId
        sheet = if (selected == null && !positionOnly) { query = ""; category = null; "destinations" } else null
    }
    fun chooseDestination(id: String) {
        positionOnly = false
        actions.onDestinationSelected(id)
        sheet = if (!startChosen && !state.liveRunning) { startQuery = ""; "start" } else null
    }
    fun swap() {
        val formerStart = startPlaceId ?: return
        val target = destinationNode ?: return
        selectStart(Offset(target.point.x.toFloat(), target.point.y.toFloat()), "${selected?.name} entrance", selected?.id)
        actions.onDestinationSelected(formerStart)
    }
    fun chooseDirection(degrees: Double) {
        if (state.liveRunning) actions.onRealHeadingAligned(degrees)
        else {
            state.initialHeadingDegrees = degrees
            state.headingChoiceConfirmed = true
            state.realHeadingStatus = "Starting direction chosen · check the arrow before walking"
        }
    }
    BackHandler(state.pickingStart || state.pickingHeading) { state.pickingStart = false; state.pickingHeading = false }
    if (details) {
        Dialog(onDismissRequest = { details = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.safeDrawingPadding()) {
                    TextButton(onClick = { details = false }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, null); Text("Back to Explore") }
                    // The complete existing controls, raw PDR, Wi-Fi diagnostics and research views remain reachable.
                    NavigationScreen(state, actions)
                }
            }
        }
    }
    val live = state.liveRunning || state.liveStarting || state.liveCloseFailed
    val instruction = if (state.liveRunning && selected != null && state.routePoints.size >= 2) nextInstruction(state.routePoints, selected.name) else null
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        val density = LocalDensity.current
        val cardWidth = if (landscape) 340.dp else maxWidth.coerceAtMost(480.dp)
        val cardHeight = with(density) { cardHeightPx.toDp() }
        val topInset = if (live) 104.dp else if (state.pickingStart || state.pickingHeading) 76.dp else 124.dp
        val mapInsets = if (landscape) PaddingValues(start = cardWidth + 16.dp, top = topInset, end = 8.dp, bottom = 8.dp)
            else PaddingValues(top = topInset, bottom = cardHeight + 8.dp, start = 8.dp, end = 8.dp)
        NavigationMap(state, Modifier.fillMaxSize(), showPosition = true, showGraph = state.debugOverlays,
            showReferences = state.pickingStart, viewportPadding = mapInsets, compactControls = true,
            previewRoute = if(!state.liveRunning) routePreview?.points?.map { Offset(it.x.toFloat(),it.y.toFloat()) } ?: emptyList() else null,
            focusDestination = !state.pickingStart && !state.pickingHeading,
            controlsPadding = if (landscape) PaddingValues(12.dp) else PaddingValues(end = 12.dp, bottom = cardHeight + 16.dp),
            rotateToFit = true, roomLabels = true,
            onDestinationTapped = if (state.pickingStart || state.pickingHeading) null else actions.onDestinationSelected,
            onTap = { point, _ ->
                when {
                    state.pickingStart && !state.sessionLocked -> selectStart(point, "Point on map")
                    state.pickingHeading -> {
                        val origin = if (state.liveRunning) state.realLivePosition else state.manualStartPoint
                        if (origin != null) runCatching {
                            MapHeading.toward(MetricPoint(origin.x.toDouble(), origin.y.toDouble()), MetricPoint(point.x.toDouble(), point.y.toDouble()))
                        }.onSuccess {
                            chooseDirection((Math.toDegrees(it) + 360.0) % 360.0)
                            state.pickingHeading = false; sheet = if (state.liveRunning) "options" else "direction"
                        }.onFailure { state.realHeadingStatus = it.message ?: "Choose a point farther ahead" }
                    }
                }
            })
        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (live) {
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary, shadowElevation = 6.dp) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        if (instruction != null) Icon(Icons.Outlined.North, null, Modifier.size(30.dp)
                            .rotate((90.0 - mapToScreen(instruction.mapHeadingDegrees)).toFloat()))
                        else Icon(Icons.Outlined.MyLocation, null, Modifier.size(26.dp))
                        Column(Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }) {
                            Text(when {
                                instruction != null -> "Continue %.0f m".format(instruction.distanceMetres)
                                state.liveStarting -> "Starting…"
                                !state.realLiveInitialized -> "Finding your position"
                                selected == null -> "Showing your position"
                                else -> state.routeStatus
                            }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2)
                            Text(instruction?.then ?: confidence, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        }
                        IconButton(onClick = { state.navigationFullscreen = true }) { Icon(Icons.Outlined.Fullscreen, "Full screen map") }
                        IconButton(onClick = { sheet = "options" }) { Icon(Icons.Outlined.MoreHoriz, "Map options") }
                    }
                }
            } else if (state.pickingStart || state.pickingHeading) {
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 4.dp) {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (state.pickingStart) Icons.Outlined.TouchApp else Icons.Outlined.Explore, null, tint = MaterialTheme.colorScheme.primary)
                        Text(if (state.pickingStart) "Tap where you are standing" else "Tap a point ahead of you, at least 1 m away",
                            Modifier.weight(1f).padding(horizontal = 12.dp), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        TextButton(onClick = { state.pickingStart = false; state.pickingHeading = false
                            sheet = if (state.liveRunning) "options" else if (state.pickingHeading) "direction" else "start" }) { Text("Cancel") }
                    }
                }
            } else {
                Surface(onClick = { openDestinations() }, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 6.dp) {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Outlined.Search, null, tint = MaterialTheme.colorScheme.primary)
                        Text("Search rooms & places", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        Surface(onClick = { state.selectDestination(TurnDestination.VENUES) }, shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                            Text(state.floorName, Modifier.padding(horizontal = 10.dp, vertical = 6.dp).widthIn(max = 96.dp),
                                style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { state.navigationFullscreen = true }, Modifier.size(40.dp)) { Icon(Icons.Outlined.Fullscreen, "Full screen map") }
                        IconButton(onClick = { sheet = "options" }, Modifier.size(40.dp)) { Icon(Icons.Outlined.MoreVert, "Map options") }
                    }
                }
                val shownCategories = placeCategories.map { it.first }.filter { c -> state.destinations.any { inCategory(it.name, c) } }
                if (shownCategories.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    shownCategories.forEach { c ->
                        Surface(onClick = { openDestinations(c) }, shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surface, shadowElevation = 3.dp) {
                            Text(c, Modifier.padding(horizontal = 14.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
        if (!state.pickingStart && !state.pickingHeading) Surface(
            modifier = Modifier.align(if (landscape) Alignment.BottomStart else Alignment.BottomCenter).width(cardWidth)
                .then(if (landscape) Modifier.padding(12.dp) else Modifier).onSizeChanged { cardHeightPx = it.height },
            shape = if (landscape) RoundedCornerShape(24.dp) else RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
            Column(Modifier.padding(horizontal = 18.dp).padding(top = 10.dp, bottom = 14.dp).heightIn(max = if (landscape) maxHeight - 24.dp else maxHeight * 0.55f)
                .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!landscape) Box(Modifier.align(Alignment.CenterHorizontally).size(36.dp, 4.dp)
                    .then(Modifier).padding(0.dp)) { Surface(Modifier.fillMaxSize(), shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.outlineVariant) {} }
                if (live) LiveCard(state, selected?.name, confidence, age, busy, onDirection = { sheet = "direction" },
                    onDestination = { openDestinations() }, onStop = actions.onRealLiveToggled)
                else {
                    Text("Plan your route", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    FromToCard(
                        from = when { previewStart != null -> startLabel ?: "Point on map"; wifiStartChosen -> "Detect with Wi-Fi"; else -> null },
                        to = selected?.name ?: if (positionOnly) "Just my position" else null,
                        onFrom = { openStart() }, onTo = { openDestinations() },
                        onSwap = if (startPlaceId != null && destinationNode != null) ({ swap() }) else null,
                        enabled = !busy && !state.sessionLocked,
                    )
                    if (routePreview != null) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        RouteStat("%.0f m".format(routePreview.distanceMetres), "Walking distance", Modifier.weight(1f))
                        RouteStat(walkingTime(routePreview.distanceMetres), "At walking pace", Modifier.weight(1f))
                    }
                    routeProblem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (selected != null && (destinationNode == null || (previewStart != null && routePreview == null)))
                        TextButton(onClick = { state.selectDestination(TurnDestination.FLOOR_EDITOR) }) { Text("Open Map setup") }
                    if (startAttempted || state.realWifiIssue != null) Text(state.realWifiIssue ?: state.realLiveStatus,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { if (hasWalkableMap) primaryAction() else state.selectDestination(TurnDestination.FLOOR_EDITOR) },
                        enabled = !busy && !state.sessionLocked && (!hasWalkableMap || !state.hasUnsavedMapChanges) &&
                            (selected == null || destinationNode != null && (previewStart == null || routePreview != null)),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(16.dp)) {
                        Icon(Icons.Outlined.NearMe, null); Spacer(Modifier.width(8.dp))
                        Text(if (!hasWalkableMap) "Prepare this floor" else "Start navigation", fontWeight = FontWeight.Bold)
                    }
                    if (state.realLivePosition != null) Text("Grey dot: last known position · tracking stopped", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (state.destinations.isEmpty()) Text(if (hasWalkableMap) "Add places and paths in Map setup to get directions."
                        else "Import a floor plan or create one with your phone, then define walkable space.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    if (sheet != null) ModalBottomSheet(onDismissRequest = { sheet = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = 600.dp).verticalScroll(rememberScrollState()).imePadding()
            .padding(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (sheet) {
                "destinations" -> {
                    Text("Where to?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    if (startChosen) Text("Start set. Now choose where you want to go.", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(query, { query = it }, placeholder = { Text("Search rooms & places") }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(16.dp))
                    val shownCategories = placeCategories.map { it.first }.filter { c -> state.destinations.any { inCategory(it.name, c) } }
                    if (shownCategories.size > 1) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        shownCategories.forEach { c -> FilterChip(category == c, { category = if (category == c) null else c }, { Text(c) }) }
                    }
                    val filtered = state.destinations.filter { it.id != startPlaceId && it.name.contains(query.trim(), ignoreCase = true) && inCategory(it.name, category) }
                        .sortedWith(compareBy({ placeDistances[it.id] ?: Double.MAX_VALUE }, { it.name }))
                    if (filtered.isEmpty()) {
                        Text(if (state.destinations.isEmpty()) "There are no places on this floor yet." else "No places match your search.")
                        TextButton(onClick = { sheet = null; state.selectDestination(TurnDestination.FLOOR_EDITOR) }) { Text("Add places in Map setup") }
                    }
                    filtered.forEach { poi ->
                        val connected = state.navigationNodes.any { it.poiId == poi.id }
                        PlaceRow(poi.name, if (!connected) "Entrance not connected" else state.floorName,
                            trailing = placeDistances[poi.id]?.let { "%.0f m".format(it) }, selected = poi.id == state.selectedDestinationId,
                            badge = poi.name.filter { it.isDigit() }.takeLast(3).ifEmpty { null }) { chooseDestination(poi.id) }
                    }
                    TextButton(onClick = {
                        actions.onDestinationSelected(null); positionOnly = true
                        sheet = if (startChosen) null else { startQuery = ""; "start" }
                    }, enabled = !busy && !state.sessionLocked && hasWalkableMap && !state.hasUnsavedMapChanges) {
                        Icon(Icons.Outlined.MyLocation, null); Spacer(Modifier.width(8.dp)); Text("Just show my position")
                    }
                }
                "start" -> {
                    Text("Where are you now?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    OutlinedTextField(startQuery, { startQuery = it }, placeholder = { Text("Search rooms & survey points") }, singleLine = true,
                        leadingIcon = { Icon(Icons.Outlined.Search, null) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
                    val enabled = !busy && !state.sessionLocked
                    if (startQuery.isBlank()) {
                        ActionRow(Icons.Outlined.TouchApp, "Pick on map", "Tap the spot you're standing on", enabled) {
                            state.pickingStart = true; state.pickingHeading = false; sheet = null
                        }
                        ActionRow(Icons.Outlined.Wifi, "Detect with Wi-Fi", "Needs a surveyed floor · waits for a confident fix", enabled) {
                            state.chooseKnownStart(null); wifiStartChosen = true; startLabel = null; startPlaceId = null
                            sheet = if (positionOnly || selected != null) null else { query = ""; category = null; "destinations" }
                        }
                    }
                    val places = state.destinations.filter { it.name.contains(startQuery.trim(), ignoreCase = true) }
                    if (places.isNotEmpty()) Text("Stand at a room's door", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    places.forEach { place ->
                        val node = previewGraph?.nodes?.firstOrNull { it.poiId == place.id }
                        val valid = node != null && previewGraph.floor.isWalkable(node.point)
                        PlaceRow(place.name, if (valid) "Entrance" else "Entrance not connected · pick on map instead", trailing = null,
                            selected = place.id == startPlaceId, badge = place.name.filter { it.isDigit() }.takeLast(3).ifEmpty { null },
                            enabled = valid && enabled) {
                            node?.let { selectStart(Offset(it.point.x.toFloat(), it.point.y.toFloat()), "${place.name} entrance", place.id) }
                        }
                    }
                    val points = state.referencePoints.filter { it.label.contains(startQuery.trim(), ignoreCase = true) }
                    if (points.isNotEmpty()) {
                        if (startQuery.isBlank()) TextButton(onClick = { showReferences = !showReferences }) {
                            Text(if (showReferences) "Hide survey points" else "Show survey points (${points.size})")
                        }
                        if (showReferences || startQuery.isNotBlank()) points.forEach { point ->
                            PlaceRow(point.label, "Survey point · stand exactly on its floor label", trailing = null, selected = false,
                                badge = null, enabled = enabled) { selectStart(point.metres, point.label) }
                        }
                    }
                    if (startQuery.isNotBlank() && places.isEmpty() && points.isEmpty()) Text("No locations match your search.")
                }
                "direction" -> {
                    Text(if (state.liveRunning) "Adjust your direction" else "Which way are you facing?",
                        style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Hold the phone flat and point its top edge the way you'll walk. Then pick the matching direction on the map.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val chosenScreen = if (state.headingChoiceConfirmed || state.liveRunning)
                        mapToScreen(if (state.liveRunning) state.realLiveHeadingDegrees else state.initialHeadingDegrees) else null
                    DirectionDial(chosenScreen, Modifier.align(Alignment.CenterHorizontally))
                    listOf(listOf("↑ Up" to 90.0, "↓ Down" to 270.0), listOf("← Left" to 180.0, "Right →" to 0.0)).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { (name, screenDegrees) ->
                                val on = !state.liveRunning && state.headingChoiceConfirmed && chosenScreen != null && abs(chosenScreen - screenDegrees) < 0.5
                                if (on) Button(onClick = { chooseDirection(screenToMap(screenDegrees)) }, enabled = !busy, modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(14.dp)) { Text(name) }
                                else OutlinedButton(onClick = { chooseDirection(screenToMap(screenDegrees)) }, enabled = !busy, modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(14.dp)) { Text(name) }
                            }
                        }
                    }
                    OutlinedButton(onClick = { state.pickingHeading = true; state.pickingStart = false; sheet = null },
                        enabled = !busy && (if (state.liveRunning) state.realLivePosition != null else state.manualStartPoint != null),
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                        Icon(Icons.Outlined.Explore, null); Spacer(Modifier.width(8.dp)); Text("Tap a point ahead on the map")
                    }
                    Text(state.realHeadingStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    routeProblem?.takeIf { !state.liveRunning }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    if (!state.liveRunning) Button(onClick = { startAttempted = true; sheet = null; actions.onRealLiveToggled() },
                        enabled = canStart && state.headingChoiceConfirmed, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        shape = RoundedCornerShape(16.dp)) { Text("I'm facing this way · Go", fontWeight = FontWeight.Bold) }
                    else Button(onClick = { sheet = null }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) { Text("Done") }
                    TextButton(onClick = { sheet = null; details = true }) { Text("Advanced direction controls") }
                }
                "options" -> {
                    Text("Map & tracking", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (state.darkTheme) Icons.Outlined.DarkMode else Icons.Outlined.LightMode, null)
                        Text("Dark mode", Modifier.weight(1f).padding(start = 12.dp)); Switch(state.darkTheme, { state.darkTheme = it })
                    }
                    TextButton(onClick = { sheet = null; state.navigationFullscreen = true }) { Icon(Icons.Outlined.Fullscreen, null); Text("Full screen map") }
                    if (state.liveRunning) {
                        val wait = (((state.realWifiNextPermittedRequestEpochMillis ?: 0L) - now + 999) / 1000).coerceAtLeast(0)
                        OutlinedButton(onClick = actions.onRealLiveScanRequested, enabled = wait == 0L && !busy, modifier = Modifier.fillMaxWidth()) {
                            Text(if (wait > 0) "Fresh Wi-Fi available in ${wait}s" else "Request fresh Wi-Fi fix")
                        }
                        Text(state.realWifiEvidenceStatus, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = actions.onRealLiveRelocalizationRequested, enabled = !busy) { Text("Relocalize with next Wi-Fi scan") }
                        TextButton(onClick = { sheet = "direction" }, enabled = !busy) { Text("Adjust direction") }
                        Text(state.realLiveRecordingStatus, style = MaterialTheme.typography.bodySmall)
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Debug overlays", Modifier.weight(1f)); Switch(state.debugOverlays, { state.debugOverlays = it })
                    }
                    if (state.debugOverlays) {
                        OverlayChoice("Raw PDR trace", state.showRawPdr) { state.showRawPdr = it }
                        OverlayChoice("Wi-Fi fixes", state.showWifiFixes) { state.showWifiFixes = it }
                        OverlayChoice("Particles", state.showParticles) { state.showParticles = it }
                    }
                    TextButton(onClick = { sheet = null; details = true }) { Text("Research details & all controls") }
                    TextButton(onClick = { sheet = null; state.openTools() }) { Text("Map setup, survey & tools") }
                }
            }
        }
    }
}

@Composable
private fun LiveCard(state: TurnAppState, destination: String?, confidence: String, age: Long?, busy: Boolean,
    onDirection: () -> Unit, onDestination: () -> Unit, onStop: () -> Unit) {
    val (pill, pillColor) = when {
        state.liveStarting -> "Starting" to TurnAmber
        !state.realLiveInitialized -> "Locating" to TurnAmber
        age != null && age > 20 -> "Signal lost" to TurnRed
        confidence.contains("paused") -> "Heading paused" to TurnAmber
        state.realLiveConfidence >= 0.65 -> "Good signal" to TurnMint
        else -> "Low accuracy" to TurnAmber
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(destination ?: "Live position", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Surface(shape = RoundedCornerShape(50), color = pillColor.copy(alpha = 0.16f)) {
            Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Surface(Modifier.size(7.dp), shape = CircleShape, color = pillColor) {}
                Text(pill, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (destination != null) RouteStat(state.remainingDistanceMetres?.let { "%.0f m".format(it) } ?: "–",
            if (age != null && age > 20) "Last known" else "Left", Modifier.weight(1f))
        RouteStat(state.realLiveUncertaintyMetres?.let { "±%.1f m".format(it) } ?: "–", "Accuracy", Modifier.weight(1f))
        RouteStat("${state.realLiveStepCount}", "Steps", Modifier.weight(1f))
    }
    TextButton(onClick = onDestination, contentPadding = PaddingValues(0.dp)) {
        Icon(Icons.Outlined.Place, null); Spacer(Modifier.width(6.dp)); Text(if (destination == null) "Choose destination" else "Change destination")
    }
    if (state.destinationReached) Text("You have arrived", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold)
    if (!state.realLiveInitialized || state.realWifiIssue != null || state.liveCloseFailed)
        Text(state.realWifiIssue ?: state.realLiveStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(onClick = onDirection, enabled = !busy && state.liveRunning, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) {
            Text("Adjust direction")
        }
        FilledTonalButton(onClick = onStop, enabled = !busy || state.liveCloseFailed, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) {
            Text(if (state.liveCloseFailed) "Retry saving" else if (state.liveStarting) "Starting…" else "End navigation")
        }
    }
}

@Composable
private fun FromToCard(from: String?, to: String?, onFrom: () -> Unit, onTo: () -> Unit, onSwap: (() -> Unit)?, enabled: Boolean) {
    Surface(shape = RoundedCornerShape(16.dp), border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Box {
            Column {
                FromToRow(true, "From", from, "Where are you?", enabled, onFrom)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                FromToRow(false, "To", to, "Choose a destination", enabled, onTo)
            }
            if (onSwap != null) FilledTonalIconButton(onClick = onSwap, enabled = enabled,
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 10.dp).size(36.dp)) {
                Icon(Icons.Outlined.SwapVert, "Swap start and destination")
            }
        }
    }
}

@Composable
private fun FromToRow(start: Boolean, label: String, value: String?, placeholder: String, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        if (start) Surface(Modifier.size(14.dp), shape = CircleShape, color = MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(3.5.dp, MaterialTheme.colorScheme.primary)) {}
        else Icon(Icons.Outlined.Place, null, tint = TurnRed, modifier = Modifier.size(18.dp).offset(x = (-2).dp))
        Column(Modifier.weight(1f).padding(end = 40.dp)) {
            Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold)
            Text(value ?: placeholder, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = if (value != null) FontWeight.SemiBold else FontWeight.Normal,
                color = if (value != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RouteStat(value: String, label: String, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun DirectionDial(screenDegrees: Double?, modifier: Modifier = Modifier) {
    val ring = MaterialTheme.colorScheme.outlineVariant
    val fill = MaterialTheme.colorScheme.primaryContainer
    val arrow = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Box(modifier.size(150.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2
            drawCircle(fill.copy(alpha = 0.5f), r)
            drawCircle(ring, r, style = Stroke(1.dp.toPx()))
            drawCircle(Color.White, r * 0.62f)
            if (screenDegrees != null) {
                val a = Math.toRadians(screenDegrees)
                val tip = center + Offset(cos(a).toFloat(), -sin(a).toFloat()) * (r * 0.5f)
                drawLine(arrow, center, tip, 7.dp.toPx(), StrokeCap.Round)
                drawCircle(arrow, 9.dp.toPx(), tip)
            }
            drawCircle(arrow, 6.dp.toPx(), center)
        }
        Text("Up", Modifier.align(Alignment.TopCenter).padding(top = 4.dp), style = MaterialTheme.typography.labelSmall, color = muted)
        Text("Down", Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp), style = MaterialTheme.typography.labelSmall, color = muted)
        Text("Left", Modifier.align(Alignment.CenterStart).padding(start = 6.dp), style = MaterialTheme.typography.labelSmall, color = muted)
        Text("Right", Modifier.align(Alignment.CenterEnd).padding(end = 6.dp), style = MaterialTheme.typography.labelSmall, color = muted)
    }
}

@Composable
private fun ActionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Icon(icon, null, Modifier.padding(9.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun OverlayChoice(name: String, selected: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(name, Modifier.weight(1f)); Switch(selected, change)
    }
}

@Composable
private fun PlaceRow(name: String, subtitle: String, trailing: String?, selected: Boolean, badge: String?,
    enabled: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Surface(Modifier.size(40.dp), shape = RoundedCornerShape(12.dp),
            color = if (enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh) {
            Box(contentAlignment = Alignment.Center) {
                if (badge != null) Text(badge, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.primary)
                else Icon(Icons.Outlined.Place, null, tint = MaterialTheme.colorScheme.primary)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (selected) Icon(Icons.Outlined.Check, "Selected", tint = MaterialTheme.colorScheme.primary)
        else if (trailing != null) Text(trailing, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
