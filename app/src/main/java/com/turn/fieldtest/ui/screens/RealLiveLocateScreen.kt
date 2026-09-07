package com.turn.fieldtest.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.turn.fieldtest.ui.TurnAppState
import com.turn.fieldtest.ui.model.EventSeverity
import com.turn.fieldtest.ui.theme.TurnAmber
import com.turn.fieldtest.ui.theme.TurnBlue
import com.turn.fieldtest.ui.theme.TurnCyan
import com.turn.fieldtest.ui.theme.TurnMint
import com.turn.fieldtest.ui.theme.TurnRed
import kotlin.math.roundToInt

/**
 * Real-mode surface. Every value here comes from WifiManager, Android sensors, Room, or the
 * deterministic positioning core; TurnDemoData is intentionally not referenced.
 */
@Composable
internal fun RealLiveLocateScreen(
    state: TurnAppState,
    compact: Boolean,
    onToggle: () -> Unit,
    onScan: () -> Unit,
    onRelocalize: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(PagePadding),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PageHeader(
            eyebrow = "05 · Physical positioning",
            title = "Live locate",
            description = "Fresh Wi-Fi supplies absolute fixes; step and relative-heading sensors propagate the particle cloud between scans.",
            compact = compact,
            action = {
                Button(onClick = onToggle) {
                    Icon(
                        if (state.liveRunning) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                        contentDescription = null,
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(if (state.liveRunning) "Stop" else "Start physical session")
                }
            },
        )

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusPill(if (state.realLiveInitialized) "absolute fix acquired" else "awaiting absolute fix", if (state.realLiveInitialized) EventSeverity.GOOD else EventSeverity.WARNING)
            StatusPill(state.lastCorrectionType, EventSeverity.INFO)
            StatusPill(if (state.realLiveFloorId == null) "floor uncertain" else state.floorName, if (state.realLiveFloorId == null) EventSeverity.WARNING else EventSeverity.GOOD)
            StatusPill("NO SIMULATED FALLBACK", EventSeverity.GOOD)
        }

        StatusCard(state)

        AdaptiveColumns(
            breakpoint = 900.dp,
            primaryWeight = 2.1f,
            secondaryWeight = 1f,
            primary = {
                SectionCard(
                    title = "${if (state.realLiveFloorId == null) "Unresolved floor" else state.floorName} · physical trace",
                    subtitle = "Metric coordinates · lower-left origin · +x initial direction",
                    trailing = {
                        StatusPill(
                            if (state.liveRunning) "foreground tracking" else "stopped",
                            if (state.liveRunning) EventSeverity.GOOD else EventSeverity.WARNING,
                        )
                    },
                ) {
                    RealMapLayerControls(state)
                    Spacer(Modifier.height(10.dp))
                    RealPositionCanvas(state, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    RealLegend()
                }
            },
            secondary = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SectionCard("Position output", "Ground truth is never used here") {
                        val position = state.realLivePosition
                        LabelValue("Fused x / y", position?.let { "%.2f m / %.2f m".format(it.x, it.y) } ?: "no estimate")
                        LabelValue("Wi-Fi x / y", state.realWifiPosition?.let { "%.2f m / %.2f m".format(it.x, it.y) } ?: "no estimate")
                        LabelValue("Raw PDR x / y", state.realRawPdrPosition?.let { "%.2f m / %.2f m".format(it.x, it.y) } ?: "not initialized")
                        LabelValue("Floor confidence", "${(state.realLiveFloorConfidence * 100).roundToInt()}%")
                        LabelValue("Confidence radius", state.realLiveUncertaintyMetres?.let { "%.2f m".format(it) } ?: "unknown")
                    }
                    SectionCard("Physical controls", "Android may throttle active Wi-Fi requests") {
                        OutlinedButton(onClick = onScan, enabled = state.liveRunning, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Outlined.Refresh, contentDescription = null)
                            Spacer(Modifier.width(7.dp))
                            Text("Request Wi-Fi scan")
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = onRelocalize, enabled = state.liveRunning, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Outlined.CenterFocusStrong, contentDescription = null)
                            Spacer(Modifier.width(7.dp))
                            Text("Relocalize on next fresh scan")
                        }
                        Spacer(Modifier.height(9.dp))
                        Text(
                            "At initialization, point the phone along map +x. Relative turns then update heading; magnetometer north is not assumed.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
        )

        AdaptiveColumns(
            primaryWeight = 1.2f,
            secondaryWeight = 1f,
            primary = {
                SectionCard("Live telemetry", state.realLiveSessionLabel) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetricCard("Steps", state.realLiveStepCount.toString(), Modifier.weight(1f), "accepted")
                        MetricCard("Distance", "%.1f m".format(state.realLiveDistanceMetres), Modifier.weight(1f), "raw PDR")
                        MetricCard("Heading", "%.0f°".format(state.realLiveHeadingDegrees), Modifier.weight(1f), state.realLiveHeadingSource)
                    }
                    Spacer(Modifier.height(10.dp))
                    LabelValue("Rejected particle moves", state.realLiveMapRejectedCount.toString())
                    LabelValue("Particle sample shown", state.realParticleCloud.size.toString())
                    LabelValue("Wi-Fi AP count", state.realWifiAccessPoints.size.toString())
                    LabelValue("Latest result age", state.realWifiNewestAgeMillis?.let { "${it / 1000.0} s" } ?: "no result")
                }
            },
            secondary = {
                SectionCard("Wi-Fi evidence", "Weighted kNN uses BSSID, not SSID") {
                    LabelValue("k", state.knnK.toString())
                    LabelValue("Missing RSSI", "${state.missingRssi} dBm")
                    LabelValue("Match distance", state.realWifiMatchDistance?.let { "%.2f dB RMS".format(it) } ?: "no match")
                    Text("Nearest fingerprints", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    if (state.realNearestFingerprintIds.isEmpty()) {
                        Text("No neighbours yet", style = MaterialTheme.typography.bodySmall)
                    } else {
                        state.realNearestFingerprintIds.forEach { id ->
                            Text("• $id", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    LabelValue("Relocalizations", state.relocalizationCount.toString())
                }
            },
        )

        BleDisabledBanner()
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun StatusCard(state: TurnAppState) {
    val color = when {
        state.realLiveStatus.contains("lost", ignoreCase = true) -> TurnRed
        state.realLiveInitialized -> TurnMint
        else -> TurnAmber
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.12f)),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(state.realLiveStatus, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = color)
            LinearProgressIndicator(
                progress = { state.realLiveConfidence.toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = color,
            )
        }
    }
}

@Composable
private fun RealMapLayerControls(state: TurnAppState) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        FilterChip(selected = state.showRawPdr, onClick = { state.showRawPdr = !state.showRawPdr }, label = { Text("Raw PDR") })
        FilterChip(selected = state.showWifiFixes, onClick = { state.showWifiFixes = !state.showWifiFixes }, label = { Text("Wi-Fi fixes") })
        FilterChip(selected = true, onClick = {}, label = { Text("Fused") })
        FilterChip(selected = state.showParticles, onClick = { state.showParticles = !state.showParticles }, label = { Text("Particles") })
    }
}

@Composable
private fun RealPositionCanvas(state: TurnAppState, modifier: Modifier = Modifier) {
    val surface = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    val outline = MaterialTheme.colorScheme.outlineVariant
    val wallColor = MaterialTheme.colorScheme.onSurface
    val floorWidth = state.floorWidthMetres.coerceAtLeast(0.1f)
    val floorHeight = state.floorHeightMetres.coerceAtLeast(0.1f)
    Box(modifier.aspectRatio(floorWidth / floorHeight)) {
        state.floorPlanContentUri?.let { FloorPlanImage(it, Modifier.matchParentSize(), alpha = 0.70f) }
        Canvas(
            modifier = Modifier.matchParentSize()
                .semantics { contentDescription = "Real physical positioning map in metres" },
        ) {
        drawRect(if (state.floorPlanContentUri == null) surface else surface.copy(alpha = 0.08f))
        val xStep = (floorWidth / 6f).roundToInt().coerceAtLeast(1)
        val yStep = (floorHeight / 4f).roundToInt().coerceAtLeast(1)
        for (x in 0..floorWidth.roundToInt() step xStep) drawLine(outline, metric(Offset(x.toFloat(), 0f), floorWidth, floorHeight), metric(Offset(x.toFloat(), floorHeight), floorWidth, floorHeight), 1f)
        for (y in 0..floorHeight.roundToInt() step yStep) drawLine(outline, metric(Offset(0f, y.toFloat()), floorWidth, floorHeight), metric(Offset(floorWidth, y.toFloat()), floorWidth, floorHeight), 1f)

        val polygon = state.draftWalkablePolygon
        if (polygon.size >= 3) {
            val path = Path().apply {
                moveTo(metric(polygon.first(), floorWidth, floorHeight).x, metric(polygon.first(), floorWidth, floorHeight).y)
                polygon.drop(1).forEach { point -> lineTo(metric(point, floorWidth, floorHeight).x, metric(point, floorWidth, floorHeight).y) }
                close()
            }
            drawPath(path, TurnMint.copy(alpha = 0.12f))
            drawPath(path, TurnMint.copy(alpha = 0.65f), style = Stroke(width = 2f))
        }
        state.draftWalls.forEach { (start, end) ->
            drawLine(wallColor, metric(start, floorWidth, floorHeight), metric(end, floorWidth, floorHeight), strokeWidth = 4f, cap = StrokeCap.Round)
        }
        if (state.showRawPdr) drawTrail(state.realRawPdrTrail, TurnAmber, 3f, floorWidth, floorHeight)
        drawTrail(state.realFusedTrail, TurnBlue, 5f, floorWidth, floorHeight)
        if (state.showWifiFixes) state.realWifiFixes.forEach { drawCircle(TurnCyan, 7f, metric(it, floorWidth, floorHeight), style = Stroke(3f)) }
        if (state.showParticles) state.realParticleCloud.forEach { drawCircle(TurnBlue.copy(alpha = 0.28f), 2.5f, metric(it, floorWidth, floorHeight)) }
        state.realLivePosition?.let { position ->
            val centre = metric(position, floorWidth, floorHeight)
            state.realLiveUncertaintyMetres?.takeIf { state.showConfidence }?.let { metres ->
                drawCircle(TurnBlue.copy(alpha = 0.12f), metres.toFloat() * size.width / floorWidth, centre)
                drawCircle(TurnBlue.copy(alpha = 0.55f), metres.toFloat() * size.width / floorWidth, centre, style = Stroke(2f))
            }
            drawCircle(Color.White, 10f, centre)
            drawCircle(TurnBlue, 7f, centre)
        }
        }
    }
}

private fun DrawScope.metric(point: Offset, floorWidth: Float, floorHeight: Float) = Offset(
    x = point.x / floorWidth * size.width,
    y = (1f - point.y / floorHeight) * size.height,
)

private fun DrawScope.drawTrail(points: List<Offset>, color: Color, width: Float, floorWidth: Float, floorHeight: Float) {
    points.zipWithNext().forEach { (from, to) ->
        drawLine(color, metric(from, floorWidth, floorHeight), metric(to, floorWidth, floorHeight), strokeWidth = width, cap = StrokeCap.Round)
    }
}

@Composable
private fun RealLegend() {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(13.dp)) {
        LegendDot("Fused", TurnBlue)
        LegendDot("Raw PDR", TurnAmber)
        LegendDot("Wi-Fi fix", TurnCyan)
        LegendDot("Walkable", TurnMint)
    }
}

@Composable
private fun LegendDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(9.dp).background(color, CircleShape))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}
