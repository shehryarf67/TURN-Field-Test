# Phase 3: multi-floor routing plan

Directions between floors (roadmap row 3, design §2.3–2.4). Builds on phase 2: `SessionBuilding`, `FloorTopology`, per-floor `sessionGraphs`, `LiveDestination`, and the placeholder "Destination is on X · directions between floors come in a later update".

## Scope
- **Core (done):** `core/MultiFloorRouting.kt`, a cross-floor router over each floor's path graph plus the session's floor links, with results split into floor legs and instruction text.
- **UI (done):** the router runs in `updateRoute()` for a live destination on another floor and in a view-model route preview without a session; the map draws the shown floor's leg and rings its link; a connector banner and leg list show in Explore and full screen; *Step-free routes* is a saved option.
- **Cross-floor search (done):** *Search places* groups the building's other floors below the open floor.

## Invariants
1. **Same floor means the same route as today.** When start and destination share a floor, the router delegates to `NavigationGraph.route` with the same arguments and returns that route as `singleFloor`. The VM keeps its current same-floor code path.
2. **Links are the session's links.** The router takes `SessionBuilding.topology`, which already drops disabled links and applies *Use unchecked floor links* (default off) through `FloorTopologyBuilder`. Tracking off, or no usable link, gives a single-floor session, and nothing changes.
3. **Step-free uses only lifts and ramps.** A link must be flagged `accessible` *and* be a lift or ramp. Stairs and escalators are never used, even if flagged. Walking edges must be `accessible` too.
4. **One-way links and one-way paths are respected.** A one-way link gives one directed portal, and one-way path edges are followed in their direction only.
5. Tracking, floor links, recording and export are untouched. The only data change is an `accessible` field on `FloorLinkInput`/`FloorPortal`, which defaults to `kind.stepFree` and is filled from the saved link.

## Cost model
Costs are in seconds. Walking metres are divided by `WALKING_SPEED_MPS = 1.25`, and each link costs its saved `travelTimeSeconds`, which already covers the link's whole level span (`ConnectorRules.defaultTravelSeconds` scales per floor). The search is Dijkstra over graph nodes plus a virtual entry and exit point for each portal. Each point attaches to the nearest wall-free path or node within 3 m, as `route()` attaches its start. A link arrival may cross straight to another link's entry within 5 m with no wall in between (a stair landing), so a pass-through floor needs no path graph. Consecutive rides on the same link name, kind and direction merge into one leg ("up 2 floors by Stairs 1").

## Core API
```kotlin
class MultiFloorRouter(graphs: Map<String, NavigationGraph>, topology: FloorTopology,
    maxAttachmentMetres: Double = 3.0, floors: Map<String, MapFloor> = graphs.mapValues { it.value.floor })
  fun route(startFloorId, start: MetricPoint, destinationFloorId, destinationNodeId, stepFreeOnly = false): MultiFloorRouteResult
sealed MultiFloorRouteResult = Found(route: MultiFloorRoute) | Unreachable(reason: RouteFailure)
enum RouteFailure { START_OFF_MAP, DESTINATION_UNKNOWN, NO_PATH_GRAPH, NO_FLOOR_LINK, NO_ROUTE }
MultiFloorRoute(legs: List<RouteLeg>, walkMetres, seconds, singleFloor: NavigationRoute?)
  crossesFloors, pointsOn(floorId), connectorFrom(floorId), walkMetresOn(floorId)
WalkLeg(floorId, points, distanceMetres, seconds, toConnector: String?)        // null = ends at destination
ConnectorLeg(portals, kind, label, fromFloorId, toFloorId, levelDelta, entry, exit, seconds)
RouteLegText.instruction(leg, floorNames, destinationName) / instructions(route, ...) / summary(route) / connectorName(leg)
```

## UI pieces for the next builder
- **`TurnRuntimeViewModel.updateRoute()`:** replace only the `destinationOnOtherFloorText` early return. When `liveDestination.floorId != realLiveFloorId`, run `MultiFloorRouter(sessionGraphs, sessionBuilding!!.topology, floors = sessionMap!!.floors).route(realLiveFloorId, position, liveDestination.floorId, node.id, appState.stepFreeRouting)`. Find the destination node in `sessionGraphs[liveDestination.floorId]` by `poiId == destination`. Build the router once per session, after `sessionGraphs` is filled, and cache it.
  - On `Found`: set `routePoints = route.pointsOn(appState.activeProject.floorId).firstOrNull()` (the displayed floor's leg), `remainingDistanceMetres = route.walkMetres`, and `routeStatus` = the first entry of `RouteLegText.instructions(...)` + " · " + `RouteLegText.summary(route)`. Store the route in a new `appState.routeLegs` for the preview.
  - On `Unreachable`: show a short status for each reason ("No step-free way to Floor X", "No checked link reaches Floor X · allow unchecked links in Map options", and so on).
  - Keep `destinationReached` false until the tracked floor is the destination floor.
- **Map:** highlight the badge of `route.connectorFrom(appState.activeProject.floorId)` (the existing S/L/E/R badge, with a ring), and draw only that floor's walk leg.
- **Banner:** within the link's radius, show "Take Stairs 1 up to Floor 4" (reuse the floor banner slot). After the confirmed floor changes, the route recalculates from the new floor, because `updateRoute()` already runs on every multi-floor particle update.
- **Step-free option:** a *Step-free routes* switch in Map options (`appState.stepFreeRouting`, default off, persisted like *Use unchecked floor links*). It affects cross-floor routes only, so same-floor routes stay identical.
- **Route preview:** a leg list from `RouteLegText.instructions`, with the current leg in bold.

## Tests
- **Done** (`MultiFloorRoutingTest`, JVM): same-floor parity with `NavigationGraph.route` (with and without step-free); stairs or lift chosen by total time; step-free avoids stairs and escalators, including flagged ones and non-step-free paths; one-way escalator; one-way paths; GF→F2 via F1 merged into one ride, and working without an F1 graph; different links split into five legs; unreachable reasons; disabled and unchecked links follow the option; leg text and summary.
- **Done (app side):** `FloorRouteTest` (JVM): grouped search, leg of the confirmed floor (including a pass-through floor), leg rows, banner trigger, `routeToPlace` failures, failure and status text. `CrossFloorRouteUiTest` (androidTest): grouped search and picking another floor's place, one-floor search unchanged, preview legs and blocked start, step-free switch (and unlocked while live), banner near the link only, next leg after a floor change, full-screen banner.
- **Next:** field courses GF→F4 by stairs and B1→F3 by lift, including the step-free variant (§2.7, phase 4).

## App side (as built)
- **Same-floor parity.** `updateRoute()` is unchanged except that its old placeholder branch (live destination on another floor than the tracked one) now calls `routeToOtherFloor()`. The same-floor preview stays in `CanvasNavigationScreen` (`previewGraph.route`). The new state (`liveFloorRoute`, `crossFloorPreview`, `buildingPlaces`) is null or empty unless a destination lies on another floor, so a one-floor building, tracking off, and every same-floor route look and behave as before.
- **Live.** The router is built once per multi-floor session from the frozen `SessionBuilding` and `sessionGraphs`. Every particle update re-plans from `realLiveFloorId`, which is phase 2's confirmed floor, so the next leg takes over when the floor changes. `routePoints` hold the displayed floor's leg. `destinationReached` stays false until the destination floor, where the same-floor code takes over.
- **Preview.** A `snapshotFlow` in the view model plans a preview when the selected place is on another floor and a start is chosen (`CrossFloorPreview`). It loads the building as a session start would, with the same links and *Use unchecked floor links*, but without writing (`persistStartRepairs = false`). The result is cached until the open floor, its saved paths, the links or the options change.
- **Pure helpers** (`ui/FloorRoute.kt`): `otherFloorPlaceGroups`, `floorLeg`, `floorLegRows`, `connectorBannerText` (shown within the link radius, at least 5 m, by straight distance or remaining walk), `routeToPlace`, `routeFailureText` and `crossFloorRouteStatus`.
- **Step-free.** `stepFreeRouting` is saved like the other map options. It applies only to routes between floors and can change while tracking, because it never touches the session.
