package com.turn.fieldtest.mapper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.turn.fieldtest.mapper.WalkMapperViewModel
import com.turn.fieldtest.mapper.data.ScanRepository
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.text.DateFormat
import java.util.Date

@Composable
internal fun FloorPlanDrafts(vm: WalkMapperViewModel, onPauseCamera: () -> Unit, onClose: () -> Unit) {
    val current = vm.doc ?: return
    var nameAction by remember { mutableStateOf<String?>(null) }
    var draftName by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    var clearCurrent by remember { mutableStateOf(false) }
    if (clearCurrent) {
        AlertDialog(
            onDismissRequest = { clearCurrent = false },
            title = { Text("Clear ${current.name}?") },
            text = { Text("This permanently removes all rooms, walls, doorways, camera sections, and measurements from this draft. Its name stays, and any published floor remains in Buildings.") },
            confirmButton = {
                TextButton(enabled = !vm.busy, onClick = {
                    onPauseCamera()
                    vm.clearDraft()
                    onClose()
                }) { Text("Clear plan", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { clearCurrent = false }) { Text("Keep plan") } },
        )
        return
    }
    val deletion = deleteTarget
    if (deletion != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete ${deletion.second}?") },
            text = { Text("This permanently deletes the editable draft and its measurements. Any floor already saved for navigation stays in Buildings.") },
            confirmButton = {
                TextButton(enabled = !vm.busy, onClick = {
                    onPauseCamera()
                    vm.deleteDraft(deletion.first)
                    deleteTarget = null
                }) { Text("Delete draft", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Keep draft") } },
        )
        return
    }
    val creating = nameAction == "new"
    if (nameAction != null) {
        AlertDialog(
            onDismissRequest = { nameAction = null },
            title = { Text(if (creating) "New floor plan" else "Rename floor plan") },
            text = {
                OutlinedTextField(draftName, { draftName = it.take(80) }, singleLine = true,
                    label = { Text("Floor plan name") }, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                Button(enabled = draftName.isNotBlank() && !vm.busy, onClick = {
                    if (creating) {
                        onPauseCamera()
                        vm.newDraft(draftName)
                        onClose()
                    } else {
                        vm.name(draftName.trim())
                        nameAction = null
                    }
                }) { Text(if (creating) "Create draft" else "Save name") }
            },
            dismissButton = { TextButton(onClick = { nameAction = null }) { Text("Cancel") } },
        )
        return
    }
    val previous = vm.availableDrafts.filterNot { it.id == current.id }
    val listHeight = (LocalConfiguration.current.screenHeightDp * 0.45f).coerceIn(140f, 360f).dp
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Floor plans") },
        text = {
            LazyColumn(Modifier.heightIn(max = listHeight), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Text("Editing", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(current.name.ifBlank { "Mapped floor" }, style = MaterialTheme.typography.titleMedium)
                    Text("${current.rooms.size} rooms · ${vm.saveStatus}", style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = { draftName = current.name; nameAction = "rename" }, enabled = !vm.busy) { Text("Rename") }
                        TextButton(onClick = { clearCurrent = true }, enabled = !vm.busy &&
                            (current.rooms.isNotEmpty() || current.walls.isNotEmpty() || current.segments.isNotEmpty())) { Text("Clear plan") }
                    }
                    Row {
                        TextButton(onClick = { deleteTarget = current.id to current.name }, enabled = !vm.busy &&
                            (current.updatedAt > 0 || vm.availableDrafts.any { it.id == current.id })) { Text("Delete draft") }
                    }
                    HorizontalDivider()
                }
                item {
                    Text("Previous drafts for this floor", style = MaterialTheme.typography.titleSmall)
                    if (previous.isEmpty()) Text("No previous drafts yet.")
                }
                items(previous, key = { it.id }) { row ->
                    val title = remember(row.documentJson) {
                        runCatching { ScanRepository.json.parseToJsonElement(row.documentJson).jsonObject["name"]?.jsonPrimitive?.contentOrNull }
                            .getOrNull()?.takeIf { it.isNotBlank() } ?: "Mapped floor"
                    }
                    Column {
                        Text(title, style = MaterialTheme.typography.titleSmall)
                        if (row.updatedAtEpochMillis > 0) {
                            val date = remember(row.updatedAtEpochMillis) { DateFormat.getDateTimeInstance().format(Date(row.updatedAtEpochMillis)) }
                            Text(date, style = MaterialTheme.typography.bodySmall)
                        }
                        Row {
                            OutlinedButton(enabled = !vm.busy, onClick = {
                                onPauseCamera()
                                vm.openDraft(row.id)
                                onClose()
                            }) { Text("Open") }
                            TextButton(enabled = !vm.busy, onClick = { deleteTarget = row.id to title }) { Text("Delete draft") }
                        }
                        HorizontalDivider()
                    }
                }
                item { Text(vm.status, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(enabled = !vm.busy, onClick = { draftName = ""; nameAction = "new" }) { Text("New floor plan") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Done") } },
    )
}
