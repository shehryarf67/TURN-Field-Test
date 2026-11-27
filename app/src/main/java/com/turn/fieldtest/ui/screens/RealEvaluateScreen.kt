package com.turn.fieldtest.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.turn.fieldtest.core.MethodError
import com.turn.fieldtest.core.summarizeErrors
import com.turn.fieldtest.data.local.TestSampleEntity
import com.turn.fieldtest.ui.TurnAppState
import com.turn.fieldtest.ui.defaultCheckpointFloor
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilterChip

/** [floorId] is the researcher's truth floor in multi-floor sessions; null means the session's start floor. */
data class CheckpointInput(val code: String, val x: Double, val y: Double, val floorId: String? = null)

@Composable
fun RealEvaluateScreen(state: TurnAppState, compact: Boolean, onCapture: (CheckpointInput) -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    var x by rememberSaveable { mutableStateOf("") }
    var y by rememberSaveable { mutableStateOf("") }
    // Multi-floor sessions name the truth floor explicitly (default: the previous checkpoint's, else the start floor),
    // never the displayed floor, so floor accuracy cannot confirm itself. Single-floor sessions show no chips.
    val tracking = state.liveFloorTracking
    val sessionFloors = tracking?.sessionFloors.orEmpty()
    val showFloorChips = sessionFloors.size > 1
    var chosenFloorId by rememberSaveable(state.liveSessionKey) { mutableStateOf<String?>(null) }
    val checkpointFloor = if (!showFloorChips) null else sessionFloors.firstOrNull { it.id == chosenFloorId }
        ?: sessionFloors.firstOrNull { it.id == defaultCheckpointFloor(state.realTestSamples.lastOrNull()?.trueFloorId,
            sessionFloors.map { f -> f.id }, tracking!!.startFloorId) }
    val widthLimit = checkpointFloor?.widthMetres ?: state.floorWidthMetres.toDouble()
    val heightLimit = checkpointFloor?.heightMetres ?: state.floorHeightMetres.toDouble()
    val parsedX = x.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..widthLimit }
    val parsedY = y.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..heightLimit }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(PagePadding),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageHeader("Accuracy test", "Accuracy test",
            "Start navigation, stand on a marked spot whose real position you measured, and enter its coordinates. TURN records where it thought you were.", compact)
        Text(state.realEvaluationStatus)
        SectionCard("Test spot", "${checkpointFloor?.name ?: state.floorName} · metric coordinates") {
            if (showFloorChips) {
                Text("Checkpoint floor")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    sessionFloors.forEach { floor ->
                        FilterChip(selected = floor.id == checkpointFloor?.id, onClick = { chosenFloorId = floor.id },
                            label = { Text(floor.name) })
                    }
                }
            }
            OutlinedTextField(code, { code = it }, label = { Text("Spot name, e.g. T1") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(x, { x = it }, label = { Text("Measured x (metres)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(y, { y = it }, label = { Text("Measured y (metres)") }, modifier = Modifier.fillMaxWidth())
            Text("Use spots you measured yourself — not your Wi-Fi spots, or the test would be unfair. If you reuse a spot name, enter the same coordinates.",
                style = MaterialTheme.typography.bodySmall)
            if (!state.liveRunning) Text("Start navigation first (Explore → Start navigation), then come back here.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Button(onClick = { onCapture(CheckpointInput(code.trim(), requireNotNull(parsedX), requireNotNull(parsedY), checkpointFloor?.id)) },
                enabled = state.liveRunning && !state.realEvaluationBusy && code.isNotBlank() && parsedX != null && parsedY != null,
                modifier = Modifier.fillMaxWidth()) { Text("Record this test") }
        }
        Text("Results", style = MaterialTheme.typography.titleMedium)
        if (state.realTestSamples.isEmpty()) Text("No tests recorded in this navigation session yet.", style = MaterialTheme.typography.bodyMedium)
        listOf("Fused", "Wi-Fi only", "Raw PDR").forEach { method ->
            val statistics = summarizeErrors(state.realTestSamples.map { sample -> sample.errorFor(method) })
            SectionCard(methodTitle(method), "This navigation session · ${statistics.sampleCount} ${if (statistics.sampleCount == 1) "test" else "tests"}") {
                LabelValue("Estimates available", "${statistics.estimateCount}")
                LabelValue("Mean / median", "${metric(statistics.mean)} / ${metric(statistics.median)}")
                LabelValue("90th percentile / maximum", "${metric(statistics.p90)} / ${metric(statistics.maximum)}")
                LabelValue("Within 3 m / 5 m", "%.1f%% / %.1f%%".format(statistics.within3Percent, statistics.within5Percent))
                LabelValue("No-estimate rate", "%.1f%%".format(statistics.failurePercent))
                LabelValue("Correct floor", "%.1f%%".format(statistics.floorAccuracyPercent))
            }
        }
        Text("Distances average only the tests where TURN had a position; the percentages count every test, including ones with no position. " +
            "Floor correctness is counted separately from distance. Every test is saved and can be exported (Export & backup); this summary restarts with each navigation.",
            style = MaterialTheme.typography.bodySmall)
    }
}

/** Plain names for the three positioning methods compared (their keys stay as stored). */
private fun methodTitle(method: String) = when (method) {
    "Fused" -> "Combined (what navigation uses)"
    "Raw PDR" -> "Steps only (PDR)"
    else -> method
}

private fun metric(value: Double?) = value?.let { "%.2f m".format(it) } ?: "—"
private fun TestSampleEntity.errorFor(method: String): MethodError = when (method) {
    "Wi-Fi only" -> MethodError(wifiOnlyHorizontalErrorMetres, wifiOnlyFloorCorrect, null)
    "Raw PDR" -> MethodError(rawPdrHorizontalErrorMetres, rawPdrFloorCorrect, null)
    else -> MethodError(fusedHorizontalErrorMetres, fusedFloorCorrect, actualErrorInsideConfidence)
}
