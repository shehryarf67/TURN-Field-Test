package com.turn.fieldtest.mapper.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.turn.fieldtest.mapper.core.*
import kotlin.math.*

/** Preview and publication both use +X right, +Y up, in metres. Screen Y alone is inverted. */
@Composable
fun ScanPreview(doc: ScanDocument, selected: String?, debug: Boolean, modifier: Modifier,
    onPoint: (String)->Unit, onWall: (String)->Unit) {
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val currentDoc by rememberUpdatedState(doc)
    val selectPoint by rememberUpdatedState(onPoint)
    val selectWall by rememberUpdatedState(onWall)
    var hitPoints by remember { mutableStateOf(emptyList<Pair<String,Offset>>()) }
    var hitWalls by remember { mutableStateOf(emptyList<Triple<String,Offset,Offset>>()) }
    Box(modifier.background(Color(0xFF101E30))) {
        Canvas(Modifier.fillMaxSize().pointerInput(Unit) {
            detectTransformGestures { _,delta,scale,_ -> zoom=(zoom*scale).coerceIn(0.3f,15f); pan+=delta }
        }.pointerInput(Unit) {
            detectTapGestures { p ->
                val point=hitPoints.minByOrNull { (it.second-p).getDistance() }
                if(point!=null && (point.second-p).getDistance()<25.dp.toPx()) selectPoint(point.first)
                else hitWalls.minByOrNull { (_,a,b)->screenDistance(p,a,b) }?.let { (id,a,b)->
                    if(screenDistance(p,a,b)<22.dp.toPx()) selectWall(id)
                }
            }
        }) {
            val d=currentDoc
            val walls=d.allWalls()+d.walls.filter { it.status=="CANDIDATE" }
            val coordinates=d.points().map { it.p }+walls.flatMap { listOf(it.a,it.b) }
            val minX=coordinates.minOfOrNull { it.x } ?: -1.0; val minY=coordinates.minOfOrNull { it.y } ?: -1.0
            val maxX=coordinates.maxOfOrNull { it.x } ?: 1.0; val maxY=coordinates.maxOfOrNull { it.y } ?: 1.0
            val ppm=(min(size.width/(maxX-minX+1).toFloat(),size.height/(maxY-minY+1).toFloat())*zoom).coerceAtLeast(.01f)
            fun screen(p: P2)=Offset(size.width/2+(p.x-(minX+maxX)/2).toFloat()*ppm,
                size.height/2-(p.y-(minY+maxY)/2).toFloat()*ppm)+pan
            d.rooms.forEach { r ->
                if(r.corners.size>=3) {
                    val path=Path(); r.corners.forEachIndexed { i,p->val s=screen(p.p); if(i==0) path.moveTo(s.x,s.y) else path.lineTo(s.x,s.y) }; path.close()
                    drawPath(path,if(r.confirmed) Color(0x2231D1EC) else Color(0x22FFB547))
                    drawPath(path,if(r.confirmed) Color(0xFF51D5E8) else Color(0xFFFFB547),style=Stroke(2f))
                    val x=r.corners.map { it.p.x }.average(); val y=r.corners.map { it.p.y }.average(); val s=screen(P2(x,y))
                    drawContext.canvas.nativeCanvas.drawText(r.name,s.x,s.y,android.graphics.Paint().apply { color=android.graphics.Color.WHITE; textSize=13.dp.toPx(); textAlign=android.graphics.Paint.Align.CENTER })
                }
            }
            walls.forEach { w ->
                val a=screen(w.a); val b=screen(w.b)
                drawLine(if(w.id==selected) Color.White else if(w.status=="CONFIRMED") Color(0xFF42D9F5) else Color(0xFFFFB547),a,b,if(w.id==selected) 7f else 4f)
            }
            d.doors.forEach { drawLine(if(it.walkable) Color(0xFF6EE7A1) else Color(0xFFF97F7F),screen(it.a),screen(it.b),9f) }
            if(debug) d.poses.zipWithNext().filter { it.first.segmentId==it.second.segmentId }.forEach { (a,b)->
                if(a.mapPoint!=null && b.mapPoint!=null) drawLine(Color(0x889BA9C1),screen(a.mapPoint),screen(b.mapPoint),2f)
            }
            d.poses.lastOrNull()?.mapPoint?.let { drawCircle(Color(0xFF4D94FF),7f,screen(it)) }
            d.points().forEach { drawCircle(if(it.id==selected) Color.White else Color(0xFF7CEAFF),if(it.id==selected) 9f else 5f,screen(it.p)) }
            // Hit targets change with the viewport, never with a guessed conversion from screen pixels.
            hitPoints=d.points().map { it.id to screen(it.p) }
            hitWalls=walls.map { Triple(it.id,screen(it.a),screen(it.b)) }
            val a=Offset(16.dp.toPx(),size.height-20.dp.toPx()); drawLine(Color.White,a,a+Offset(ppm,0f),3f)
            drawContext.canvas.nativeCanvas.drawText("1 m",a.x,a.y-8,android.graphics.Paint().apply { color=android.graphics.Color.WHITE; textSize=11.dp.toPx() })
        }
        TextButton(onClick={ zoom=1f; pan=Offset.Zero },modifier=Modifier.align(androidx.compose.ui.Alignment.TopEnd)) { Text("Fit map") }
    }
}
private fun screenDistance(p: Offset,a: Offset,b: Offset): Float {
    val v=b-a; val denominator=v.x*v.x+v.y*v.y
    if(denominator<.001f) return (p-a).getDistance()
    val t=(((p.x-a.x)*v.x+(p.y-a.y)*v.y)/denominator).coerceIn(0f,1f)
    return (p-(a+v*t)).getDistance()
}
