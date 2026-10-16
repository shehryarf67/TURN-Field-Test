package com.turn.fieldtest.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.turn.fieldtest.ui.TurnAppState
import com.turn.fieldtest.ui.theme.*
import kotlin.math.*

/** One metric projection for the image, editing hit tests and all positioning/navigation overlays. */
@Composable
internal fun NavigationMap(
    state: TurnAppState,
    modifier: Modifier = Modifier,
    showGraph: Boolean = false,
    showReferences: Boolean = false,
    showPosition: Boolean = false,
    onTap: ((Offset, Float) -> Unit)? = null,
) {
    var zoom by remember(state.activeProject) { mutableFloatStateOf(1f) }
    var pan by remember(state.activeProject) { mutableStateOf(Offset.Zero) }
    val tapHandler by rememberUpdatedState(onTap)
    BoxWithConstraints(modifier.semantics { contentDescription = "Floor map" }.clipToBounds().background(TurnNavyRaised)) {
        val density = LocalDensity.current
        val width = with(density) { maxWidth.toPx() }; val height = with(density) { maxHeight.toPx() }
        val scale = min(width / state.floorWidthMetres, height / state.floorHeightMetres) * 0.92f
        val imageWidth = scale * state.floorWidthMetres; val imageHeight = scale * state.floorHeightMetres
        val origin = Offset((width - imageWidth) / 2, (height + imageHeight) / 2)
        val centre = Offset(width / 2, height / 2)
        fun project(p: Offset) = Offset(origin.x + p.x * scale, origin.y - p.y * scale)
        Box(Modifier.fillMaxSize()
            .pointerInput(state.activeProject, width, height) {
                detectTransformGestures { centroid, delta, factor, _ ->
                    val next = (zoom * factor).coerceIn(1f, 8f)
                    pan = centroid - centre - (centroid - centre - pan) * (next / zoom) + delta
                    zoom = next
                }
            }
            .pointerInput(state.activeProject, scale, width, height) {
                detectTapGestures { screen ->
                    val base = (screen - centre - pan) / zoom + centre
                    val point = Offset((base.x - origin.x) / scale, (origin.y - base.y) / scale)
                    if (point.x in 0f..state.floorWidthMetres && point.y in 0f..state.floorHeightMetres)
                        tapHandler?.invoke(point, 22f / (scale * zoom))
                }
            }) {
            Box(Modifier.fillMaxSize().graphicsLayer {
                scaleX = zoom; scaleY = zoom; translationX = pan.x; translationY = pan.y
            }) {
                state.floorPlanContentUri?.let { uri ->
                    FloorPlanImage(uri, Modifier.offset(x = with(density) { origin.x.toDp() },
                        y = with(density) { (origin.y - imageHeight).toDp() })
                        .size(with(density) { imageWidth.toDp() }, with(density) { imageHeight.toDp() }), alpha = 0.82f)
                }
                Canvas(Modifier.fillMaxSize()) {
                    state.otherWalkableRegions.forEach { region ->
                        val vertices=com.turn.fieldtest.data.repository.decodePolygon(region.polygonJson).vertices.map { project(Offset(it.x.toFloat(),it.y.toFloat())) }
                        val outline=Path().apply { vertices.forEachIndexed { i,p->if(i==0) moveTo(p.x,p.y) else lineTo(p.x,p.y) }; close() }
                        drawPath(outline,TurnCyan.copy(alpha=0.08f)); drawPath(outline,TurnCyan.copy(alpha=0.4f),style=Stroke(1.5f/zoom))
                    }
                    fun line(points: List<Offset>, color: Color, stroke: Float) {
                        if (points.size < 2) return
                        val path = Path().apply { val first = project(points.first()); moveTo(first.x, first.y)
                            points.drop(1).forEach { val p = project(it); lineTo(p.x, p.y) } }
                        drawPath(path, color, style = Stroke(stroke / zoom, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                    if (state.draftWalkablePolygon.size >= 3) {
                        val path = Path().apply { val first = project(state.draftWalkablePolygon.first()); moveTo(first.x, first.y)
                            state.draftWalkablePolygon.drop(1).forEach { val p = project(it); lineTo(p.x, p.y) }; close() }
                        drawPath(path, TurnCyan.copy(alpha = 0.09f))
                        drawPath(path, TurnCyan.copy(alpha = 0.45f), style = Stroke(1.5f / zoom))
                    } else line(state.draftWalkablePolygon.toList(), TurnCyan, 2f)
                    state.draftWalls.forEach { (a, b) -> drawLine(TurnAmber, project(a), project(b), 3f / zoom) }
                    state.pendingWallStart?.let { drawCircle(TurnAmber, 5f / zoom, project(it)) }
                    state.calibrationPoints.forEach { drawCircle(TurnAmber, 6f / zoom, project(it), style = Stroke(2f / zoom)) }
                    if (showGraph) {
                        val nodes = state.navigationNodes.associateBy { it.id }
                        state.navigationEdges.forEach { edge ->
                            val a = nodes[edge.fromNodeId]; val b = nodes[edge.toNodeId]
                            if (a != null && b != null) line(listOf(Offset(a.xMetres.toFloat(), a.yMetres.toFloat()),
                                Offset(b.xMetres.toFloat(), b.yMetres.toFloat())), if (edge.walkable) TurnCyan else TurnRed, 3f)
                        }
                        state.navigationNodes.forEach { node ->
                            val selected = node.id == state.selectedNodeId
                            drawCircle(if (selected) TurnAmber else TurnCyan, (if (selected) 8f else 5f) / zoom,
                                project(Offset(node.xMetres.toFloat(), node.yMetres.toFloat())))
                        }
                    }
                    line(state.routePoints, Color.White.copy(alpha = 0.9f), 9f)
                    line(state.routePoints, TurnBlue, 5f)
                    fun label(text: String, p: Offset, color: Color) {
                        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                            this.color = color.toArgb(); textSize = 12f * density.density / zoom
                            setShadowLayer(2f / zoom, 0f, 0f, android.graphics.Color.BLACK)
                        }
                        drawContext.canvas.nativeCanvas.drawText(text, p.x + 9f / zoom, p.y - 9f / zoom, paint)
                    }
                    state.destinations.forEach { poi ->
                        val p = project(Offset(poi.xMetres.toFloat(), poi.yMetres.toFloat()))
                        drawCircle(if (poi.id == state.selectedDestinationId) TurnMint else Color.White, 6f / zoom, p)
                        label(poi.name, p, Color.White)
                    }
                    if (showReferences) state.referencePoints.forEach { point ->
                        val p = project(point.metres)
                        val color = when {
                            point.id == state.selectedSurveyReferencePointId && state.surveyRunning -> TurnAmber
                            state.surveyPointProgress[point.id]?.status == "COMPLETED" -> TurnMint
                            else -> TurnCyan
                        }
                        drawCircle(color, (if (point.id == state.selectedSurveyReferencePointId) 8f else 5f) / zoom, p)
                        label(point.label, p, Color.White)
                    }
                    if (showPosition && state.debugOverlays) {
                        if (state.showRawPdr) line(state.realRawPdrTrail, TurnAmber, 2f)
                        if (state.showWifiFixes) state.realWifiPosition?.let {
                            val p = project(it)
                            drawCircle(TurnMint, 7f / zoom, p, style = Stroke(2f / zoom))
                            label("Last Wi-Fi match", p, TurnMint)
                        }
                        if (state.showParticles) state.realParticleCloud.forEach { drawCircle(TurnCyan.copy(alpha = 0.6f), 2f / zoom, project(it)) }
                    }
                    if (showPosition) state.realLivePosition?.let { position ->
                        val p = project(position)
                        state.realLiveUncertaintyMetres?.takeIf { it.isFinite() }?.let {
                            drawCircle(TurnBlue.copy(alpha = 0.15f), it.toFloat() * scale, p)
                            drawCircle(TurnBlue.copy(alpha = 0.5f), it.toFloat() * scale, p, style = Stroke(1.5f / zoom))
                        }
                        val angle = Math.toRadians(state.realLiveHeadingDegrees)
                        drawLine(TurnBlue, p, p + Offset(cos(angle).toFloat(), -sin(angle).toFloat()) * (24f / zoom), 5f / zoom, StrokeCap.Round)
                        drawCircle(Color.White, 10f / zoom, p); drawCircle(TurnBlue, 7f / zoom, p)
                    }
                    if (showPosition && !state.liveRunning) state.manualStartPoint?.let {
                        val p = project(it)
                        drawCircle(TurnBlue, 9f / zoom, p, style = Stroke(3f / zoom))
                        if (state.headingChoiceConfirmed) {
                            val angle = Math.toRadians(state.initialHeadingDegrees)
                            val tip = p + Offset(cos(angle).toFloat(), -sin(angle).toFloat()) * (36f / zoom)
                            drawLine(TurnAmber, p, tip, 4f / zoom, StrokeCap.Round)
                            label("Starting direction", tip, TurnAmber)
                        }
                    }
                }
            }
        }
        Row(Modifier.align(Alignment.BottomEnd).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilledTonalButton(onClick = { zoom = (zoom / 1.5f).coerceAtLeast(1f) }, contentPadding = PaddingValues(10.dp)) { Text("−") }
            FilledTonalButton(onClick = { zoom = (zoom * 1.5f).coerceAtMost(8f) }, contentPadding = PaddingValues(10.dp)) { Text("+") }
            FilledTonalButton(onClick = { zoom = 1f; pan = Offset.Zero }, contentPadding = PaddingValues(10.dp)) { Text("Fit") }
        }
    }
}
