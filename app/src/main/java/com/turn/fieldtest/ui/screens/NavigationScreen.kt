package com.turn.fieldtest.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.ui.text.style.TextOverflow
import com.turn.fieldtest.ui.*
import kotlinx.coroutines.delay

@Composable
fun NavigationScreen(state: TurnAppState, actions: TurnAppActions) {
    var destinationsOpen by remember { mutableStateOf(false) }
    var startsOpen by remember { mutableStateOf(false) }
    var heading by remember { mutableStateOf(state.initialHeadingDegrees.toInt().toString()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var research by remember { mutableStateOf(false) }
    LaunchedEffect(state.liveRunning) { while (state.liveRunning) { now = System.currentTimeMillis(); delay(1000) } }
    val age = state.liveEstimateEpochMillis?.let { ((now - it).coerceAtLeast(0) / 1000) }
    val confidence = navigationConfidence(state, age)
    if (research) {
        Column {
            TextButton(onClick = { research = false }) { Text("Back to navigation") }
            RealLiveLocateScreen(state, true, actions.onRealLiveToggled, actions.onRealLiveScanRequested, actions.onRealLiveRelocalizationRequested)
        }; return
    }
    @Composable fun MapPanel(modifier: Modifier) {
      Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(state.floorName, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(confidence, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            state.realLiveUncertaintyMetres?.let { Text("± %.1f m".format(it), style = MaterialTheme.typography.titleMedium) }
            IconButton(onClick = { state.navigationFullscreen = true }) {
                Icon(Icons.Outlined.Fullscreen, contentDescription = "Full screen map")
            }
        }
        NavigationMap(state, Modifier.fillMaxWidth().weight(1f), showPosition = true,
            showGraph = state.debugOverlays, showReferences = state.pickingStart, onTap = { point, _ ->
                if (state.pickingStart && !state.sessionLocked) {
                    state.manualStartPoint = point; state.pickingStart = false
                    state.realLiveStatus = "Known start selected. Set the direction you are facing, then start."
                }
            })
      }
    }
    @Composable fun Controls(modifier: Modifier) {
        Column(modifier.verticalScroll(rememberScrollState()).padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box {
                OutlinedButton(onClick = { destinationsOpen = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(state.destinations.firstOrNull { it.id == state.selectedDestinationId }?.name ?: "Choose destination")
                }
                DropdownMenu(destinationsOpen, { destinationsOpen = false }) {
                    if (state.destinations.isEmpty()) DropdownMenuItem(text = { Text("Add destinations in Map setup") }, onClick = { destinationsOpen = false })
                    state.destinations.forEach { poi -> DropdownMenuItem(text = { Text(poi.name) }, onClick = {
                        actions.onDestinationSelected(poi.id); destinationsOpen = false
                    }) }
                    DropdownMenuItem(text = { Text("Clear destination") }, onClick = { actions.onDestinationSelected(null); destinationsOpen = false })
                }
            }
            if (state.selectedDestinationId != null) {
                Text(if (state.liveRunning && age != null && age > 20) "Route based on last known position" else state.routeStatus, style = MaterialTheme.typography.titleMedium)
                state.remainingDistanceMetres?.let { Text("%.1f m remaining".format(it), color = MaterialTheme.colorScheme.primary) }
            }
            if (!state.sessionLocked) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { state.pickingStart = !state.pickingStart }, modifier = Modifier.weight(1f)) { Text(if (state.pickingStart) "Tap your known location" else "Pick known start") }
                    Box(Modifier.weight(1f)) {
                        OutlinedButton(onClick = { startsOpen = true }, modifier = Modifier.fillMaxWidth()) { Text("At a reference point") }
                        DropdownMenu(startsOpen, { startsOpen = false }) {
                            state.referencePoints.forEach { point -> DropdownMenuItem(text = { Text("I am at ${point.label}") }, onClick = {
                                state.manualStartPoint = point.metres; state.pickingStart = false; startsOpen = false
                            }) }
                        }
                    }
                }
                Text(state.manualStartPoint?.let { "Known start: %.1f, %.1f m".format(it.x, it.y) } ?: "Start position: wait for a confident fresh Wi-Fi fix", style = MaterialTheme.typography.bodySmall)
                if (state.manualStartPoint != null) TextButton(onClick = { state.manualStartPoint = null }) { Text("Use automatic Wi-Fi initialization") }
                Text("Which way are you walking on the map?", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Right" to 0.0, "Up" to 90.0, "Left" to 180.0, "Down" to 270.0).forEach { (label, degrees) ->
                        FilterChip(selected = heading.toDoubleOrNull() == degrees,
                            onClick = { heading = degrees.toInt().toString(); state.initialHeadingDegrees = degrees },
                            label = { Text(label) })
                    }
                }
                Text("Use the direction on this drawing, not compass north. Face the top of the visible phone screen that way. Use degrees below for diagonal directions.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(heading, { heading = it; it.toDoubleOrNull()?.takeIf(Double::isFinite)?.let { value -> state.initialHeadingDegrees = value } },
                    label = { Text("Facing direction on map (degrees)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("0° = right · 90° = up · 180° = left · 270° = down. Choose portrait or landscape before starting. Keep the top of the visible screen facing your walking direction.", style = MaterialTheme.typography.bodySmall)
            }
            Text(state.realWifiEvidenceStatus, style = MaterialTheme.typography.bodySmall)
            state.realWifiFixEpochMillis?.let { Text("Latest usable Wi-Fi match: ${(now - it).coerceAtLeast(0) / 1000}s ago", style = MaterialTheme.typography.bodySmall) }
            Text(state.realLiveStatus, style = MaterialTheme.typography.bodySmall)
            state.realWifiIssue?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Button(onClick = actions.onRealLiveToggled, modifier = Modifier.fillMaxWidth(),
                enabled = !state.liveStarting && !state.projectBusy && !state.surveyRunning && heading.toDoubleOrNull()?.isFinite() == true) {
                Text(if (state.liveStarting) "Starting…" else if (state.liveCloseFailed) "Retry saving" else if (state.liveRunning) "Stop navigation" else "Start navigation")
            }
            if (state.liveRunning) state.realWifiNextPermittedRequestEpochMillis?.takeIf { it > now }?.let {
                Text("Next Wi-Fi request in ${(it - now + 999) / 1000}s", style = MaterialTheme.typography.bodySmall)
            }
            if (state.liveRunning) OutlinedButton(onClick = actions.onRealLiveScanRequested) { Text("Request fresh Wi-Fi fix") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(state.debugOverlays, { state.debugOverlays = !state.debugOverlays }, label = { Text("Debug overlays") })
                TextButton(onClick = { research = true }) { Text("Research details") }
            }
            if (state.debugOverlays) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(state.showRawPdr, { state.showRawPdr = !state.showRawPdr }, label = { Text("PDR") })
                FilterChip(state.showWifiFixes, { state.showWifiFixes = !state.showWifiFixes }, label = { Text("Wi-Fi") })
                FilterChip(state.showParticles, { state.showParticles = !state.showParticles }, label = { Text("Particles") })
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        if (maxWidth > maxHeight) {
            val controlsWidth = (maxWidth * 0.38f).coerceAtMost(320.dp)
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                MapPanel(Modifier.weight(1f).fillMaxHeight())
                Controls(Modifier.width(controlsWidth).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MapPanel(Modifier.fillMaxWidth().weight(1f))
                Controls(Modifier.fillMaxWidth().heightIn(max = 290.dp))
            }
        }
    }
}
