# Canvas UI

Implemented 2026-11-03 from the selected Canvas design. This is a native Kotlin/Compose interface over TURN's existing runtime, not an embedded HTML preview.

## Navigation

- **Explore:** the saved single-floor map, place search, destination selection, real fused blue dot, estimated uncertainty, A* route and remaining distance. Floating map controls support pinch/pan, fit, recenter and full-screen portrait/landscape. Recenter only changes the camera view, never the estimated position.
- **Buildings:** create/open a venue and floor, reopen persisted projects, or return to map setup. Projects remain local and offline.
- **Tools:** guided preparation plus all radio/sensor diagnostics, evaluation, export, backup and settings screens. During tracking, a return-to-map action retains the existing session.

## First-floor workflow

1. Buildings → Add a building. Enter venue/floor names.
2. Map setup → Scale. Import PNG/JPEG, or save changes and open **Scan building with phone** for guided AR/measured mapping.
3. Calibrate scale, then use the next-step actions for Layout → Paths → Points. Leave actual door openings, connect destination nodes and add reference points. Save the map.
4. Continue to Wi-Fi survey. Select a known point, collect fresh scans, save and repeat. Partial and completed survey states retain their existing Room lineage.
5. Continue to Explore. Search/select a destination and choose **Navigate here**, or start positioning without a destination.
6. Choose a known start on the map/at a reference point, or wait for a confident fresh Wi-Fi initialization. Stand still and choose the facing direction on the drawing, or tap a point ahead. The screen top should face the walking direction. Starting requires an explicit direction choice.
7. Start tracking. The blue dot comes only from the existing estimator. An absent/stale heading, unavailable position, scan throttling, low confidence and save failures remain visible. No movement is simulated in Real Device mode.

In Explore's overflow menu: appearance, full screen, fresh Wi-Fi requests with cooldown, explicit relocalization, optional raw PDR/Wi-Fi/particle overlays and all legacy research controls. Direction adjustment records the existing manual heading correction and retains position and trails.

## Appearance and responsiveness

New installations start in blue/white light mode. Existing stored appearance choices remain valid. Dark mode is available in map options, the non-map toolbar and Settings, and is saved through the existing DataStore preference path. Walk Mapper reads that same preference. Imported floor-plan pixels retain their original colors; TURN does not invert or reinterpret architectural drawings in dark mode.

Explore adapts its map viewport around the floating search and destination card. Landscape places the card beside the map. Full screen retains the existing display-orientation and lifecycle controls. Short editor/survey windows allow scrolling without hiding their tools. Coordinate projection, map tapping, route, geometry and blue-dot overlays share the same transform.

## Data and map sources

No Room entity, DAO, migration, hardware source or positioning algorithm is replaced. Additive schema 1 → 2 → 3 remains intact. No destructive migration, app-data reset or new package ID is introduced. Saved maps, graphs, reference points, raw scans, fingerprints, positioning sessions and scan drafts remain owned by their existing repositories.

Existing map sources remain image import/manual editing and the experimental AR/measured Walk Mapper. AR capture still needs corner/door review, metric calibration and independent measurement checks; it is not automatic reliable whole-floor reconstruction. Publishing creates a separate floor project and preserves source fingerprints.

The partner's floor-plan model is **not integrated by this UI change**. Its eventual adapter should translate validated metric geometry and graph/POI output into the existing repository contract: floor identity, X-right/Y-up metres, walkable polygons, wall/door boundaries, node/edge identities, distances and POI associations. Validate connectivity and wall-safe edges before saving. The UI consumes saved maps and graphs independently of their source. Model-specific parsing, multi-floor routing and physical accuracy evaluation remain separate work.

## Verification

Use the existing JVM, migration, persistence, survey, motion, navigation-rotation and measured-mapper tests alongside `CanvasWorkflowTest`. The Canvas tests use isolated UI fixtures and verify search/destination callbacks, start/direction gating, blank-floor setup, tools/AR access, unsaved-map safeguards, inset-aware recentering and theme/debug changes without changing a live position/session. UI fixtures are not physical positioning evidence.

Physical walking, Wi-Fi accuracy, sensor reliability and AR drift still require testing on the actual phone/building. Automated UI checks do not establish those results.

Verified on 2026-11-03 with JDK 17: 153 JVM tests and 33 API-35 emulator tests passed; debug app/test APKs build, and Android lint reports 0 errors (27 dependency/API/style/resource advisories). The installed app's light/dark appearance was visually checked and its saved choice survived a cold restart. Testing used a separate emulator and test databases; no physical phone data was reset.

The mapper now accommodates the software keyboard and supplies Done actions on room/measurement fields. Its end-to-end test waits for Android's keyboard layout to settle, then verifies room creation, four measured corners, calibration, independent validation and publication into the existing TURN map/graph repository.
