package com.turn.fieldtest.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.turn.fieldtest.ui.*
import com.turn.fieldtest.ui.model.TurnDestination
import androidx.compose.ui.text.font.FontWeight

@Composable
fun FieldSurveyScreen(state: TurnAppState, actions: TurnAppActions) {
    var details by remember { mutableStateOf(false) }
    var magneticMode by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.magneticWalkRunning) { if (state.magneticWalkRunning) magneticMode = true }
    var target by remember(state.surveyTargetSnapshots) { mutableStateOf(state.surveyTargetSnapshots.toString()) }
    var notes by remember { mutableStateOf("") }
    val selected = state.referencePoints.firstOrNull { it.id == state.selectedSurveyReferencePointId }
    val savedProgress = selected?.let { state.surveyPointProgress[it.id] }
    val collecting = state.surveyRunning || state.surveyCloseFailed
    val freshCount = if (collecting) state.surveyAcceptedSnapshots else savedProgress?.freshCount ?: 0
    val targetCount = if (collecting) state.surveyTargetSnapshots else savedProgress?.target ?: state.surveyTargetSnapshots
    val apCount = if (collecting) state.surveyDistinctBssidCount else savedProgress?.accessPointCount ?: 0
    val surveyed = state.referencePoints.filter { state.surveyPointProgress[it.id]?.status in setOf("COMPLETED", "LEGACY") }.map { it.id }.toSet()
    // Nearest point still to survey, so the walk between points stays short.
    val next = state.referencePoints.filter { it.id !in surveyed && it.id != selected?.id }
        .minByOrNull { p -> selected?.let { (it.metres - p.metres).getDistance() } ?: 0f }
    if (details) {
        Column {
            TextButton(onClick = { details = false }) { Text("Back to survey") }
            SurveyScreen(state, true, actions.onRealSurveyStarted, actions.onRealSurveyFinished)
        }; return
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val shortWindow = maxHeight < 420.dp
    val panelHeight = (maxHeight * 0.43f).coerceAtMost(300.dp)
    Column(Modifier.fillMaxSize().then(if (shortWindow) Modifier.verticalScroll(rememberScrollState()) else Modifier)
        .padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!magneticMode, { magneticMode = false }, enabled = !state.sessionLocked, label = { Text("Wi-Fi points") })
            FilterChip(magneticMode, { magneticMode = true }, enabled = !state.sessionLocked, label = { Text("Magnetic paths") })
        }
        Text(if (magneticMode) "Magnetic survey" else "Wi-Fi survey", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(if (magneticMode) "Pick two points on one corridor · Stand at the first · Walk straight · Finish at the second"
            else "Select a point · Stand still · Collect · Save", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text("${state.venueName} · ${state.floorName}", style = MaterialTheme.typography.bodySmall)
        if (!magneticMode && state.referencePoints.isNotEmpty()) {
            Text("${surveyed.size} of ${state.referencePoints.size} points surveyed", style = MaterialTheme.typography.labelLarge)
            LinearProgressIndicator(progress = { surveyed.size / state.referencePoints.size.toFloat() }, modifier = Modifier.fillMaxWidth())
        }
        NavigationMap(state, Modifier.fillMaxWidth().then(if (shortWindow) Modifier.height(220.dp) else Modifier.weight(1f).heightIn(min = 100.dp)), showReferences = true, roomLabels = true, showMagneticCoverage = magneticMode, onTap = { p, tolerance ->
            val point = if (state.sessionLocked) null else state.referencePoints.minByOrNull { (it.metres - p).getDistance() }
                ?.takeIf { (it.metres - p).getDistance() < tolerance * 2 }
            if (point != null) {
                if (!magneticMode) state.selectedSurveyReferencePointId = point.id
                // Magnetic mode: first tap picks the start, second the end; a third starts a new pair.
                else if (state.magneticWalkFromId == null || state.magneticWalkToId != null) {
                    state.magneticWalkFromId = point.id; state.magneticWalkToId = null
                } else if (point.id != state.magneticWalkFromId) state.magneticWalkToId = point.id
            }
        })
        if (magneticMode) MagneticPathPanel(state, actions)
        else {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.referencePoints.forEach { point ->
                val progress = state.surveyPointProgress[point.id]
                val label = when {
                    state.surveyRunning && point.id == selected?.id -> "Collecting"
                    progress?.status == "COMPLETED" -> "Completed"
                    progress?.status == "LEGACY" -> "Saved legacy"
                    progress != null -> "Partial ${progress.freshCount}"
                    else -> "Not surveyed"
                }
                FilterChip(point.id == selected?.id, { state.selectedSurveyReferencePointId = point.id }, enabled = !state.sessionLocked,
                    label = { Text("${point.label} · $label") })
            }
        }
        Column(Modifier.fillMaxWidth().then(if(shortWindow) Modifier else Modifier.height(panelHeight).verticalScroll(rememberScrollState())).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (selected == null) {
                Text("Add reference points in Map setup before surveying.")
                TextButton(onClick = { state.mapSetupSection = "Points"; state.selectDestination(TurnDestination.FLOOR_EDITOR) }) { Text("Add survey points") }
            }
            else Text("${selected.label} · x %.1f m, y %.1f m".format(selected.metres.x, selected.metres.y), style = MaterialTheme.typography.titleMedium)
            if (!state.sessionLocked) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(target, { value -> target = value.filter(Char::isDigit).take(2); target.toIntOrNull()?.let { state.surveyTargetSnapshots = it.coerceIn(1, 50) } },
                    label = { Text("Fresh scans") }, modifier = Modifier.weight(0.4f), singleLine = true)
                OutlinedTextField(notes, { notes = it }, label = { Text("Notes (optional)") }, modifier = Modifier.weight(0.6f), singleLine = true)
            }
            LinearProgressIndicator(progress = { (freshCount.toFloat() / targetCount.coerceAtLeast(1)).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            Text("${freshCount} / ${targetCount} fresh snapshots · ${apCount} APs", style = MaterialTheme.typography.labelLarge)
            Text(state.surveyRuntimeStatus, style = MaterialTheme.typography.bodySmall)
            state.realWifiIssue?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Text(if (collecting) state.surveySaveStatus else when (savedProgress?.status) {
                "COMPLETED" -> "Fingerprint saved on this phone"
                "LEGACY" -> "Saved fingerprint from an earlier version; target count was not recorded"
                null -> "This point has no saved fingerprint"
                else -> "Partial collection saved. Begin a new collection to complete this point."
            }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            Button(modifier = Modifier.fillMaxWidth().heightIn(min=52.dp), shape=RoundedCornerShape(16.dp), enabled = !state.surveyStarting && !state.projectBusy && !state.liveRunning && selected != null,
                onClick = {
                    if (state.surveyRunning || state.surveyCloseFailed) actions.onRealSurveyFinished()
                    else selected?.let { actions.onRealSurveyStarted(SurveyCaptureMetadata(it.id, null, null, notes)) }
                }) { Text(if (state.surveyStarting) "Starting…" else if (state.surveyCloseFailed) "Retry saving" else if (state.surveyRunning) "Finish and save" else "Begin collection") }
            if (!collecting && next != null && (selected == null || selected.id in surveyed)) {
                val away = selected?.let { (it.metres - next.metres).getDistance() }
                Text(if (selected != null) "${selected.label} done. Next: ${next.label}" + (away?.let { " · %.1f m away".format(it) } ?: "")
                    else "Start with ${next.label}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                FilledTonalButton(onClick = { state.selectedSurveyReferencePointId = next.id }, enabled = !state.sessionLocked,
                    modifier = Modifier.fillMaxWidth()) { Text("Go to ${next.label}") }
            } else if (!collecting && next != null) TextButton(onClick = { state.selectedSurveyReferencePointId = next.id },
                enabled = !state.sessionLocked) { Text("Skip to nearest unsurveyed point (${next.label})") }
            if (!collecting && state.referencePoints.isNotEmpty() && next == null && selected?.id in surveyed)
                Text("All ${state.referencePoints.size} points surveyed. Add more points for denser coverage, or continue to Explore.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            TextButton(onClick = { state.mapSetupSection = "Points"; state.selectDestination(TurnDestination.FLOOR_EDITOR) },
                enabled = !collecting) { Text("Add or suggest more points") }
            TextButton(onClick = { details = true }) { Text("Research details & RSSI aggregation") }
            if (!collecting && state.surveyPointProgress.values.any { it.status == "COMPLETED" || it.status == "LEGACY" }) {
                OutlinedButton(onClick = { state.selectDestination(TurnDestination.LIVE_LOCATE) }, enabled = !state.sessionLocked,
                    modifier = Modifier.fillMaxWidth()) { Text("Continue to Explore") }
            }
        }
        }
    }
    }
}

@Composable
private fun MagneticPathPanel(state: TurnAppState, actions: TurnAppActions) {
    val from = state.referencePoints.firstOrNull { it.id == state.magneticWalkFromId }
    val to = state.referencePoints.firstOrNull { it.id == state.magneticWalkToId }
    // Bounded height: on short windows the whole survey page scrolls, and nested unbounded scrolling would crash.
    Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(state.magneticSensorStatus, style = MaterialTheme.typography.bodySmall,
            color = if (state.magneticSensorStatus.startsWith("Magnetometer ready")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        Text("%d cells mapped · %d walks saved".format(state.magneticCoverage.size, state.magneticWalkCount),
            style = MaterialTheme.typography.labelLarge)
        if (state.referencePoints.size < 2) {
            Text("Add at least two survey points along a corridor in Map setup first.")
            TextButton(onClick = { state.mapSetupSection = "Points"; state.selectDestination(TurnDestination.FLOOR_EDITOR) }) { Text("Add survey points") }
            return@Column
        }
        Text("Tap two survey points on the map: where you start, then where you stop.", style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedCard(Modifier.weight(1f)) { Column(Modifier.padding(10.dp)) {
                Text("FROM", style = MaterialTheme.typography.labelSmall); Text(from?.label ?: "Tap a point", fontWeight = FontWeight.Bold) } }
            OutlinedCard(Modifier.weight(1f)) { Column(Modifier.padding(10.dp)) {
                Text("TO", style = MaterialTheme.typography.labelSmall); Text(to?.label ?: "Tap a point", fontWeight = FontWeight.Bold) } }
        }
        if (from != null && to != null) Text("%.1f m straight line · about %d steps".format((from.metres - to.metres).getDistance(),
            ((from.metres - to.metres).getDistance() / 0.7f).toInt().coerceAtLeast(1)), style = MaterialTheme.typography.bodySmall)
        if (state.magneticWalkRunning) {
            Text("${state.magneticWalkSteps} steps", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(state.magneticWalkStatus, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = actions.onMagneticWalkFinished, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                shape = RoundedCornerShape(16.dp)) { Text("I'm at ${to?.label ?: "the end"} · Finish") }
            TextButton(onClick = actions.onMagneticWalkCancelled) { Text("Cancel walk") }
        } else {
            Text(state.magneticWalkStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            Button(onClick = { if (from != null && to != null) actions.onMagneticWalkStarted(from.id, to.id) },
                enabled = from != null && to != null && !state.sessionLocked && !state.hasUnsavedMapChanges,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(16.dp)) {
                Text(if (from != null) "I'm at ${from.label} · Start walk" else "Start walk")
            }
            if (from != null && to != null) TextButton(onClick = { state.magneticWalkFromId = to.id; state.magneticWalkToId = from.id }) {
                Text("Swap direction (walk each path both ways)")
            }
            Text("Hold the phone the way you will when navigating, keep away from laptops and metal trolleys, and walk at your normal pace.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
