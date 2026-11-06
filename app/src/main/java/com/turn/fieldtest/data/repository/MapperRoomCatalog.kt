package com.turn.fieldtest.data.repository

import com.turn.fieldtest.core.*
import kotlinx.serialization.json.*

/** A label is a place even when the supplied graph has no entrance for it. Never infer new paths. */
data class MapperRoomLabel(val id: String, val name: String, val polygon: MetricPolygon,
    val labelPoint: MetricPoint, val sourcePlaceId: String? = null)

object MapperRoomCatalog {
    fun parse(source: JsonObject): List<MapperRoomLabel> {
        val frame=source.getValue("frame").jsonObject
        require(frame.getValue("units").jsonPrimitive.content=="metres" && frame.getValue("axes").jsonPrimitive.content=="x-right-y-up")
        val floor=source.getValue("floors").jsonArray.single().jsonObject
        fun point(e: JsonElement)=e.jsonObject.let {MetricPoint(it.getValue("x").jsonPrimitive.double,it.getValue("y").jsonPrimitive.double)}
        val rooms=floor.getValue("rooms").jsonArray.map {e ->
            val r=e.jsonObject
            val polygon=decodePolygon(r.getValue("polygon").toString()).also(::validateSimpleWalkablePolygon)
            val centre=MetricPoint(polygon.vertices.map {it.x}.average(),polygon.vertices.map {it.y}.average())
            val label=r["labelPosition"]?.let(::point)?.takeIf {it.x.isFinite() && it.y.isFinite() && polygon.contains(it)}
                ?: centre.takeIf {polygon.contains(it)} ?: polygon.vertices.first()
            MapperRoomLabel(r.getValue("id").jsonPrimitive.content,r.getValue("name").jsonPrimitive.content,polygon,label)
        }
        require(rooms.map {it.id}.distinct().size==rooms.size) {"Duplicate room identities"}
        val nodes=source.getValue("nodes").jsonArray.associate {e->e.jsonObject.let {it.getValue("id").jsonPrimitive.content to point(it)}}
        val pois=source.getValue("pois").jsonArray.map {it.jsonObject}
        fun name(s: String)=s.trim().lowercase(java.util.Locale.ROOT)
        // Repeated labels are matched one-to-one by their source locations. Existing graph nodes stay unchanged.
        data class Match(val room: MapperRoomLabel,val poi: JsonObject,val distance: Double)
        val matches=rooms.flatMap {r->pois.filter {name(it.getValue("name").jsonPrimitive.content)==name(r.name)}.mapNotNull {p->
            nodes[p.getValue("nodeId").jsonPrimitive.content]?.let {Match(r,p,r.labelPoint.distanceTo(it))}
        }}.sortedBy {it.distance}
        val assignments=mutableMapOf<String,String>(); val used=mutableSetOf<String>()
        matches.forEach {m->val id=m.poi.getValue("id").jsonPrimitive.content
            if(m.room.id !in assignments && id !in used) {assignments[m.room.id]=id; used+=id}}
        return rooms.map {it.copy(sourcePlaceId=assignments[it.id])}
    }
}

private fun SavedFloorMap.mapperSource(): JsonObject? = runCatching {
    val notes=venue.notes.orEmpty()
    if(!notes.startsWith("TURN-Mapper ")) return null
    val start=notes.lastIndexOf("\n{"); if(start<0) return null
    val source=Json.parseToJsonElement(notes.substring(start+1)).jsonObject
    val f=source.getValue("floors").jsonArray.single().jsonObject
    require(kotlin.math.abs(f.getValue("widthMetres").jsonPrimitive.double-floor.widthMetres)<.001 &&
        kotlin.math.abs(f.getValue("heightMetres").jsonPrimitive.double-floor.heightMetres)<.001)
    val frame=source.getValue("frame").jsonObject
    require(frame.getValue("units").jsonPrimitive.content=="metres" && frame.getValue("axes").jsonPrimitive.content=="x-right-y-up")
    source
}.getOrNull()

private fun SavedFloorMap.mapperRooms(): List<MapperRoomLabel> = mapperSource()?.let {
    runCatching {MapperRoomCatalog.parse(it)}.getOrDefault(emptyList())
}.orEmpty()

/** Rename only untouched importer placeholders; retain reference IDs and all survey lineage. */
fun SavedFloorMap.descriptiveMapperReferences(): List<com.turn.fieldtest.data.local.ReferencePointEntity> = runCatching {
    val source=mapperSource() ?: return emptyList()
    val anchors=source["anchors"]?.jsonArray.orEmpty().map {it.jsonObject}
    val occupied=points.map {it.name}.toMutableSet()
    points.mapNotNull {p ->
        if(!p.name.matches(Regex("Anchor-\\d+")) || p.notes?.startsWith("Mapper manual anchor;")!=true) return@mapNotNull null
        val a=anchors.singleOrNull {it["position"]?.jsonObject?.let {pos->
            kotlin.math.abs(pos.getValue("x").jsonPrimitive.double-p.xMetres)<.001 &&
                kotlin.math.abs(pos.getValue("y").jsonPrimitive.double-p.yMetres)<.001
        }==true} ?: return@mapNotNull null
        val label=a["label"]?.jsonPrimitive?.content?.trim()?.takeIf {it.isNotEmpty()} ?: return@mapNotNull null
        if(label in occupied) return@mapNotNull null
        occupied+=label
        p.copy(name=label)
    }
}.getOrDefault(emptyList())

private fun SavedFloorMap.roomId(room: MapperRoomLabel)="${floor.id}:mapper-room:${room.id}"

private fun SavedFloorMap.roomBindings(): List<Pair<MapperRoomLabel,String?>> {
    val used=mutableSetOf<String>()
    val rooms=mapperRooms()
    val reserved=rooms.mapNotNull {room -> room.sourcePlaceId?.let {sourceId->destinations.singleOrNull {it.id.endsWith("-$sourceId")}}?.id}.toSet()
    return rooms.map {room ->
        val sourceMatches=room.sourcePlaceId?.let {sourceId->destinations.filter {it.id.endsWith("-$sourceId")}}.orEmpty()
        val existing=sourceMatches.singleOrNull() ?: destinations.firstOrNull {it.id==roomId(room)}
            ?: destinations.filter {it.name==room.name && it.id !in used && it.id !in reserved}.singleOrNull()
        val id=existing?.id?.takeIf {it !in used}; if(id!=null) used+=id
        room to id
    }
}

/** Additive upgrade for already-installed floors. Existing POIs, graph, survey and fingerprint rows survive. */
fun SavedFloorMap.missingMapperRooms()=roomBindings().filter {it.second==null}.map {(room,_)->
    com.turn.fieldtest.data.local.PointOfInterestEntity(roomId(room),floor.id,room.name,"ROOM",
        room.labelPoint.x,room.labelPoint.y,notes="Mapper room ${room.id}. No entrance inferred; connect a verified entrance in Map setup.")
}

fun SavedFloorMap.mapperDestinationAreas(): List<DestinationArea> = roomBindings().mapNotNull {(room,id)->
    id?.let {DestinationArea(it,room.polygon)}
}
