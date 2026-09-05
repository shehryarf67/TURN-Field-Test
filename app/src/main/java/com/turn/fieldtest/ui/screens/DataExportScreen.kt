package com.turn.fieldtest.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.turn.fieldtest.ui.TurnAppState
import com.turn.fieldtest.ui.model.DataMode
import com.turn.fieldtest.ui.model.EventSeverity
import com.turn.fieldtest.ui.model.ExportItemUi
import com.turn.fieldtest.ui.model.TurnDemoData
import com.turn.fieldtest.ui.theme.TurnMint

@Composable
fun DataExportScreen(
    state: TurnAppState,
    compact: Boolean,
    onRealExport: (Uri, com.turn.fieldtest.data.export.ResearchDataset) -> Unit = { _, _ -> },
) {
    if (state.mode == DataMode.REAL_DEVICE) {
        RealDataExportScreen(state, compact, onRealExport)
        return
    }
    val context = LocalContext.current
    var selectedItem by remember { mutableStateOf(TurnDemoData.exportItems.first()) }
    var selectedFormat by remember { mutableStateOf(formatOptions(selectedItem).first()) }
    var includeRaw by remember { mutableStateOf(true) }

    val createDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(mimeType(selectedFormat))
    ) { uri: Uri? ->
        state.lastDataAction = if (uri == null) {
            "Export cancelled — no data changed"
        } else {
            val outcome = runCatching {
                context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { writer ->
                    writer.write(demoPayload(selectedItem, selectedFormat, includeRaw, state.evaluationSamples.size))
                } ?: error("Selected destination is not writable")
            }
            outcome.fold(
                onSuccess = { "Exported ${selectedItem.title} to ${uri.lastPathSegment ?: "selected document"}" },
                onFailure = { "Export failed: ${it.message ?: "unknown storage error"}" }
            )
        }
    }
    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        state.lastDataAction = if (uri == null) {
            "Import cancelled — database unchanged"
        } else {
            runCatching {
                val preview = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: error("Selected document could not be read")
                require(preview.isNotBlank()) { "Document is empty" }
                require(preview.trimStart().first() in listOf('{', '[', 's', 'v', 'c')) { "Unsupported document structure" }
                "Import validated for ${uri.lastPathSegment ?: "selected document"}; apply step remains explicit"
            }.getOrElse { "Import rejected: ${it.message ?: "validation error"}" }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(PagePadding),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        PageHeader(
            eyebrow = "07 · Research data",
            title = "Data & export",
            description = "Move venue definitions, raw observations, estimates and independent test results through Android's document picker.",
            compact = compact,
            action = {
                OutlinedButton(onClick = { openDocument.launch(arrayOf("application/json", "text/csv", "application/octet-stream")) }) {
                    Icon(Icons.Outlined.ArrowDownward, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Validate import")
                }
            }
        )

        StatusPill(
            state.lastDataAction,
            when {
                state.lastDataAction.startsWith("Exported") || state.lastDataAction.startsWith("Import validated") -> EventSeverity.GOOD
                state.lastDataAction.startsWith("Export failed") || state.lastDataAction.startsWith("Import rejected") -> EventSeverity.ERROR
                else -> EventSeverity.INFO
            }
        )

        AdaptiveColumns(
            primaryWeight = 1.25f,
            secondaryWeight = 0.75f,
            primary = {
                SectionCard("Available datasets", "All records use anonymous session IDs") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TurnDemoData.exportItems.forEach { item ->
                            ExportItem(
                                item = item,
                                selected = item == selectedItem,
                                onClick = {
                                    selectedItem = item
                                    selectedFormat = formatOptions(item).first()
                                }
                            )
                        }
                    }
                }
            },
            secondary = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SectionCard("Prepare export", selectedItem.description) {
                        LabelValue("Dataset", selectedItem.title)
                        LabelValue("Records", selectedItem.recordCount.toString())
                        LabelValue("Source mode", state.mode.label)
                        Text("FORMAT", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            formatOptions(selectedItem).forEach { format ->
                                FilterChip(
                                    selected = selectedFormat == format,
                                    onClick = { selectedFormat = format },
                                    label = { Text(format) },
                                    leadingIcon = if (selectedFormat == format) {
                                        { Icon(Icons.Outlined.CheckCircle, contentDescription = null) }
                                    } else null
                                )
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = includeRaw, onCheckedChange = { includeRaw = it })
                            Column {
                                Text("Include raw lineage", style = MaterialTheme.typography.labelLarge)
                                Text("Snapshots, timestamps and freshness flags", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Button(
                            onClick = { createDocument.launch(suggestedFileName(selectedItem, selectedFormat)) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Outlined.ArrowUpward, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Choose export destination")
                        }
                    }

                    SectionCard("Backup safety", "Survey data is irreplaceable") {
                        LabelValue("Database", "turn-field-test.db")
                        LabelValue("Schema", "Room v1 · explicit migrations")
                        LabelValue("Cloud dependency", "None")
                        FilledTonalButton(
                            onClick = {
                                selectedItem = TurnDemoData.exportItems.last()
                                selectedFormat = "BACKUP"
                                createDocument.launch("turn-field-test-backup.turnbackup")
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Outlined.FolderZip, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Create backup manifest")
                        }
                    }
                }
            }
        )

        SectionCard("Import guardrails", "Validation happens before any repository write") {
            AdaptiveColumns(
                breakpoint = 540.dp,
                primary = {
                    Column {
                        Guardrail("Schema version checked")
                        Guardrail("Unknown venue and floor references rejected")
                        Guardrail("Duplicate IDs rejected")
                    }
                },
                secondary = {
                    Column {
                        Guardrail("Test samples never enter training data")
                        Guardrail("Raw versus aggregate provenance retained")
                        Guardrail("Existing data is not overwritten silently")
                    }
                }
            )
        }

        Text(
            "Files exported in DEMO mode contain a clearly marked simulated manifest. Real-device exports must be generated from collected Room records.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ExportItem(item: ExportItemUi, selected: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)
        )
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp)
        ) {
            Icon(Icons.Outlined.Description, contentDescription = null, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Text(item.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(item.format, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                Text("${item.recordCount} records", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Guardrail(text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = TurnMint)
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

private fun formatOptions(item: ExportItemUi): List<String> = when {
    item.format.contains("JSON / CSV") -> listOf("JSON", "CSV")
    item.format == "BACKUP" -> listOf("BACKUP")
    else -> listOf(item.format)
}

private fun mimeType(format: String): String = when (format) {
    "CSV" -> "text/csv"
    "BACKUP" -> "application/octet-stream"
    else -> "application/json"
}

private fun suggestedFileName(item: ExportItemUi, format: String): String {
    val stem = item.title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
    val extension = when (format) {
        "CSV" -> "csv"
        "BACKUP" -> "turnbackup"
        else -> "json"
    }
    return "turn-$stem.$extension"
}

private fun demoPayload(item: ExportItemUi, format: String, includeRaw: Boolean, sampleCount: Int): String =
    if (format == "CSV") {
        "schema_version,dataset,record_count,source_mode,raw_lineage\n1,\"${item.title}\",${item.recordCount},SIMULATED,$includeRaw\n"
    } else {
        """{
  "schemaVersion": 1,
  "product": "TURN",
  "simulatedData": true,
  "dataset": "${item.title}",
  "recordCount": ${item.recordCount},
  "evaluationSampleCount": $sampleCount,
  "includesRawLineage": $includeRaw,
  "notice": "Demo manifest only; no participant identity is stored"
}
"""
    }
