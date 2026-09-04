package com.turn.fieldtest.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.turn.fieldtest.ui.SurveyCaptureMetadata
import com.turn.fieldtest.ui.TurnAppState
import com.turn.fieldtest.ui.model.DataMode
import com.turn.fieldtest.ui.model.EventSeverity
import com.turn.fieldtest.ui.model.TurnDemoData
import com.turn.fieldtest.ui.theme.TurnAmber
import com.turn.fieldtest.ui.theme.TurnMint
import kotlinx.coroutines.delay

@Composable
fun SurveyScreen(
    state: TurnAppState,
    compact: Boolean,
    onStartRealSurvey: (SurveyCaptureMetadata) -> Unit = {},
    onFinishRealSurvey: () -> Unit = {},
) {
    var orientation by remember { mutableStateOf("North-facing") }
    var crowd by remember { mutableStateOf("Normal") }
    var notes by remember { mutableStateOf("Pilot survey after lunch") }
    var saveStatus by remember { mutableStateOf("Draft autosaved locally") }
    val excluded = remember { mutableStateMapOf<String, Boolean>() }
    val simulated = state.mode == DataMode.DEMO
    val aggregates = if (simulated) TurnDemoData.fingerprintAggregates else state.realSurveyAggregates

    if (simulated && state.surveyRunning) {
        LaunchedEffect(state.surveyRunning, state.surveyAcceptedSnapshots) {
            delay(850)
            val nextIsFresh = (state.surveyAcceptedSnapshots + state.surveyCachedIgnored + 1) % 4 != 0
            state.acceptDemoSurveySnapshot(nextIsFresh)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(PagePadding),
        verticalArrangement = Arrangement.spacedBy(15.dp)
    ) {
        PageHeader(
            eyebrow = "04 · Training data",
            title = "Wi-Fi fingerprint survey",
            description = "Collect bounded, distinct scan snapshots at a known point. Raw observations remain traceable to their snapshot.",
            compact = compact,
            action = {
                Button(onClick = {
                    if (simulated) {
                        state.toggleSurvey()
                    } else if (state.surveyRunning) {
                        onFinishRealSurvey()
                    } else {
                        onStartRealSurvey(
                            SurveyCaptureMetadata(
                                referencePointId = state.selectedSurveyReferencePointId,
                                orientationLabel = orientation,
                                crowdConditionLabel = crowd,
                                researcherNotes = notes,
                            ),
                        )
                    }
                }) {
                    Icon(if (state.surveyRunning) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(7.dp))
                    Text(if (state.surveyRunning) "Pause collection" else if (state.surveyAcceptedSnapshots >= state.surveyTargetSnapshots) "Collect again" else "Begin collection")
                }
            }
        )

        AdaptiveColumns(
            breakpoint = 750.dp,
            primaryWeight = 1.05f,
            secondaryWeight = 1.4f,
            primary = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SectionCard("Survey context", "Required before collection") {
                        SelectorRow("Venue", "Computing Block Pilot", "VEN-CS-01")
                        SelectorRow("Floor", "Ground floor", "FL-G")
                        val selectedPoint = state.referencePoints.firstOrNull {
                            it.id == state.selectedSurveyReferencePointId
                        } ?: state.referencePoints.first()
                        SelectorRow(
                            "Reference point",
                            selectedPoint.id,
                            "x %.1f m · y %.1f m".format(selectedPoint.metres.x, selectedPoint.metres.y),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            state.referencePoints.forEach { point ->
                                FilterChip(
                                    selected = point.id == state.selectedSurveyReferencePointId,
                                    onClick = { state.selectedSurveyReferencePointId = point.id },
                                    enabled = !state.surveyRunning,
                                    label = { Text(point.id) },
                                )
                            }
                        }
                        SelectorRow("Device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}", "Android ${android.os.Build.VERSION.RELEASE}")
                        SelectorRow(
                            "Session",
                            if (simulated) "SUR-DEMO-A" else state.surveySessionLabel,
                            if (simulated) "anonymous simulated session" else "anonymous local Room session",
                        )
                    }

                    SectionCard("Conditions", "Optional labels improve stratified analysis") {
                        Text("Orientation", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        ChoiceChips(listOf("North-facing", "East-facing", "South-facing", "West-facing"), orientation) { orientation = it }
                        Spacer(Modifier.height(9.dp))
                        Text("Crowd condition", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        ChoiceChips(listOf("Quiet", "Normal", "Busy"), crowd) { crowd = it }
                        Spacer(Modifier.height(9.dp))
                        OutlinedTextField(
                            value = notes,
                            onValueChange = { notes = it },
                            label = { Text("Researcher notes") },
                            minLines = 2,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            },
            secondary = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SectionCard(
                        title = if (state.surveyRunning) "Collection in progress" else "Collection status",
                        subtitle = if (simulated) "Prerecorded snapshot sequence · SIMULATED" else "Physical WifiManager scan sequence",
                        trailing = {
                            StatusPill(
                                if (state.surveyRunning) "recording" else if (state.surveyAcceptedSnapshots >= state.surveyTargetSnapshots) "complete" else "ready",
                                if (state.surveyAcceptedSnapshots >= state.surveyTargetSnapshots) EventSeverity.GOOD else EventSeverity.INFO
                            )
                        }
                    ) {
                        val progress = state.surveyAcceptedSnapshots.toFloat() / state.surveyTargetSnapshots
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                            Text(
                                "${state.surveyAcceptedSnapshots}",
                                style = MaterialTheme.typography.displaySmall,
                                fontWeight = FontWeight.Black,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(" / ${state.surveyTargetSnapshots} fresh snapshots", modifier = Modifier.padding(bottom = 7.dp), style = MaterialTheme.typography.bodyMedium)
                        }
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(8.dp))
                        Spacer(Modifier.height(14.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            MetricCard("Raw observations", if (simulated) "${state.surveyAcceptedSnapshots * 5 + 3}" else state.surveyRawObservationCount.toString(), Modifier.weight(1f), "retained")
                            MetricCard("Distinct BSSIDs", if (simulated) "9" else state.surveyDistinctBssidCount.toString(), Modifier.weight(1f), "union")
                            MetricCard("Cached ignored", state.surveyCachedIgnored.toString(), Modifier.weight(1f), "not independent", TurnAmber)
                        }
                        Spacer(Modifier.height(12.dp))
                        SnapshotTimeline(state.surveyAcceptedSnapshots, state.surveyCachedIgnored)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "A repeated cached result is logged for diagnostics but does not advance the collection target or detection-rate denominator.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (!simulated) {
                            Spacer(Modifier.height(8.dp))
                            Text(state.surveyRuntimeStatus, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }

                    SectionCard("Save readiness", "Wi-Fi fingerprints do not require BLE") {
                        ValidationLine("Known point selected", true)
                        ValidationLine("Point inside walkable region", true)
                        ValidationLine("At least 8 fresh snapshots", state.surveyAcceptedSnapshots >= 8)
                        ValidationLine("Raw observations retained", true)
                        Spacer(Modifier.height(8.dp))
                        Button(
                            enabled = simulated && state.surveyAcceptedSnapshots >= 8 && !state.surveyRunning,
                            onClick = {
                                if (simulated) saveStatus = "Demo fingerprint saved in this simulated session"
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Outlined.Save, contentDescription = null)
                            Spacer(Modifier.width(7.dp))
                            Text(if (simulated) "Save demo fingerprint" else "Persisted automatically in Room")
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(if (simulated) saveStatus else state.surveySaveStatus, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        )

        SectionCard(
            title = "Live aggregation at RP-G-07",
            subtitle = "Median RSSI is the default matching value; every raw reading remains stored",
            trailing = {
                StatusPill(
                    if (aggregates.isEmpty()) "no aggregate yet" else "${aggregates.size} BSSIDs",
                    if (aggregates.isEmpty()) EventSeverity.WARNING else EventSeverity.GOOD,
                )
            }
        ) {
            TableShell(
                headers = listOf(
                    "BSSID" to 160.dp,
                    "Median" to 70.dp,
                    "Mean" to 70.dp,
                    "Std dev" to 72.dp,
                    "Min / max" to 88.dp,
                    "N" to 48.dp,
                    "Detection" to 82.dp,
                    "Matching" to 104.dp
                )
            ) {
                aggregates.forEach { aggregate ->
                    DataRow(
                        cells = listOf(
                            aggregate.bssid to 160.dp,
                            "%.1f".format(aggregate.medianDbm) to 70.dp,
                            "%.1f".format(aggregate.meanDbm) to 70.dp,
                            "%.1f".format(aggregate.standardDeviation) to 72.dp,
                            "${aggregate.range.first} / ${aggregate.range.last}" to 88.dp,
                            aggregate.observations.toString() to 48.dp,
                            "${aggregate.detectionRatePercent}%" to 82.dp,
                            (if (excluded[aggregate.bssid] == true) "EXCLUDED" else if (aggregate.stable) "INCLUDED" else "REVIEW") to 104.dp
                        ),
                        tint = if (aggregate.stable) TurnMint else TurnAmber
                    )
                    if (!aggregate.stable) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Unstable AP · σ ${aggregate.standardDeviation} dB", style = MaterialTheme.typography.labelSmall, color = TurnAmber, modifier = Modifier.weight(1f))
                            Text("Exclude from matching", style = MaterialTheme.typography.labelSmall)
                            Spacer(Modifier.width(8.dp))
                            Switch(
                                checked = excluded[aggregate.bssid] == true,
                                onCheckedChange = { if (simulated) excluded[aggregate.bssid] = it },
                                enabled = simulated,
                            )
                        }
                    }
                }
                if (aggregates.isEmpty()) {
                    Text(
                        if (simulated) "No demo aggregate is available."
                        else "No real aggregate exists yet. Begin collection and wait for at least one fresh Android scan.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }

        AdaptiveColumns(
            primary = {
                SectionCard("Fingerprint completeness", "Coverage across point, orientation and device") {
                    CompletenessRow("Fresh scan target", state.surveyAcceptedSnapshots, state.surveyTargetSnapshots)
                    CompletenessRow("BSSID detection", if (simulated) 9 else state.surveyDistinctBssidCount, if (simulated) 10 else maxOf(1, state.surveyDistinctBssidCount))
                    CompletenessRow("Orientation repeats", if (simulated) 2 else if (state.surveyAcceptedSnapshots > 0) 1 else 0, 4)
                    CompletenessRow("Device repeats", if (simulated) 1 else if (state.surveyAcceptedSnapshots > 0) 1 else 0, 3)
                }
            },
            secondary = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    BleDisabledBanner()
                    SectionCard("Data isolation", "Ground truth never becomes training data") {
                        Text(
                            "This survey writes only to survey sessions, scan snapshots, raw observations and aggregate fingerprints. Evaluation checkpoints use a separate Test Mode path.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        )
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SelectorRow(label: String, value: String, supporting: String) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(supporting, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("CHANGE", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun ChoiceChips(options: List<String>, selected: String, onSelected: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        options.forEach { option ->
            FilterChip(selected = selected == option, onClick = { onSelected(option) }, label = { Text(option) })
        }
    }
}

@Composable
private fun SnapshotTimeline(accepted: Int, cached: Int) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(12) { index ->
            val position = index + 1
            val isCachedMarker = cached > 0 && position == accepted.coerceAtLeast(4) - 2
            val color = when {
                isCachedMarker -> TurnAmber
                position <= accepted -> TurnMint
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
            Box(
                Modifier.size(29.dp).clip(RoundedCornerShape(9.dp)).background(color.copy(alpha = if (position <= accepted || isCachedMarker) 0.25f else 0.7f)),
                contentAlignment = Alignment.Center
            ) {
                Text(if (isCachedMarker) "C" else position.toString(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = if (isCachedMarker) TurnAmber else MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun ValidationLine(label: String, valid: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(
            Icons.Outlined.CheckCircle,
            contentDescription = if (valid) "$label complete" else "$label incomplete",
            tint = if (valid) TurnMint else MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(19.dp)
        )
        Text(label, style = MaterialTheme.typography.bodySmall, color = if (valid) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CompletenessRow(label: String, value: Int, total: Int) {
    val progress = if (total == 0) 0f else value.toFloat() / total
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(120.dp))
        LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.weight(1f))
        Text("$value / $total", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}
