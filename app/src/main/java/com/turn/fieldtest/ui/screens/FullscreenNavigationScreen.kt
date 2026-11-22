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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
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
    val confidence = navigationConfidence(state, age, now)
    var headerHeight by remember {mutableIntStateOf(0)}
    val topInset=with(LocalDensity.current) {headerHeight.toDp()}
    val safe=WindowInsets.safeDrawing.asPaddingValues()
    val direction=LocalLayoutDirection.current
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val landscape=maxWidth>maxHeight
        val mapPadding=if(landscape) PaddingValues(start=340.dp+safe.calculateLeftPadding(direction),top=safe.calculateTopPadding()+12.dp,
            end=safe.calculateRightPadding(direction)+12.dp,bottom=safe.calculateBottomPadding()+12.dp)
            else PaddingValues(top=topInset,bottom=124.dp+safe.calculateBottomPadding())
        NavigationMap(state, Modifier.fillMaxSize(), showPosition = true, showGraph = state.debugOverlays,
            rotateToFit=true,roomLabels=true,viewportPadding=mapPadding,
            controlsPadding=PaddingValues(end=12.dp+safe.calculateRightPadding(direction),bottom=12.dp+safe.calculateBottomPadding()))
        Row(Modifier.align(if(landscape) Alignment.TopStart else Alignment.TopCenter)
            .then(if(landscape) Modifier.width(340.dp) else Modifier.fillMaxWidth())
            .onSizeChanged {headerHeight=it.height}.windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            Surface(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f), contentColor = MaterialTheme.colorScheme.onSurface, shape = MaterialTheme.shapes.medium) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(state.floorName + com.turn.fieldtest.ui.fullscreenFloorSuffix(state.liveFloorTracking), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (state.debugOverlays && state.liveRunning) Text("Steps ${state.realLiveStepCount} / ${state.realLiveDetectedSteps} detected",
                        style = MaterialTheme.typography.bodySmall)
                    Text(confidence + (state.realLiveUncertaintyMetres?.let { " · ± %.1f m".format(it) } ?: ""),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    if(state.debugOverlays) Text(if (state.realHeadingAligned) "Heading %.0f° on map".format((state.realLiveHeadingDegrees + 360.0) % 360.0)
                        else state.realHeadingStatus, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall)
                    (state.destinations.firstOrNull { it.id == state.selectedDestinationId }?.name
                        ?: state.liveDestination?.takeIf { state.liveRunning && it.id == state.selectedDestinationId }?.name)?.let { name ->
                        Text(name + (state.remainingDistanceMetres?.let { " · %.1f m remaining".format(it) } ?: ""),
                            maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        Text(if (age != null && age > 20) "Route based on last known position" else state.routeStatus,
                            maxLines = 2, style = MaterialTheme.typography.bodySmall)
                    }
                    // Phase 3: near the link of a route to another floor, the same banner text as the explore screen.
                    val leg = state.liveFloorRoute?.takeIf { state.liveRunning }?.let { com.turn.fieldtest.ui.floorLeg(it, state.activeProject?.floorId) }
                    com.turn.fieldtest.ui.connectorBannerText(leg, state.realLivePosition?.let {
                        com.turn.fieldtest.core.MetricPoint(it.x.toDouble(), it.y.toDouble()) })?.let {
                        Text(it, maxLines = 2, style = MaterialTheme.typography.bodyMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            if(!landscape) Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f), contentColor = MaterialTheme.colorScheme.onSurface, shape = MaterialTheme.shapes.medium) {
                TextButton(onClick = { state.navigationFullscreen = false }) { Text("Exit full screen") }
            }
        }
        if(landscape) Surface(Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp),
            color=MaterialTheme.colorScheme.surface,shape=MaterialTheme.shapes.small) {
            TextButton(onClick={state.navigationFullscreen=false}) {Text("Exit full screen")}
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

internal fun navigationConfidence(state: TurnAppState, ageSeconds: Long?, nowEpochMillis: Long = System.currentTimeMillis()): String =
    baseNavigationConfidence(state, ageSeconds, nowEpochMillis) +
        // Multi-floor sessions only: single-floor sessions have no floor tracking state.
        if (state.liveRunning && state.liveFloorTracking?.state == com.turn.fieldtest.core.FloorState.UNCERTAIN) " · Floor uncertain" else ""

private fun baseNavigationConfidence(state: TurnAppState, ageSeconds: Long?, nowEpochMillis: Long): String = when {
    !state.liveRunning -> "Stopped"
    !state.realLiveInitialized -> "Position unavailable"
    !state.realHeadingAligned -> "Heading unavailable · movement paused"
    state.realHeadingSampleEpochMillis?.let { nowEpochMillis - it > 2_000L } != false -> "Heading stale · movement paused"
    ageSeconds != null && ageSeconds > 20 -> "Last estimate ${ageSeconds}s ago"
    state.realLiveConfidence >= 0.65 -> "Tracking"
    else -> "Low confidence"
}
