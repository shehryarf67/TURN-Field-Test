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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.turn.fieldtest.data.export.ResearchDataset
import com.turn.fieldtest.ui.TurnAppState

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
        PageHeader("07 · Research data", "Data & export",
            "Save physical Room records through Android's document picker.", compact)
        Text(state.lastDataAction, style = MaterialTheme.typography.bodyMedium)
        SectionCard("Complete database", "Keep a JSON backup after every field session") {
            Text("Includes all database tables, raw readings, fingerprints, geometry, sessions and estimates. Image URIs reference phone-local assets; copy floor-plan images separately.")
            Button(
                onClick = { backup.launch("turn-database-${System.currentTimeMillis()}.json") },
                enabled = !state.realExportBusy && !state.surveyRunning && !state.liveRunning,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save complete database JSON") }
        }
        SectionCard("Analysis CSV files", "Every entity field and lineage ID is included") {
            ResearchDataset.entries.filter { it != ResearchDataset.BACKUP }.forEach { dataset ->
                val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) {
                    if (it != null) onExport(it, dataset)
                    else state.lastDataAction = "Export cancelled; stored records unchanged"
                }
                Button(
                    onClick = { picker.launch("turn-${dataset.name.lowercase()}-${System.currentTimeMillis()}.csv") },
                    enabled = !state.realExportBusy && !state.surveyRunning && !state.liveRunning,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(dataset.label) }
            }
        }
        Text("Stop Survey and Live locate before exporting. Empty datasets produce a header-only CSV. CSV files join through session and reference-point IDs; save the complete JSON as the full record. Import/restore is not yet exposed in this screen.")
    }
}
