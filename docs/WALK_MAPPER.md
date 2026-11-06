# TURN Walk Mapper — guided Scan-to-Map prototype

Walk Mapper is an alternative source of map data inside TURN. It creates a one-floor, multi-room draft from real ARCore observations and operator-marked corners, or from explicitly entered physical measurements. It does not infer a floor plan from a walking/PDR trace, identify every architectural wall, or guarantee dimensional accuracy.

## Open and use it

1. Open/create a project in **Real Device** mode. Open **Map Setup → Scale** and save pending map changes.
2. Choose **Scan building with phone**. Existing image import remains available alongside it. Stop active survey/navigation sessions first.
3. Enter a room name and choose **Start next room**. The initial room uses this same button.
4. Choose **Start camera**. TURN requests camera permission at this point and checks Google Play Services for AR. Accept its install/update prompt when required, then return to TURN. No camera recording is stored.
5. Move slowly while looking at the actual floor. Once tracking is stable, tap a visible floor point, inspect its height below the camera, and **Confirm floor / origin**. Reject a table, bed or other incorrect horizontal surface. The app cannot reliably identify the floor for you.
6. Tap another point on that same floor, at least 0.5 m away, in the direction you want to be map **Up**. This declares drawing orientation, not magnetic north. It does not automatically calibrate TURN's live-navigation heading.
7. Use **Corner** and tap each room corner in perimeter order. Floor/wall surfaces must produce a reliable hit. A failed hit stores nothing: improve lighting/viewpoint or use measured drawing after pausing. **Boundary corner** and **Entrance corner** also contribute to the ordered room outline; they are not automatic door detection.
8. Inspect **2D preview** while capture continues. Cyan indicates confirmed geometry; amber indicates drafts/candidates; green marks walkable openings. The blue camera marker and optional path are observation history, not live PDR or an inferred wall. Pinch to zoom, drag to pan, and **Fit map** to reset. Map rotation is an explicit saved geometry correction in Review.
9. **Confirm outline** only after marking the complete perimeter. Three corners are a minimum, not evidence of complete coverage. The screen reports corner count and extent; observed surface coverage is unknown. Real non-rectangular outlines are retained.
10. Automatically proposed walls require explicit confirmation in **Review → Select wall**. Quality/height/observation count are evidence summaries, not calibrated probabilities. Reject wardrobes, furniture, mirrors and glass mistaken for walls. Room outlines already create boundary walls: avoid confirming redundant/conflicting interior candidates.
11. Use **Wall endpoints** for missing internal wall segments. Select a confirmed wall in the preview/selector, choose **Door endpoints**, and mark both ends of its opening. The opening must lie on the selected wall. Review its coordinates, measured width, walkability and room links.
12. To continue into a corridor with uninterrupted tracking, start a named next room and mark its perimeter in the same coordinate frame. Reuse physically consistent shared boundaries. In Review, tick both adjacent rooms for their shared doorway. A one-room entrance can link only that room.

## Measured drawing without ARCore

The map preview, room tools, editing, verification, saving and PNG generation work without ARCore. **Start camera** is unavailable when support is absent. Depth absence alone still permits AR plane-based marking.

Choose a physical origin and one perpendicular pair of drawing axes. Coordinates are metres, **X right and Y up**. Enter the measured X/Y location of every perimeter corner using **Add measured point**; choose the appropriate tool first. For example, a physically measured rectangle can be entered as `(0,0), (width,0), (width,height), (0,height)`. These values must come from the building, not from a demonstration template. Enter the next room in the same frame. Confirm the outlines, select their boundary wall and enter two measured door endpoints. Link the rooms in Review. The provenance records `MANUAL_MEASURED`; no camera observation is claimed.

## Pause, reopen and align another camera section

Pause/background/exit stops camera capture. Tracking loss after the floor frame is established permanently blocks additions to that AR section. Recovery of camera tracking alone does not unlock it. Saved geometry remains editable. A restored active section after process death is also marked interrupted.

For new AR capture with existing geometry:

1. In Capture, choose two saved physical floor corners as **Reference A / origin** and **Reference B / forward**, at least 0.5 m apart. Prefer a longer visible baseline measured independently.
2. Start a new camera section. Tap the exact physical A on the floor, confirm it, then tap physical B. TURN computes and records a two-correspondence similarity transform into the existing draft.
3. A scale disagreement outside 0.8–1.25 is rejected. This gate is a guard against gross mistakes, not an accuracy claim. Pause/retry if the wrong correspondences were selected.
4. Check shared walls and measurements. Review interrupted sections explicitly before publishing. This confirmation does not repair drift by itself.

Geometry is persistent; ARCore anchors/world coordinates are not persisted spatial sessions. TURN does not use Cloud Anchors, VPS or hidden relocalisation. Separate sessions are never silently joined. A two-point alignment cannot remove nonuniform tracking drift.

## Correct and verify

Pause the camera before editing. Review supports corner coordinate edits/deletion, reopening room outlines, explicit candidate acceptance/rejection, manual wall endpoint edits, collinear joins and midpoint splits. Opening-bearing walls must have their doors reassigned/removed before join/split so provenance is not silently lost. **Snap and split confirmed wall intersections** is an explicit, undoable cleanup; it preserves opening-bearing wall identities. Room boundaries are edited through their corners. Cleanup is not an automatic architectural reconstruction algorithm.

Select a capture section for a controlled translation/rotation/scale correction. Rotation is in degrees counterclockwise about draft `(0,0)`; translation is applied afterward. Rotating the entire map changes drawing orientation explicitly. Large changes can invalidate shared door boundaries and require outline review. Capture alignments are composed with these corrections so future coordinates use the same frame. Undo retains bounded pose history but restores the previous geometry.

In **Verify & save**, select two corners and enter the physical tape/laser distance. Label it either **Scale calibration** or **Independent validation**. Review captured estimate, current length, absolute/percentage error and any applied scale. Calibration offers a controlled global scale correction of 0.5–2.0. Repeated inconsistent lengths require remeasurement, local correction or rescanning; a global scale is not treated as a cure for distortion. The same point pair cannot count as both calibration and independent validation. Remove an incorrectly entered measurement explicitly.

Default proposed evaluation tolerance is `max(0.15 m, 5% of measured distance)`. At least one independent validation length is required to publish. Passing it does not certify the whole layout. Use multiple lengths in different directions/rooms, including a diagonal and a doorway check where possible.

Configurable review thresholds: endpoint snap 0.001–0.30 m (default 0.10), RANSAC line inlier 0.01–0.20 m (default 0.06), minimum candidate wall 0.40–2.0 m (default 0.40), validation percentage 1–20% (default 5%). Door-endpoint acceptance tolerance is 0.12 m, and linked room boundary discrepancy must be ≤0.03 m. Door width gate is 0.45–3 m. These prototype gates are not accessibility or safety certification. Openings are projected to their owning wall before collision geometry is cut.

Quality checks report incomplete/unconfirmed outlines, invalid/overlapping polygons, missing alignment, interrupted sections, invalid doorway locations/room links, disconnected rooms and inconsistent measured verification. Unconfirmed wall candidates are identified and excluded. Shared boundary segments are merged before opening intervals are removed; cleanup cannot heal an explicit opening. Every proposed edge is checked against the exact polygon union and blocking walls.

An applied calibration and its endpoints cannot be deleted to erase its history. Undo the scale correction before removing them. If an old incorrect calibration is no longer in the bounded undo history, start a new draft and recheck the measurements. A scale factor of exactly 1 is still recorded as an applied calibration. Unapplied measurements can be removed normally.

## Save, reopen and export

**Save as new TURN project** creates a separate venue/project named after the source with a mapped-layout suffix, keeping the source floor’s actual level number, its calibrated PNG asset, one walkable region per room, collision walls with doorway gaps, room destinations, path nodes/edges and scan provenance. It never replaces/rescales the source floor or moves its existing fingerprints. Publishing again creates another independent project/floor identity. The existing unique (venue, level) constraint is retained; an alternate layout is never assigned a fictitious new storey. Room/node/POI identities are scoped to the new floor.

Review any path warnings. Direct, wall-safe room-centre/doorway links are proposed. Concave rooms or confirmed internal walls can leave the graph incomplete; this is recorded as `PUBLISHED_REVIEW_PATHS`, not repaired with an unsafe edge. **Open saved floor in TURN**, then inspect/complete **Map Setup → Paths** with intermediate nodes at turns. Layout provides a room selector, and graph checks consider all rooms. Saving ordinary map edits retains the other rooms. Add reference points, save, collect fingerprints and use existing Navigate/PDR/particle-filter/Wi-Fi correction as before. Set your initial walking direction on the drawing in Navigate; scanning is not a new positioning engine.

Close/reopen TURN and open the generated floor from Projects to restore the actual PNG, geometry, POIs and graph from Room. Reopen the source project's mapper to resume/edit its saved scan draft. Drafts auto-save periodically (about 700 ms after a pending change) and can be saved explicitly. A hard termination can lose changes newer than the last successful save. A failed explicit exit-save keeps the mapper open for retry. New/open draft operations save the current draft first.

Before leaving the published draft, **Export PNG** and **Export scan JSON** use Android's document picker. PNG is the last published map, not an unsaved edit; publish a new floor to regenerate it. The PNG is rendered from the same translated metric geometry and includes room labels, real wall/opening geometry and a scale indicator. The experimental JSON contains version, axes/units, rooms, openings, segment transforms, bounded poses, raw-source labels, measurements, thresholds and publication notes. It is **not** TURN-Mapper `venue.json 0.1.1`; no importer for that independent format is provided. Existing full Room JSON backups now include scan drafts; a PNG/scan JSON alone is not a portable backup/import of a surveyed TURN project. Internal PNG URIs in existing Room exports retain the existing same-installation asset limitation.

## Implementation and database safety

The repository audit reused `MainActivity`, `TurnApp`/`TurnRuntimeViewModel`, `MapSetupScreen`/`NavigationMap`, `ProjectRepository`, existing geometry/A*, Room repositories/DAOs and the university prototype flow. Wi-Fi freshness, survey lineage, sensor/PDR/PF and evaluation engines remain the existing implementations. The missing components were AR capture, scan drafts, floor-frame alignment, depth fitting, room/door review and conversion to existing map rows.

- `mapper/ar/ArScanView.kt`: one ARCore/OpenGL camera, hit testing, floor anchors, display geometry on every frame/size change, vertical-plane candidates, native/image cleanup and bounded background raw-depth processing.
- `mapper/WalkMapperActivity.kt`: support/install/permission checks, optional session lifecycle, pause/cleanup, SAF exports and result handoff to MainActivity.
- `mapper/WalkMapperViewModel.kt`: guided capture/edit state, asynchronous serialized saves, bounded undo, explicit new-section alignment and publication.
- `mapper/core/{ScanModels,ScanGeometry,DepthGeometry,CaptureGuard}.kt`: serializable metric contract, transformations, geometry/door/QA/graph proposals, stride-aware back-projection, bounded robust fitting and lost-session guard.
- `mapper/data/{ScanDraftEntity,ScanRepository}.kt`: draft DAO, transactional publication and private PNG rendering.
- `mapper/ui/{WalkMapperScreen,ScanPreview}.kt`: capture/review/verification UI and responsive map surface.
- `MainActivity.kt`, `ui/{TurnApp,TurnAppState,TurnRuntimeViewModel}.kt`, `ui/screens/{MapSetupScreen,NavigationMap,FloorEditorScreen}.kt`: entry/result and safe multi-region editing/rendering.
- `data/local/TurnDatabase.kt`, `data/export/{BackupDao,ExportModels}.kt`, manifest, app Gradle and extraction rules: optional AR dependency, additive storage and export integration.
- New JVM and Android tests cover transforms, depth, geometry, capture guards, region editing, migration, publication rollback and measured fallback. See test sources and `WALK_MAPPER_VERIFICATION.md` for actual run results.

Room schema **2 → 3** only creates `walk_scan_drafts` and its floor index/foreign key. The document stores room/door/capture-specific data without changing existing metric entities. The existing 1 → 2 migration remains in the complete 1 → 2 → 3 chain; schemas 1 and 2 are retained and schema 3 is committed. No destructive fallback is enabled. Full database export schema is now 3, with an optional empty draft list for legacy JSON. Scan content and outer project ownership are validated. Publishing is transactional; an insertion failure rolls back all new rows and removes only that attempt's PNG.

After upgrading a phone database to schema 3, use builds that understand schema 3. An older schema-2 APK cannot reopen it; a destructive downgrade is deliberately not provided. Keep the same application ID/signing key when installing from Android Studio to preserve the existing data.

## AR, coordinate and performance details

Native ARCore **1.57.0** is optional; it is a library in the existing app. Runtime support and actual session capabilities are authoritative for **both Galaxy Z Fold7 and Vivo V50 5G**. Supported does not imply depth, usable frames or good accuracy. Missing depth uses reliable planes and manual marking; missing ARCore uses measured drawing. No simulated scene or sensor fallback is added. Initial Google Play Services for AR setup may require connectivity; local mapping needs no TURN backend.

AR +Y is vertical. The operator's origin and forward vector define a right-handed floor projection: `x = -dx·forwardZ + dz·forwardX`, `y = dx·forwardX + dz·forwardZ`. Similarity alignment maps sections into the common draft. Publication only translates to positive TURN coordinates with a 0.25 m margin. Screen Y is inverted for rendering; sensor world coordinates are not rotated with the screen. ARCore display geometry/hit testing handles screen rotation and folded/unfolded viewport dimensions. Rotation/folding and renderer lifecycle still require actual Fold7 testing.

Raw depth is processed at most twice per second (slower when warm), and only when its timestamp equals the current pose frame and exceeds previous accepted timestamps. Confidence must be ≥160/255; depth must be 0.2–8 m. Missing/low-confidence values are discarded. Independent row/pixel strides, unsigned millimetres and resolution-scaled **texture intrinsics** are respected. Copied pixels are back-projected into AR camera coordinates `(right, up, back)` and transformed using the matching camera pose; images close before background processing. Vertical evidence 0.35–2.3 m above the selected floor is accumulated in at most 4096 spatial cells. RANSAC proposes up to six lines with vertical span ≥0.6 m. No full video/depth history is retained. The plane path needs vertical span ≥0.7 m and evidence near the floor.

Pose history is bounded to 4000 samples at at most 4 Hz; anchors are capped at 96 per camera section, candidates at 300, corners at 2000. Duplicate/stale frames are rejected. Severe thermal status stops geometric additions; warm status lowers sampling frequency. Camera-loss states, low-quality surfaces and processing failures are visible. Preview can remain open while AR capture runs beneath it. No measured FPS, battery, memory or device accuracy results are claimed.

AR pose/point/wall timestamps use ARCore frame nanoseconds within their source session. Manually entered corner timestamps use epoch milliseconds; manual walls with timestamp 0 have no sensor timestamp. The source/segment fields distinguish them. Depth wall candidates require at least six supporting samples from the current accepted raw frame; empty or unrelated new frames cannot turn historical walls into fresh observations. Undo re-expresses camera history in the restored map frame and cannot clear a recorded tracking-loss flag. Pause capture before undoing a change that alters its frame setup.

Private Room data and PNGs stay in app storage. CameraX is not opened by the mapper; ARCore owns its camera. Cloud Anchors and Geospatial are disabled. No analytics, upload service, camera-video export or remote logging is added. Android extraction rules exclude the scan PNG folder as well as the database. Exports require explicit action. Normal signed APK updates preserve the database; uninstall/clear-storage removes it.

## Physical acceptance protocol — bedroom → corridor → optional room

Use the Fold7 first, with clear lighting and an unoccupied walking path. Record phone/software versions, fold/display state and reported AR/depth capabilities. Repeat capability/fallback checks on Vivo rather than assuming support.

1. Before scanning, measure two perpendicular bedroom wall lengths plus at least one **additional independent** check (prefer a diagonal). Measure doorway width and one corridor length as well. Record values separately from TURN.
2. Scan/name the bedroom, confirm actual floor/orientation, mark complete ordered corners, reject furniture candidates, confirm structural walls and mark the doorway. Record capture time and every failed hit/manual correction.
3. Continue through the door with stable tracking, start Hallway, mark/confirm its outline and link the same opening to both rooms. Add the optional next room through another explicitly marked door.
4. Pause, inspect room adjacency, openings and dimensions. Use one measured length for calibration only if needed. Add the perpendicular/diagonal/corridor lengths as independent validation. Record captured/current estimates, absolute error `|estimate − measured|` and percentage `100·error/measured` for **every** check. Do not relabel calibration as validation.
5. Proposed initial acceptance: every independent check meets `max(0.15 m, 5%)`, door gaps are visibly/physically in the correct place, no room overlaps, and all intended rooms have a valid safe route. These are evaluation criteria, **not achieved guarantees**. Tighten criteria if positioning needs it. Stop and rescan/correct if checks disagree; log the failure and correction time.
6. Save as a new floor, export PNG/scan JSON, close and reopen TURN. Confirm the saved map/rooms/doors/POIs and draft measurements persist. Confirm the original floor and any fingerprints are unchanged.
7. Inspect Paths. Complete missing concave-room/corridor connections, save, select a destination and verify A* goes only through the marked doorway. Test an unmarked wall and a non-walkable opening: neither should permit a crossing. Add a reference point in each room and confirm ownership after reopening.
8. Optionally collect multiple fresh Wi-Fi snapshots at known points, save/reopen, initialize at a known physical point and declare the correct walking direction. Test existing live navigation on the generated map; distinguish mapping error, heading/stride error and radio ambiguity.
9. During a second scan, cover the camera/turn toward a blank surface, deny camera permission, background/return, rotate portrait/landscape and fold/unfold. Saved geometry must survive, additions must stop when tracking is lost, and new sessions must demand explicit reference alignment. Test the plane-only and measured fallback honestly when available.
10. Record battery/thermal status, responsiveness, failure messages, cleanup/camera release, total time and manual corrections. No physical pass has been recorded by automated/emulator tests. Report actual results before using a scan for a public demonstration.

## Official references used

- [ARCore Android quickstart](https://developers.google.com/ar/develop/java/quickstart) and [AR Optional support/install lifecycle](https://developers.google.com/ar/develop/java/enable-arcore).
- [Depth API](https://developers.google.com/ar/develop/java/depth/developer-guide), [Raw Depth and confidence](https://developers.google.com/ar/develop/java/depth/raw-depth), [hit testing](https://developers.google.com/ar/develop/java/hit-test/developer-guide) and [Frame API](https://developers.google.com/ar/reference/java/com/google/ar/core/Frame).
- Google's [hello_ar_java](https://github.com/google-ar/arcore-android-sdk/tree/master/samples/hello_ar_java) and [raw_depth_java](https://github.com/google-ar/arcore-android-sdk/tree/master/samples/raw_depth_java) were consulted for camera/render lifecycle and depth geometry; TURN uses its own renderer and mapping code.

Completed software scope is guided capture, correction, persistence and integration. Wall candidates, dimensional accuracy, spatial alignment over long walks, foldable camera lifecycle and performance remain experimental until this physical protocol is executed. Automatic semantic room/door recognition, persistent cross-session AR relocalisation, global drift correction and a venue.json importer are not implemented.
