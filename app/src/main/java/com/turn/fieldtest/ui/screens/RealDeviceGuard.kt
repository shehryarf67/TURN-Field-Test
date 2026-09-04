package com.turn.fieldtest.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.turn.fieldtest.ui.model.EventSeverity

/** Prevents demo trajectories or metrics from being mistaken for physical field evidence. */
@Composable
internal fun RealDeviceGuard(
    eyebrow: String,
    title: String,
    description: String,
    nextStep: String,
    compact: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(PagePadding),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PageHeader(
            eyebrow = eyebrow,
            title = title,
            description = description,
            compact = compact,
        )
        StatusPill("NO SIMULATED FALLBACK", EventSeverity.WARNING)
        SectionCard("Real-device workflow not initialized", "TURN will not invent field results") {
            Text(nextStep, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "Switch to DEMO only when you intentionally want the labelled deterministic replay.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
