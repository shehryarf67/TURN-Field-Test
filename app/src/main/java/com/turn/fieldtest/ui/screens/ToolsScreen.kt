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
        Text("Prepare a floor. Then explore it.", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        if (!state.realMapReady || state.activeProject == null) {
            ToolCard("Choose a building", "Open a saved floor or create a new one to begin.", Icons.Outlined.Apartment,
                onClick = { state.selectDestination(TurnDestination.VENUES) })
        } else {
            Text("${state.venueName} · ${state.floorName}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            ToolCard("1 · Map setup", if (state.hasUnsavedMapChanges) "Unsaved edits · Save your map before surveying"
                else "Import or scan, calibrate, draw paths and add places", Icons.Outlined.DesignServices,
                onClick = { state.selectDestination(TurnDestination.FLOOR_EDITOR) })
            ToolCard("2 · Wi-Fi survey", "${state.referencePoints.size} reference points · ${state.surveyPointProgress.values.count { it.status == "COMPLETED" }} completed",
                Icons.Outlined.Wifi, onClick = { state.selectDestination(TurnDestination.SURVEY) })
            ToolCard("3 · Explore & navigate", "Choose a start and destination, then confirm your direction", Icons.Outlined.NearMe,
                onClick = { state.selectDestination(TurnDestination.LIVE_LOCATE) })
            ToolCard("Create a map with your phone", "Guided AR capture or measured drawing · review and verify before use",
                Icons.Outlined.ViewInAr, enabled = state.canEditMap && !state.hasUnsavedMapChanges && state.mode == DataMode.REAL_DEVICE,
                onClick = actions.onWalkMapperRequested)
            if (state.hasUnsavedMapChanges) Text("Save your map in Map setup before opening AR mapping.", style = MaterialTheme.typography.bodySmall)
        }
        HorizontalDivider()
        Text("Research & settings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        listOf(Triple(TurnDestination.RADIO_DIAGNOSTICS, Icons.Outlined.Sensors, "Wi-Fi and sensor diagnostics"),
            Triple(TurnDestination.EVALUATE, Icons.AutoMirrored.Outlined.FactCheck, "Independent checkpoint testing"),
            Triple(TurnDestination.DATA_EXPORT, Icons.Outlined.FileDownload, "Recordings, exports and backups"),
            Triple(TurnDestination.SETTINGS, Icons.Outlined.Settings, "Appearance, data mode and algorithms")).forEach { (destination, icon, detail) ->
            ToolCard(destination.title, detail, icon, onClick = { state.selectDestination(destination) })
        }
    }
}

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
