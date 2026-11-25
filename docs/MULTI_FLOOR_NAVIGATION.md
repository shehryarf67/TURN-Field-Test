# Multi-floor navigation: design and roadmap

TURN tracks and routes on one floor at a time. This document sets out what multi-floor navigation needs, which technologies to use, and the order to build it in. It is written against the code on `main` as of 2026-11-19.

## 1. What already exists

| Building block | Where | State |
|---|---|---|
| Multi-floor metric model (`MetricMap.floors`, per-floor walkable space and walls) | `core/Geometry.kt` | Ready; sessions only ever load one floor |
| Vertical transition (stairs/lift area on each of two floors) | `core/Geometry.kt` `VerticalTransition` | Ready, unused |
| Particle floor IDs, floor confidence, `requestFloorTransition` (only particles inside a transition move) | `core/ParticleFilter.kt` | Ready, never called |
| Wi-Fi floor voting and cross-floor penalty | `core/WifiPositioning.kt`, `ParticleFilter.correctWithWifi` | Ready; fingerprints are filtered to the current floor |
| `vertical_transitions` table (from/to floor, point on each, kind, radius, bidirectional) | `data/local/Entities.kt` | In schema and backups, no editor |
| Floor level number and per-floor evaluation (`*FloorCorrect`) | `FloorEntity`, `TestSampleEntity` | Ready |
| Barometer sensor | `platform/sensors/SensorSource.kt` | Registered, shown in Diagnostics, not used |
| Seven C-block floors (C-B1 … C-F5) with stair-landing anchors | `assets/mapper/` | Imported one floor at a time; packages with connectors are rejected |

Key gaps: a project is one venue **and one floor** (`ProjectContext`), live sessions load one floor, routing is per floor, the UI shows one floor, and nothing detects a floor change.

**Coordinate frames.** Every mapper floor has its own origin ("bottom-left corner per floor"). The stairs are not at the same coordinates on every floor (C-GF landings at x≈41–46 m, C-F3/F4 at x≈5 and x≈51 m). Do not stack floors in one frame. Keep per-floor coordinates and connect floors with explicit **portal pairs**: an area on floor A linked to an area on floor B. `VerticalTransition` and `vertical_transitions` already work this way. A floor-to-floor alignment transform is optional and only needed for 3-D display.

## 2. Concepts and technologies

### 2.1 Building model
- **Venue = building; floors belong to a venue.** The project opens a venue, and the active floor becomes state, not identity.
- **Connectors** (stairs, lift, escalator, ramp): a polygon or point plus radius on each floor, with kind, direction, accessibility (step-free), travel cost (e.g. stairs ≈ 10–15 s per floor; lift = wait + ≈ 5 s per floor) and an optional label ("Stairs 1"). A lift serving several floors is one connector with several stops, stored as pairwise links.
- **Destinations across floors:** search lists every floor's places with their floor badge.

### 2.2 Floor detection: fuse several weak cues, never trust one
No single sensor is reliable on every phone, so floor changes come from evidence fused in the particle filter:

1. **Map context (hard rule).** A floor change is only possible for particles inside a connector area. `requestFloorTransition` already enforces this.
2. **Barometer (best cue when present).** Air pressure falls about 0.12 hPa per metre near Islamabad's altitude, so one 3.5 m floor is about 0.4 hPa. That is well above a phone barometer's short-term noise, but far below weather drift (≈1 hPa per hour) and building HVAC or door effects. Use only *relative* change over a short window (≈10–30 s), with a smoothed signal and hysteresis (e.g. a sustained change ≥ 60 % of the floor height). Re-anchor the reference whenever Wi-Fi floor confidence is high. Many budget phones have no barometer, so everything below must work without it.
3. **Wi-Fi floor evidence.** Fingerprints from all floors take part in matching. The matcher's floor vote and the filter's cross-floor penalty already exist. APs on the same floor are strongest, but signals leak through slabs and atriums, so treat this as probabilistic.
4. **Motion context (works on every phone).**
   - *Stairs:* steps continue while inside a stair connector. Step cadence and phone pitch change, and with a barometer the pressure changes steadily.
   - *Lift:* no steps, a characteristic vertical-acceleration pulse at the start and end (accelerometer/gravity), and usually a strong magnetic disturbance inside the car.
   - A small on-device classifier (rule-based first, a learned model later if needed) emits *"vertical movement up/down, kind, magnitude"* events.
5. **Magnetic and Wi-Fi maps per floor.** After a transition, floor-specific maps confirm or reject the new floor within a few steps.

**Filter behaviour.** On a vertical-movement event, move particles inside the connector to the linked floor's exit area, with the count of floors from the barometer or lift duration. Keep a minority of hypotheses on the original floor until Wi-Fi or magnetic evidence resolves it. Report floor confidence in the UI and refuse to announce a floor change below a threshold. Without any vertical evidence, a particle stays on its floor.

### 2.3 Multi-floor routing
- One routing graph across floors: per-floor nodes plus **connector edges** whose cost is travel time, not metres.
- **Accessibility:** step-free routing uses lifts and ramps only (`accessible` already exists on edges).
- **A\*:** keep the 2-D heuristic within a floor; add a lower bound per floor change (cheapest connector) so the heuristic stays admissible.
- **Instructions are floor legs:** "Walk to Stairs 1 → go up 2 floors to F4 → walk 18 m to C-402". The route preview shows the current floor's leg and highlights the connector to use.

### 2.4 UI and UX
- Floor switcher next to the search bar (the Wayfinder floor badge becomes a picker). The map follows the estimated floor automatically during live tracking unless the user is browsing another floor.
- A banner at connectors: "Take the stairs up to Floor 4". On arrival: "You're on Floor 4", with a confirm or correct option if floor confidence is low.
- Search across floors, grouped by floor; route preview per floor leg.
- **Setup:** a "Link floors" tool to tap a point on floor A, then the matching point on floor B, choose the kind, and save. Suggested links come from stair-landing anchors with matching labels, but must be confirmed on site.

### 2.5 Surveying
- Wi-Fi and magnetic surveys stay per floor (already supported). Add survey points within 3–5 m of every connector on each floor; transitions are where floor evidence matters most.
- **Barometer reference:** record pressure at each surveyed point, then store relative floor heights per venue (floor height = pressure difference ÷ local hPa per metre). This calibrates "how much pressure change is one floor here".
- Walk each connector during setup so the motion classifier can be checked on the real stairs and lift.

### 2.6 Data and persistence
- Schema 5 (additive): connector `label`, `travelTimeSeconds`, `accessible`, and multi-stop lift support in `vertical_transitions`. Add barometric reference pressure on reference points and floor height on floors. (Floor height landed in schema 8 as `floors.elevationMetres`; reference pressure is still to do.)
- **Import:** a "Import whole building" option that loads all C-block floors as one venue and proposes stair connectors from matching anchor labels, for confirmation.
- **Export:** include connectors and floor-change events. The evaluation already stores floor correctness.

### 2.7 Evaluation
Measure on the real C block before tuning:
- **Floor accuracy:** the share of samples on the correct floor (already recorded).
- **Transition latency:** seconds from stepping off the stairs or lift until the estimate shows the correct floor.
- **False floor changes:** changes per hour with no real transition.
- **Route success** for cross-floor test courses (GF → F4 by stairs, B1 → F3 by lift), including the accessible-only variant.
- Compare phones with and without a barometer.

The practical protocol and the replay tool are in `docs/FIELD_TEST_PROTOCOL.md` (phase 4).

## 3. Roadmap

| Phase | Outcome | Main work |
|---|---|---|
| **0. Venue-level runtime** | Open a building, switch floors, data unchanged | `ProjectContext` → venue + active floor; load all floors into `MetricMap`; per-floor fingerprints and magnetic maps loaded together; floor switcher. No tracking change yet. |
| **1. Connectors** | Floors linked in data and on screen | Schema 5 migration; "Link floors" editor; whole-building C-block import with suggested stair links; connectors drawn on maps; backup/export. |
| **2. Floor detection** | Tracking follows real floor changes | Barometer pipeline (relative, hysteresis, re-anchoring); stairs/lift motion classifier; Wi-Fi across floors; particle-filter transition step; floor confidence UI; tests with recorded sensor traces. |
| **3. Multi-floor routing** | Directions between floors | Cross-floor graph with connector costs; accessible-only option; floor-leg instructions and banners; cross-floor search. |
| **4. Field evaluation and tuning** | Measured accuracy on C block | Cross-floor test courses; latency and false-change metrics; threshold tuning per phone class; documentation. |

Phases 0 and 1 are low-risk groundwork. Phase 2 carries the research risk and needs real stair and lift recordings from C block early. Sessions currently record steps, positions and corrections but not raw barometer or accelerometer traces, so add a small "record raw sensors" diagnostic during phase 1 and collect recordings then.

## 4. Decisions to make now
1. **Pilot scope:** which floors and connectors first? Suggested: C-GF, C-F1 and C-F4 with both staircases, then the lift if C block has one.
2. **Phones:** which test phones have a barometer? Check Diagnostics on each. This decides how much weight motion and Wi-Fi cues must carry.
3. **Floor heights:** measure or confirm C-block floor-to-floor height. It sets the barometer threshold and stair travel time.
4. **Connector truth:** confirm on site which stair landings connect, since mapper anchor labels are automatic.

## 5. Immediate next steps
1. Start phase 0: venue-level project context and floor switcher, with no change to tracking behaviour.
2. Add a raw-sensor recording diagnostic (barometer, accelerometer/gravity, steps, magnetometer, Wi-Fi scans with timestamps), then record C-block stairs up and down, and the lift if available, on two phones.
3. Draft the schema 5 migration and the "Link floors" editor (phase 1).

## 6. Progress
- **Phase 0 (done):** buildings hold several floors; floor switcher; per-floor mapper sources, levels unique per building.
- **Phase 1 (done):** schema 6 adds a name, travel time, step-free flag and "checked on site" flag to each floor link (additive migration; old lift/ramp rows become step-free). *Tools → Link floors* (or *Link floors* on the open building's card) lists links, suggests stair links from matching numbered stair landings on adjacent levels, and edits links by tapping a point on each floor's map. Links show on the navigation map as lettered badges (S, L, E, R); unchecked links have a dashed amber ring. *Diagnostics → Raw sensor recording* writes barometer, motion, magnetic and Wi-Fi rows (no network names) to a CSV you can save anywhere, for the phase 2 stair and lift recordings.
- **Before phase 2:** confirm suggested stair links on site, and record C-block stairs and the lift on two phones with the raw recorder.
- **Phase 2 (steps 1-9 done; steps 10-11 moved to phase 4):** live tracking follows stairs and lifts through checked floor links. With *Multi-floor tracking* on (Map options, default on) and at least one usable link from the start floor, a session loads the linked floors, the particle filter carries floor hypotheses, the barometer and stairs/lift cues move them through links, and Wi-Fi may move the position to another floor only with confirmation. The map follows the confirmed floor; the live card shows a floor chip, a banner announces changes (with Relocalize when unsure), and Evaluate asks for the checkpoint floor. *Use unchecked floor links* (default off) admits links not yet checked on site. With tracking off, or no usable link, sessions run exactly as before. Thresholds are synthetic until the field recordings are replayed.
- **Phase 3 (routing and UI done; field courses in phase 4):** directions between floors. A route to a place on another floor walks each floor's paths and rides the session's floor links, choosing stairs or lift by total time; consecutive rides on the same stairs merge ("Go up 2 floors to F4 by Stairs 1"). *Search places* lists the open floor first, then the building's other floors grouped by level. Picking a place on another floor previews the route from the chosen start on the open floor: the floor's leg on the map, the link to take ringed in blue, walking distance, time and the leg list. While tracking, the route re-plans from the confirmed floor on every update, so the next leg takes over when the floor changes; near the link a banner says which link to take and where (also in full screen). *Step-free routes* (Map options, default off) uses only lifts and ramps flagged step-free, and step-free paths. Routes respect *Use unchecked floor links*, and each failure gets one short reason ("No step-free way to F4", "No checked floor link reaches F4 · allow unchecked links in Map options"). Same-floor routes and single-floor sessions run exactly as before.
- **Phase 4 (floor change log, recording markers, field replay tool and field test protocol done; field courses and threshold tuning next):**
  - *Floor change log.* Schema 7 adds `floor_transition_events` (additive migration 6→7). Multi-floor sessions write every floor-tracking event to it, numbered per session from 0; single-floor sessions write nothing. Rows are written off the main thread and a failed write never stops tracking. The full JSON export is now schema 4 with a `floorTransitionEvents` list (older exports import with an empty list; rows must name a known session, floors and link), and *Export data* has a "Floor change log" CSV.
  - *Event types.* CONFIG (sequence 0: the session's floor-detection config and whether a barometer was present), VERTICAL_EVENT (the barometer saw a climb or descent), DWELL_CUE (no-barometer stairs or lift cue), APPLIED (the event moved the floor hypotheses through a link), REJECTED (no link explained it; the height stays pending), PENDING_EXPIRED (that pending height timed out), CONFIRMED and UNCERTAIN (the floor decision changed state), ABSOLUTE_FIX (manual start, QR, Wi-Fi start or Relocalize set the floor), WIFI_CROSS_FLOOR_RELOCALIZATION / _DEFERRED / _VETOED (what happened to a Wi-Fi fix on another floor), BAROMETER_HEALTH (the barometer went OK, stale or missing).
  - *Reading the data.* FUSED position rows keep the particle filter's most likely floor; the floor the app displayed is the `toFloorId` of the latest CONFIRMED (or ABSOLUTE_FIX) row in `floor_transition_events`. The CONFIG row holds the config a replay needs to reproduce the session. Because the map follows the confirmed floor, the saved settings remember the followed floor: TURN reopens on the floor a session ended on, not the one it started on.
  - *Recording markers.* *Diagnostics → Raw sensor recording* has marker buttons, usable only while recording: Start stairs up/down, Enter lift up/down, Standing still, and "Arrived on…" chips for the open building's floors. They write the `TraceMarkers` grammar (`VERTICAL_START STAIRS UP`, `STILL`, `ARRIVED <floorId>`) that the replay harness reads as floor truth.
  - *Advice.* Give each floor link an activation radius that covers the whole stairwell landing (or lift lobby), so the particles near the link when the barometer event lands are inside it.
  - *Field replay and protocol.* `docs/FIELD_TEST_PROTOCOL.md` is the C block guide: setup, the four courses, which marker to tap when, export, and the targets (accuracy, latency ≤ 6 s, 0 false changes, route success). Recordings dropped into `app/src/test/resources/field/` (a CSV and a small JSON each, with floors and links read from a complete database JSON) are replayed by `./gradlew.bat testDebugUnitTest --tests "com.turn.fieldtest.core.replay.FieldReplayTest"`, which writes a metrics report; with no recordings it is skipped. Synthetic replays now also cover stairs and a lift ride without a barometer.
  - *Fixes from the phase 2 and 3 reviews.* Wi-Fi that agrees with the displayed floor right after a change is no longer vetoed (it used to be able to mark the floor uncertain). The live map's per-floor summary is cached, and the session router no longer re-validates every floor's paths on each update. In a multi-floor session, a floor without saved paths says so instead of borrowing the start floor's paths.
- **Home testing tools (schema 8, export schema 5).** For a first test in a two-storey house (2.6-2.8 m storeys, each floor mapped in its own Walk Mapper session):
  - *Floor heights.* `floors.elevationMetres` (nullable, additive migration 7→8). *Link floors → Floor heights* fills every floor from one storey height (level × height) or takes each floor's height, and checks that each level is 1.5-10 m above the one below. When every floor of a session has a height (`SessionBuilding.elevations`, `core/FloorElevations.kt`), a settled barometric event is judged against the real height between the floors involved: the fractional floor count is read off the piecewise-linear elevation curve from each floor holding at least 10 % of the weight (plus the confirmed floor), with the same 0.6-minimum and 0.35-ambiguity rule as before. So a 2.7 m climb is exactly one floor, a climb the barometer under-reads (1.9 m of a 2.6 m storey) is still a floor, and two 2.7 m storeys in one climb are two floors (the nominal 3.5 m storey said "one or two" and could stop at the middle floor). The pending-height checks (Wi-Fi gate, "back at the anchored height") use the same heights. Unset or incomplete heights keep the nominal 3.5 m storey and give exactly the previous results (the detector's original code path; the CONFIG event text is unchanged); with heights, the CONFIG event ends with `; elevations=<floor>=<metres>, …` and the start status line with "floor heights set".
  - *Align floors at the stairs.* `floors.frameAlignmentJson` (nullable, same migration): *Link floors → Align two floors* takes the same two spots picked on two floors and fits a rigid transform (rotation + translation, least squares, scale 1; `core/FloorAlignment.kt`), shows the turn, shift, residual and the distance ratio, and warns when distances differ by more than 5 %. It is saved on the aligned floor (one alignment per floor pair; chains are followed in either direction). The link editor can place the far end of a link straight above or below the first, and every link card says how far apart its ends are in plan (more than 1 m for a lift, or 5 m for stairs, escalators and ramps, is flagged). Tracking and routing never read the alignment: floors still change only through links or absolute fixes, and buildings without an alignment behave as before.
  - *Threshold editor.* *Diagnostics → Floor detection thresholds* lists 29 `FloorDetectionConfig` values by group with their defaults and allowed ranges (`core/FloorDetectionOverrides.kt`). Overrides are validated (range, whole numbers, and combinations the config refuses, e.g. a settle speed above the start speed), saved in the settings DataStore (`floor_detection_overrides`, a small JSON object), and applied when the next *Live locate* session starts; a running session keeps the values it started with. *Reset to defaults* removes them all. A session with overrides lists them at the end of its CONFIG floor event (`; overrides=key=value, …`, after the full config JSON) and its status line ends with "custom thresholds"; with none, the config is exactly `FloorDetectionConfig()`.
  - *Replay.* Field-replay JSON files take `elevations` and `thresholds` (FIELD_TEST_PROTOCOL.md §5); floor heights also come from the database JSON. FIELD_TEST_PROTOCOL.md §7 is the home-testing checklist.
