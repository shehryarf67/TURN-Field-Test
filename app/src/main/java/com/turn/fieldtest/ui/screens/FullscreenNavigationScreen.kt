package com.turn.fieldtest.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.turn.fieldtest.ui.NavigationViewOrientation
import com.turn.fieldtest.ui.TurnAppState
import kotlinx.coroutines.delay

@Composable
fun FullscreenNavigationScreen(state: TurnAppState) {
    BackHandler { state.navigationFullscreen = false }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.liveRunning) {
        while (state.liveRunning) { now = System.currentTimeMillis(); delay(1000) }
    }
    val age = state.liveEstimateEpochMillis?.let { (now - it).coerceAtLeast(0) / 1000 }
    val confidence = navigationConfidence(state, age)
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        NavigationMap(state, Modifier.fillMaxSize(), showPosition = true, showGraph = state.debugOverlays)
        Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            Surface(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f), contentColor = MaterialTheme.colorScheme.onSurface, shape = MaterialTheme.shapes.medium) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(state.floorName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(confidence + (state.realLiveUncertaintyMetres?.let { " · ± %.1f m".format(it) } ?: ""),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    state.destinations.firstOrNull { it.id == state.selectedDestinationId }?.let { poi ->
                        Text(poi.name + (state.remainingDistanceMetres?.let { " · %.1f m remaining".format(it) } ?: ""),
                            maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        Text(if (age != null && age > 20) "Route based on last known position" else state.routeStatus,
                            maxLines = 2, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f), contentColor = MaterialTheme.colorScheme.onSurface, shape = MaterialTheme.shapes.medium) {
                TextButton(onClick = { state.navigationFullscreen = false }) { Text("Exit full screen") }
            }
        }
        Row(Modifier.align(Alignment.BottomStart).windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(start = 12.dp, bottom = 72.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NavigationViewOrientation.entries.forEach { mode ->
                FilterChip(selected = state.navigationViewOrientation == mode,
                    onClick = { state.navigationViewOrientation = mode },
                    colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)),
                    label = { Text(when (mode) {
                        NavigationViewOrientation.AUTO -> "Auto rotate"
                        NavigationViewOrientation.PORTRAIT -> "Portrait"
                        NavigationViewOrientation.LANDSCAPE -> "Landscape"
                    }) })
            }
        }
    }
}

internal fun navigationConfidence(state: TurnAppState, ageSeconds: Long?): String = when {
    !state.liveRunning -> "Stopped"
    !state.realLiveInitialized -> "Position unavailable"
    ageSeconds != null && ageSeconds > 20 -> "Last estimate ${ageSeconds}s ago"
    state.realLiveConfidence >= 0.65 -> "Tracking"
    else -> "Low confidence"
}
