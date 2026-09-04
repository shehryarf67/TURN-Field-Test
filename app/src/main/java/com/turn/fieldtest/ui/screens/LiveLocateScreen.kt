package com.turn.fieldtest.ui.screens

import android.graphics.Paint
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
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.turn.fieldtest.ui.TurnAppState
import com.turn.fieldtest.ui.model.DataMode
import com.turn.fieldtest.ui.model.EventSeverity
import com.turn.fieldtest.ui.model.PositionSampleUi
import com.turn.fieldtest.ui.model.TurnDemoData
import com.turn.fieldtest.ui.theme.TurnAmber
import com.turn.fieldtest.ui.theme.TurnBlue
import com.turn.fieldtest.ui.theme.TurnCyan
import com.turn.fieldtest.ui.theme.TurnMint
import com.turn.fieldtest.ui.theme.TurnRed
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private const val LiveMapWidth = 42f
private const val LiveMapHeight = 28f

@Composable
fun LiveLocateScreen(
    state: TurnAppState,
    compact: Boolean,
    onRealLiveToggle: () -> Unit = {},
    onRealScanRequested: () -> Unit = {},
    onRealRelocalizationRequested: () -> Unit = {},
) {
    if (state.mode == DataMode.REAL_DEVICE) {
        RealLiveLocateScreen(
            state = state,
            compact = compact,
            onToggle = onRealLiveToggle,
            onScan = onRealScanRequested,
            onRelocalize = onRealRelocalizationRequested,
        )
        return
    }
    var showQrDialog by remember { mutableStateOf(false) }
    val index = state.replayIndex.coerceIn(0, TurnDemoData.fusedTrail.lastIndex)
    val fusedPosition = TurnDemoData.fusedTrail[index]
    val rawPosition = TurnDemoData.rawPdrTrail[index.coerceAtMost(TurnDemoData.rawPdrTrail.lastIndex)]
    val simulated = state.mode == DataMode.DEMO

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(PagePadding),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        PageHeader(
            eyebrow = "05 · Fused positioning",
            title = "Live locate",
            description = "PDR propagates each step; map constraints reject impossible motion; fresh Wi-Fi periodically corrects drift.",
            compact = compact,
            action = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showQrDialog = true }) {
                        Icon(Icons.Outlined.QrCodeScanner, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("QR anchor")
                    }
                    Button(onClick = { state.liveRunning = !state.liveRunning }) {
                        Icon(if (state.liveRunning) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (state.liveRunning) "Pause" else if (simulated) "Replay" else "Start")
                    }
                }
            }
        )

        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill("PDR prediction", EventSeverity.INFO)
            StatusPill("fresh Wi-Fi correction", EventSeverity.GOOD)
            StatusPill("map constraint applied", EventSeverity.WARNING)
            StatusPill("floor 0 · 94%", EventSeverity.GOOD)
        }

        AdaptiveColumns(
            breakpoint = 900.dp,
            primaryWeight = 2.25f,
            secondaryWeight = 0.95f,
            primary = {
                SectionCard(
                    title = "Ground floor · live track",
                    subtitle = "Metric map · last correction ${5 + (index % 4)} s ago",
                    trailing = { StatusPill(if (state.liveRunning) "tracking" else "paused", if (state.liveRunning) EventSeverity.GOOD else EventSeverity.WARNING) }
                ) {
                    MapLayerControls(state)
                    Spacer(Modifier.height(10.dp))
                    LiveMapCanvas(state, index, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(11.dp))
                    MapLegend()
                }
            },
            secondary = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    PositionSummary(fusedPosition, rawPosition)
                    SectionCard("Active modalities", "No modality silently substitutes another") {
                        ModalityRow("PDR", true, "step detector · game rotation")
                        ModalityRow("Map matching", true, "walkable + walls")
                        ModalityRow("Wi-Fi", true, "fresh correction available")
                        ModalityRow("Particle filter", true, "600 particles")
                        ModalityRow("QR", true, "ready on demand")
                        ModalityRow("Barometer", true, "assist only")
                        ModalityRow("BLE", false, "not configured")
                    }
                    SectionCard("Recovery", "Explicit initialization and relocalization") {
                        OutlinedButton(
                            onClick = {
                                state.replayIndex = 0
                                state.lastCorrectionType = "manual position initialization"
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Outlined.MyLocation, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Select position on map")
                        }
                        Spacer(Modifier.height(8.dp))
                        FilledTonalButton(
                            onClick = {
                                state.replayIndex = TurnDemoData.fusedTrail.lastIndex
                                state.relocalizationCount += 1
                                state.lastCorrectionType = "globally relocalized"
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Outlined.CenterFocusStrong, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Global relocalization")
                        }
                    }
                }
            }
        )

        if (compact) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LiveMetrics(state, fusedPosition, compact = true)
                WifiEvidenceCard(state)
            }
        } else {
            AdaptiveColumns(
                primaryWeight = 1.3f,
                secondaryWeight = 1f,
                primary = { LiveMetrics(state, fusedPosition, compact = false) },
                secondary = { WifiEvidenceCard(state) }
            )
        }

        AdaptiveColumns(
            primaryWeight = 1.25f,
            secondaryWeight = 1f,
            primary = {
                SectionCard("Correction & constraint timeline", "All changes to the fused state are auditable") {
                    TurnDemoData.events.forEachIndexed { eventIndex, event ->
                        TimelineRow(
                            time = if (eventIndex == 0) "NOW" else event.time,
                            title = if (eventIndex == 0) state.lastCorrectionType.replaceFirstChar { it.uppercase() } else event.title,
                            detail = event.detail,
                            severity = event.severity,
                            last = eventIndex == TurnDemoData.events.lastIndex
                        )
                    }
                }
            },
            secondary = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SectionCard("Floor evidence", "PDR cannot change floors by itself") {
                        EvidenceBar("Particle vote · floor 0", 0.94f, TurnBlue)
                        EvidenceBar("Wi-Fi neighbour vote", 0.88f, TurnCyan)
                        EvidenceBar("Transition topology", 1f, TurnMint)
                        EvidenceBar("Barometer evidence", 0.16f, TurnAmber)
                        Spacer(Modifier.height(6.dp))
                        Text("No vertical-transition event · floor remains Ground", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    BleDisabledBanner()
                }
            }
        )

        Spacer(Modifier.height(16.dp))
    }

    if (showQrDialog) {
        AlertDialog(
            onDismissRequest = { showQrDialog = false },
            icon = { Icon(Icons.Outlined.QrCodeScanner, contentDescription = null) },
            title = { Text("QR anchor correction") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Validated demo payload", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "schemaVersion: 1\nvenueId: VEN-CS-01\nfloorId: FL-G\nanchorId: QR-G-ENTRANCE\nx: 5.0 m · y: 9.5 m\ndirection: +x",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    StatusPill("known venue · valid coordinates", EventSeverity.GOOD)
                    Text("A QR anchor is an explicit fix at scan time, not a continuously broadcasting beacon.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Button(onClick = {
                    state.replayIndex = 0
                    state.lastCorrectionType = "QR correction"
                    showQrDialog = false
                }) { Text("Apply anchor") }
            },
            dismissButton = { TextButton(onClick = { showQrDialog = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun MapLayerControls(state: TurnAppState) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        FilterChip(selected = state.showRawPdr, onClick = { state.showRawPdr = !state.showRawPdr }, label = { Text("Raw PDR") })
        FilterChip(selected = state.showWifiFixes, onClick = { state.showWifiFixes = !state.showWifiFixes }, label = { Text("Wi-Fi fixes") })
        FilterChip(selected = true, onClick = {}, label = { Text("Fused") })
        FilterChip(selected = state.showConfidence, onClick = { state.showConfidence = !state.showConfidence }, label = { Text("Confidence") })
        FilterChip(selected = state.showParticles, onClick = { state.showParticles = !state.showParticles }, label = { Text("Particles") })
    }
}

@Composable
private fun LiveMapCanvas(state: TurnAppState, index: Int, modifier: Modifier = Modifier) {
    val surface = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    val grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
    val walls = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    val label = MaterialTheme.colorScheme.onSurface
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = surface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .aspectRatio(LiveMapWidth / LiveMapHeight)
                .semantics {
                    contentDescription = "Live metric map showing raw PDR, periodic Wi-Fi fixes, fused blue dot, map constraints and optional particle cloud"
                }
        ) {
            drawRect(surface)
            drawLiveGrid(grid)
            drawLivePlan(walls, label)
            val safeIndex = index.coerceAtLeast(0)
            if (state.showRawPdr) {
                drawTrajectory(
                    TurnDemoData.rawPdrTrail.take(safeIndex + 1),
                    TurnAmber,
                    dashed = true,
                    width = 3f
                )
            }
            drawTrajectory(TurnDemoData.fusedTrail.take(safeIndex + 1), TurnBlue, dashed = false, width = 5f)
            if (state.showWifiFixes) {
                TurnDemoData.wifiFixes.forEach { drawWifiFix(it) }
            }
            val current = TurnDemoData.fusedTrail[safeIndex.coerceAtMost(TurnDemoData.fusedTrail.lastIndex)]
            if (state.showConfidence) drawConfidence(current, 2.4f)
            if (state.showParticles) drawParticleCloud(current)
            drawFusedPosition(current, label)
            val raw = TurnDemoData.rawPdrTrail[safeIndex.coerceAtMost(TurnDemoData.rawPdrTrail.lastIndex)]
            if (state.showRawPdr) drawCircle(TurnAmber, radius = 7f, center = liveMetric(raw), style = Stroke(3f))
            drawMapConstraintMarker(Offset(10.9f, 18.5f), label)
        }
    }
}

private fun DrawScope.drawLiveGrid(color: Color) {
    for (x in 0..40 step 5) drawLine(color, liveMetric(Offset(x.toFloat(), 0f)), liveMetric(Offset(x.toFloat(), LiveMapHeight)), 1f)
    for (y in 0..25 step 5) drawLine(color, liveMetric(Offset(0f, y.toFloat())), liveMetric(Offset(LiveMapWidth, y.toFloat())), 1f)
}

private fun DrawScope.drawLivePlan(wall: Color, label: Color) {
    val walkable = listOf(
        Offset(4f, 7f), Offset(37f, 7f), Offset(37f, 12f), Offset(17f, 12f),
        Offset(17f, 23f), Offset(9f, 23f), Offset(9f, 12f), Offset(4f, 12f)
    )
    val path = Path().apply {
        val first = liveMetric(walkable.first())
        moveTo(first.x, first.y)
        walkable.drop(1).forEach { point ->
            val p = liveMetric(point)
            lineTo(p.x, p.y)
        }
        close()
    }
    drawPath(path, TurnCyan.copy(alpha = 0.10f))
    drawPath(path, wall, style = Stroke(4f))
    listOf(12f, 19f, 27f).forEach { x ->
        drawLine(wall, liveMetric(Offset(x, 7f)), liveMetric(Offset(x, 12f)), 2f)
    }
    drawLine(wall, liveMetric(Offset(9f, 17.5f)), liveMetric(Offset(17f, 17.5f)), 2f)
    drawLiveLabel("ENTRANCE", liveMetric(Offset(4.7f, 8.2f)), label, 16f)
    drawLiveLabel("MAIN CORRIDOR", liveMetric(Offset(19f, 10.3f)), label.copy(alpha = 0.7f), 16f)
    drawLiveLabel("NORTH WING", liveMetric(Offset(10f, 21.5f)), label.copy(alpha = 0.7f), 16f)
    val stairs = liveMetric(Offset(16f, 10f))
    drawRoundRect(TurnAmber.copy(alpha = 0.3f), stairs - Offset(12f, 12f), Size(24f, 24f), androidx.compose.ui.geometry.CornerRadius(5f, 5f))
    drawLiveLabel("ST", stairs + Offset(-8f, 5f), TurnAmber, 16f, true)
}

private fun DrawScope.drawTrajectory(points: List<Offset>, color: Color, dashed: Boolean, width: Float) {
    if (points.size < 2) return
    val path = Path().apply {
        val first = liveMetric(points.first())
        moveTo(first.x, first.y)
        points.drop(1).forEach { point ->
            val p = liveMetric(point)
            lineTo(p.x, p.y)
        }
    }
    drawPath(
        path,
        color,
        style = Stroke(
            width = width,
            cap = StrokeCap.Round,
            pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) else null
        )
    )
}

private fun DrawScope.drawWifiFix(fix: PositionSampleUi) {
    val center = liveMetric(fix.metres)
    val radius = fix.confidenceMetres / LiveMapWidth * size.width
    drawCircle(TurnCyan.copy(alpha = 0.08f), radius, center)
    drawCircle(TurnCyan.copy(alpha = 0.7f), radius, center, style = Stroke(2f))
    drawCircle(TurnCyan, 6f, center)
    drawCircle(Color.White, 2f, center)
}

private fun DrawScope.drawConfidence(point: Offset, radiusMetres: Float) {
    val center = liveMetric(point)
    val radius = radiusMetres / LiveMapWidth * size.width
    drawCircle(TurnBlue.copy(alpha = 0.13f), radius, center)
    drawCircle(TurnBlue.copy(alpha = 0.55f), radius, center, style = Stroke(2f))
}

private fun DrawScope.drawParticleCloud(centerMetres: Offset) {
    repeat(46) { index ->
        val angle = index * 2.399963f
        val radius = 0.35f + (index % 9) * 0.17f
        val particle = Offset(
            centerMetres.x + cos(angle) * radius,
            centerMetres.y + sin(angle) * radius * 0.7f
        )
        drawCircle(TurnBlue.copy(alpha = 0.42f), 2.2f, liveMetric(particle))
    }
}

private fun DrawScope.drawFusedPosition(point: Offset, label: Color) {
    val center = liveMetric(point)
    drawCircle(Color.White, 12f, center)
    drawCircle(TurnBlue, 9f, center)
    drawCircle(Color.White.copy(alpha = 0.85f), 3f, center)
    val headingEnd = center + Offset(24f, -8f)
    drawLine(TurnBlue, center, headingEnd, 4f, cap = StrokeCap.Round)
    drawLiveLabel("FUSED", center + Offset(14f, -15f), label, 17f, true)
}

private fun DrawScope.drawMapConstraintMarker(point: Offset, label: Color) {
    val center = liveMetric(point)
    drawLine(TurnRed, center - Offset(6f, 6f), center + Offset(6f, 6f), 3f)
    drawLine(TurnRed, center + Offset(-6f, 6f), center + Offset(6f, -6f), 3f)
    drawLiveLabel("rejected", center + Offset(9f, 4f), label, 14f)
}

private fun DrawScope.liveMetric(point: Offset): Offset = Offset(
    x = point.x / LiveMapWidth * size.width,
    y = size.height - point.y / LiveMapHeight * size.height
)

private fun DrawScope.drawLiveLabel(text: String, point: Offset, color: Color, sizePx: Float, bold: Boolean = false) {
    drawContext.canvas.nativeCanvas.drawText(
        text,
        point.x,
        point.y,
        Paint().apply {
            this.color = android.graphics.Color.argb(
                (color.alpha * 255).roundToInt(),
                (color.red * 255).roundToInt(),
                (color.green * 255).roundToInt(),
                (color.blue * 255).roundToInt()
            )
            textSize = sizePx
            isAntiAlias = true
            typeface = if (bold) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
        }
    )
}

@Composable
private fun MapLegend() {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(13.dp)) {
        LegendItem(TurnAmber, "raw PDR", outlined = true)
        LegendItem(TurnCyan, "Wi-Fi-only fixes")
        LegendItem(TurnBlue, "fused / constrained")
        LegendItem(TurnRed, "rejected movement")
    }
}

@Composable
private fun LegendItem(color: Color, text: String, outlined: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                .size(10.dp)
                .background(if (outlined) color.copy(alpha = 0.35f) else color, if (outlined) RoundedCornerShape(2.dp) else CircleShape)
        )
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PositionSummary(fused: Offset, raw: Offset) {
    SectionCard("Current estimate", "Strongest consistent particle cluster") {
        Text("x %.2f m".format(fused.x), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black, color = TurnBlue)
        Text("y %.2f m".format(fused.y), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black, color = TurnBlue)
        Spacer(Modifier.height(8.dp))
        LabelValue("Estimated floor", "Ground (0)")
        LabelValue("Floor confidence", "94%")
        LabelValue("Confidence radius", "2.4 m")
        LabelValue("Raw PDR", "%.1f, %.1f m".format(raw.x, raw.y), valueColor = TurnAmber)
        LabelValue("Filter state", "TRACKING", valueColor = TurnMint)
    }
}

@Composable
private fun LiveMetrics(state: TurnAppState, fused: Offset, compact: Boolean) {
    SectionCard("Movement & filter state", "Frequent relative updates between radio corrections") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricCard("Steps", "${34 + state.replayIndex}", Modifier.weight(1f), "detector")
            MetricCard("Distance", "${"%.1f".format((34 + state.replayIndex) * 0.73)} m", Modifier.weight(1f), "session")
            MetricCard("Stride", "0.73 m", Modifier.weight(1f), "scale 1.02")
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricCard("Heading", "+31.4°", Modifier.weight(1f), "game rotation")
            MetricCard("Offset", "+4.2°", Modifier.weight(1f), "session hypothesis")
            MetricCard("Particles", "600", Modifier.weight(1f), "ESS 441")
        }
        Spacer(Modifier.height(10.dp))
        LabelValue("Prediction equation", "x += stride × cos(heading)")
        LabelValue("Last move", "accepted · corridor connected")
        LabelValue("Relocalizations", state.relocalizationCount.toString())
        LabelValue("Position", "%.1f, %.1f m".format(fused.x, fused.y))
    }
}

@Composable
private fun WifiEvidenceCard(state: TurnAppState) {
    SectionCard("Wi-Fi absolute evidence", "Periodic correction · not a high-frequency tracker") {
        LabelValue("Scan status", "fresh results applied", valueColor = TurnMint)
        LabelValue("Newest result age", "5 s")
        LabelValue("Next permitted request", "00:23")
        LabelValue("Visible AP count", "8")
        LabelValue("Union vector size", "12 BSSIDs")
        LabelValue("Missing RSSI", "${state.missingRssi} dBm")
        Spacer(Modifier.height(8.dp))
        Text("Nearest fingerprints", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        NeighbourRow("RP-G-07", "Ground", "4.2", "0.41")
        NeighbourRow("RP-G-08", "Ground", "5.0", "0.29")
        NeighbourRow("RP-G-04", "Ground", "6.8", "0.18")
        NeighbourRow("RP-1-03", "First", "9.7", "0.12")
        Spacer(Modifier.height(7.dp))
        StatusPill("database likeness acceptable", EventSeverity.GOOD)
    }
}

@Composable
private fun NeighbourRow(id: String, floor: String, distance: String, weight: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(id, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text(floor, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(50.dp))
        Text("d $distance", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(48.dp))
        Text("w $weight", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun ModalityRow(label: String, active: Boolean, detail: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(8.dp).background(if (active) TurnMint else MaterialTheme.colorScheme.outline, CircleShape))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
            Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(if (active) "ACTIVE" else "OFF", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = if (active) TurnMint else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TimelineRow(time: String, title: String, detail: String, severity: EventSeverity, last: Boolean) {
    val color = when (severity) {
        EventSeverity.INFO -> MaterialTheme.colorScheme.primary
        EventSeverity.GOOD -> TurnMint
        EventSeverity.WARNING -> TurnAmber
        EventSeverity.ERROR -> TurnRed
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(10.dp).background(color, CircleShape))
            if (!last) Box(Modifier.width(2.dp).height(38.dp).background(MaterialTheme.colorScheme.outlineVariant))
        }
        Column(Modifier.weight(1f).padding(bottom = 9.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text(title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EvidenceBar(label: String, value: Float, color: Color) {
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Text("${(value * 100).roundToInt()}%", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        }
        Box(Modifier.fillMaxWidth().height(5.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(50))) {
            Box(Modifier.fillMaxWidth(value.coerceIn(0f, 1f)).height(5.dp).background(color, RoundedCornerShape(50)))
        }
    }
}
