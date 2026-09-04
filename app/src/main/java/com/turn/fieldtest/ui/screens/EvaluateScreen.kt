package com.turn.fieldtest.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddLocationAlt
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.turn.fieldtest.ui.TurnAppState
import com.turn.fieldtest.ui.model.DataMode
import com.turn.fieldtest.ui.model.EvaluationSampleUi
import com.turn.fieldtest.ui.model.EventSeverity
import com.turn.fieldtest.ui.theme.TurnAmber
import com.turn.fieldtest.ui.theme.TurnMint
import com.turn.fieldtest.ui.theme.TurnRed
import kotlin.math.ceil

@Composable
fun EvaluateScreen(state: TurnAppState, compact: Boolean) {
    if (state.mode == DataMode.REAL_DEVICE) {
        RealDeviceGuard(
            eyebrow = "06 · Independent validation",
            title = "Evaluate positioning",
            description = "Evaluation is disabled until a physical positioning session has produced real estimates.",
            nextStep = "Start a real live-location session, obtain a non-simulated estimate, then capture a physically marked checkpoint. TURN will not fabricate an error sample.",
            compact = compact,
        )
        return
    }
    val samples = state.evaluationSamples.toList()
    val fused = samples.mapNotNull { it.fusedErrorMetres }
    val floorAccuracy = percentage(samples.count { it.floorCorrect }, samples.size)
    val confidenceCoverage = percentage(samples.count { it.confidenceContainedTruth }, samples.size)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(PagePadding),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        PageHeader(
            eyebrow = "06 · Independent validation",
            title = "Evaluate positioning",
            description = "Capture estimates at physically marked checkpoints. Test samples stay separate from the training fingerprint database.",
            compact = compact,
            action = {
                Button(onClick = state::captureEvaluationSample) {
                    Icon(Icons.Outlined.AddLocationAlt, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Capture test sample")
                }
            }
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                .padding(13.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Outlined.Science, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text("Independent Test Mode", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Text(
                    "Ground truth is read only after TURN records its Wi-Fi-only, raw PDR and fused estimates.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            StatusPill("training isolation on", EventSeverity.GOOD)
        }

        SectionCard(
            title = "Ground-truth checkpoint",
            subtitle = "Select the marker you are physically standing on before capture"
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("CP-G-01", "CP-G-04", "CP-G-09", "CP-1-02").forEach { checkpoint ->
                    FilterChip(
                        selected = state.selectedCheckpoint == checkpoint,
                        onClick = { state.selectedCheckpoint = checkpoint },
                        label = { Text(checkpoint) },
                        leadingIcon = if (state.selectedCheckpoint == checkpoint) {
                            { Icon(Icons.Outlined.CheckCircle, contentDescription = null) }
                        } else null
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            AdaptiveColumns(
                breakpoint = 540.dp,
                primary = {
                    Column {
                        LabelValue("Known floor", if (state.selectedCheckpoint.startsWith("CP-1")) "First floor" else "Ground floor")
                        LabelValue("Known coordinate", checkpointCoordinate(state.selectedCheckpoint))
                    }
                },
                secondary = {
                    Column {
                        LabelValue("Current live estimate", "(14.5, 14.0) m · Ground")
                        LabelValue("Last fresh Wi-Fi", "2.1 s ago · 8 APs")
                    }
                }
            )
        }

        AdaptiveColumns(
            primary = {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricCard("Samples", samples.size.toString(), Modifier.weight(1f), "anonymous checkpoints")
                    MetricCard("Median fused", formatMetres(median(fused)), Modifier.weight(1f), "horizontal error", TurnMint)
                }
            },
            secondary = {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricCard("P90 fused", formatMetres(percentile(fused, 0.90)), Modifier.weight(1f), "horizontal error", TurnAmber)
                    MetricCard("Floor accuracy", "$floorAccuracy%", Modifier.weight(1f), "stored separately", TurnMint)
                }
            }
        )

        AdaptiveColumns(
            primaryWeight = 1.15f,
            secondaryWeight = 0.85f,
            primary = {
                SectionCard("Method comparison", "Lower horizontal error is better") {
                    MethodPerformance("Wi-Fi only", samples.mapNotNull { it.wifiErrorMetres }, TurnAmber)
                    MethodPerformance("Raw PDR", samples.mapNotNull { it.pdrErrorMetres }, TurnRed)
                    MethodPerformance("Fused particle filter", fused, TurnMint)
                    MethodPerformance("Map constrained", samples.mapNotNull { it.constrainedErrorMetres }, MaterialTheme.colorScheme.primary)
                }
            },
            secondary = {
                SectionCard("Evaluation health", "Confidence is not derived from ground truth") {
                    LabelValue("Mean fused error", formatMetres(fused.averageOrNull()))
                    LabelValue("Maximum fused error", formatMetres(fused.maxOrNull()))
                    LabelValue("Within 3 m", "${percentage(fused.count { it <= 3.0 }, fused.size)}%")
                    LabelValue("Within 5 m", "${percentage(fused.count { it <= 5.0 }, fused.size)}%")
                    LabelValue("Confidence contains truth", "$confidenceCoverage%")
                    LabelValue("Failure / no estimate", "0%")
                }
            }
        )

        SectionCard(
            title = "Captured samples",
            subtitle = "Horizontal error and floor correctness are reported independently"
        ) {
            TableShell(
                headers = listOf(
                    "Checkpoint" to 120.dp,
                    "Time" to 90.dp,
                    "Wi-Fi" to 90.dp,
                    "Raw PDR" to 90.dp,
                    "Fused" to 90.dp,
                    "Constrained" to 110.dp,
                    "Floor" to 90.dp,
                    "Confidence" to 110.dp
                )
            ) {
                samples.asReversed().forEach { sample ->
                    DataRow(
                        cells = listOf(
                            sample.checkpointId to 120.dp,
                            sample.timestamp to 90.dp,
                            formatMetres(sample.wifiErrorMetres) to 90.dp,
                            formatMetres(sample.pdrErrorMetres) to 90.dp,
                            formatMetres(sample.fusedErrorMetres) to 90.dp,
                            formatMetres(sample.constrainedErrorMetres) to 110.dp,
                            (if (sample.floorCorrect) "correct" else "wrong") to 90.dp,
                            (if (sample.confidenceContainedTruth) "contains truth" else "missed truth") to 110.dp
                        ),
                        tint = if (sample.floorCorrect) null else TurnRed
                    )
                }
            }
        }

        Text(
            "A captured checkpoint is evaluation evidence only; it is never converted into a survey reference point or fingerprint.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun MethodPerformance(label: String, values: List<Double>, color: androidx.compose.ui.graphics.Color) {
    val mean = values.averageOrNull()
    val median = median(values)
    Column(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
            Text("median ${formatMetres(median)} · mean ${formatMetres(mean)}", style = MaterialTheme.typography.labelSmall, color = color)
        }
        LinearProgressIndicator(
            progress = { ((mean ?: 0.0) / 8.0).toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
            color = color,
            trackColor = color.copy(alpha = 0.14f)
        )
    }
}

private fun checkpointCoordinate(id: String): String = when (id) {
    "CP-G-01" -> "(5.0, 20.0) m"
    "CP-G-04" -> "(11.0, 19.5) m"
    "CP-1-02" -> "(18.0, 9.5) m"
    else -> "(14.5, 14.0) m"
}

private fun List<Double>.averageOrNull(): Double? = if (isEmpty()) null else average()

private fun median(values: List<Double>): Double? {
    if (values.isEmpty()) return null
    val sorted = values.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2.0 else sorted[middle]
}

private fun percentile(values: List<Double>, quantile: Double): Double? {
    if (values.isEmpty()) return null
    val sorted = values.sorted()
    return sorted[(ceil(quantile * sorted.size).toInt() - 1).coerceIn(sorted.indices)]
}

private fun percentage(part: Int, total: Int): Int = if (total == 0) 0 else ((part * 100.0) / total).toInt()

private fun formatMetres(value: Double?): String = value?.let { "%.1f m".format(it) } ?: "—"
