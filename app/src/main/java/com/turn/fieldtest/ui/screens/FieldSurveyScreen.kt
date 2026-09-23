package com.turn.fieldtest.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.turn.fieldtest.ui.*

@Composable
fun FieldSurveyScreen(state: TurnAppState, actions: TurnAppActions) {
    var details by remember { mutableStateOf(false) }
    var target by remember(state.surveyTargetSnapshots) { mutableStateOf(state.surveyTargetSnapshots.toString()) }
    var notes by remember { mutableStateOf("") }
    val selected = state.referencePoints.firstOrNull { it.id == state.selectedSurveyReferencePointId }
    val savedProgress = selected?.let { state.surveyPointProgress[it.id] }
    val collecting = state.surveyRunning || state.surveyCloseFailed
    val freshCount = if (collecting) state.surveyAcceptedSnapshots else savedProgress?.freshCount ?: 0
    val targetCount = if (collecting) state.surveyTargetSnapshots else savedProgress?.target ?: state.surveyTargetSnapshots
    val apCount = if (collecting) state.surveyDistinctBssidCount else savedProgress?.accessPointCount ?: 0
    if (details) {
        Column {
            TextButton(onClick = { details = false }) { Text("Back to survey") }
            SurveyScreen(state, true, actions.onRealSurveyStarted, actions.onRealSurveyFinished)
        }; return
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Collect fingerprints", style = MaterialTheme.typography.titleLarge)
        Text("${state.venueName} · ${state.floorName}", style = MaterialTheme.typography.bodySmall)
        NavigationMap(state, Modifier.fillMaxWidth().weight(1f).heightIn(min = 200.dp), showReferences = true, onTap = { p, tolerance ->
            if (!state.sessionLocked) state.referencePoints.minByOrNull { (it.metres - p).getDistance() }
                ?.takeIf { (it.metres - p).getDistance() < tolerance * 2 }?.let { state.selectedSurveyReferencePointId = it.id }
        })
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
        Column(Modifier.fillMaxWidth().heightIn(max = 270.dp).verticalScroll(rememberScrollState()).padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (selected == null) Text("Add reference points in Map setup before surveying.")
            else Text("${selected.label} · x %.1f m, y %.1f m".format(selected.metres.x, selected.metres.y), style = MaterialTheme.typography.titleMedium)
            if (!state.sessionLocked) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(target, { value -> target = value.filter(Char::isDigit).take(2); target.toIntOrNull()?.let { state.surveyTargetSnapshots = it.coerceIn(1, 50) } },
                    label = { Text("Fresh scans") }, modifier = Modifier.weight(0.4f), singleLine = true)
                OutlinedTextField(notes, { notes = it }, label = { Text("Notes / orientation") }, modifier = Modifier.weight(0.6f), singleLine = true)
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
            Button(modifier = Modifier.fillMaxWidth(), enabled = !state.surveyStarting && !state.projectBusy && !state.liveRunning && selected != null,
                onClick = {
                    if (state.surveyRunning || state.surveyCloseFailed) actions.onRealSurveyFinished()
                    else selected?.let { actions.onRealSurveyStarted(SurveyCaptureMetadata(it.id, null, null, notes)) }
                }) { Text(if (state.surveyStarting) "Starting…" else if (state.surveyCloseFailed) "Retry saving" else if (state.surveyRunning) "Finish and save" else "Begin collection") }
            TextButton(onClick = { details = true }) { Text("Research details & RSSI aggregation") }
        }
    }
}
