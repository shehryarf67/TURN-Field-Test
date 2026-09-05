package com.turn.fieldtest.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.turn.fieldtest.ui.TurnAppState
import com.turn.fieldtest.ui.model.EventSeverity
import com.turn.fieldtest.ui.model.TurnDemoData
import com.turn.fieldtest.ui.model.TurnDestination
import com.turn.fieldtest.ui.model.VenueSummary

@Composable
fun VenuesScreen(state: TurnAppState, compact: Boolean) {
    if (state.mode == com.turn.fieldtest.ui.model.DataMode.REAL_DEVICE) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(PagePadding),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            PageHeader("01 · Setup", "Physical pilot workspace",
                "This build uses one fixed ground-floor workspace. Verify its metric geometry before surveying.", compact)
            SectionCard("Computing Block Pilot", "VEN-CS-01 · Ground floor FL-G") {
                LabelValue("Canvas", "42 × 28 metres · lower-left origin")
                LabelValue("Reference points loaded", state.referencePoints.size.toString())
                LabelValue("Wall segments loaded", state.draftWalls.size.toString())
                Text(state.editorStatus)
                Button(onClick = { state.selectDestination(TurnDestination.FLOOR_EDITOR) }) { Text("Open metric editor") }
            }
            Text("Multiple venue/floor creation, image import and calibrated backgrounds are still pending. Demo workspace coverage and readiness figures are examples and do not describe physical data.")
        }
        return
    }
    var addVenueDialog by remember { mutableStateOf(false) }
    var addFloorDialog by remember { mutableStateOf(false) }
    var setupMessage by remember { mutableStateOf("Venue definition stored locally · schema v1") }
    val selectedVenue = TurnDemoData.venues.firstOrNull { it.id == state.selectedVenueId } ?: TurnDemoData.venues.first()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(PagePadding),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        PageHeader(
            eyebrow = "01 · Setup",
            title = "Venues & floors",
            description = "Build a metric map workspace before surveying. Imported drawings remain replaceable visual backgrounds.",
            compact = compact,
            action = {
                Button(onClick = { addVenueDialog = true }) {
                    Icon(Icons.Outlined.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Create venue")
                }
            }
        )

        AdaptiveColumns(
            primaryWeight = 0.9f,
            secondaryWeight = 1.5f,
            primary = {
                SectionCard(
                    title = "Local venues",
                    subtitle = "${TurnDemoData.venues.size} research workspaces"
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TurnDemoData.venues.forEach { venue ->
                            VenueListItem(
                                venue = venue,
                                selected = venue.id == state.selectedVenueId,
                                onClick = {
                                    state.selectedVenueId = venue.id
                                    state.selectedFloorId = venue.floors.first().id
                                }
                            )
                        }
                    }
                }
            },
            secondary = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SectionCard(
                        title = selectedVenue.name,
                        subtitle = "${selectedVenue.id} · ${selectedVenue.address}",
                        trailing = {
                            Row {
                                IconButton(
                                    onClick = { setupMessage = "Rename form ready for ${selectedVenue.id}" },
                                    modifier = Modifier.semantics { contentDescription = "Rename ${selectedVenue.name}" }
                                ) { Icon(Icons.Outlined.Edit, contentDescription = null) }
                                IconButton(
                                    onClick = { setupMessage = "Delete requires confirmation and cascades only after explicit approval" },
                                    modifier = Modifier.semantics { contentDescription = "Delete ${selectedVenue.name}" }
                                ) { Icon(Icons.Outlined.DeleteOutline, contentDescription = null) }
                            }
                        }
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            selectedVenue.floors.forEach { floor ->
                                FloorRow(
                                    floorName = floor.name,
                                    level = floor.level,
                                    size = "%.0f × %.0f m".format(floor.widthMetres, floor.heightMetres),
                                    points = floor.referencePoints,
                                    coverage = floor.fingerprintCoveragePercent,
                                    selected = floor.id == state.selectedFloorId,
                                    onClick = { state.selectedFloorId = floor.id }
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { addFloorDialog = true }) {
                                    Icon(Icons.Outlined.Add, contentDescription = null)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Add floor")
                                }
                                FilledTonalButton(onClick = {
                                    state.selectDestination(TurnDestination.FLOOR_EDITOR)
                                }) {
                                    Icon(Icons.Outlined.Map, contentDescription = null)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Open editor")
                                }
                            }
                        }
                    }

                    SectionCard(
                        title = "Selected floor setup",
                        subtitle = "Metric coordinates are authoritative; pixels are only presentation"
                    ) {
                        AdaptiveColumns(
                            breakpoint = 520.dp,
                            primary = {
                                Column {
                                    LabelValue("Plan background", "hand_sketch_ground.jpg")
                                    LabelValue("Real dimensions", "42.0 m × 28.0 m")
                                    LabelValue("Coordinate origin", "Lower-left · (0.0, 0.0) m")
                                    LabelValue("Scale calibration", "8.00 m / 612 px")
                                    LabelValue("Calibration residual", "0.06 m")
                                }
                            },
                            secondary = {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        onClick = { setupMessage = "Storage Access Framework image picker requested" },
                                        modifier = Modifier.fillMaxWidth()
                                    ) { Text("Import PNG / JPEG") }
                                    OutlinedButton(
                                        onClick = { setupMessage = "Choose two image points, then enter measured distance" },
                                        modifier = Modifier.fillMaxWidth()
                                    ) { Text("Calibrate scale") }
                                    OutlinedButton(
                                        onClick = { setupMessage = "Coordinate-origin selector ready" },
                                        modifier = Modifier.fillMaxWidth()
                                    ) { Text("Define origin") }
                                }
                            }
                        )
                    }
                }
            }
        )

        StatusPill(setupMessage, EventSeverity.GOOD)

        AdaptiveColumns(
            primary = {
                SectionCard("Geometry readiness", subtitle = "Ground floor") {
                    ReadinessRow("Walkable corridor polygons", "3 regions", 1f)
                    ReadinessRow("Walls / boundaries", "18 segments", 0.92f)
                    ReadinessRow("Doors and junctions", "7 configured", 0.78f)
                    ReadinessRow("Vertical transitions", "2 connected", 1f)
                }
            },
            secondary = {
                SectionCard("Survey readiness", subtitle = "Ground floor") {
                    ReadinessRow("Reference points", "14 placed", 1f)
                    ReadinessRow("Inside walkable space", "13 valid · 1 warning", 0.93f)
                    ReadinessRow("QR anchors", "2 configured", 1f)
                    ReadinessRow("Fingerprint coverage", "12 / 14 points", 0.86f)
                }
            }
        )

        Spacer(Modifier.height(12.dp))
    }

    if (addVenueDialog) {
        VenueDialog(
            title = "Create venue",
            confirmLabel = "Create locally",
            onDismiss = { addVenueDialog = false },
            onConfirm = { name ->
                setupMessage = "Venue ‘$name’ created in UI draft"
                addVenueDialog = false
            }
        )
    }
    if (addFloorDialog) {
        VenueDialog(
            title = "Add floor",
            confirmLabel = "Add floor",
            initialName = "Second floor",
            onDismiss = { addFloorDialog = false },
            onConfirm = { name ->
                setupMessage = "Floor ‘$name’ added to ${selectedVenue.id} draft"
                addFloorDialog = false
            }
        )
    }
}

@Composable
private fun VenueListItem(venue: VenueSummary, selected: Boolean, onClick: () -> Unit) {
    val color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(color)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Text(venue.floors.size.toString(), fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
        }
        Column(Modifier.weight(1f)) {
            Text(venue.name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${venue.floors.size} floors · ${venue.lastEdited}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FloorRow(
    floorName: String,
    level: Int,
    size: String,
    points: Int,
    coverage: Int,
    selected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)
        ),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) { Text(if (level == 0) "G" else level.toString(), fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.secondary) }
            Column(Modifier.weight(1f)) {
                Text(floorName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text("$size · $points reference points", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("$coverage%", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Text("surveyed", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ReadinessRow(label: String, value: String, progress: Float) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.width(14.dp))
        Text(value, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun VenueDialog(
    title: String,
    confirmLabel: String,
    initialName: String = "",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Saved on this device. No participant identity is collected.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(name.ifBlank { "Untitled" }) }) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
