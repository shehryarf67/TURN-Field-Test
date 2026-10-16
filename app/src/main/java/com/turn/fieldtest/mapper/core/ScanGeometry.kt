package com.turn.fieldtest.mapper.core

import com.turn.fieldtest.core.*
import kotlin.math.*

data class ScanIssue(val message: String, val blocking: Boolean = true)
data class GeneratedScan(val document: ScanDocument, val width: Double, val height: Double,
    val floor: MapFloor, val nodes: List<NavigationNode>, val edges: List<NavigationEdge>, val transform: Alignment)

object ScanGeometry {
    /** Explicit editing action. Opening-bearing walls keep their identity and endpoints. */
    fun cleanupWalls(input: List<ScanWall>,protected: Set<String>,tolerance: Double): List<ScanWall> {
        require(tolerance in 0.001..0.3)
        val fixed=input.filter {it.id in protected}.flatMap {listOf(it.a,it.b)}.toMutableList()
        fun snap(p: P2): P2 {val nearest=fixed.filter {it.distance(p)<=tolerance}.minByOrNull {it.distance(p)}; if(nearest!=null) return nearest; fixed.add(p); return p}
        val adjusted=input.map {w->if(w.status=="CONFIRMED" && w.id !in protected) {
            val a=snap(w.a); val b=snap(w.b); if(a.distance(b)<.01) w else w.copy(a=a,b=b,source="MANUAL_CLEANUP")
        } else w}
        val lines=adjusted.filter {it.status=="CONFIRMED" && it.id !in protected && it.a.distance(it.b)>=.01}
        val merged=mergeWalls(lines,0.001)
        val all=merged+adjusted.filter {it.id in protected}
        val split=merged.flatMap {w->
            val dx=w.b.x-w.a.x; val dy=w.b.y-w.a.y
            val ts=mutableListOf(0.0,1.0)
            all.filter {it.id!=w.id}.forEach {other->
                val ex=other.b.x-other.a.x; val ey=other.b.y-other.a.y; val det=dx*ey-dy*ex
                if(abs(det)>1e-9) {
                    val ox=other.a.x-w.a.x; val oy=other.a.y-w.a.y
                    val t=(ox*ey-oy*ex)/det; val u=(ox*dy-oy*dx)/det
                    if(t in 1e-6..(1-1e-6) && u in -1e-6..(1+1e-6)) ts.add(t)
                }
            }
            ts.distinct().sorted().zipWithNext().mapIndexedNotNull {i,(a,b)->if((b-a)*w.a.distance(w.b)<.01) null else w.copy(id=if(ts.size==2) w.id else "${w.id}:split$i",a=P2(w.a.x+a*dx,w.a.y+a*dy),b=P2(w.a.x+b*dx,w.a.y+b*dy))}
        }
        return adjusted.filter {it.status!="CONFIRMED" || it.id in protected}+split
    }
    fun outlineError(points: List<P2>): String? = runCatching {
        require(points.size >= 3) { "Room outline is incomplete: mark at least three corners" }
        require(points.all { it.finite }) { "Room has invalid coordinates" }
        require(points.all {abs(it.x)<=1000 && abs(it.y)<=1000}) {"Room coordinates exceed ±1000 m; check units"}
        validateSimpleWalkablePolygon(MetricPolygon(points.map { it.metric() }))
    }.exceptionOrNull()?.message

    fun projection(p: P2, a: P2, b: P2): P2 = projectOnSegment(p.metric(),a.metric(),b.metric()).let { P2(it.x,it.y) }
    fun collinear(a: ScanWall, b: ScanWall, tolerance: Double): Boolean {
        val dx=a.b.x-a.a.x; val dy=a.b.y-a.a.y; val length=a.a.distance(a.b)
        if (length < 1e-6 || b.a.distance(b.b) < 1e-6) return false
        fun deviation(p: P2)=abs(dx*(p.y-a.a.y)-dy*(p.x-a.a.x))/length
        val angle=abs(dx*(b.b.y-b.a.y)-dy*(b.b.x-b.a.x))/(length*b.a.distance(b.b))
        return angle <= 0.04 && deviation(b.a) <= tolerance && deviation(b.b) <= tolerance
    }
    /** Only joins overlapping/touching collinear segments; never bridges an intentional gap. */
    tailrec fun mergeWalls(input: List<ScanWall>, tolerance: Double): List<ScanWall> {
        val result=mutableListOf<ScanWall>()
        input.filter { it.a.finite && it.b.finite && it.a.distance(it.b) > 0.01 }.forEach { w ->
            val i=result.indexOfFirst { a -> collinear(a,w,tolerance) &&
                minOf(a.a.distance(projection(a.a,w.a,w.b)),a.b.distance(projection(a.b,w.a,w.b)),
                    w.a.distance(projection(w.a,a.a,a.b)),w.b.distance(projection(w.b,a.a,a.b))) <= tolerance }
            if(i<0) result.add(w) else {
                val a=result[i]; val dx=a.b.x-a.a.x; val dy=a.b.y-a.a.y; val length=a.a.distance(a.b)
                val ts=listOf(a.a,a.b,w.a,w.b).map { ((it.x-a.a.x)*dx+(it.y-a.a.y)*dy)/length }
                fun point(t: Double)=P2(a.a.x+dx/length*t,a.a.y+dy/length*t)
                result[i]=a.copy(a=point(ts.min()),b=point(ts.max()),observations=a.observations+w.observations)
            }
        }
        return if(result.size<input.size) mergeWalls(result,tolerance) else result
    }

    fun collisionWalls(doc: ScanDocument): List<ScanWall> {
        // Openings are cut AFTER shared boundaries merge. They cannot be healed by endpoint snapping.
        val originals=doc.allWalls().associateBy {it.id}
        val openings=doc.doors.mapNotNull {door->originals[door.wallId]?.let {w->door.copy(a=projection(door.a,w.a,w.b),b=projection(door.b,w.a,w.b))}}
        return mergeWalls(doc.allWalls(),0.001).flatMap { wall ->
            val length=wall.a.distance(wall.b); val dx=(wall.b.x-wall.a.x)/length; val dy=(wall.b.y-wall.a.y)/length
            fun t(p: P2)=(p.x-wall.a.x)*dx+(p.y-wall.a.y)*dy
            val gaps=openings.filter { it.walkable && collinear(wall,ScanWall(a=it.a,b=it.b,source="DOOR",segmentId=""),0.02) }
                .map { min(t(it.a),t(it.b)).coerceIn(0.0,length) to max(t(it.a),t(it.b)).coerceIn(0.0,length) }
                .filter { it.second-it.first > 0.01 }.sortedBy { it.first }
            val pieces=mutableListOf<Pair<Double,Double>>(); var cursor=0.0
            gaps.forEach { (a,b) -> if(a>cursor) pieces.add(cursor to a); cursor=max(cursor,b) }
            if(cursor<length) pieces.add(cursor to length)
            pieces.mapIndexedNotNull { i,(a,b) -> if(b-a<0.01) null else wall.copy(id="${wall.id}:part$i",
                a=P2(wall.a.x+dx*a,wall.a.y+dy*a),b=P2(wall.a.x+dx*b,wall.a.y+dy*b)) }
        }
    }

    fun issues(doc: ScanDocument): List<ScanIssue> = buildList {
        if(doc.name.isBlank()) add(ScanIssue("Name the mapped floor"))
        doc.walls.filter {it.status=="CONFIRMED"}.forEach {w->if(!w.a.finite || !w.b.finite || w.a.distance(w.b)<.01) add(ScanIssue("Confirmed wall ${w.id.take(6)} has invalid endpoints"))}
        if(doc.rooms.isEmpty()) add(ScanIssue("Start and confirm at least one room"))
        doc.rooms.forEach { r ->
            outlineError(r.corners.map { it.p })?.let { add(ScanIssue("${r.name}: $it")) }
            if(!r.confirmed) add(ScanIssue("${r.name}: outline is not confirmed"))
            if(r.name.isBlank()) add(ScanIssue("Name every room"))
        }
        val used=doc.points().map { it.segmentId }.toSet()+doc.walls.filter { it.status=="CONFIRMED" }.map { it.segmentId }
        used.forEach { id ->
            val s=doc.segments.firstOrNull { it.id==id }
            if(s==null || !s.aligned) add(ScanIssue("Capture section $id has unresolved alignment"))
            if(s?.interrupted==true && !s.reviewedAfterLoss) add(ScanIssue("Tracking was lost in section $id: review geometry and measurements"))
        }
        if(doc.walls.any { it.status=="CANDIDATE" }) add(ScanIssue("Unconfirmed wall candidates are excluded from the saved map",false))
        val walls=doc.allWalls().associateBy { it.id }
        doc.doors.forEach { door ->
            val wall=walls[door.wallId]
            if(wall==null) add(ScanIssue("Doorway ${door.id.take(6)} has not been assigned to an existing wall"))
            else if(door.a.distance(projection(door.a,wall.a,wall.b))>doc.thresholds.doorToleranceMetres ||
                door.b.distance(projection(door.b,wall.a,wall.b))>doc.thresholds.doorToleranceMetres)
                add(ScanIssue("Doorway ${door.id.take(6)} endpoints do not lie on their wall"))
            if(door.a.distance(door.b) !in 0.45..3.0) add(ScanIssue("Doorway width must be 0.45–3 m; verify its endpoints"))
            if(door.roomIds.isEmpty() || door.roomIds.any { id -> doc.rooms.none { it.id==id } }) add(ScanIssue("Assign doorway to existing room(s)"))
            door.roomIds.mapNotNull { id -> doc.rooms.firstOrNull { it.id==id } }.forEach { r ->
                if(outlineError(r.corners.map { it.p })==null) {
                    val mid=P2((door.a.x+door.b.x)/2,(door.a.y+door.b.y)/2)
                    val polygon=MetricPolygon(r.corners.map { it.p.metric() })
                    val boundary=listOf(door.a,mid,door.b).maxOf {polygon.closestBoundaryPoint(it.metric()).distanceMetres}
                    if(boundary>0.03) add(ScanIssue("Doorway does not match the boundary of ${r.name}; adjust room alignment"))
                }
            }
        }
        // Interior overlap indicates incompatible scans. Touching shared boundaries are allowed.
        val valid=doc.rooms.filter { outlineError(it.corners.map { p->p.p })==null }
        valid.forEachIndexed { i,a -> valid.drop(i+1).forEach { b ->
            val pa=MetricPolygon(a.corners.map { it.p.metric() }); val pb=MetricPolygon(b.corners.map { it.p.metric() })
            if(pb.contains(interior(pa),false) || pa.contains(interior(pb),false) || a.corners.any { pb.contains(it.p.metric(),false) } || b.corners.any { pa.contains(it.p.metric(),false) } ||
                pa.edges.any {pb.contains(MetricPoint((it.start.x+it.end.x)/2,(it.start.y+it.end.y)/2),false)} ||
                pb.edges.any {pa.contains(MetricPoint((it.start.x+it.end.x)/2,(it.start.y+it.end.y)/2),false)} ||
                pa.edges.any { ea->pb.edges.any { eb->MetricGeometry.intersection(ea,eb)==SegmentIntersection.CROSS } })
                add(ScanIssue("${a.name} and ${b.name} overlap: correct their alignment"))
        } }
        val pairs=doc.measurements.filter { it.purpose=="CALIBRATION" }.map { setOf(it.pointA,it.pointB) }
        doc.measurements.forEach { m ->
            val a=doc.points().firstOrNull { it.id==m.pointA }; val b=doc.points().firstOrNull { it.id==m.pointB }
            if(a==null || b==null || !m.measured.isFinite() || m.measured<=0) add(ScanIssue("Measurement has missing endpoints or invalid length"))
            else if(m.purpose=="VALIDATION") {
                if(setOf(m.pointA,m.pointB) in pairs) add(ScanIssue("A calibration pair cannot also be independent validation"))
                val error=abs(a.p.distance(b.p)-m.measured)
                if(error>max(doc.thresholds.validationAbsoluteTolerance,m.measured*doc.thresholds.validationRelativeTolerance))
                    add(ScanIssue("Independent length disagrees by %.2f m: remeasure, correct or rescan".format(error)))
            }
        }
        if(doc.measurements.none { it.purpose=="VALIDATION" }) add(ScanIssue("Add an independent measured check distance before publishing"))
        val currentPoints=doc.points().associateBy {it.id}
        val scales=doc.measurements.filter {it.purpose=="CALIBRATION"}.mapNotNull {m->
            val a=currentPoints[m.pointA]?.p; val b=currentPoints[m.pointB]?.p
            if(a!=null && b!=null && a.distance(b)>.01) m.measured/a.distance(b) else null
        }
        if(scales.size>1 && scales.max()/scales.min()>1.05) add(ScanIssue("Scale verification is inconsistent; one scale cannot resolve nonuniform drift"))
        if(doc.rooms.size>1) {
            val reached=mutableSetOf(doc.rooms.first().id); var changed=true
            while(changed) { changed=false; doc.doors.filter { it.walkable && it.roomIds.any(reached::contains) }.forEach {
                if(reached.addAll(it.roomIds)) changed=true
            } }
            if(reached.size<doc.rooms.size) add(ScanIssue("Rooms are disconnected: mark and link their doorway openings"))
        }
    }

    fun interior(polygon: MetricPolygon): MetricPoint {
        val centre=polygon.centroid(); if(polygon.contains(centre,false)) return centre
        val minX=polygon.vertices.minOf { it.x }; val maxX=polygon.vertices.maxOf { it.x }
        val minY=polygon.vertices.minOf { it.y }; val maxY=polygon.vertices.maxOf { it.y }
        return (1..39).flatMap { x->(1..39).map { y->MetricPoint(minX+(maxX-minX)*x/40,minY+(maxY-minY)*y/40) } }
            .filter { polygon.contains(it,false) }.maxByOrNull { polygon.closestBoundaryPoint(it).distanceMetres }
            ?: throw IllegalArgumentException("Room too narrow: choose a manual safe navigation node")
    }

    fun generate(original: ScanDocument, floorId: String): GeneratedScan {
        val errors=issues(original).filter { it.blocking }; require(errors.isEmpty()) { errors.joinToString("; ") { it.message } }
        val points=original.points().map { it.p }+original.allWalls().flatMap { listOf(it.a,it.b) }
        val minX=points.minOf { it.x }; val minY=points.minOf { it.y }
        val t=Alignment(tx=0.25-minX,ty=0.25-minY)
        val doc=original.transformed(t); val width=points.maxOf { it.x }-minX+0.5; val height=points.maxOf { it.y }-minY+0.5
        require(width in 0.5..1000.0 && height in 0.5..1000.0) { "Map dimensions must be below 1000 m" }
        val polygons=doc.rooms.map { MetricPolygon(it.corners.map { p->p.p.metric() }) }
        val floor=MapFloor(floorId,polygons,collisionWalls(doc).map { WallSegment(it.id,it.a.metric(),it.b.metric()) })
        val nodes=doc.rooms.mapIndexed { i,r->NavigationNode("room:${r.id}",floorId,interior(polygons[i]),r.id) }.toMutableList()
        val edges=mutableListOf<NavigationEdge>()
        doc.doors.filter { it.walkable }.forEach { door ->
            val p=MetricPoint((door.a.x+door.b.x)/2,(door.a.y+door.b.y)/2)
            val doorNode=NavigationNode("door:${door.id}",floorId,p); nodes.add(doorNode)
            door.roomIds.forEach { room -> val centre=nodes.firstOrNull { it.poiId==room }
                if(centre!=null && floor.isPathInsideWalkableSpace(centre.point,p) && !floor.crossesWall(centre.point,p))
                    edges.add(NavigationEdge("${room}:${door.id}",centre.id,doorNode.id,centre.point.distanceTo(p)))
            }
        }
        val graph=NavigationGraph(floor,nodes,edges)
        require(graph.validationErrors().isEmpty()) { graph.validationErrors().joinToString("; ") }
        // Safe direct proposals only. Concave rooms can require manual path nodes in TURN's editor.
        return GeneratedScan(doc,width,height,floor,nodes,edges,t)
    }
}
