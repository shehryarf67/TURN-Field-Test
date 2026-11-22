package com.turn.fieldtest.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.turn.fieldtest.core.FloorState
import com.turn.fieldtest.ui.LiveFloorUi
import com.turn.fieldtest.ui.TurnAppState
import com.turn.fieldtest.ui.barometerStatusText
import com.turn.fieldtest.ui.floorBannerOffersRelocalize
import com.turn.fieldtest.ui.floorBannerText
import com.turn.fieldtest.ui.floorChipText
import com.turn.fieldtest.ui.theme.TurnAmber
import com.turn.fieldtest.ui.theme.TurnMint

/** The confirmed floor in the live card's pill row (multi-floor sessions only); amber while uncertain. */
@Composable
internal fun FloorChip(floor: LiveFloorUi) {
    val colour = when (floor.state) {
        FloorState.CONFIRMED -> TurnMint
        FloorState.CHANGING, FloorState.UNCERTAIN -> TurnAmber
    }
    Surface(shape = RoundedCornerShape(50), color = colour.copy(alpha = 0.16f)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Surface(Modifier.size(7.dp), shape = CircleShape, color = colour) {}
            Text(floorChipText(floor), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

/** Announces a floor change for a few seconds, and stays while the floor is uncertain with a Relocalize action. */
@Composable
internal fun LiveFloorBanner(floor: LiveFloorUi, onRelocalize: () -> Unit) {
    val uncertain = floor.state == FloorState.UNCERTAIN
    Surface(shape = RoundedCornerShape(18.dp), shadowElevation = 4.dp,
        color = if (uncertain) TurnAmber.copy(alpha = 0.95f) else MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Layers, null, tint = if (uncertain) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary)
            Text(floorBannerText(floor), Modifier.weight(1f).padding(horizontal = 12.dp), style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            if (floorBannerOffersRelocalize(floor)) TextButton(onClick = onRelocalize) { Text("Relocalize") }
        }
    }
}

/** Options-sheet switches for floor tracking; locked while a session runs so one session never mixes modes. */
@Composable
internal fun FloorTrackingOptions(state: TurnAppState, live: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Multi-floor tracking")
            Text("Follow stairs and lifts through saved floor links", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(state.floorTrackingEnabled, { state.floorTrackingEnabled = it }, enabled = !live)
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Use unchecked floor links")
            Text("Links not checked on site move fewer hypotheses", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(state.useUncheckedFloorLinks, { state.useUncheckedFloorLinks = it }, enabled = !live && state.floorTrackingEnabled)
    }
    if (live) {
        state.liveFloorTracking?.let { Text(barometerStatusText(it.barometer), style = MaterialTheme.typography.bodySmall) }
        state.floorTrackingStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
