package com.turn.fieldtest.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import kotlinx.coroutines.delay

/** Canvas is a presentation of the existing real-device session, never a second positioning engine. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CanvasNavigationScreen(state: TurnAppState, actions: TurnAppActions) {
    var sheet by rememberSaveable(state.activeProject) { mutableStateOf<String?>(null) }
    var query by rememberSaveable(state.activeProject) { mutableStateOf("") }
    var startQuery by rememberSaveable(state.activeProject) { mutableStateOf("") }
    var showReferences by rememberSaveable(state.activeProject) { mutableStateOf(false) }
    var wifiStartChosen by rememberSaveable(state.activeProject) { mutableStateOf(false) }
    var positionOnly by rememberSaveable(state.activeProject) { mutableStateOf(false) }
    var details by rememberSaveable { mutableStateOf(false) }
    var startAttempted by rememberSaveable(state.activeProject) { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.liveRunning) { while (state.liveRunning) { now = System.currentTimeMillis(); delay(1000) } }
    val age = state.liveEstimateEpochMillis?.let { (now - it).coerceAtLeast(0) / 1000 }
    val confidence = navigationConfidence(state, age, now)
    val selected = state.destinations.firstOrNull { it.id == state.selectedDestinationId }
    LaunchedEffect(selected?.id) {if(selected!=null) positionOnly=false}
    val hasWalkableMap = state.allWalkablePolygons().any { it.size >= 3 }
    val busy = state.projectBusy || state.liveStarting || state.surveyRunning || state.surveyStarting || state.realHeadingAlignmentBusy
    val previewGraph = remember(state.activeProject, state.navigationPreviewGraph, state.hasUnsavedMapChanges, state.navigationNodes.toList(),
        state.navigationEdges.toList(), state.draftWalkablePolygon.toList(), state.draftWalls.toList(), state.otherWalkableRegions.toList()) {
        if(state.hasUnsavedMapChanges) null else state.navigationPreviewGraph ?: runCatching { state.draftNavigationGraph() }.getOrNull()
    }
    val previewStart = state.manualStartPoint
    val destinationNode = previewGraph?.nodes?.firstOrNull { it.poiId == selected?.id && selected != null }
    val routePreview = remember(previewGraph, previewStart, selected?.id, state.liveRunning) {
        if(state.liveRunning || previewStart==null || destinationNode==null) null else
            runCatching { previewGraph?.route(MetricPoint(previewStart.x.toDouble(),previewStart.y.toDouble()),destinationNode.id) }.getOrNull()
    }
    val routeMessage = when {
        state.hasUnsavedMapChanges -> "Save map changes in Map setup to preview directions."
        selected == null -> "Select a room to see directions."
        destinationNode == null -> "This destination has no connected path. Connect its entrance in Map setup."
        previewStart == null && wifiStartChosen -> "The route will appear after a confident fresh Wi-Fi fix."
        previewStart == null -> "Choose where you are standing to preview the route."
        routePreview == null -> "No walkable route from this start. Choose another start or check paths in Map setup."
        else -> "Route ready · ends at the room entrance"
    }
    fun prepareStart(stage: String) {
        if(stage=="start") startQuery=""
        state.headingChoiceConfirmed = false
        state.realHeadingStatus = "Stand still and choose the direction you are facing for this session"
        sheet = stage
    }
    fun begin() {
        if (state.liveRunning || state.liveCloseFailed) actions.onRealLiveToggled()
        else if(selected==null) {positionOnly=false;query="";sheet="destinations"}
        else prepareStart(if (state.manualStartPoint == null && !wifiStartChosen) "start" else "direction")
    }
    fun selectStart(point: androidx.compose.ui.geometry.Offset) {
        wifiStartChosen=false
        state.chooseKnownStart(point)
        query=""
        sheet=if(positionOnly) "direction" else if(selected==null) "destinations" else null
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
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        val cardWidth = if (landscape) { 310.dp } else { maxWidth.coerceAtMost(460.dp) - 24.dp }
        val cardHeight = if (landscape) {
            (maxHeight - if(state.liveRunning) 124.dp else 146.dp).coerceAtLeast(100.dp)
        } else {
            if(state.liveRunning) 190.dp else (maxHeight * 0.40f).coerceAtMost(280.dp)
        }
        val mapInsets = if (landscape) {
            PaddingValues(start = cardWidth + 24.dp, top = if(state.liveRunning) 112.dp else 130.dp, end = 8.dp, bottom = 8.dp)
        } else {
            PaddingValues(top = if(state.liveRunning) 112.dp else 130.dp, bottom = cardHeight + 12.dp, start = 8.dp, end = 8.dp)
        }
        NavigationMap(state, Modifier.fillMaxSize(), showPosition = true, showGraph = state.debugOverlays,
            showReferences = state.pickingStart, viewportPadding = mapInsets, compactControls = true,
            previewRoute = if(!state.liveRunning) routePreview?.points?.map { androidx.compose.ui.geometry.Offset(it.x.toFloat(),it.y.toFloat()) } ?: emptyList() else null,
            focusDestination = !state.pickingStart && !state.pickingHeading,
            controlsPadding = if (landscape) PaddingValues(12.dp) else PaddingValues(end = 12.dp, bottom = cardHeight + 28.dp),
            onDestinationTapped = if (state.pickingStart || state.pickingHeading) null else actions.onDestinationSelected,
            onTap = { point, _ ->
                when {
                    state.pickingStart && !state.sessionLocked -> selectStart(point)
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
        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
                    Text("turn.", Modifier.padding(horizontal = 14.dp, vertical = 5.dp), style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Black)
                }
                Surface(onClick = { state.selectDestination(TurnDestination.VENUES) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surface) {
                    Text(state.floorName, Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if(state.liveRunning) IconButton(onClick={state.navigationFullscreen=true}) { Icon(Icons.Outlined.Fullscreen,"Full screen map") }
                IconButton(onClick = { sheet = "options" }) { Icon(Icons.Outlined.MoreVert, "Map options") }
            }
            if (state.liveRunning) Surface(shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.primaryContainer) {
                Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically) {
                    Icon(Icons.Outlined.NearMe,null,tint=MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Text(if(selected==null) "Live position · choose a room for directions" else if(state.routePoints.isEmpty()) state.routeStatus else "Follow the blue route to ${selected.name}",
                        style=MaterialTheme.typography.bodyMedium,maxLines=2)
                }
            }
            if (!state.liveRunning) Surface(onClick = { query="";sheet = "destinations" },
                shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 2.dp) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.Search, null, tint = MaterialTheme.colorScheme.primary)
                    Text("Find a room or facility", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = { state.navigationFullscreen = true }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Outlined.Fullscreen, "Full screen map")
                    }
                }
            }
        }
        Surface(modifier = Modifier.align(if (landscape) Alignment.BottomStart else Alignment.BottomCenter)
            .padding(12.dp).width(cardWidth).heightIn(max = cardHeight), shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface, shadowElevation = 3.dp) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
              Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                  verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.pickingStart || state.pickingHeading) {
                    Text(if (state.pickingStart) "Tap where you are standing" else "Tap a point ahead of you",
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(if (state.pickingStart) "Choose your known location on this floor." else
                        "Face your walking direction and tap at least 1 m ahead on the map.", style = MaterialTheme.typography.bodySmall)
                    if (state.pickingHeading) Text(state.realHeadingStatus, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { state.pickingStart = false; state.pickingHeading = false; sheet = if (state.liveRunning) "options" else "start" }) { Text("Cancel map selection") }
                } else if (state.liveRunning || state.liveStarting || state.liveCloseFailed) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Outlined.MyLocation, null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f)) {
                            Text(selected?.name ?: "Live position", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(confidence, style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        }
                        state.realLiveUncertaintyMetres?.let { Text("± %.1f m".format(it), style = MaterialTheme.typography.labelMedium) }
                    }
                    if (selected != null) {
                        Text(if (age != null && age > 20) "Route based on last known position" else state.routeStatus,
                            style = MaterialTheme.typography.bodySmall)
                        state.remainingDistanceMetres?.let { Text("%.1f m remaining".format(it), style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) }
                    } else TextButton(onClick = { query="";sheet = "destinations" }) { Text("Choose destination") }
                    if (!state.realLiveInitialized || state.realWifiIssue != null || state.liveCloseFailed)
                        Text(state.realWifiIssue ?: state.realLiveStatus, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                } else {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(selected?.name ?: "Where to?", style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = { prepareStart("start") }, enabled = !busy && !state.sessionLocked) {
                            Text(if (state.manualStartPoint == null && !wifiStartChosen) "Set start" else "Change start")
                        }
                    }
                    if (selected == null) {
                        Text("Set your start, then choose a destination. You can also tap a room on the map.",style=MaterialTheme.typography.bodySmall)
                        if (state.destinations.isEmpty()) Text(if (hasWalkableMap) "Add places and paths in Map setup to get directions."
                            else "Import a floor plan or create one with your phone, then define walkable space.", style = MaterialTheme.typography.bodySmall)
                        if(state.destinations.isNotEmpty()) TextButton(onClick={query="";sheet="destinations"},contentPadding=PaddingValues(0.dp)) {
                            Text("Browse all ${state.destinations.size} places on ${state.floorName}")
                        }
                    } else {
                        Row {
                            Text(routeMessage, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            IconButton(onClick = { actions.onDestinationSelected(null) }) { Icon(Icons.Outlined.Close, "Clear destination") }
                        }
                        routePreview?.let { Text("%.1f m · route preview".format(it.distanceMetres),style=MaterialTheme.typography.titleLarge,color=MaterialTheme.colorScheme.primary) }
                        TextButton(onClick={query="";sheet="destinations"}) {Text("Change destination")}
                        if(destinationNode==null || (previewStart!=null && routePreview==null)) TextButton(onClick={state.selectDestination(TurnDestination.FLOOR_EDITOR)}) { Text("Open Map setup") }
                    }
                    if(state.manualStartPoint!=null) Text("Start location selected · ${state.floorName}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.primary)
                    else if(wifiStartChosen) Text("Start: fresh Wi-Fi fix",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.primary)
                    if (state.hasUnsavedMapChanges) Text("Save map changes in Tools before tracking.", color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                    if (startAttempted || state.realWifiIssue != null) Text(state.realWifiIssue ?: state.realLiveStatus,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (state.realLivePosition != null) "Last known position · tracking stopped" else "Real device · ${state.venueName}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
              }
              if (!state.pickingStart && !state.pickingHeading) {
                if (state.liveRunning || state.liveStarting || state.liveCloseFailed) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { sheet = "direction" }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Adjust direction") }
                        Button(onClick = actions.onRealLiveToggled, enabled = !busy, modifier = Modifier.weight(1f)) {
                            Text(if (state.liveCloseFailed) "Retry saving" else if (state.liveStarting) "Starting…" else "Stop tracking")
                        }
                    }
                } else Button(onClick = { if (hasWalkableMap) begin() else state.selectDestination(TurnDestination.FLOOR_EDITOR) },
                    enabled = !busy && !state.sessionLocked && (!hasWalkableMap || !state.hasUnsavedMapChanges) &&
                        (selected==null || destinationNode!=null && (previewStart==null || routePreview!=null)),
                    modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp)) {
                    Icon(Icons.Outlined.NearMe, null); Spacer(Modifier.width(8.dp))
                    Text(if (!hasWalkableMap) "Prepare this floor" else if (selected == null) "Choose destination" else "Navigate here")
                }
              }
            }
        }
    }
    if (sheet != null) ModalBottomSheet(onDismissRequest = { sheet = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()).imePadding()
            .padding(start = 22.dp, end = 22.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            when (sheet) {
                "destinations" -> {
                    Text("Choose your destination", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    if(state.manualStartPoint!=null || wifiStartChosen) Text("Start selected. Now choose where you want to go.",style=MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(query, { query = it }, label = { Text("Room or facility") }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true)
                    val filtered = state.destinations.filter { it.name.contains(query.trim(), ignoreCase = true) }
                    if (filtered.isEmpty()) {
                        Text(if (state.destinations.isEmpty()) "There are no destinations on this floor yet." else "No places match your search.")
                        TextButton(onClick = { sheet = null; state.selectDestination(TurnDestination.FLOOR_EDITOR) }) { Text("Add destinations in Map setup") }
                    }
                    filtered.forEach { poi -> PlaceRow(poi.name,
                        if(state.navigationNodes.any {it.poiId==poi.id}) state.floorName else "${state.floorName} · entrance not connected",
                        poi.id == state.selectedDestinationId) {
                        positionOnly=false;actions.onDestinationSelected(poi.id); sheet = null
                    } }
                    TextButton(onClick={actions.onDestinationSelected(null);positionOnly=true;prepareStart(if(state.manualStartPoint==null && !wifiStartChosen) "start" else "direction")},enabled=!busy && !state.sessionLocked && hasWalkableMap && !state.hasUnsavedMapChanges) {
                        Text("Track position without a destination")
                    }
                }
                "start" -> {
                    Text("1 · Where are you standing?", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Choose a reference point or a room only when you are at its mapped entrance. Otherwise, pick a known walkable location on the map.", style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(startQuery,{startQuery=it},label={Text("Search current location")},singleLine=true,
                        leadingIcon={Icon(Icons.Outlined.Search,null)},modifier=Modifier.fillMaxWidth())
                    Button(onClick = { state.pickingStart = true; state.pickingHeading = false; sheet = null },
                        enabled = !busy && !state.sessionLocked, modifier = Modifier.fillMaxWidth()) { Text("Pick my location on the map") }
                    val starts=state.referencePoints.filter {it.label.contains(startQuery.trim(),ignoreCase=true)}
                    val places=state.destinations.filter {it.name.contains(startQuery.trim(),ignoreCase=true)}
                    TextButton(onClick={showReferences=!showReferences}) {Text(if(showReferences) "Hide saved reference points" else "Show saved reference points (optional)")}
                    if(showReferences) Text("Known points for starting a session or Wi-Fi surveying. Choose one only if you can identify its exact physical location.",style=MaterialTheme.typography.bodySmall)
                    starts.filter {showReferences || startQuery.isNotBlank()}.forEach { point -> OutlinedButton(onClick = {
                        selectStart(point.metres)
                    }, enabled = !busy && !state.sessionLocked, modifier = Modifier.fillMaxWidth()) { Text("I am at ${point.label}") } }
                    if(places.isNotEmpty()) Text("Rooms & places · ${state.floorName}",style=MaterialTheme.typography.titleSmall)
                    places.forEach {place ->
                        val node=previewGraph?.nodes?.firstOrNull {it.poiId==place.id}
                        val valid=node!=null && previewGraph?.floor?.isWalkable(node.point)==true
                        OutlinedButton(onClick={node?.let {
                            selectStart(androidx.compose.ui.geometry.Offset(it.point.x.toFloat(),it.point.y.toFloat()))
                        }},enabled=valid && !busy && !state.sessionLocked,modifier=Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth()) {
                                Text("I am at ${place.name} entrance")
                                if(!valid) Text("Entrance not connected · pick your location on the map",style=MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    if(starts.isEmpty() && places.isEmpty()) Text("No locations match your search.")
                    TextButton(onClick = { state.chooseKnownStart(null); wifiStartChosen=true; query="";sheet=if(positionOnly || selected!=null) "direction" else "destinations" }, enabled = !busy && !state.sessionLocked) {
                        Text("Use a confident fresh Wi-Fi fix")
                    }
                    Text("Automatic initialization needs surveyed points and a reliable fresh match. An uncertain match will wait.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                "direction" -> {
                    Text(if (state.liveRunning) "Adjust your direction" else "Which way are you facing?",
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Stand still. Hold the phone level with the top of the visible screen pointing where you will walk. Directions refer to this drawing.")
                    listOf(listOf("← Left" to 180.0, "Right →" to 0.0), listOf("↑ Up" to 90.0, "↓ Down" to 270.0)).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { (name, degrees) -> OutlinedButton(onClick = { chooseDirection(degrees) },
                                enabled = !busy, modifier = Modifier.weight(1f)) { Text(name) } }
                        }
                    }
                    OutlinedButton(onClick = { state.pickingHeading = true; state.pickingStart = false; sheet = null },
                        enabled = !busy && (if (state.liveRunning) state.realLivePosition != null else state.manualStartPoint != null),
                        modifier = Modifier.fillMaxWidth()) { Text("Point ahead on the map") }
                    Text(state.realHeadingStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    if(selected!=null && !state.liveRunning) Text(routeMessage,style=MaterialTheme.typography.bodySmall)
                    if (!state.liveRunning) Button(onClick = { startAttempted = true; sheet = null; actions.onRealLiveToggled() },
                        enabled = !busy && state.headingChoiceConfirmed && !state.hasUnsavedMapChanges && !state.sessionLocked &&
                            (if(selected==null) positionOnly else destinationNode!=null && (previewStart==null || routePreview!=null)),
                        modifier = Modifier.fillMaxWidth()) { Text("Start tracking") }
                    else Button(onClick = { sheet = null }, modifier = Modifier.fillMaxWidth()) { Text("Return to map") }
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
private fun OverlayChoice(name: String, selected: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(name, Modifier.weight(1f)); Switch(selected, change)
    }
}

@Composable
private fun PlaceRow(name: String, floor: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Icon(Icons.Outlined.Place, null, Modifier.padding(9.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(floor, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(if (selected) Icons.Outlined.Check else Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.primary)
    }
}
