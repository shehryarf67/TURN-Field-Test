package com.turn.fieldtest.data.repository

import com.turn.fieldtest.core.*
import kotlinx.serialization.json.*

/** Adapter for VenuePackage v1. Source coordinates are already metric X-right/Y-up. */
data class MapperPackage(val source: String, val name: String, val revision: String,
    val floorId: String, val level: Int, val width: Double, val height: Double,
    val regions: List<MetricPolygon>, val rooms: List<Pair<String, MetricPolygon>>,
    val walls: List<WallSegment>, val nodes: List<NavigationNode>, val edges: List<NavigationEdge>,
    val places: List<MapperPlace>, val anchors: List<MetricPoint>, val provenance: String) {
    val graph get() = NavigationGraph(MapFloor(floorId, regions, walls), nodes, edges)
    companion object {
        fun parse(text: String): MapperPackage {
            val d = Json.parseToJsonElement(text).jsonObject
            fun JsonObject.s(key: String) = getValue(key).jsonPrimitive.content
            fun JsonObject.n(key: String) = getValue(key).jsonPrimitive.double.also { require(it.isFinite()) { "$key must be finite" } }
            fun JsonObject.a(key: String) = getValue(key).jsonArray
            fun point(e: JsonElement): MetricPoint = e.jsonObject.let { MetricPoint(it.n("x"), it.n("y")) }
            fun polygon(e: JsonElement) = MetricPolygon(e.jsonArray.map(::point)).also(::validateSimpleWalkablePolygon)
            require(d.getValue("schemaVersion").jsonPrimitive.int == 1) { "Expected VenuePackage v1" }
            require(d.getValue("frame").jsonObject.let { it.s("units") == "metres" && it.s("axes") == "x-right-y-up" }) { "Unsupported coordinate frame" }
            require(d.a("floors").size == 1 && d.a("connectors").isEmpty()) { "Import a single-floor package; multi-floor support is next" }
            val f = d.a("floors").single().jsonObject; val id = f.s("id")
            val width = f.n("widthMetres"); val height = f.n("heightMetres")
            require(width > 0 && height > 0 && width <= 2000 && height <= 2000) { "Invalid floor dimensions" }
            val regions = f.a("walkablePolygons").map(::polygon)
            require(regions.isNotEmpty()) { "No walkable geometry" }
            val rooms = f.a("rooms").map { it.jsonObject.let { r -> r.s("name") to polygon(r.getValue("polygon")) } }
            var repairs=0
            val adjustedIds=mutableSetOf<String>()
            val geometry=MapFloor(id,regions,emptyList())
            fun normalize(p: MetricPoint): MetricPoint {
                if(geometry.isWalkable(p)) return p
                val boundary=regions.flatMap { poly -> poly.vertices.zipWithNext()+listOf(poly.vertices.last() to poly.vertices.first()) }
                    .map { (a,b)->MetricGeometry.project(p,MetricSegment(a,b)).point }.minBy {it.distanceTo(p)}
                require(boundary.distanceTo(p)<=.02) {"Node outside walkable space by more than 2 cm"}
                val adjusted=(0..31).map { i->val a=i*Math.PI/16;MetricPoint(boundary.x+.001*kotlin.math.cos(a),boundary.y+.001*kotlin.math.sin(a)) }
                    .filter {geometry.isWalkable(it) && it.distanceTo(p)<=.02}.minByOrNull {it.distanceTo(p)}
                requireNotNull(adjusted) {"Cannot align node to contour"};repairs++;return adjusted
            }
            val nodes = d.a("nodes").map { it.jsonObject.let { n ->
                require(n.s("floorId") == id) { "Node belongs to another floor" }
                val original=point(n);val normalized=normalize(original)
                if(original!=normalized) adjustedIds.add(n.s("id"))
                NavigationNode(n.s("id"), id, normalized)
            } }
            require(nodes.isNotEmpty() && nodes.map { it.id }.distinct().size == nodes.size) { "Missing or duplicate nodes" }
            val byId = nodes.associateBy { it.id }
            val edges = d.a("edges").map { it.jsonObject.let { e ->
                val from=byId.getValue(e.s("from"));val to=byId.getValue(e.s("to"))
                require(e.n("distanceMetres")>=from.point.distanceTo(to.point)-.04) {"Invalid source edge distance"}
                NavigationEdge(e.s("id"), e.s("from"), e.s("to"), if(from.id in adjustedIds || to.id in adjustedIds) from.point.distanceTo(to.point) else e.n("distanceMetres"),
                bidirectional = e.getValue("bidirectional").jsonPrimitive.boolean, accessible = e.getValue("stepFree").jsonPrimitive.boolean) } }
            val walls = f.a("walls").mapIndexed { i, e -> e.jsonObject.let { WallSegment("wall-$i", point(it.getValue("from")), point(it.getValue("to"))) } }
            val places = d.a("pois").map { it.jsonObject.let { p ->
                require(p.s("nodeId") in byId) { "Destination has no path node" }
                MapperPlace(p.s("id"), p.s("name"), p.s("category"), p.s("nodeId"))
            } }
            require(places.map { it.id }.distinct().size == places.size) { "Duplicate destinations" }
            val anchorNodes = d.a("anchors").map { it.jsonObject.let { a ->
                require(a.s("venueId") == d.s("venueId") && a.s("floorId") == id) { "Invalid anchor identity" }
                val node = requireNotNull(byId[a.s("nodeId")]) { "Anchor has no node" }
                require(node.point.distanceTo(point(a.getValue("position"))) < .001) { "Anchor/node mismatch" }; node
            } }
            require(anchorNodes.isNotEmpty()) { "No initialization anchors" }
            (regions.flatMap { it.vertices } + rooms.flatMap {it.second.vertices} + walls.flatMap {listOf(it.start,it.end)} + nodes.map { it.point }).forEach {
                require(it.x >= 0 && it.y >= 0 && it.x <= width && it.y <= height) { "Geometry outside floor bounds" }
            }
            val result = MapperPackage(d.s("venueId"), d.s("name"), d.s("revision"), id, f.getValue("level").jsonPrimitive.int,
                width, height, regions, rooms, walls, nodes, edges, places, anchorNodes.map { it.point }, d.getValue("provenance").toString()+"\nContour-aligned nodes (<=2 cm): $repairs; edge lengths recalculated from aligned endpoints")
            val errors = result.graph.validationErrors()
            require(errors.isEmpty()) { errors.take(5).joinToString("; ") }
            anchorNodes.forEach { anchor -> places.forEach { poi ->
                require(result.graph.route(anchor.point, poi.nodeId, maxAttachmentMetres = 0.001) != null) { "Unreachable destination ${poi.name}" }
            } }
            return result
        }
    }
}
data class MapperPlace(val id: String, val name: String, val category: String, val nodeId: String)
