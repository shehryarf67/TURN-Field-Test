package com.turn.fieldtest.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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

data class CheckpointInput(val code: String, val x: Double, val y: Double)

@Composable
fun RealEvaluateScreen(state: TurnAppState, compact: Boolean, onCapture: (CheckpointInput) -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    var x by rememberSaveable { mutableStateOf("") }
    var y by rememberSaveable { mutableStateOf("") }
    val parsedX = x.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..42.0 }
    val parsedY = y.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..28.0 }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(PagePadding),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageHeader("06 · Independent validation", "Evaluate positioning",
            "Stand at a physically marked checkpoint while Live locate is running. Enter its measured coordinates.", compact)
        Text(state.realEvaluationStatus)
        SectionCard("Independent checkpoint", "Ground floor · FL-G · fixed pilot coordinates") {
            OutlinedTextField(code, { code = it }, label = { Text("Checkpoint code") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(x, { x = it }, label = { Text("Measured x (metres)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(y, { y = it }, label = { Text("Measured y (metres)") }, modifier = Modifier.fillMaxWidth())
            Text("Use independently measured checkpoints, separate from training reference points. Reusing a checkpoint code requires the same coordinate.")
            Button(onClick = { onCapture(CheckpointInput(code.trim(), requireNotNull(parsedX), requireNotNull(parsedY))) },
                enabled = state.liveRunning && !state.realEvaluationBusy && code.isNotBlank() && parsedX != null && parsedY != null,
                modifier = Modifier.fillMaxWidth()) { Text("Capture test sample") }
        }
        listOf("Wi-Fi only", "Raw PDR", "Fused").forEach { method ->
            val statistics = summarizeErrors(state.realTestSamples.map { sample -> sample.errorFor(method) })
            SectionCard(method, "Current positioning session · ${statistics.sampleCount} checkpoints") {
                LabelValue("Estimates available", "${statistics.estimateCount}")
                LabelValue("Mean / median", "${metric(statistics.mean)} / ${metric(statistics.median)}")
                LabelValue("90th percentile / maximum", "${metric(statistics.p90)} / ${metric(statistics.maximum)}")
                LabelValue("Within 3 m / 5 m", "%.1f%% / %.1f%%".format(statistics.within3Percent, statistics.within5Percent))
                LabelValue("No-estimate rate", "%.1f%%".format(statistics.failurePercent))
                LabelValue("Correct floor", "%.1f%%".format(statistics.floorAccuracyPercent))
            }
        }
        Text("Distance statistics use available estimates. Threshold and floor percentages use all captures, including failures. Horizontal error and floor correctness are separate. Full sample records are saved to Room and available in Data/export; the on-screen summary resets for a new live session.")
    }
}

private fun metric(value: Double?) = value?.let { "%.2f m".format(it) } ?: "—"
private fun TestSampleEntity.errorFor(method: String): MethodError = when (method) {
    "Wi-Fi only" -> MethodError(wifiOnlyHorizontalErrorMetres, wifiOnlyFloorCorrect, null)
    "Raw PDR" -> MethodError(rawPdrHorizontalErrorMetres, rawPdrFloorCorrect, null)
    else -> MethodError(fusedHorizontalErrorMetres, fusedFloorCorrect, actualErrorInsideConfidence)
}
