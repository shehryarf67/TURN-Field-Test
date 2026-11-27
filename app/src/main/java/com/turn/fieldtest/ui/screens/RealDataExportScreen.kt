package com.turn.fieldtest.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.turn.fieldtest.data.export.ResearchDataset
import com.turn.fieldtest.ui.TurnAppState
import com.turn.fieldtest.ui.components.AdvancedSection

@Composable
fun RealDataExportScreen(
    state: TurnAppState,
    compact: Boolean,
    onExport: (Uri, ResearchDataset) -> Unit,
) {
    val backup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {
        if (it != null) onExport(it, ResearchDataset.BACKUP)
        else state.lastDataAction = "Export cancelled; stored records unchanged"
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(PagePadding),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PageHeader("Export & backup", "Export & backup",
            "Save a copy of your maps, Wi-Fi recordings and sessions to your files.", compact)
        Text(state.lastDataAction, style = MaterialTheme.typography.bodyMedium)
        if (state.surveyRunning || state.liveRunning) Text("Stop recording Wi-Fi and navigation before exporting.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        SectionCard("Back up everything", "One file with all your data — save one after each mapping or testing session") {
            Text("Includes every map, Wi-Fi recording, session and position estimate. Floor plan pictures stay on the phone; copy them separately if you need them.",
                style = MaterialTheme.typography.bodySmall)
            Button(shape=MaterialTheme.shapes.small,
                onClick = { backup.launch("turn-database-${System.currentTimeMillis()}.json") },
                enabled = !state.realExportBusy && !state.surveyRunning && !state.liveRunning,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save complete database JSON") }
        }
        AdvancedSection(title = "Spreadsheet files (CSV)", subtitle = "Individual tables for analysis; every field and ID is included") {
            ResearchDataset.entries.filter { it != ResearchDataset.BACKUP }.forEach { dataset ->
                val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) {
                    if (it != null) onExport(it, dataset)
                    else state.lastDataAction = "Export cancelled; stored records unchanged"
                }
                OutlinedButton(shape=MaterialTheme.shapes.small,
                    onClick = { picker.launch("turn-${dataset.name.lowercase()}-${System.currentTimeMillis()}.csv") },
                    enabled = !state.realExportBusy && !state.surveyRunning && !state.liveRunning,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(dataset.label) }
            }
        }
        Text("An empty table gives a file with just the column headings. Files link to each other through session and survey-point IDs. " +
                "The complete backup above is the full record. Restoring a backup isn't available on this screen yet.", style = MaterialTheme.typography.bodySmall)
    }
}
