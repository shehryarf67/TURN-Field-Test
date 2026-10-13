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
    var editLiveHeading by remember { mutableStateOf(false) }
    var heading by remember(state.activeProject) { mutableStateOf(state.initialHeadingDegrees.toInt().toString()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var research by remember { mutableStateOf(false) }
    LaunchedEffect(state.liveRunning) { while (state.liveRunning) { now = System.currentTimeMillis(); delay(1000) } }
    val age = state.liveEstimateEpochMillis?.let { ((now - it).coerceAtLeast(0) / 1000) }
    val confidence = navigationConfidence(state, age, now)
    val canChooseHeading = !state.liveStarting && !state.liveCloseFailed && !state.realHeadingAlignmentBusy
    fun proposeHeading(degrees: Double) {
        heading = "%.1f".format(java.util.Locale.US, degrees)
        if (!state.liveRunning) {
            state.initialHeadingDegrees = degrees
            state.headingChoiceConfirmed = true
            state.realHeadingStatus = "Starting direction chosen · check the arrow before walking"
        }
    }
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
                if (state.pickingHeading) {
                    val origin = if (state.liveRunning) state.realLivePosition else state.manualStartPoint
                    if (origin != null) {
                        runCatching { com.turn.fieldtest.core.MapHeading.toward(
                            com.turn.fieldtest.core.MetricPoint(origin.x.toDouble(), origin.y.toDouble()),
                            com.turn.fieldtest.core.MetricPoint(point.x.toDouble(), point.y.toDouble())) }
                            .onSuccess { proposeHeading((Math.toDegrees(it) + 360.0) % 360.0); state.pickingHeading = false }
                            .onFailure { state.realHeadingStatus = it.message ?: "Choose a point farther ahead" }
                    }
                } else if (state.pickingStart && !state.sessionLocked) {
                    state.chooseKnownStart(point)
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
                    OutlinedButton(onClick = { state.pickingHeading = false; state.pickingStart = !state.pickingStart }, modifier = Modifier.weight(1f)) { Text(if (state.pickingStart) "Tap your known location" else "Pick known start") }
                    Box(Modifier.weight(1f)) {
                        OutlinedButton(onClick = { startsOpen = true }, modifier = Modifier.fillMaxWidth()) { Text("At a reference point") }
                        DropdownMenu(startsOpen, { startsOpen = false }) {
                            state.referencePoints.forEach { point -> DropdownMenuItem(text = { Text("I am at ${point.label}") }, onClick = {
                                state.chooseKnownStart(point.metres); startsOpen = false
                            }) }
                        }
                    }
                }
                Text(state.manualStartPoint?.let { "Known start: %.1f, %.1f m".format(it.x, it.y) } ?: "Start position: wait for a confident fresh Wi-Fi fix", style = MaterialTheme.typography.bodySmall)
                if (state.manualStartPoint != null) TextButton(onClick = { state.chooseKnownStart(null) }) { Text("Use automatic Wi-Fi initialization") }
            }
            if (state.liveRunning) {
                Text("Walking heading: %.0f° on map".format((state.realLiveHeadingDegrees + 360.0) % 360.0), style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = { editLiveHeading = !editLiveHeading; heading = ((state.realLiveHeadingDegrees + 360.0) % 360.0).toInt().toString(); state.pickingHeading = false }) {
                    Text(if (editLiveHeading) "Close direction controls" else "Correct walking direction")
                }
            }
            if (!state.liveRunning || editLiveHeading) {
                Text("Which way are you walking on the map?", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Right" to 0.0, "Up" to 90.0, "Left" to 180.0, "Down" to 270.0).forEach { (label, degrees) ->
                        FilterChip(selected = heading.toDoubleOrNull() == degrees && (state.liveRunning || state.headingChoiceConfirmed),
                            enabled = canChooseHeading,
                            onClick = { proposeHeading(degrees) }, label = { Text(label) })
                    }
                }
                Text("Directions refer to this drawing, not north. Hold the phone roughly level, with the top of the visible screen toward your walking direction. Stand still when aligning.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { state.pickingStart = false; state.pickingHeading = !state.pickingHeading },
                    enabled = canChooseHeading && (if (state.liveRunning) state.realLivePosition != null else state.manualStartPoint != null)) {
                    Text(if (state.pickingHeading) "Tap a point ahead of you" else "Point ahead on map")
                }
                if (state.pickingHeading) Text("Tap at least 1 m ahead from the ${if (state.liveRunning) "fused dot" else "known start"}. This calculates the angle; it does not change position.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(heading, { value ->
                    heading = value
                    if (!state.liveRunning) {
                        val parsed = value.toDoubleOrNull()?.takeIf(Double::isFinite)
                        state.headingChoiceConfirmed = parsed != null
                        parsed?.let { state.initialHeadingDegrees = it }
                    }
                },
                    label = { Text("Facing direction on map (degrees)") }, enabled = canChooseHeading, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (state.liveRunning) {
                    Button(onClick = { heading.toDoubleOrNull()?.let(actions.onRealHeadingAligned) }, enabled = !state.realHeadingAlignmentBusy && heading.toDoubleOrNull()?.isFinite() == true) {
                        Text("Align to this direction")
                    }
                    TextButton(onClick = { actions.onRealHeadingAligned((state.realLiveHeadingDegrees + 540.0) % 360.0) }, enabled = !state.realHeadingAlignmentBusy) { Text("Reverse heading (180°)") }
                } else {
                    TextButton(onClick = { heading.toDoubleOrNull()?.takeIf(Double::isFinite)?.let(::proposeHeading) }, enabled = canChooseHeading) { Text("Confirm starting direction") }
                    Text(if (state.headingChoiceConfirmed) "Starting direction confirmed: %.0f° · check the map arrow".format((state.initialHeadingDegrees + 360.0) % 360.0)
                        else "Starting direction required · choose Right, Up, Left, Down or a point ahead", style = MaterialTheme.typography.bodySmall)
                }
                Text("0° right · 90° up · 180° left · 270° down. Keep the same grip; rotate the phone with your turns.", style = MaterialTheme.typography.bodySmall)
            }
            Text(state.realHeadingStatus, style = MaterialTheme.typography.bodySmall)
            Text(state.realWifiEvidenceStatus, style = MaterialTheme.typography.bodySmall)
            state.realWifiFixEpochMillis?.let { Text("Latest usable Wi-Fi match: ${(now - it).coerceAtLeast(0) / 1000}s ago", style = MaterialTheme.typography.bodySmall) }
            Text(state.realLiveStatus, style = MaterialTheme.typography.bodySmall)
            if (state.liveRunning) {
                Text("Steps detected: ${state.realLiveDetectedSteps} · used: ${state.realLiveStepCount}", style = MaterialTheme.typography.labelLarge)
                Text(state.realLiveStepStatus, style = MaterialTheme.typography.bodySmall)
                Text(state.realLiveRecordingStatus, style = MaterialTheme.typography.bodySmall)
            }
            state.realWifiIssue?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Button(onClick = actions.onRealLiveToggled, modifier = Modifier.fillMaxWidth(),
                enabled = !state.liveStarting && !state.projectBusy && !state.surveyRunning && !state.realHeadingAlignmentBusy && (state.liveRunning || state.liveCloseFailed || state.headingChoiceConfirmed && heading.toDoubleOrNull()?.isFinite() == true)) {
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
