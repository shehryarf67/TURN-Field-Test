package com.turn.fieldtest.ui.screens

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.turn.fieldtest.ui.TurnAppState
import com.turn.fieldtest.ui.model.EditorTool
import com.turn.fieldtest.ui.model.EventSeverity
import com.turn.fieldtest.ui.theme.TurnAmber
import com.turn.fieldtest.ui.theme.TurnBlue
import com.turn.fieldtest.ui.theme.TurnCyan
import com.turn.fieldtest.ui.theme.TurnMint
import com.turn.fieldtest.ui.theme.TurnRed
import kotlin.math.roundToInt

private const val DemoFloorWidth = 42f
private const val DemoFloorHeight = 28f

@Composable
fun FloorEditorScreen(state: TurnAppState, compact: Boolean) {
    var showBackground by remember { mutableStateOf(true) }
    var showMetricGrid by remember { mutableStateOf(true) }
    var calibrationDistance by remember { mutableStateOf("8.00") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(PagePadding),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        PageHeader(
            eyebrow = "02 · Metric map",
            title = "Floor-plan editor",
            description = "Draw navigable geometry in metres over a replaceable sketch. Tap the canvas with a tool selected to add geometry.",
            compact = compact,
            action = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { state.editorStatus = "Venue JSON export queued through Storage Access Framework" }) {
                        Icon(Icons.Outlined.FileDownload, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("JSON")
                    }
                    Button(onClick = { state.editorStatus = "Metric floor draft saved" }) {
                        Icon(Icons.Outlined.Save, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Save")
                    }
                }
            }
        )

        ToolPicker(state.editorTool) {
            state.editorTool = it
            state.pendingWallStart = null
            state.editorStatus = "${it.label} tool selected"
        }

        AdaptiveColumns(
            breakpoint = 840.dp,
            primaryWeight = 2.2f,
            secondaryWeight = 0.9f,
            primary = {
                SectionCard(
                    title = "Ground floor · FL-G",
                    subtitle = "42.0 m × 28.0 m · origin lower-left",
                    trailing = { StatusPill("1 px = 0.0131 m", EventSeverity.GOOD) }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        LayerChip("Background", showBackground) { showBackground = !showBackground }
                        LayerChip("Metric grid", showMetricGrid) { showMetricGrid = !showMetricGrid }
                        LayerChip("Geometry", state.geometryLayerVisible) { state.geometryLayerVisible = !state.geometryLayerVisible }
                        LayerChip("Labels", state.labelsLayerVisible) { state.labelsLayerVisible = !state.labelsLayerVisible }
                    }
                    Spacer(Modifier.height(12.dp))
                    MetricFloorCanvas(
                        state = state,
                        showBackground = showBackground,
                        showGrid = showMetricGrid,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        StatusPill(state.editorTool.label, EventSeverity.INFO)
                        Text(
                            state.editorStatus,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            },
            secondary = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SectionCard("Floor calibration", "Image points A–B define pixel-to-metre scale") {
                        OutlinedButton(
                            onClick = { state.editorStatus = "Background image picker requested" },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Outlined.Image, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Replace image")
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = calibrationDistance,
                            onValueChange = { calibrationDistance = it.filter { char -> char.isDigit() || char == '.' } },
                            label = { Text("Measured A–B distance") },
                            suffix = { Text("m") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        LabelValue("Image separation", "612 px")
                        LabelValue("Computed scale", "0.01307 m / px")
                        LabelValue("Coordinate origin", "lower-left")
                        FilledTonalButton(
                            onClick = { state.editorStatus = "Scale calibrated using $calibrationDistance m reference" },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Outlined.CenterFocusStrong, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Apply calibration")
                        }
                    }

                    SectionCard("Geometry summary", "Stored in metres") {
                        LabelValue("Walkable regions", "1 draft · 8 vertices")
                        LabelValue("Wall segments", "${state.draftWalls.size}")
                        LabelValue("Doors", "4")
                        LabelValue("Junctions", "3")
                        LabelValue("Stairs / lifts", "1 / 1")
                        LabelValue("QR anchors", state.qrAnchors.size.toString())
                        LabelValue("Survey points", state.referencePoints.size.toString())
                    }

                    SectionCard("Map topology", "Validation before survey") {
                        ValidationRow("Walkable polygon closed", true)
                        ValidationRow("Corridors connected", true)
                        ValidationRow("Walls have no gaps", false)
                        ValidationRow("Floor transition paired", true)
                        ValidationRow("All survey points valid", false)
                    }
                }
            }
        )

        AdaptiveColumns(
            primary = {
                SectionCard("Survey reference points", "Known coordinates · independent from estimates") {
                    state.referencePoints.forEach { point ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(9.dp)
                        ) {
                            Box(Modifier.size(10.dp).background(TurnCyan, RoundedCornerShape(50)))
                            Text(point.id, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Text("x %.1f m  ·  y %.1f m".format(point.metres.x, point.metres.y), style = MaterialTheme.typography.bodySmall)
                            StatusPill("walkable", EventSeverity.GOOD)
                        }
                    }
                }
            },
            secondary = {
                SectionCard("Coordinate convention", "Applied throughout PDR and exports") {
                    LabelValue("Origin", "south-west / lower-left")
                    LabelValue("+x", "right / east on drawing")
                    LabelValue("+y", "up / north on drawing")
                    LabelValue("0° heading", "+x direction")
                    LabelValue("90° heading", "+y direction")
                    Text(
                        "Screen y coordinates are inverted only at render time; all persisted values remain metric Cartesian coordinates.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        )
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ToolPicker(selected: EditorTool, onSelected: (EditorTool) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text("EDITOR TOOLS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            EditorTool.entries.forEach { tool ->
                FilterChip(
                    selected = selected == tool,
                    onClick = { onSelected(tool) },
                    label = { Text(tool.label) },
                    leadingIcon = {
                        Box(
                            Modifier.size(24.dp).background(
                                if (selected == tool) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                                else MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(8.dp)
                            ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(tool.code, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Black)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun LayerChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = {
            Icon(
                if (selected) Icons.Outlined.Check else Icons.Outlined.Close,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
        }
    )
}

@Composable
private fun MetricFloorCanvas(
    state: TurnAppState,
    showBackground: Boolean,
    showGrid: Boolean,
    modifier: Modifier = Modifier
) {
    val background = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f)
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
    val imageInk = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.20f)
    val labelColor = MaterialTheme.colorScheme.onSurface
    val wallColor = MaterialTheme.colorScheme.onSurface
    val pending = state.pendingWallStart

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = background)
    ) {
        Box(Modifier.fillMaxWidth()) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(DemoFloorWidth / DemoFloorHeight)
                    .semantics {
                        contentDescription = "Interactive metric floor canvas, 42 metres wide by 28 metres high. ${state.referencePoints.size} survey points and ${state.qrAnchors.size} QR anchors."
                    }
                    .pointerInput(state.editorTool, state.draftWalkablePolygon.size, state.draftWalls.size) {
                        detectTapGestures { pixel ->
                            if (size.width == 0 || size.height == 0) return@detectTapGestures
                            val metric = Offset(
                                x = (pixel.x / size.width) * DemoFloorWidth,
                                y = (1f - pixel.y / size.height) * DemoFloorHeight
                            )
                            state.addMapPoint(metric)
                        }
                    }
            ) {
                drawRect(background)
                if (showBackground) drawPlanBackground(imageInk)
                if (showGrid) drawMetricGrid(gridColor)
                if (state.geometryLayerVisible) {
                    drawWalkable(state.draftWalkablePolygon)
                    state.draftWalls.forEach { (start, end) ->
                        drawLine(wallColor, metricToCanvas(start), metricToCanvas(end), strokeWidth = 4f, cap = StrokeCap.Round)
                    }
                    drawDoor(metricToCanvas(Offset(9f, 7f)))
                    drawDoor(metricToCanvas(Offset(24f, 7f)))
                    drawTransition(metricToCanvas(Offset(16f, 10f)), "ST", TurnAmber)
                    drawTransition(metricToCanvas(Offset(34f, 10f)), "LF", TurnMint)
                    state.referencePoints.forEach { point ->
                        drawReferencePoint(metricToCanvas(point.metres), point.id, labelColor, state.labelsLayerVisible)
                    }
                    state.qrAnchors.forEach { point ->
                        drawQr(metricToCanvas(point.metres), point.id, labelColor, state.labelsLayerVisible)
                    }
                    pending?.let { start ->
                        drawCircle(TurnRed, radius = 8f, center = metricToCanvas(start))
                    }
                }
                drawOrigin(labelColor)
            }
        }
    }
}

private fun DrawScope.drawPlanBackground(color: Color) {
    val rooms = listOf(
        floatArrayOf(4f, 7f, 8f, 5f), floatArrayOf(12f, 7f, 7f, 5f),
        floatArrayOf(19f, 7f, 8f, 5f), floatArrayOf(27f, 7f, 10f, 5f),
        floatArrayOf(9f, 12f, 8f, 5.5f), floatArrayOf(9f, 17.5f, 8f, 5.5f)
    )
    rooms.forEach { room ->
        val topLeft = metricToCanvas(Offset(room[0], room[1] + room[3]))
        val bottomRight = metricToCanvas(Offset(room[0] + room[2], room[1]))
        drawRect(
            color = color,
            topLeft = Offset(topLeft.x, topLeft.y),
            size = Size(bottomRight.x - topLeft.x, bottomRight.y - topLeft.y),
            style = Stroke(width = 2f)
        )
    }
}

private fun DrawScope.drawMetricGrid(color: Color) {
    var x = 0
    while (x <= DemoFloorWidth.roundToInt()) {
        val alpha = if (x % 5 == 0) 0.8f else 0.35f
        drawLine(color.copy(alpha = color.alpha * alpha), metricToCanvas(Offset(x.toFloat(), 0f)), metricToCanvas(Offset(x.toFloat(), DemoFloorHeight)), 1f)
        x += 1
    }
    var y = 0
    while (y <= DemoFloorHeight.roundToInt()) {
        val alpha = if (y % 5 == 0) 0.8f else 0.35f
        drawLine(color.copy(alpha = color.alpha * alpha), metricToCanvas(Offset(0f, y.toFloat())), metricToCanvas(Offset(DemoFloorWidth, y.toFloat())), 1f)
        y += 1
    }
}

private fun DrawScope.drawWalkable(points: List<Offset>) {
    if (points.size < 2) return
    val path = Path().apply {
        val first = metricToCanvas(points.first())
        moveTo(first.x, first.y)
        points.drop(1).forEach { point ->
            val pixel = metricToCanvas(point)
            lineTo(pixel.x, pixel.y)
        }
        if (points.size >= 3) close()
    }
    drawPath(path, TurnCyan.copy(alpha = 0.18f))
    drawPath(path, TurnCyan, style = Stroke(width = 3f))
    points.forEach { drawCircle(TurnCyan, radius = 5f, center = metricToCanvas(it)) }
}

private fun DrawScope.drawDoor(center: Offset) {
    drawLine(TurnMint, center - Offset(12f, 0f), center + Offset(12f, 0f), strokeWidth = 7f, cap = StrokeCap.Round)
}

private fun DrawScope.drawTransition(center: Offset, text: String, color: Color) {
    drawRoundRect(color.copy(alpha = 0.22f), topLeft = center - Offset(14f, 14f), size = Size(28f, 28f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f, 6f))
    drawRoundRect(color, topLeft = center - Offset(14f, 14f), size = Size(28f, 28f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f, 6f), style = Stroke(2f))
    drawLabel(text, center + Offset(-8f, 5f), color, 18f, bold = true)
}

private fun DrawScope.drawReferencePoint(center: Offset, id: String, labelColor: Color, showLabel: Boolean) {
    drawCircle(Color(0xFF071522), radius = 10f, center = center)
    drawCircle(TurnCyan, radius = 9f, center = center, style = Stroke(3f))
    drawCircle(TurnCyan, radius = 3f, center = center)
    if (showLabel) drawLabel(id.substringAfterLast('-'), center + Offset(12f, -10f), labelColor, 17f, bold = true)
}

private fun DrawScope.drawQr(center: Offset, id: String, labelColor: Color, showLabel: Boolean) {
    drawRect(TurnBlue, topLeft = center - Offset(8f, 8f), size = Size(16f, 16f))
    drawRect(Color.White, topLeft = center - Offset(4f, 4f), size = Size(3f, 3f))
    drawRect(Color.White, topLeft = center + Offset(1f, 1f), size = Size(3f, 3f))
    if (showLabel) drawLabel("QR", center + Offset(11f, 5f), labelColor, 17f, bold = true)
}

private fun DrawScope.drawOrigin(color: Color) {
    val origin = metricToCanvas(Offset(0f, 0f)) + Offset(12f, -12f)
    drawLine(TurnRed, origin, origin + Offset(30f, 0f), 3f, cap = StrokeCap.Round)
    drawLine(TurnMint, origin, origin + Offset(0f, -30f), 3f, cap = StrokeCap.Round)
    drawLabel("0,0", origin + Offset(5f, -8f), color, 16f, bold = true)
}

private fun DrawScope.drawLabel(text: String, point: Offset, color: Color, sizePx: Float, bold: Boolean = false) {
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

private fun DrawScope.metricToCanvas(point: Offset): Offset = Offset(
    x = (point.x / DemoFloorWidth) * size.width,
    y = size.height - (point.y / DemoFloorHeight) * size.height
)

@Composable
private fun ValidationRow(label: String, valid: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = valid, onCheckedChange = null)
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        if (!valid) StatusPill("review", EventSeverity.WARNING)
    }
}
