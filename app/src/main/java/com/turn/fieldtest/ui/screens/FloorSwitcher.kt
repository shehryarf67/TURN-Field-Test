package com.turn.fieldtest.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apartment
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.turn.fieldtest.ui.TurnAppActions
import com.turn.fieldtest.ui.TurnAppState
import com.turn.fieldtest.ui.model.TurnDestination

/**
 * Current floor as a chip; opens the building's other floors. Switching reopens the project on that floor,
 * so it is refused while a session runs or the map has unsaved edits (both would be lost or mixed).
 */
@Composable
internal fun FloorSwitcher(state: TurnAppState, actions: TurnAppActions, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val venueId = state.activeProject?.venueId
    val floors = state.projects.filter { it.venueId == venueId }.sortedByDescending { it.floorLevel }
    val locked = state.sessionLocked || state.liveRunning || state.liveStarting || state.surveyRunning || state.projectBusy
    Box(modifier) {
        Surface(onClick = { open = true }, enabled = !locked, shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.semantics { contentDescription = "Floor ${state.floorName}, change floor" }) {
            Row(Modifier.padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(state.floorName, Modifier.widthIn(max = 96.dp), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!locked) Icon(Icons.Outlined.UnfoldMore, null, Modifier.size(16.dp))
            }
        }
        DropdownMenu(open, { open = false }) {
            if (state.hasUnsavedMapChanges) Text("Save map changes before switching floors", Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            floors.forEach { f ->
                val current = f.context == state.activeProject
                DropdownMenuItem(
                    text = { Text("${levelBadge(f.floorLevel)} · ${f.floorName}", fontWeight = if (current) FontWeight.Bold else FontWeight.Normal) },
                    trailingIcon = { if (current) Icon(Icons.Outlined.Check, "Current floor") },
                    enabled = !current && !state.hasUnsavedMapChanges,
                    onClick = { open = false; actions.onProjectOpened(f.context, false) },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(text = { Text("All buildings") }, leadingIcon = { Icon(Icons.Outlined.Apartment, null) },
                onClick = { open = false; state.selectDestination(TurnDestination.VENUES) })
        }
    }
}
