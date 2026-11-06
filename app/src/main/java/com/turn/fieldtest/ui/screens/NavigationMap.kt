package com.turn.fieldtest.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.geometry.Size
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
import androidx.compose.ui.unit.LayoutDirection
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
    onDestinationTapped: ((String) -> Unit)? = null,
    viewportPadding: PaddingValues = PaddingValues(0.dp),
    controlsPadding: PaddingValues = PaddingValues(12.dp),
    compactControls: Boolean = false,
    showCalibration: Boolean = false,
    previewRoute: List<Offset>? = null,
    followRequested: Boolean = showPosition,
    focusDestination: Boolean = false,
) {
    var zoom by remember(state.activeProject) { mutableFloatStateOf(1f) }
    var pan by remember(state.activeProject) { mutableStateOf(Offset.Zero) }
    var following by remember(state.activeProject) { mutableStateOf(false) }
    val tapHandler by rememberUpdatedState(onTap)
    val destinationHandler by rememberUpdatedState(onDestinationTapped)
    val palette = MaterialTheme.colorScheme
    BoxWithConstraints(modifier.semantics { contentDescription = "Floor map" }.clipToBounds().background(palette.surfaceContainerLow)) {
        val density = LocalDensity.current
        val width = with(density) { maxWidth.toPx() }; val height = with(density) { maxHeight.toPx() }
        val left = with(density) { viewportPadding.calculateLeftPadding(LayoutDirection.Ltr).toPx() }
        val right = with(density) { viewportPadding.calculateRightPadding(LayoutDirection.Ltr).toPx() }
        val top = with(density) { viewportPadding.calculateTopPadding().toPx() }
        val bottom = with(density) { viewportPadding.calculateBottomPadding().toPx() }
        val availableWidth = (width - left - right).coerceAtLeast(1f)
        val availableHeight = (height - top - bottom).coerceAtLeast(1f)
        val scale = min(availableWidth / state.floorWidthMetres, availableHeight / state.floorHeightMetres) * 0.92f
        val imageWidth = scale * state.floorWidthMetres; val imageHeight = scale * state.floorHeightMetres
        val origin = Offset(left + (availableWidth - imageWidth) / 2, top + (availableHeight + imageHeight) / 2)
        val centre = Offset(width / 2, height / 2)
        val viewportCentre = Offset(left + availableWidth / 2, top + availableHeight / 2)
        fun project(p: Offset) = Offset(origin.x + p.x * scale, origin.y - p.y * scale)
        val liveFocus = state.realLivePosition?.takeIf { state.liveRunning && state.realLiveInitialized }
        LaunchedEffect(followRequested, state.liveRunning, liveFocus != null, width, height) {
            if (followRequested && state.liveRunning && liveFocus != null) {
                following = true
                zoom = (min(availableWidth, availableHeight) / 18f / scale).coerceIn(1f, 12f)
            } else following = false
        }
        LaunchedEffect(state.selectedDestinationId, focusDestination, previewRoute, width, height) {
            if (focusDestination && !state.liveRunning) state.destinations.firstOrNull { it.id == state.selectedDestinationId }?.let {
                val destination = Offset(it.xMetres.toFloat(), it.yMetres.toFloat())
                val points = previewRoute.orEmpty().ifEmpty { listOf(destination) }
                val lowX = points.minOf { p -> p.x }; val highX = points.maxOf { p -> p.x }
                val lowY = points.minOf { p -> p.y }; val highY = points.maxOf { p -> p.y }
                zoom = min(availableWidth / ((highX-lowX+10f)*scale), availableHeight / ((highY-lowY+10f)*scale)).coerceIn(1f, 8f)
                pan = viewportCentre-centre-(project(Offset((lowX+highX)/2,(lowY+highY)/2))-centre)*zoom
            }
        }
        val targetPan = if (following && liveFocus != null) viewportCentre-centre-(project(liveFocus)-centre)*zoom else pan
        val animatedFollowPan by animateOffsetAsState(targetPan, tween(220), label="Navigation camera")
        // Finger movement must never feed back through an animation's previous frame.
        val cameraPan = if (following) animatedFollowPan else pan
        val latestCameraPan by rememberUpdatedState(cameraPan)
        Box(Modifier.fillMaxSize()
            .pointerInput(state.activeProject, width, height) {
                detectTransformGestures { centroid, delta, factor, _ ->
                    val currentPan = if (following) latestCameraPan else pan
                    val next = (zoom * factor).coerceIn(1f, 12f)
                    pan = centroid - centre - (centroid - centre - currentPan) * (next / zoom) + delta
                    following = false
                    zoom = next
                }
            }
            .pointerInput(state.activeProject, scale, origin, width, height) {
                detectTapGestures { screen ->
                    val base = (screen - centre - latestCameraPan) / zoom + centre
                    val point = Offset((base.x - origin.x) / scale, (origin.y - base.y) / scale)
                    if (point.x in 0f..state.floorWidthMetres && point.y in 0f..state.floorHeightMetres) {
                        val tolerance = 22f / (scale * zoom)
                        val area = if (destinationHandler == null) null else state.destinationAreas.firstOrNull {
                            it.polygon.contains(com.turn.fieldtest.core.MetricPoint(point.x.toDouble(), point.y.toDouble())) &&
                                state.destinations.any { poi -> poi.id == it.destinationId }
                        }
                        val poi = if (destinationHandler == null) null else state.destinations.minByOrNull {
                            (Offset(it.xMetres.toFloat(), it.yMetres.toFloat()) - point).getDistance()
                        }?.takeIf { (Offset(it.xMetres.toFloat(), it.yMetres.toFloat()) - point).getDistance() <= tolerance }
                        if (area != null) destinationHandler?.invoke(area.destinationId)
                        else if (poi != null) destinationHandler?.invoke(poi.id) else tapHandler?.invoke(point, tolerance)
                    }
                }
            }) {
            Box(Modifier.fillMaxSize().graphicsLayer {
                scaleX = zoom; scaleY = zoom; translationX = cameraPan.x; translationY = cameraPan.y
            }) {
                state.floorPlanContentUri?.takeIf { state.mapLayerVisible }?.let { uri ->
                    FloorPlanImage(uri, Modifier.offset(x = with(density) { origin.x.toDp() },
                        y = with(density) { (origin.y - imageHeight).toDp() })
                        .size(with(density) { imageWidth.toDp() }, with(density) { imageHeight.toDp() }), alpha = 1f)
                }
                Canvas(Modifier.fillMaxSize()) {
                    state.destinationAreas.filter { it.destinationId == state.selectedDestinationId }.forEach { area ->
                        val path = Path().apply { area.polygon.vertices.forEachIndexed { i,v ->
                            val p = project(Offset(v.x.toFloat(),v.y.toFloat()))
                            if(i==0) moveTo(p.x,p.y) else lineTo(p.x,p.y)
                        }; close() }
                        drawPath(path,TurnBlue.copy(alpha=.14f))
                        drawPath(path,TurnBlue,style=Stroke(2f/zoom))
                    }
                    state.otherWalkableRegions.filter { state.geometryLayerVisible }.forEach { region ->
                        val vertices=com.turn.fieldtest.data.repository.decodePolygon(region.polygonJson).vertices.map { project(Offset(it.x.toFloat(),it.y.toFloat())) }
                        val outline=Path().apply { vertices.forEachIndexed { i,p->if(i==0) moveTo(p.x,p.y) else lineTo(p.x,p.y) }; close() }
                        drawPath(outline,palette.primary.copy(alpha=0.08f)); drawPath(outline,palette.primary.copy(alpha=0.4f),style=Stroke(1.5f/zoom))
                    }
                    fun line(points: List<Offset>, color: Color, stroke: Float) {
                        if (points.size < 2) return
                        val path = Path().apply { val first = project(points.first()); moveTo(first.x, first.y)
                            points.drop(1).forEach { val p = project(it); lineTo(p.x, p.y) } }
                        drawPath(path, color, style = Stroke(stroke / zoom, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                    if (state.geometryLayerVisible && state.draftWalkablePolygon.size >= 3) {
                        val path = Path().apply { val first = project(state.draftWalkablePolygon.first()); moveTo(first.x, first.y)
                            state.draftWalkablePolygon.drop(1).forEach { val p = project(it); lineTo(p.x, p.y) }; close() }
                        drawPath(path, palette.primary.copy(alpha = 0.06f))
                        drawPath(path, palette.primary.copy(alpha = 0.4f), style = Stroke(1.5f / zoom))
                    } else if (state.geometryLayerVisible) line(state.draftWalkablePolygon.toList(), palette.primary, 2f)
                    state.draftWalls.filter { state.geometryLayerVisible }.forEach { (a, b) -> drawLine(palette.outline, project(a), project(b), 3f / zoom) }
                    state.pendingWallStart?.let { drawCircle(TurnAmber, 5f / zoom, project(it)) }
                    if (showCalibration) {
                        state.calibrationPoints.forEach { drawCircle(TurnAmber, 6f / zoom, project(it), style = Stroke(2f / zoom)) }
                    }
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
                    val displayedRoute = previewRoute ?: state.routePoints
                    val routeWidth = with(density) { (if (state.liveRunning) 8.dp else 5.dp).toPx() }
                    line(displayedRoute, Color.White.copy(alpha = 0.95f), routeWidth + with(density) { 3.dp.toPx() })
                    line(displayedRoute, TurnBlue, routeWidth)
                    val labelBounds = mutableListOf<android.graphics.RectF>()
                    fun label(text: String, p: Offset, color: Color, priority: Boolean = false) {
                        if (!state.labelsLayerVisible) return
                        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                            this.color = (if (color == Color.White) palette.onSurface else color).toArgb()
                            textSize = 11f * density.density / zoom
                        }
                        val pad = 4f * density.density / zoom
                        val maxTextWidth = 112f * density.density / zoom
                        val displayed = android.text.TextUtils.ellipsize(text, android.text.TextPaint(paint),
                            maxTextWidth, android.text.TextUtils.TruncateAt.END).toString()
                        val textWidth = paint.measureText(displayed)
                        val gap = 10f / zoom
                        // Keep labels in the visible map viewport and omit overlapping labels.
                        // Every destination remains available through search or its map marker.
                        val candidates = listOf(p.x + gap to p.y - gap,
                            p.x - gap - textWidth to p.y - gap)
                        val candidate = candidates.firstOrNull { (x, y) ->
                            val rect = android.graphics.RectF(x - pad, y + paint.fontMetrics.ascent - pad,
                                x + textWidth + pad, y + paint.fontMetrics.descent + pad)
                            val screenLeft = centre.x + (rect.left - centre.x) * zoom + cameraPan.x
                            val screenRight = centre.x + (rect.right - centre.x) * zoom + cameraPan.x
                            val screenTop = centre.y + (rect.top - centre.y) * zoom + cameraPan.y
                            val screenBottom = centre.y + (rect.bottom - centre.y) * zoom + cameraPan.y
                            screenLeft >= left && screenRight <= width - right && screenTop >= top &&
                                screenBottom <= height - bottom && (priority || labelBounds.none {
                                    android.graphics.RectF.intersects(it, rect)
                                })
                        } ?: return
                        val (x, y) = candidate
                        val rect = android.graphics.RectF(x - pad, y + paint.fontMetrics.ascent - pad,
                            x + textWidth + pad, y + paint.fontMetrics.descent + pad)
                        labelBounds += rect
                        val background = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { this.color = palette.surface.copy(alpha = 0.96f).toArgb() }
                        drawContext.canvas.nativeCanvas.drawRoundRect(rect, pad, pad, background)
                        drawContext.canvas.nativeCanvas.drawText(displayed, x, y, paint)
                    }
                    state.destinations.sortedByDescending { it.id == state.selectedDestinationId }.forEach { poi ->
                        val p = project(Offset(poi.xMetres.toFloat(), poi.yMetres.toFloat()))
                        drawCircle(Color.White, 8f / zoom, p)
                        drawCircle(if (poi.id == state.selectedDestinationId) TurnBlue else palette.primary, 6f / zoom, p)
                        label(poi.name, p, Color.White, priority = poi.id == state.selectedDestinationId)
                    }
                    if (showReferences) {
                      state.referencePoints.forEach { point ->
                        val p = project(point.metres)
                        val color = when {
                            point.id == state.selectedSurveyReferencePointId && state.surveyRunning -> TurnAmber
                            state.surveyPointProgress[point.id]?.status == "COMPLETED" -> TurnMint
                            else -> TurnCyan
                        }
                        drawCircle(color, (if (point.id == state.selectedSurveyReferencePointId) 8f else 5f) / zoom, p)
                        label(point.label, p, Color.White)
                      }
                    }
                    if (showPosition && state.debugOverlays) {
                        if (state.showRawPdr) { line(state.realRawPdrTrail, TurnAmber, 2f) }
                        if (state.showWifiFixes) {
                          state.realWifiPosition?.let {
                            val p = project(it)
                            drawCircle(TurnMint, 7f / zoom, p, style = Stroke(2f / zoom))
                            label("Last Wi-Fi match", p, TurnMint)
                          }
                        }
                        if (state.showParticles) state.realParticleCloud.forEach { drawCircle(TurnCyan.copy(alpha = 0.6f), 2f / zoom, project(it)) }
                    }
                    if (showPosition) {
                      state.realLivePosition?.let { position ->
                        val p = project(position)
                        state.realLiveUncertaintyMetres?.takeIf { state.liveRunning && it.isFinite() }?.let {
                            drawCircle(TurnBlue.copy(alpha = 0.15f), it.toFloat() * scale, p)
                            drawCircle(TurnBlue.copy(alpha = 0.5f), it.toFloat() * scale, p, style = Stroke(1.5f / zoom))
                        }
                        val angle = Math.toRadians(state.realLiveHeadingDegrees)
                        if (state.liveRunning) {
                            val radius = 48f * density.density / zoom
                            drawArc(TurnBlue.copy(alpha=.16f),(-state.realLiveHeadingDegrees-32).toFloat(),64f,true,
                                topLeft=p-Offset(radius,radius),size=Size(radius*2,radius*2))
                            drawLine(TurnBlue, p, p + Offset(cos(angle).toFloat(), -sin(angle).toFloat()) * (24f * density.density / zoom), 3f * density.density / zoom, StrokeCap.Round)
                        }
                        drawCircle(Color.White, 10f * density.density / zoom, p)
                        drawCircle(if (state.liveRunning) TurnBlue else palette.onSurfaceVariant, 7f * density.density / zoom, p)
                        if (!state.liveRunning) label("Last position", p, palette.onSurfaceVariant, priority = true)
                      }
                    }
                    if (showPosition && !state.liveRunning) {
                      state.manualStartPoint?.let {
                        val p = project(it)
                        drawCircle(TurnBlue, 9f / zoom, p, style = Stroke(3f / zoom))
                        if (state.headingChoiceConfirmed) {
                            val angle = Math.toRadians(state.initialHeadingDegrees)
                            val tip = p + Offset(cos(angle).toFloat(), -sin(angle).toFloat()) * (36f / zoom)
                            drawLine(TurnAmber, p, tip, 4f / zoom, StrokeCap.Round)
                            label("Starting direction", tip, TurnAmber, priority = true)
                        }
                      }
                    }
                }
            }
        }
        Column(Modifier.align(Alignment.BottomEnd).padding(controlsPadding), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!compactControls) {
              Surface(shape = MaterialTheme.shapes.large, color = palette.surface, shadowElevation = 1.dp) {
                Column {
                    IconButton(onClick = { zoom = (zoom * 1.5f).coerceAtMost(8f) }) { Icon(Icons.Outlined.Add, "Zoom in") }
                    IconButton(onClick = { zoom = (zoom / 1.5f).coerceAtLeast(1f) }) { Icon(Icons.Outlined.Remove, "Zoom out") }
                }
              }
            }
            Surface(shape = MaterialTheme.shapes.large, color = palette.surface, shadowElevation = 1.dp) {
                IconButton(onClick = { following=false; zoom = 1f; pan = Offset.Zero }) { Icon(Icons.Outlined.FitScreen, "Fit floor map") }
            }
            if (showPosition) {
              Surface(shape = MaterialTheme.shapes.large, color = palette.surface, shadowElevation = 1.dp) {
                IconButton(onClick = { state.realLivePosition?.let {
                    following=state.liveRunning && state.realLiveInitialized
                    if(following) zoom=(min(availableWidth,availableHeight)/18f/scale).coerceIn(1f,12f)
                    pan = viewportCentre - centre - (project(it) - centre) * zoom
                } }, enabled = state.realLivePosition != null) {
                    Icon(Icons.Outlined.MyLocation, if (following) "Following my position" else if (state.liveRunning) "Center on my position" else "Center on last position")
                }
              }
            }
        }
    }
}
