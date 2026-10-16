package com.turn.fieldtest.mapper.data

import android.content.Context
import android.graphics.*
import androidx.room.withTransaction
import com.turn.fieldtest.data.local.*
import com.turn.fieldtest.mapper.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.io.File
import kotlin.math.*

class ScanRepository(private val db: TurnDatabase,private val context: Context) {
    companion object { val json=Json { encodeDefaults=true; ignoreUnknownKeys=false } }
    suspend fun save(doc: ScanDocument) = db.withTransaction {
        require(doc.points().size<=2000 && doc.poses.size<=4000 && doc.walls.size<=300) { "Draft size limit reached; save a new section" }
        require(doc.version==1 && doc.rooms.size<=100 && doc.doors.size<=300 && doc.segments.size<=100) {"Unsupported or oversized scan draft"}
        require(db.floorPlanDao().getFloor(doc.floorId)?.venueId==doc.venueId) { "Draft belongs to another project" }
        db.scanDraftDao().save(ScanDraftEntity(doc.id,doc.floorId,json.encodeToString(doc),doc.status,doc.updatedAt))
    }
    suspend fun load(id: String) = db.scanDraftDao().get(id)?.let { row->
        json.decodeFromString<ScanDocument>(row.documentJson).also {doc->require(doc.id==row.id && doc.floorId==row.floorId && doc.version==1 && db.floorPlanDao().getFloor(row.floorId)?.venueId==doc.venueId) {"Scan ownership/version is invalid"}}
    }
    suspend fun drafts(floor: String) = db.scanDraftDao().forFloor(floor)

    /** Fork the project: venue+level is unique, so a revision must not masquerade as another storey. */
    suspend fun publish(doc: ScanDocument): String {
        val floorId="FL-${scanId()}"
        val mappedVenueId="VEN-${scanId()}"
        val generated=withContext(Dispatchers.Default) { ScanGeometry.generate(doc,floorId) }
        val image=withContext(Dispatchers.IO) { render(generated,floorId) }
        val now=System.currentTimeMillis()
        val floorName=doc.name.trim().ifBlank { "Mapped floor" }
        val newFloor=FloorEntity(id=floorId,venueId=mappedVenueId,name=floorName,levelNumber=0,
            widthMetres=generated.width,heightMetres=generated.height,createdAtEpochMillis=now,updatedAtEpochMillis=now)
        val newAsset=FloorPlanAssetEntity(id="ASSET-$floorId",floorId=floorId,contentUri=android.net.Uri.fromFile(image.first).toString(),
            mimeType="image/png",pixelWidth=image.second.first,pixelHeight=image.second.second,metresPerPixel=generated.width/image.second.first,importedAtEpochMillis=now)
        try {
            db.withTransaction {
                val sourceFloor=requireNotNull(db.floorPlanDao().getFloor(doc.floorId)) {"Source floor unavailable"}
                require(sourceFloor.venueId==doc.venueId) {"Source project unavailable"}
                val sourceVenue=requireNotNull(db.venueDao().get(doc.venueId)) {"Source venue unavailable"}
                val mappedName=(sourceVenue.name+" · mapped layout").take(120)
                db.venueDao().upsert(VenueEntity(mappedVenueId,mappedName,notes="Walk Mapper revision of ${doc.venueId}/${doc.floorId}; draft ${doc.id}",createdAtEpochMillis=now,updatedAtEpochMillis=now))
                val mappedFloor=newFloor.copy(levelNumber=sourceFloor.levelNumber)
                db.floorPlanDao().upsertFloor(mappedFloor)
                check(db.floorPlanDao().getFloor(floorId)==mappedFloor) {"New floor insertion was not retained"}
                db.floorPlanDao().upsertAsset(newAsset)
                generated.document.rooms.forEach { r -> db.floorPlanDao().upsertWalkableRegion(WalkableRegionEntity(
                    "REGION-$floorId-${r.id}",floorId,r.name,json.encodeToString(r.corners.map { it.p }),updatedAtEpochMillis=now)) }
                generated.floor.walls.forEachIndexed { i,w -> db.floorPlanDao().upsertWall(WallSegmentEntity(
                    "WALL-$floorId-$i",floorId,w.start.x,w.start.y,w.end.x,w.end.y)) }
                val idMap=generated.nodes.associate { it.id to "$floorId:${it.id}" }
                val pois=generated.document.rooms.map { r -> val n=generated.nodes.first { it.poiId==r.id }
                    PointOfInterestEntity("POI-$floorId-${r.id}",floorId,r.name,"ROOM",n.point.x,n.point.y,
                        "TURN WALK MAPPER; operator-confirmed geometry; draft ${doc.id}") }
                db.navigationDao().saveDestinations(pois)
                db.navigationDao().saveNodes(generated.nodes.map { NavigationNodeEntity(idMap.getValue(it.id),floorId,it.point.x,it.point.y,it.poiId?.let { p->"POI-$floorId-$p" }) })
                db.navigationDao().saveEdges(generated.edges.map { NavigationEdgeEntity("$floorId:${it.id}",floorId,idMap.getValue(it.from),idMap.getValue(it.to),it.distanceMetres) })
                val linked=generated.nodes.firstOrNull()?.let {first->
                    val reached=mutableSetOf(first.id); var changed=true
                    while(changed) {changed=false; generated.edges.forEach {e->if(e.from in reached || e.to in reached) {if(reached.add(e.from)) changed=true; if(reached.add(e.to)) changed=true}}}
                    reached.size==generated.nodes.size
                } ?: false
                val warnings=ScanGeometry.issues(doc).filterNot {it.blocking}.map {it.message}+
                    if(linked) emptyList() else listOf("Path proposals are disconnected. Complete safe paths in Map Setup before navigating.")
                save(doc.copy(publishedVenueId=mappedVenueId,publishedFloorId=floorId,publicationTransform=generated.transform,status=if(linked) "PUBLISHED" else "PUBLISHED_REVIEW_PATHS",publicationIssues=warnings,updatedAt=now))
            }
            return floorId
        } catch (e: Exception) { withContext(Dispatchers.IO) { image.first.delete() }; throw e }
    }

    private fun render(g: GeneratedScan,id: String): Pair<File,Pair<Int,Int>> {
        val ppm=minOf(100.0,4096/g.width,4096/g.height,sqrt(6_000_000.0/(g.width*g.height)))
        val w=ceil(g.width*ppm).toInt().coerceAtLeast(1); val h=ceil(g.height*ppm).toInt().coerceAtLeast(1)
        val bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
        val target=File(context.filesDir,"walk-mapper/$id.png"); target.parentFile!!.mkdirs()
        try {
            val canvas=Canvas(bitmap); canvas.drawColor(Color.rgb(244,247,250))
            fun x(p: P2)=(p.x/g.width*w).toFloat()
            fun y(p: P2)=((1-p.y/g.height)*h).toFloat()
            val paint=Paint(Paint.ANTI_ALIAS_FLAG)
            g.document.rooms.forEach { r ->
                val path=Path(); r.corners.forEachIndexed { i,p->if(i==0) path.moveTo(x(p.p),y(p.p)) else path.lineTo(x(p.p),y(p.p)) }; path.close()
                paint.style=Paint.Style.FILL; paint.color=Color.rgb(222,234,240); canvas.drawPath(path,paint)
                val c=ScanGeometry.interior(com.turn.fieldtest.core.MetricPolygon(r.corners.map { it.p.metric() }))
                paint.color=Color.rgb(24,51,71); paint.textSize=(ppm*0.18).toFloat().coerceIn(10f,24f); paint.textAlign=Paint.Align.CENTER
                canvas.drawText(r.name.take(36),x(P2(c.x,c.y)),y(P2(c.x,c.y)),paint)
            }
            paint.style=Paint.Style.STROKE; paint.strokeWidth=(ppm*0.055).toFloat().coerceAtLeast(2f); paint.color=Color.rgb(15,31,46)
            g.floor.walls.forEach { a->canvas.drawLine(x(P2(a.start.x,a.start.y)),y(P2(a.start.x,a.start.y)),x(P2(a.end.x,a.end.y)),y(P2(a.end.x,a.end.y)),paint) }
            paint.style=Paint.Style.FILL; paint.textSize=12f; paint.textAlign=Paint.Align.LEFT; paint.color=Color.DKGRAY
            canvas.drawText("Guided metric map · verify dimensions",8f,16f,paint)
            if(g.width>=1.5) { val p=P2(0.15,0.12); canvas.drawLine(x(p),y(p),x(p.copy(x=1.15)),y(p),paint); canvas.drawText("1 m",x(p),y(p)-4,paint) }
            target.outputStream().use { require(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) { "PNG generation failed" } }
            return target to (w to h)
        } catch(e: Exception) { target.delete(); throw e } finally { bitmap.recycle() }
    }
}
