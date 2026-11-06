package com.turn.fieldtest.data.repository

import android.content.Context
import android.graphics.*
import androidx.room.withTransaction
import com.turn.fieldtest.data.local.*
import kotlinx.coroutines.flow.first
import java.io.File
import java.util.UUID

class MapperImporter(private val db: TurnDatabase, private val context: Context) {
    suspend fun install(p: MapperPackage, sourceJson: String, replaceExisting: Boolean): ProjectContext {
        val now=System.currentTimeMillis(); val prefix="MAPPER-${UUID.randomUUID()}"
        val result=ProjectContext("$prefix-V", "$prefix-F")
        val file=File(context.filesDir,"$prefix.png")
        val obsoleteImages=mutableListOf<File>()
        try {
            render(p,file)
            db.withTransaction {
                // Deletion and replacement are one transaction, after complete parsing/routing validation.
                if(replaceExisting) db.venueDao().observeAll().first().forEach { venue ->
                    db.floorPlanDao().observeFloors(venue.id).first().forEach { floor ->
                        db.floorPlanDao().observeAsset(floor.id).first()?.contentUri?.let { uri ->
                            val parsed=android.net.Uri.parse(uri)
                            if(parsed.scheme=="file") parsed.path?.let {obsoleteImages.add(File(it))}
                        }
                    }
                    db.venueDao().delete(venue)
                }
                db.venueDao().upsert(VenueEntity(result.venueId,p.name,
                    notes="TURN-Mapper ${p.revision}\n${p.provenance}\n$sourceJson",createdAtEpochMillis=now,updatedAtEpochMillis=now))
                val dao=db.floorPlanDao()
                dao.upsertFloor(FloorEntity(result.floorId,result.venueId,p.floorId,p.level,p.width,p.height,
                    createdAtEpochMillis=now,updatedAtEpochMillis=now))
                dao.upsertAsset(FloorPlanAssetEntity("$prefix-image",result.floorId,android.net.Uri.fromFile(file).toString(),"image/png",importedAtEpochMillis=now))
                p.regions.forEachIndexed { i, region -> dao.upsertWalkableRegion(WalkableRegionEntity("$prefix-region-$i",result.floorId,"Walkable ${i+1}",
                    kotlinx.serialization.json.JsonArray(region.vertices.map { v -> kotlinx.serialization.json.buildJsonObject {
                        put("x",kotlinx.serialization.json.JsonPrimitive(v.x)); put("y",kotlinx.serialization.json.JsonPrimitive(v.y)) } }).toString(),updatedAtEpochMillis=now)) }
                p.walls.forEachIndexed { i,w -> dao.upsertWall(WallSegmentEntity("$prefix-wall-$i",result.floorId,w.start.x,w.start.y,w.end.x,w.end.y)) }
                val nodes=p.nodes.associateBy {it.id}
                val poiByNode=p.places.associateBy {it.nodeId}
                require(poiByNode.size==p.places.size) {"Multiple destinations on the same node need review"}
                val nav=db.navigationDao()
                nav.saveDestinations(p.places.map { val n=nodes.getValue(it.nodeId); PointOfInterestEntity("$prefix-${it.id}",result.floorId,it.name,it.category,n.point.x,n.point.y) })
                nav.saveNodes(p.nodes.map { NavigationNodeEntity("$prefix-${it.id}",result.floorId,it.point.x,it.point.y,poiByNode[it.id]?.let {poi->"$prefix-${poi.id}"}) })
                nav.saveEdges(p.edges.map { NavigationEdgeEntity("$prefix-${it.id}",result.floorId,"$prefix-${it.from}","$prefix-${it.to}",it.distanceMetres,
                    bidirectional=it.bidirectional,accessible=it.accessible) })
                p.anchors.distinct().forEachIndexed { i,a -> dao.upsertReferencePoint(ReferencePointEntity("$prefix-anchor-$i",result.floorId,"Anchor-${i+1}",a.x,a.y,true,
                    notes="Mapper manual anchor; verify on site and collect new fingerprints",updatedAtEpochMillis=now)) }
            }
            obsoleteImages.forEach { old -> runCatching {
                if(old.canonicalPath.startsWith(context.filesDir.canonicalPath+File.separator) && old.isFile) old.delete()
            } }
            return result
        } catch(e: Exception) {file.delete();throw e}
    }
    private fun render(p: MapperPackage, file: File) {
        val scale= minOf(1800.0/p.width,1200.0/p.height)
        val bitmap=Bitmap.createBitmap((p.width*scale).toInt().coerceAtLeast(1),(p.height*scale).toInt().coerceAtLeast(1),Bitmap.Config.ARGB_8888)
        try {
            val c=Canvas(bitmap);c.drawColor(Color.rgb(248,250,253));val paint=Paint(Paint.ANTI_ALIAS_FLAG)
            fun x(v:Double)=(v*scale).toFloat()
            fun y(v:Double)=((p.height-v)*scale).toFloat()
            p.rooms.forEach { (name,poly)->
                val path=Path();poly.vertices.forEachIndexed {i,v->if(i==0)path.moveTo(x(v.x),y(v.y))else path.lineTo(x(v.x),y(v.y))};path.close()
                paint.style=Paint.Style.FILL;paint.color=Color.rgb(226,234,245);c.drawPath(path,paint)
                paint.color=Color.rgb(34,53,76);paint.textSize=14f;paint.textAlign=Paint.Align.CENTER
                val cx=poly.vertices.map {it.x}.average();val cy=poly.vertices.map {it.y}.average()
                c.drawText(name.take(28),x(cx),y(cy),paint)
            }
            paint.color=Color.rgb(80,99,120);paint.strokeWidth=2f
            p.walls.forEach {c.drawLine(x(it.start.x),y(it.start.y),x(it.end.x),y(it.end.y),paint)}
            file.outputStream().use {check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}
        } finally {bitmap.recycle()}
    }
}
