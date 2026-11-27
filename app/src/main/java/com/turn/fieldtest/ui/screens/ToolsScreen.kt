package com.turn.fieldtest.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.FactCheck
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.turn.fieldtest.ui.TurnAppActions
import com.turn.fieldtest.ui.TurnAppState
import com.turn.fieldtest.ui.model.DataMode
import com.turn.fieldtest.ui.model.TurnDestination

@Composable
fun ToolsScreen(state: TurnAppState, actions: TurnAppActions) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Set up a floor, then explore it", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Work through the numbered steps in order. Each floor of a building is set up on its own.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!state.realMapReady || state.activeProject == null) {
            ToolCard("Choose a building", "Open a saved floor, or add a building to begin.", Icons.Outlined.Apartment,
                onClick = { state.selectDestination(TurnDestination.VENUES) })
        } else {
            Text("Open floor: ${state.venueName} · ${state.floorName}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            ToolCard(MAP_WITH_PHONE, "Step by step: walk around with the camera and tap room corners. Saves a new floor plan.",
                Icons.Outlined.ViewInAr, enabled = state.canEditMap && !state.hasUnsavedMapChanges && state.mode == DataMode.REAL_DEVICE,
                onClick = actions.onWalkMapperRequested)
            if (state.hasUnsavedMapChanges) Text("Save your changes in Map setup first.", style = MaterialTheme.typography.bodySmall)
            ToolCard("1 · Map setup", if (state.hasUnsavedMapChanges) "You have unsaved changes · save them before recording Wi-Fi"
                else "Check the floor plan and its size, add rooms and places, and draw walking paths", Icons.Outlined.DesignServices,
                onClick = { state.selectDestination(TurnDestination.FLOOR_EDITOR) })
            ToolCard("2 · Record Wi-Fi", "Stand at each survey point and record the Wi-Fi there · " +
                "${state.surveyPointProgress.values.count { it.status == "COMPLETED" }} of ${state.referencePoints.size} points done",
                Icons.Outlined.Wifi, onClick = { state.selectDestination(TurnDestination.SURVEY) })
            ToolCard("3 · Explore & navigate", "Choose where you are and where to go, then follow the directions", Icons.Outlined.NearMe,
                onClick = { state.selectDestination(TurnDestination.LIVE_LOCATE) })
            if (state.projects.count { it.venueId == state.activeProject?.venueId } > 1)
                ToolCard("Link floors", "Stairs and lifts between floors, floor heights and lining floors up · ${state.floorConnectors.size} " +
                    if (state.floorConnectors.size == 1) "link" else "links", Icons.Outlined.Stairs,
                    enabled = !state.sessionLocked, onClick = { state.selectDestination(TurnDestination.FLOOR_LINKS) })
        }
        HorizontalDivider()
        Text("More", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        listOf(Triple(TurnDestination.SETTINGS, Icons.Outlined.Settings, "Appearance, map options and how positioning works"),
            Triple(TurnDestination.DATA_EXPORT, Icons.Outlined.FileDownload, "Back up, restore and export your data"),
            Triple(TurnDestination.EVALUATE, Icons.AutoMirrored.Outlined.FactCheck, "Measure how accurate positioning is at known points"),
            Triple(TurnDestination.RADIO_DIAGNOSTICS, Icons.Outlined.Sensors, "Technical: Wi-Fi, sensors, raw recordings and markers")).forEach { (destination, icon, detail) ->
            ToolCard(destination.title, detail, icon, onClick = { state.selectDestination(destination) })
        }
    }
}

/** Name of the step-by-step camera mapper, used on every screen that opens it. */
internal const val MAP_WITH_PHONE = "Map a floor with your phone"

@Composable
internal fun ToolCard(title: String, detail: String, icon: ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Surface(Modifier.size(44.dp),shape=MaterialTheme.shapes.small,color=MaterialTheme.colorScheme.primaryContainer) {
                Box(contentAlignment=Alignment.Center) {Icon(icon, null, tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)}
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Outlined.ChevronRight, null)
        }
    }
}
