package com.turn.fieldtest.ui

import com.turn.fieldtest.core.ConnectorLeg
import com.turn.fieldtest.core.MetricPoint
import com.turn.fieldtest.core.MultiFloorRoute
import com.turn.fieldtest.core.MultiFloorRouteResult
import com.turn.fieldtest.core.MultiFloorRouter
import com.turn.fieldtest.core.NavigationGraph
import com.turn.fieldtest.core.RouteFailure
import com.turn.fieldtest.core.RouteLegText
import com.turn.fieldtest.core.WalkLeg

// Phase 3: directions between floors as the screens show them. Pure, so the leg and banner rules are JVM-tested.

/** One saved place of the building, as the destination search lists it. */
data class BuildingPlace(
    val id: String, val name: String, val floorId: String, val floorName: String, val level: Int, val connected: Boolean,
)

/** The places of one other floor in the destination search. */
data class PlaceGroup(val floorId: String, val floorName: String, val level: Int, val places: List<BuildingPlace>)

/**
 * The building's other floors for the destination search, highest level first, places by name. The open floor is
 * not included: the search keeps listing it from its live list as before. Floors with no match are dropped, so a
 * one-floor building gives no groups and the search looks exactly as it did.
 */
fun otherFloorPlaceGroups(currentFloorId: String?, places: List<BuildingPlace>, matches: (BuildingPlace) -> Boolean): List<PlaceGroup> =
    places.filter { it.floorId != currentFloorId && matches(it) }
        .groupBy { it.floorId }
        .map { (floorId, rows) -> PlaceGroup(floorId, rows.first().floorName, rows.first().level, rows.sortedBy { it.name.lowercase() }) }
        .sortedWith(compareByDescending<PlaceGroup> { it.level }.thenBy { it.floorName })

/** A route to a place on another floor (live session or route preview), with the names its text needs. */
data class FloorRouteUi(
    val route: MultiFloorRoute,
    val destinationId: String,
    val destinationFloorId: String,
    val destinationName: String,
    val floorNames: Map<String, String>,
)

/** The route preview of a destination on another floor, for the start chosen on the open floor. */
data class CrossFloorPreview(
    val destinationId: String,
    val floorId: String,
    val start: MetricPoint?,
    val route: FloorRouteUi?,
    val problem: String?,
)

/**
 * What [floorId] shows of a route: its walk line, the link to take there (null on the destination floor), the name
 * the walk leads to, the walk's length and the ride instruction ("Go up 2 floors to F4 by Stairs 1").
 */
data class FloorLegUi(
    val points: List<MetricPoint>,
    val connector: ConnectorLeg?,
    val target: String,
    val walkMetres: Double,
    val instruction: String,
)

/**
 * The leg of [route] on [floorId], normally the confirmed floor. Each update re-plans from the confirmed floor, so
 * after a floor change the next leg takes over by itself. Null when the floor is not on the route (for example a
 * floor a merged ride passes through).
 */
fun floorLeg(route: FloorRouteUi, floorId: String?): FloorLegUi? {
    if (floorId == null) return null
    val walk = route.route.legs.filterIsInstance<WalkLeg>().firstOrNull { it.floorId == floorId }
    val connector = route.route.connectorFrom(floorId)
    if (walk == null && connector == null) return null
    val target = when {
        walk?.toConnector != null -> walk.toConnector
        connector != null -> RouteLegText.connectorName(connector)
        else -> route.destinationName
    }
    val instruction = connector?.let { RouteLegText.instruction(it, route.floorNames, route.destinationName) }
        ?: RouteLegText.instruction(walk!!, route.floorNames, route.destinationName)
    return FloorLegUi(walk?.points.orEmpty(), connector, target, walk?.distanceMetres ?: 0.0, instruction)
}

/** One line of the leg list; [current] marks the leg of the floor being shown. */
data class FloorLegRow(val text: String, val floorId: String, val current: Boolean)

/** The legs as instruction rows, folding a zero-length walk into its ride as [RouteLegText.instructions] does. */
fun floorLegRows(route: FloorRouteUi, currentFloorId: String?): List<FloorLegRow> {
    val legs = route.route.legs
    var marked = false
    return legs.mapIndexedNotNull { i, leg ->
        if (leg is WalkLeg && leg.toConnector != null && leg.distanceMetres < 1.0 && i + 1 < legs.size) return@mapIndexedNotNull null
        val floorId = when (leg) {
            is WalkLeg -> leg.floorId
            is ConnectorLeg -> leg.fromFloorId
        }
        val current = !marked && floorId == currentFloorId
        if (current) marked = true
        FloorLegRow(RouteLegText.instruction(leg, route.floorNames, route.destinationName), floorId, current)
    }
}

/** Distance from the link at which its banner appears, when the link's own radius is smaller. */
const val CONNECTOR_BANNER_METRES = 5.0

/**
 * The connector banner ("Go up 2 floors to F4 by Stairs 1"), or null. It shows once the walk left to the link, or
 * the straight distance from [position] to it, is within the link's radius (at least [CONNECTOR_BANNER_METRES]).
 * Nothing shows on the destination floor.
 */
fun connectorBannerText(leg: FloorLegUi?, position: MetricPoint?): String? {
    val connector = leg?.connector ?: return null
    val portal = connector.portals.firstOrNull()
    val reach = maxOf(portal?.radiusMetres ?: 0.0, CONNECTOR_BANNER_METRES)
    val centre = portal?.fromCentre ?: connector.entry
    val near = leg.walkMetres <= reach || (position != null && position.distanceTo(centre) <= reach)
    return if (near) leg.instruction else null
}

/**
 * Routes from [fromFloorId] to the place [destinationId] on [destinationFloorId] with the session's (or preview's)
 * router. A floor outside the building's linked floors, a missing path graph or a place without a path node give
 * the matching failure instead of a search.
 */
fun routeToPlace(
    router: MultiFloorRouter?, linkedFloorIds: Set<String>, graphs: Map<String, NavigationGraph>,
    fromFloorId: String, start: MetricPoint, destinationId: String, destinationFloorId: String, stepFree: Boolean,
): MultiFloorRouteResult {
    if (router == null || fromFloorId !in linkedFloorIds || destinationFloorId !in linkedFloorIds)
        return MultiFloorRouteResult.Unreachable(RouteFailure.NO_FLOOR_LINK)
    val graph = graphs[destinationFloorId] ?: return MultiFloorRouteResult.Unreachable(RouteFailure.NO_PATH_GRAPH)
    val node = graph.nodes.firstOrNull { it.poiId == destinationId }
        ?: return MultiFloorRouteResult.Unreachable(RouteFailure.DESTINATION_UNKNOWN)
    return router.route(fromFloorId, start, destinationFloorId, node.id, stepFree)
}

/** One short status per reason a route to another floor could not be found. */
fun routeFailureText(reason: RouteFailure, destinationName: String, floorName: String, stepFree: Boolean, uncheckedLinksAllowed: Boolean): String =
    when (reason) {
        RouteFailure.START_OFF_MAP -> "Start is off the walkable map · relocalize or choose another start"
        RouteFailure.DESTINATION_UNKNOWN -> "$destinationName has no connected path yet · connect its entrance on $floorName in Map setup"
        RouteFailure.NO_PATH_GRAPH -> "A floor on the way to $floorName has no saved paths · add paths in Map setup"
        RouteFailure.NO_FLOOR_LINK -> if (uncheckedLinksAllowed) "No floor link reaches $floorName · add one in Floor links"
            else "No checked floor link reaches $floorName · allow unchecked links in Map options"
        RouteFailure.NO_ROUTE -> if (stepFree) "No step-free way to $floorName" else "No route to $floorName · check its floor links and paths"
    }

/** Status when the destination is on another floor but floor tracking is off (the session tracks one floor). */
fun floorTrackingOffRouteText(floorName: String) =
    "Destination is on $floorName · turn on Multi-floor tracking in Map options for directions between floors"

/** Live status line for a route found to another floor: the next instruction and the time and walking summary. */
fun crossFloorRouteStatus(route: FloorRouteUi): String =
    (RouteLegText.instructions(route.route, route.floorNames, route.destinationName).firstOrNull() ?: "Follow the blue route") +
        " · " + RouteLegText.summary(route.route)
