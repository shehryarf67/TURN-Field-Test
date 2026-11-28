# TURN Walk Mapper — guided Scan-to-Map prototype

Walk Mapper is an alternative source of map data inside TURN. It creates a one-floor, multi-room draft from real ARCore observations and operator-marked corners, or from explicitly entered physical measurements. It does not infer a floor plan from a walking/PDR trace, identify every architectural wall, or guarantee dimensional accuracy.

## How to map your home

This is the short, plain version. The sections after it explain each feature in detail.

**Before you start.** Turn on the lights, clear the floor near you, and have a tape measure handy. In TURN, open (or add) your building in **Buildings**, then go to **Tools → Map a floor with your phone**. Map one floor at a time; for a two-storey house, map the ground floor, then add the upstairs floor (**Buildings → Add floor**) and map it the same way.

The mapper walks you through six steps. Each step tells you what to do and why; tap **?** for more detail.

1. **Set the starting point.** Tap **Start camera**, point your phone at a clear patch of floor near where you're standing, and tap it (or put the **+** on it and tap **Use the spot under the +**). Confirm with **Yes, this is the floor** — not a table, bed or sofa. This spot becomes the corner of your map (0,0). *Why the floor?* TURN needs to know where the floor is so it can measure everything flat along the ground, like a tape measure.
2. **Set which way is "up" on the map.** Tap another floor point a few steps in front of you, e.g. along a hallway. The map is drawn with this direction pointing up. It doesn't need to be north.
3. **Mark the corners of this room.** Stand wherever you can see the corners. Tap them in order around the room. You can stay near the middle of the room and move sideways a little if the camera needs a better view. When the floor corner is hidden, choose **More… → Above corner** and aim at the wall seam or ceiling corner directly above it.
   - The **+** shows what TURN can mark at the centre of the camera. Green means a surface is ready, amber means the point is approximate or above the floor, and white means TURN is still looking. You can tap elsewhere on the camera image or use the button to mark under the **+**. Check amber points against the room and a tape measure.
   - Keep the phone aimed low enough to see some floor and furniture as you walk. Tilt up briefly to mark a wall or ceiling corner, then tilt back down.
   - Can't see the seam above a corner? Choose **Corner behind furniture**. Tap two spots on the visible face of one wall, at least 30 cm apart sideways, then **Next wall**. Repeat on the other visible wall face and tap **Place corner**. The mini map shows the wall samples as you add them.
   - Going into another room? Tap **Next room**, name it, and keep the camera running while you mark its corners. This places both rooms in the same map coordinates.
   - Made a mistake? Tap **Undo**.
   - When every room on this floor is done, tap **Finish floor**. Camera wall suggestions are shown as dashed lines in **Advanced → Edit → Walls**. Confirm a suggestion only if it matches a real wall missing from the outline.
4. **Check the outline.** Look at the map: does each room's shape match the real room? Tap **Looks right — finish <room>** for each room. If the camera was paused while you mapped, check the rooms still line up and tap **Rooms line up**. To move or delete a corner, or to tick which rooms a doorway joins, use **Fix corners, walls and doorways**.
   - Once the adjoining outlines are confirmed, choose **Back to marking corners** and line the camera up again with two saved corners. Choose **Doorway**, select the shared wall in **Show map**, then mark both jambs from the camera. Under **Advanced → Edit → Doorways**, tick both adjoining rooms on that one opening so the route can cross it.
5. **Check the size with a tape measure.** Measure one wall with a tape and type its length — this checks the scale. Pick the corners at each end of the wall (the numbers match the corner numbers on the map) and tap **Check this length**. TURN shows how far off the map is; about 5 % (at least 15 cm) is fine. Only if the whole map is consistently too big or small, open **Fix the map size**.
6. **Name and save.** Give the floor a name (e.g. "Ground floor") and tap **Save floor plan**. TURN saves it as a new map you can navigate on. Tap **Open saved floor** to see it.

**If the camera says…**

| Message | What to do |
| --- | --- |
| Move your phone slowly from side to side so it can see the room | Sweep the phone gently over the floor and furniture until it says Ready. |
| Too dark / Not enough detail | Turn on a light, or point at a rug, furniture edge or patterned floor. |
| No surface detected yet | Move sideways a little so the phone can see the wall, then aim again. |
| That spot is too far / too close | Move closer or step back. Marks must be 0.2–8 m from the phone. |
| That spot is too high / below the floor | Aim between floor level and 3.5 m for room corners, or up to 2.5 m for wall and doorway ends. |
| Tilt the phone down so it can see the floor and furniture | Lower your aim while walking so the camera can see textured surfaces again. |
| Lost track — point at the floor or furniture and hold still | Marking pauses briefly. If tracking returns within about three seconds and the camera position is consistent, continue in the same section. |
| The phone lost track of where it is | Tap **Pause camera**. Your work is saved. Tap **Continue with camera**, pick two corners you've already marked, and tap them on the floor to line up again. If there are no saved corners, set the starting point and direction again. |

**Manage your plans.** The mapper header shows the plan and room you're working on. Tap **Floor plans** to rename the current draft, choose **New floor plan** to start a separate draft, or choose **Open** next to an earlier draft for this floor. **Clear plan** removes the current draft's rooms, geometry, camera sections and measurements while keeping its name. **Delete draft** removes the draft from the list. Both actions ask for confirmation. Floors already saved for navigation are available in **Buildings**.

**Coming back later.** Everything is saved on the phone as you go. When you reopen a draft with corners, step 3 asks you to pick two corners you've already marked (**Saved corner A** and **B**, at least half a metre apart). Start the camera and tap the floor exactly at those two corners; the camera then lines up with your saved map and you carry on.

**Advanced.** The **Advanced** button in the mapper opens the full editor: typed coordinates, wall suggestions from the camera, joining and splitting walls, doorway widths and room links, moving/turning/resizing a camera session, fine-tuning settings (corner snap distance, wall-detection tolerance, shortest detected wall, allowed size error) and drafts. You never need it for a normal home.

**After saving.** Open the new floor, check it in **Map setup** (add place names and walking paths where needed, and mark Wi-Fi spots), then **Record Wi-Fi** at each spot and try **Explore & navigate**. For two floors, use **Tools → Link floors** to mark the stairs on both floors; setting **Floor heights** (about 2.7 m per storey in a house) and **Line up floors** there is optional.

## Open and use it

1. Open/create a project in **Real Device** mode. Open **Map Setup → Scale** and save pending map changes.
2. Choose **Map a floor with your phone** (Tools, or Map setup → Plan & size). Existing image import remains available alongside it. Stop active survey/navigation sessions first.
3. The step-by-step mapper names the first room "Room 1" automatically (rename it any time). In the Advanced editor, enter a room name and choose **Start new room**.
4. Choose **Start camera**. TURN requests camera permission at this point and checks Google Play Services for AR. Accept its install/update prompt when required, then return to TURN. No camera recording is stored.
5. Move slowly while looking at the actual floor. Once tracking is stable, tap a visible floor point, inspect its height below the camera, and confirm with **Yes, this is the floor**. Reject a table, bed or other incorrect horizontal surface. The app cannot reliably identify the floor for you.
6. Tap another point on that same floor, at least 0.5 m away, in the direction you want to be map **Up**. This declares drawing orientation, not magnetic north. It does not automatically calibrate TURN's live-navigation heading.
7. Use **Corner** (step 3, "Mark the corners of this room") and tap each room corner in perimeter order. Floor/wall surfaces must produce a reliable hit. A failed hit stores nothing: improve lighting/viewpoint or use measured drawing after pausing. **Open-side corner** (stored as BOUNDARY) and **Entrance corner** also contribute to the ordered room outline; they are not automatic door detection. If furniture hides a corner, aim at the wall or ceiling straight above it, or use **Corner behind furniture** (see *Corners hidden by furniture*).
8. Inspect the map (the mini map on the camera view, **Show map**, or **Show map** in the Advanced editor) while capture continues. Cyan indicates confirmed geometry; amber indicates drafts/candidates; green marks walkable openings. The blue camera marker and optional path are observation history, not live PDR or an inferred wall. Pinch to zoom, drag to pan, and **Fit to screen** to reset. Map rotation is an explicit saved geometry correction (Advanced → Edit → Move, turn or resize the map).
9. **Looks right — finish <room>** (step 4; **Finish room** in the Advanced editor) only after marking the complete perimeter. Three corners are a minimum, not evidence of complete coverage. The screen reports corner count and extent; observed surface coverage is unknown. Real non-rectangular outlines are retained.
10. Automatically proposed walls require explicit confirmation in **Advanced → Edit → Wall** (shown as "Suggested"). Quality/height/observation count are evidence summaries, not calibrated probabilities. Reject wardrobes, furniture, mirrors and glass mistaken for walls. Room outlines already create boundary walls: avoid confirming redundant/conflicting interior candidates.
11. Use **Wall (two ends)** (under **More…**) for missing internal wall segments. Select a confirmed wall on the map, choose **Doorway**, and mark both sides of its opening. The opening must lie on the selected wall. Review its coordinates, measured width, walkability and room links.
12. To continue into a corridor with uninterrupted tracking, start a named next room and mark its perimeter in the same coordinate frame. Reuse physically consistent shared boundaries. In Advanced → Edit → Doorways, tick both adjacent rooms for their shared doorway. A one-room entrance can link only that room.

## Corners hidden by furniture

A corner normally needs a reliable floor or wall hit at the corner itself. In furnished rooms a wardrobe, sofa or bed often hides it. Two ways round that, both recorded in the draft so nothing is silently guessed:

- **Above corner.** Aim at the wall seam or ceiling corner directly over the floor corner. TURN drops the observed point straight down. This tool only accepts points more than 0.3 m and up to 3.5 m above the confirmed floor and saves the height with `provenance = PROJECTED`. A tap elsewhere along the wall projects to that spot's floor position.
- **Corner behind furniture.** Tap two or more visible points on the first wall face at least 0.3 m apart horizontally, tap **Next wall**, repeat on the second wall face, then tap **Place corner**. TURN accepts camera points with evidence of a wall face and rejects taps landing in nearly the same floor position. It fits a line to each set and uses their intersection. It rejects angles under 30°, line fits more than 0.08 m RMS off, and intersections more than 5 m beyond the samples. The inferred corner records the samples and fit quality. You can also enter measured wall points. **Undo** removes the last wall sample while collecting, and **Start over** clears those samples.

Both kinds are drawn with an amber ring in the 2D preview (inferred corners also show their wall points), and the quality report lists them per room as a non-blocking note: "Living room: 1 corner inferred from two walls, 1 projected down from a high wall or ceiling hit; check them with a tape measure". Map corrections (rotation, section transform, scale) move the wall points with the corner. Drafts saved before this change read every corner as `OBSERVED`; the fields are additive and the draft version is unchanged.

## Several floors of one building

Each floor is mapped in its own AR session, so each has its own origin and "up". TURN keeps it that way (tracking and routing move between floors only through floor links), but *Tools → Link floors* has two building tools that help a multi-storey home:

- **Floor heights** (schema 8, `floors.elevationMetres`): the height of each floor above the ground floor, filled from one storey height. Floor detection then judges stairs and lift rides against the real storey instead of the standard 3.5 m.
- **Align two floors** (schema 8, `floors.frameAlignmentJson`): pick the same two physical spots on two floors (e.g. stairwell corners) and TURN fits a rotation and translation (scale stays 1; a distance disagreement over 5 % is reported as a warning) with its residual. The link editor can then place a link's far end straight above or below the first one, and each link card shows how far apart its ends are in plan.

See MULTI_FLOOR_NAVIGATION.md §6 and FIELD_TEST_PROTOCOL.md §7.

## Measured drawing without ARCore

The map preview, room tools, editing, verification, saving and PNG generation work without ARCore. **Start camera** is unavailable when support is absent. Depth absence alone still permits AR plane-based marking.

Choose a physical origin and one perpendicular pair of drawing axes. Coordinates are metres, **X right and Y up**. Enter the measured X/Y location of every perimeter corner using **Add measured corner** (step 3 → **No camera? Enter measurements instead**, or the Advanced editor); choose the appropriate tool first. For example, a physically measured rectangle can be entered as `(0,0), (width,0), (width,height), (0,height)`. These values must come from the building, not from a demonstration template. Enter the next room in the same frame. Confirm the outlines, select their boundary wall and enter two measured door endpoints. Link the rooms in Review. The provenance records `MANUAL_MEASURED`; no camera observation is claimed.

## Pause, reopen and align another camera section

Pause/background/exit stops camera capture. After the floor frame is established, a tracking gap temporarily disables marking. Tracking can resume in the same section within three seconds when the recovered camera pose is within 1 m and 30° of the last tracked pose. A longer gap or a larger jump interrupts the section and requires a new alignment. Hold still when the recovery message appears. A restored active section after process death is also marked interrupted.

For new AR capture with existing geometry:

1. In Capture, choose two saved physical floor corners as **Saved corner A** and **Saved corner B** (step 3 when the camera is paused, or the Advanced editor), at least 0.5 m apart. Prefer a longer visible baseline measured independently.
2. Start a new camera section. Tap the exact physical A on the floor, confirm it, then tap physical B. TURN computes and records a two-correspondence similarity transform into the existing draft.
3. A scale disagreement outside 0.8–1.25 is rejected. This gate is a guard against gross mistakes, not an accuracy claim. Pause/retry if the wrong correspondences were selected.
4. Check shared walls and measurements. Review interrupted sections explicitly before publishing. This confirmation does not repair drift by itself.

Geometry is persistent; ARCore anchors/world coordinates are not persisted spatial sessions. TURN does not use Cloud Anchors, VPS or hidden relocalisation. Separate sessions are never silently joined. A two-point alignment cannot remove nonuniform tracking drift.

## Correct and verify

Pause the camera before editing. Review supports corner coordinate edits/deletion, reopening room outlines, explicit candidate acceptance/rejection, manual wall endpoint edits, collinear joins and midpoint splits. Opening-bearing walls must have their doors reassigned/removed before join/split so provenance is not silently lost. **Tidy up where walls cross** is an explicit, undoable cleanup; it preserves opening-bearing wall identities. Room boundaries are edited through their corners. Cleanup is not an automatic architectural reconstruction algorithm.

In **Advanced → Edit → Move, turn or resize the map**, select a camera session for a controlled translation/rotation/scale correction. Rotation is in degrees counterclockwise about draft `(0,0)`; translation is applied afterward. Rotating the entire map changes drawing orientation explicitly. Large changes can invalidate shared door boundaries and require outline review. Capture alignments are composed with these corrections so future coordinates use the same frame. Undo retains bounded pose history but restores the previous geometry.

In step 5 (**Check the size with a tape measure**) or **Advanced → Measure & save**, select two corners and enter the physical tape/laser distance. A normal entry is an independent check (validation); the **Fix the map size** option (or "Use it to: Fix the size" in Advanced) records a scale calibration. Review captured estimate, current length, absolute/percentage error and any applied scale. Calibration offers a controlled global scale correction of 0.5–2.0. Repeated inconsistent lengths require remeasurement, local correction or rescanning; a global scale is not treated as a cure for distortion. The same point pair cannot count as both calibration and independent validation. Remove an incorrectly entered measurement explicitly.

Default proposed evaluation tolerance is `max(0.15 m, 5% of measured distance)`. At least one independent validation length is required to publish. Passing it does not certify the whole layout. Use multiple lengths in different directions/rooms, including a diagonal and a doorway check where possible.

Configurable review thresholds (Advanced → Measure & save → Fine-tuning): corner snap distance 0.001–0.30 m (default 0.10), wall-detection tolerance (RANSAC line inlier) 0.01–0.20 m (default 0.06), shortest detected wall 0.40–2.0 m (default 0.40), allowed size error 1–20% (default 5%). Door-endpoint acceptance tolerance is 0.12 m, and linked room boundary discrepancy must be ≤0.03 m. Door width gate is 0.45–3 m. These prototype gates are not accessibility or safety certification. Openings are projected to their owning wall before collision geometry is cut.

Quality checks report incomplete/unconfirmed outlines, invalid/overlapping polygons, missing alignment, interrupted sections, invalid doorway locations/room links, disconnected rooms and inconsistent measured verification. Unconfirmed wall candidates are identified and excluded. Shared boundary segments are merged before opening intervals are removed; cleanup cannot heal an explicit opening. Every proposed edge is checked against the exact polygon union and blocking walls.

An applied calibration and its endpoints cannot be deleted to erase its history. Undo the scale correction before removing them. If an old incorrect calibration is no longer in the bounded undo history, start a new draft and recheck the measurements. A scale factor of exactly 1 is still recorded as an applied calibration. Unapplied measurements can be removed normally.

## Save, reopen and export

**Save floor plan** (step 6) creates a separate venue/project named after the source with a mapped-layout suffix, keeping the source floor’s actual level number, its calibrated PNG asset, one walkable region per room, collision walls with doorway gaps, room destinations, path nodes/edges and scan provenance. It never replaces/rescales the source floor or moves its existing fingerprints. Publishing again creates another independent project/floor identity. The existing unique (venue, level) constraint is retained; an alternate layout is never assigned a fictitious new storey. Room/node/POI identities are scoped to the new floor.

Review any path warnings. Direct, wall-safe room-centre/doorway links are proposed. Concave rooms or confirmed internal walls can leave the graph incomplete; this is recorded as `PUBLISHED_REVIEW_PATHS`, not repaired with an unsafe edge. **Open saved floor**, then inspect/complete **Map Setup → Paths** with intermediate nodes at turns. Layout provides a room selector, and graph checks consider all rooms. Saving ordinary map edits retains the other rooms. Add reference points, save, collect fingerprints and use existing Navigate/PDR/particle-filter/Wi-Fi correction as before. Set your initial walking direction on the drawing in Navigate; scanning is not a new positioning engine.

Close/reopen TURN and open the generated floor from Projects to restore the actual PNG, geometry, POIs and graph from Room. Reopen the source project's mapper to resume/edit its saved scan draft. Drafts auto-save periodically (about 700 ms after a pending change) and can be saved explicitly. A hard termination can lose changes newer than the last successful save. A failed explicit exit-save keeps the mapper open for retry. New/open draft operations save the current draft first.

Before leaving the published draft, **Export image (PNG)** and **Export map data (JSON)** (step 6 → Export, or Advanced) use Android's document picker. PNG is the last published map, not an unsaved edit; publish a new floor to regenerate it. The PNG is rendered from the same translated metric geometry and includes room labels, real wall/opening geometry and a scale indicator. The experimental JSON contains version, axes/units, rooms, openings, segment transforms, bounded poses, raw-source labels, measurements, thresholds and publication notes. It is **not** TURN-Mapper `venue.json 0.1.1`; no importer for that independent format is provided. Existing full Room JSON backups now include scan drafts; a PNG/scan JSON alone is not a portable backup/import of a surveyed TURN project. Internal PNG URIs in existing Room exports retain the existing same-installation asset limitation.

## Implementation and database safety

The repository audit reused `MainActivity`, `TurnApp`/`TurnRuntimeViewModel`, `MapSetupScreen`/`NavigationMap`, `ProjectRepository`, existing geometry/A*, Room repositories/DAOs and the university prototype flow. Wi-Fi freshness, survey lineage, sensor/PDR/PF and evaluation engines remain the existing implementations. The missing components were AR capture, scan drafts, floor-frame alignment, depth fitting, room/door review and conversion to existing map rows.

- `mapper/ar/ArScanView.kt`: one ARCore/OpenGL camera, hit testing, floor anchors, display geometry on every frame/size change, vertical-plane candidates, native/image cleanup and bounded background raw-depth processing.
- `mapper/WalkMapperActivity.kt`: support/install/permission checks, optional session lifecycle, pause/cleanup, SAF exports and result handoff to MainActivity.
- `mapper/WalkMapperViewModel.kt`: guided capture/edit state, asynchronous serialized saves, bounded undo, explicit new-section alignment and publication.
- `mapper/core/{ScanModels,ScanGeometry,DepthGeometry,CaptureGuard}.kt`: serializable metric contract, transformations, geometry/door/QA/graph proposals, stride-aware back-projection, bounded robust fitting and lost-session guard.
- `mapper/core/HiddenCorners.kt`: corner from two fitted wall lines (parallel, straightness and extrapolation gates), vertical projection of elevated hits, corner provenance and its quality note (tests: `HiddenCornersTest`).
- `mapper/data/{ScanDraftEntity,ScanRepository}.kt`: draft DAO, transactional publication and private PNG rendering.
- `mapper/ui/{WalkMapperScreen,ScanPreview}.kt`: capture/review/verification UI and responsive map surface.
- `MainActivity.kt`, `ui/{TurnApp,TurnAppState,TurnRuntimeViewModel}.kt`, `ui/screens/{MapSetupScreen,NavigationMap,FloorEditorScreen}.kt`: entry/result and safe multi-region editing/rendering.
- `data/local/TurnDatabase.kt`, `data/export/{BackupDao,ExportModels}.kt`, manifest, app Gradle and extraction rules: optional AR dependency, additive storage and export integration.
- New JVM and Android tests cover transforms, depth, geometry, capture guards, region editing, migration, publication rollback and measured fallback. See test sources and `WALK_MAPPER_VERIFICATION.md` for actual run results.

Room schema **2 → 3** only creates `walk_scan_drafts` and its floor index/foreign key. The document stores room/door/capture-specific data without changing existing metric entities. The existing 1 → 2 migration remains in the complete 1 → 2 → 3 chain; schemas 1 and 2 are retained and schema 3 is committed. No destructive fallback is enabled. Full database export schema is now 3, with an optional empty draft list for legacy JSON. Scan content and outer project ownership are validated. Publishing is transactional; an insertion failure rolls back all new rows and removes only that attempt's PNG.

After upgrading a phone database to schema 3, use builds that understand schema 3. An older schema-2 APK cannot reopen it; a destructive downgrade is deliberately not provided. Keep the same application ID/signing key when installing from Android Studio to preserve the existing data.

## AR, coordinate and performance details

Native ARCore **1.57.0** is optional; it is a library in the existing app. Runtime support and actual session capabilities are authoritative for **both Galaxy Z Fold7 and Vivo V50 5G**. Supported does not imply depth, usable frames or good accuracy. TURN tracks both horizontal and vertical planes and prefers Automatic depth when supported. Missing depth still allows plane and oriented feature hits; missing ARCore uses measured drawing. No simulated scene or sensor fallback is added. Initial Google Play Services for AR setup may require connectivity; local mapping needs no TURN backend.

The start and direction taps require hits inside an upward-facing plane polygon, followed by floor confirmation and a same-floor check. After alignment, TURN compares plane, depth and oriented feature hits within 0.25 m of the nearest valid hit. This keeps a distant floor plane from overriding a wall in the foreground. Vertical and downward-facing planes allow a 0.3 m direct-hit margin. The fallback can extend a tracked wall up to 0.65 m from its observed boundary, the confirmed floor, or a nearby detected ceiling patch. The chosen tool limits which surfaces qualify. Margin, feature and extended-plane hits are approximate and appear amber. The draft records the sources of corner hits, wall and doorway endpoints, and hidden-corner samples.

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
9. During a second scan, test the reticle and record successful/failed taps at floor corners, above furniture, on both hidden-corner walls, and at ceiling corners. Briefly cover the camera while holding the phone still and check that marking resumes in the same section. Repeat with a longer gap and verify that marking stays blocked until realignment. Deny camera permission, background/return, rotate portrait/landscape and fold/unfold. Saved geometry must survive. Test without depth and with measured drawing when available.
10. Record battery/thermal status, responsiveness, failure messages, cleanup/camera release, total time and manual corrections. No physical pass has been recorded by automated/emulator tests. Report actual results before using a scan for a public demonstration.

## Official references used

- [ARCore Android quickstart](https://developers.google.com/ar/develop/java/quickstart) and [AR Optional support/install lifecycle](https://developers.google.com/ar/develop/java/enable-arcore).
- [Depth API](https://developers.google.com/ar/develop/java/depth/developer-guide), [Raw Depth and confidence](https://developers.google.com/ar/develop/java/depth/raw-depth), [hit testing](https://developers.google.com/ar/develop/java/hit-test/developer-guide) and [Frame API](https://developers.google.com/ar/reference/java/com/google/ar/core/Frame).
- Google's [hello_ar_java](https://github.com/google-ar/arcore-android-sdk/tree/master/samples/hello_ar_java) and [raw_depth_java](https://github.com/google-ar/arcore-android-sdk/tree/master/samples/raw_depth_java) were consulted for camera/render lifecycle and depth geometry; TURN uses its own renderer and mapping code.

Completed software scope is guided capture, correction, persistence and integration. Wall candidates, dimensional accuracy, spatial alignment over long walks, foldable camera lifecycle and performance remain experimental until this physical protocol is executed. Automatic semantic room/door recognition, persistent cross-session AR relocalisation, global drift correction and a venue.json importer are not implemented.
