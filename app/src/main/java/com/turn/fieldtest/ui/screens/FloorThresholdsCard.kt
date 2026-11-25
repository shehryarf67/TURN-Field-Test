package com.turn.fieldtest.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.turn.fieldtest.core.FloorDetectionConfig
import com.turn.fieldtest.core.FloorDetectionOverrides
import com.turn.fieldtest.core.ThresholdSpec
import com.turn.fieldtest.ui.TurnAppState
import com.turn.fieldtest.ui.model.EventSeverity

/**
 * Diagnostics → Floor detection thresholds: view every editable [FloorDetectionConfig] value and override it on the
 * phone. Overrides are saved with the other preferences and read when the next tracking session starts (a running
 * session keeps the thresholds it started with); the session's CONFIG floor event records them.
 */
@Composable
internal fun FloorThresholdsCard(state: TurnAppState) {
    var open by rememberSaveable { mutableStateOf(false) }
    val overrides = state.floorDetectionOverrides
    SectionCard(
        title = "Floor detection thresholds",
        subtitle = "Tune stairs, lift and floor-change detection; changes apply when the next tracking session starts",
        trailing = { StatusPill(if (overrides.isEmpty()) "Defaults" else "${overrides.size} changed",
            if (overrides.isEmpty()) EventSeverity.INFO else EventSeverity.WARNING) },
    ) {
        if (state.liveRunning) Text("A session is running with the thresholds it started with. Changes here apply to the next one.",
            style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { open = !open }, modifier = Modifier.weight(1f)) { Text(if (open) "Hide thresholds" else "Show thresholds") }
            OutlinedButton(onClick = { state.floorDetectionOverrides = emptyMap() }, enabled = overrides.isNotEmpty(), modifier = Modifier.weight(1f)) {
                Text("Reset to defaults")
            }
        }
        if (!open) return@SectionCard
        val defaults = remember { FloorDetectionOverrides.defaults() }
        FloorDetectionOverrides.specs.groupBy { it.group }.forEach { (group, specs) ->
            Text(group, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp))
            specs.forEach { spec -> ThresholdRow(spec, defaults.getValue(spec.key), overrides[spec.key],
                conflict = { v -> FloorDetectionOverrides.apply(overrides + (spec.key to v)).rejected[spec.key] }) { value ->
                state.floorDetectionOverrides = FloorDetectionOverrides.sanitize(
                    if (value == null) overrides - spec.key else overrides + (spec.key to value))
            } }
        }
    }
}

@Composable
private fun ThresholdRow(spec: ThresholdSpec, default: Double, override: Double?, conflict: (Double) -> String?, onChange: (Double?) -> Unit) {
    var text by remember(override) { mutableStateOf(override?.let(spec::format).orEmpty()) }
    val typed = text.trim().toDoubleOrNull()
    val problem = when {
        text.isBlank() -> null
        typed == null -> "Enter a number"
        else -> spec.problem(typed) ?: conflict(typed)
    }
    val unit = if (spec.unit.isEmpty()) "" else " ${spec.unit}"
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(text, { text = it.filter { c -> c.isDigit() || c == '.' }.take(8) },
                label = { Text(spec.label) }, placeholder = { Text("${spec.format(default)}$unit") },
                supportingText = { Text(problem ?: "Default ${spec.format(default)}$unit · ${spec.format(spec.min)}–${spec.format(spec.max)}") },
                isError = problem != null, singleLine = true, modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { onChange(typed) }, enabled = typed != null && problem == null && typed != override) { Text("Use") }
            TextButton(onClick = { text = ""; onChange(null) }, enabled = override != null) { Text("Default") }
            if (override != null) Text("Now ${spec.format(override)}$unit", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp))
        }
    }
}
