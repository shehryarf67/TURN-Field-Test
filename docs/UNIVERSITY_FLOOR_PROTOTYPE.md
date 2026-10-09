# One-floor navigation prototype

TURN's physical workflow is now **Projects → Map setup → Survey → Navigate**. Evaluation, diagnostics, settings and exports remain available through Tools & settings; detailed sensor and radio panels are also reachable from Survey and Navigate. The app remains offline and building-independent.

## Prepare the university floor

1. In **Real Device**, create or open the university venue/floor in Projects.
2. In Map setup → Scale, import the PNG/JPEG. New imports are copied into private app storage. Set the full image's measured width/height, or select A–B and enter the measured distance. The image fills that metric rectangle; use an image cropped to the floor's measured extents.
3. In Layout, tap the corners of the walkable outline and close it. Add walls by tapping two endpoints. Leave openings at doors. This editor supports one simple polygon; existing unsupported geometry is preserved and cannot be overwritten through this editor.
4. In Paths, select **Node** and place nodes at corridor ends, turns, intersections and destinations. Select **Connect**, then tap successive nodes to link them. Connections are bidirectional, weighted by metric length, and rejected if they cross a wall or leave walkable space. Crossing lines are not automatically connected: place a shared node at each intersection.
5. Select a node with Inspect, enter a destination name, and save the destination in the draft. Use names appropriate to this building, such as Lab 1, Office or Main entrance.
6. In Points, tap known survey locations. Advanced map tools retain exact coordinate entry and the existing QR-anchor definitions. **Save map** commits the geometry, points, destinations and paths together.

Calibrate before adding path nodes or recording measurements. Scale changes are locked afterward to protect recorded coordinates. Saving rejects moving/removing a fingerprinted reference point. Prefer a new point/project when the physical survey coordinate system changes.

## Collect real fingerprints

1. Grant location/nearby Wi-Fi permissions, enable Wi-Fi and location services, and check Diagnostics on the actual phone.
2. Stand at the selected reference point. Choose a fresh-snapshot target and Begin collection. Keep TURN in the foreground.
3. TURN stores raw BSSID/RSSI rows and scan metadata as results arrive. Cached broadcasts, repeated AP timestamps, stale AP rows and readings from before the survey began cannot count as new evidence. Android throttling/wait states remain visible.
4. Reaching the target closes and saves the fingerprint automatically. **Finish and save** before the target records a partial collection, preserving its raw lineage. A partial new collection is excluded from live matching; start a new collection to finish the point. Older completed data at that point remains usable.
5. Repeat at enough distinct physical positions to cover the route. Close/reopen the app: map, graph, sessions and completion badges reload from Room. Old surveys are labelled Saved legacy because their original target was not recorded; they remain available for matching.

Survey facing direction is optional research metadata. It does not select a fingerprint, rotate reference-point coordinates or initialize live heading. Research details now defaults to **Not recorded** (saved as null) and offers optional **Map right/up/left/down** notes instead of compass labels. Old stored North/East/South/West notes are preserved. Hold the phone consistently during surveying and subsequent use; grip/orientation can affect the radio measurements themselves even though the text note is not used by matching.

## Walk the route

1. Open Navigate and select a destination. Choose **Pick known start** on the map or **At a reference point** only when physically standing there; otherwise use fresh Wi-Fi initialization.
2. Choose **Right / Up / Left / Down** for the direction you are walking on the drawing, or enter degrees for a diagonal: 0° right, 90° up, 180° left, 270° down. Keep the top of the visible phone screen facing that direction when pressing Start navigation. This aligns relative sensor heading with the drawing; it does not infer compass orientation from the image. No compass direction is required for surveying. Automatic alignment of live heading would require a known relationship between the drawing and physical north or another heading anchor; a compass alone cannot establish it.
3. Start navigation. A declared known location initializes a particle distribution; automatic initialization waits for a sufficiently confident fresh weighted-kNN fix. There is no simulated fallback if hardware or data is missing.
4. The blue dot is the particle-filter estimate, with an uncertainty circle. Physical step events propagate PDR and particles; geometry rejects impossible motion; fresh Wi-Fi evidence periodically corrects the filter. The phone needs a working supported step source and relative-heading source.
5. A* attaches the current estimate to the nearest wall-safe mapped path/node within 3 m and routes to the destination. It recalculates from new estimates, showing remaining metric distance. It does not snap or animate the position along the route. Disconnected paths, off-path estimates and lost tracking produce an explicit unavailable-route state.
6. Arrival is indicated only within 1.5 m along the route, with confidence ≥ 0.35 and uncertainty ≤ 3 m. Estimates older than 20 seconds are labelled as last known; stale arrival guidance is suppressed. These prototype thresholds are not a positioning accuracy guarantee.
7. Enable Debug overlays for particles, raw PDR or Wi-Fi fixes. Research details retain correction diagnostics and explicit global relocalization. Stop navigation to close the persisted positioning session.

### Optional full-screen map and landscape

Tap the **Full screen map** icon beside the Navigate header. The map fills the display with compact floor/tracking/destination information. Choose **Landscape**, **Portrait** or **Auto rotate**; Auto follows the phone's rotation setting. **Exit full screen** or Android Back returns to the normal view and restores the earlier orientation policy. The normal landscape layout also places controls beside the map.

Choose the orientation before starting your walk when possible. Set the initial map direction for the top edge of the **visible screen**, and keep that edge facing your walking direction. Screen rotation does not change Wi-Fi measurements or step detection. Android's sensor axes remain tied to the device's natural orientation, so TURN remaps rotation-vector heading to the displayed orientation and compensates display turns for the gyroscope fallback. Rotation keeps the same Activity, sensor listeners, positioning session, particles and route; it does not invoke the foreground-stop path. Changing physical grip or phone tilt while walking can still disturb heading, especially with gyroscope-only tracking; this requires validation on the actual phone. See Android's [sensor coordinate-system documentation](https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview) and [immersive-mode guidance](https://developer.android.com/develop/ui/views/layout/immersive).

Normal app restart preserves data. Uninstalling the app or clearing its storage does not. Android backgrounding ends collection/tracking rather than pretending sensors continued uninterrupted; begin a new session when returning.

## Persistence and compatibility

- Room schema **1 → 2** uses an explicit additive migration: navigation nodes/edges and survey target/completion metadata. Existing map, Wi-Fi, sensor, positioning and evaluation tables/rows are retained. There is no destructive migration fallback.
- Graph records have floor ownership and foreign keys. Saving a map uses a transaction. Calibration and surveyed coordinate protections remain in place.
- JSON database exports now include the graph and survey completion fields (export schema 2); the decoder still accepts schema 1. Existing research CSV export remains available. Backup JSON references image URIs and does not embed the image bytes; cross-device backup restore/image packaging is not part of this prototype UI.
- Real Device evidence comes only from Android hardware. Demo remains separately labelled. BLE is optional and unused by this workflow.

## Demonstration acceptance run

### Checking live Wi-Fi accuracy — 2026-10-09

The hollow marker is the latest usable **Wi-Fi match**, not ground truth or the fused blue dot. Earlier fixes remain in research state, but the map no longer draws them all as current positions. Fresh Wi-Fi changes particle weights; a correction does not necessarily teleport the fused estimate to that marker. Raw PDR intentionally retains its uncorrected trajectory for evaluation.

Manual **Request fresh Wi-Fi fix** requires AP measurements taken after the button press, using monotonic Android scan timestamps. Cached broadcasts, duplicate AP measurements, pre-session/pre-request rows, stale rows and simulated batches cannot provide that requested correction. A rejected/throttled request is labelled explicitly; the screen shows the next permitted request countdown. Keep the phone stationary while waiting for a new result. Android can deliver platform/other-app scans too; fresh post-request evidence can be used without claiming the broadcast came from TURN's request.

Weighted kNN now treats k as a maximum. Candidates must share at least 3 BSSIDs for physical live matching, and only candidates within best RSSI distance + max(3 dB, 50% of best distance) contribute. If competitive candidates have spatial spread over 6 m and none carries 60% of the selected-floor weight, the match is labelled ambiguous and cannot initialize, correct or globally relocalize the filter. Matches unlike the database or averaging outside walkable space are also rejected. These conservative prototype thresholds need field validation; they cannot make identical radio fingerprints distinguishable.

For the next hallway test:

1. Keep existing Room data. Confirm reference-point coordinates at the corridor start, middle and end and that each survey is Completed. Collect additional completed surveys at a point if its radio environment or phone grip has changed.
2. Use the same phone and grip as surveying. Leave device-offset normalization off for this comparison: removing a uniform RSSI difference may remove useful location evidence when fingerprints differ mainly in overall signal level.
3. Stand still at each known point, request a fresh fix, and wait for a new usable-match timestamp. Repeated taps cannot bypass Android throttling.
4. Open Research details → Wi-Fi evidence. Record the candidate reference IDs, RMS RSSI distances, weights, shared AP counts, result age, rejection reason, Wi-Fi marker and fused estimate. Repeat several independent fresh scans at each point.
5. Use evaluation checkpoints/exports to compare against known coordinates. If start and end have similar scores, improve survey coverage or record that radio ambiguity; do not force the endpoint just because the researcher knows where they are.

The existing raw survey observations, completion eligibility, BSSID matching, Room persistence and real/demo separation are retained. Physical university-floor accuracy still requires this phone test.

On the actual university floor, record the complete prepare/survey/reopen/navigate sequence. Include at least one turn, a wall separating nearby corridors, multiple surveyed points, a disconnected-path failure, and a stop/restart. Use independent evaluation checkpoints to measure error rather than treating the displayed blue dot as ground truth.

Automated builds, graph/freshness tests and emulator persistence/UI tests cannot validate physical step detection, heading alignment, radio coverage or positioning accuracy. A real-phone walk-through and parameter tuning on the chosen floor remain required before the external demonstration. Full multi-floor navigation, cloud services, automatic floor-plan extraction, BLE deployment, QR camera initialization and accessibility certification are outside this slice.

## Verification — 2026-09-23

- `testDebugUnitTest`: 88 passing JVM tests, including routing, fresh-evidence eligibility and graph export compatibility.
- `assembleDebug`, `assembleDebugAndroidTest`, `lintDebug`: successful; lint has 0 errors and 9 existing warnings.
- Android API 35 x86_64 emulator: all 7 instrumentation tests pass, covering populated v1 migration, restart persistence, partial/demo fingerprint exclusion, map/destination editing, selected survey progress, known-start selection and map hit testing after resize.
- Verification used an isolated build copy and test emulator. No physical-phone data or installation was changed. Physical university-floor accuracy has not been validated.

## Full-screen verification — 2026-10-04

- 89 JVM tests and 11 Android API 35 instrumentation tests pass; debug app/test APKs compile and lint reports 0 errors, 9 existing warnings.
- New checks cover heading remapping for all four display rotations, gyroscope display-turn compensation, full-screen exit, immersive bars, Android Back, orientation-policy restoration and Activity/ViewModel/session continuity through landscape and portrait.
- Portrait and landscape layouts were inspected on the isolated emulator. Room schemas and persistence are unchanged by this feature.
- These checks validate display math and lifecycle behavior, not physical walking accuracy. Validate phone grip, tilt, heading and radio coverage on the actual university floor before recording the demonstration.

## Wi-Fi and survey workflow verification — 2026-10-09

- 96 JVM tests pass with no failures/skips, including pre-refresh/session timestamp rejection, repeated/cached/simulated evidence rejection, duplicate AP row selection, hallway endpoint matching, ambiguity detection, shared-BSSID eligibility and rejection by the particle filter.
- All 13 Android API 35 instrumentation tests pass. New UI checks cover requesting Wi-Fi during initialized tracking, optional survey metadata without invented north, and choosing an initial direction on the drawing. Existing migration, persistence, navigation and rotation checks remain passing.
- Debug app/test APKs compile; lint reports 0 errors and 9 existing warnings. Verification used an isolated build and emulator; source matches the verified copy and Room schemas remain unchanged. No physical-phone installation or saved data was altered.
- No recorded hallway radio dataset was available for replay. The changes address reproducible code weaknesses, but the cause and accuracy of the reported physical test must be verified with the next known-point run.
