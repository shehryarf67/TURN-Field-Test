# C block field test protocol (phase 4)

A short guide to collecting floor-tracking and routing data on C block, and to replaying it on a computer. The goal is
to measure the phase 2 and 3 work on real stairs and lifts (MULTI_FLOOR_NAVIGATION.md §2.7) before any threshold is
tuned.

## 1. Before you go (once per building)

1. **Link floors.** In *Tools → Link floors*, every stair and lift you will use should have a link between the floors it
   serves. A lift that serves several floors needs one link per pair of floors. Set the travel time and mark lifts and
   ramps as step-free.
2. **Check links on site.** Stand at each end and confirm the two points really connect, then tap *Mark checked on site*.
   Routes and tracking only use checked links unless *Use unchecked floor links* is on.
3. **Link radius.** About 2 m for stairs, and the size of the car or lobby for lifts. Too small and a real climb is
   rejected; too large and walking past the stairs can start a false change.
4. **Paths and places.** Each floor you will walk needs saved paths, and the destination places need to be connected to
   them, or routes will say why they cannot be found.
5. **Map options.** *Multi-floor tracking* on. *Step-free routes* off, except for the step-free course.
6. **Phones.** Check *Diagnostics* for a barometer. Bring one phone with a barometer and, if you can, one without. Charge
   both and turn on Wi-Fi.

## 2. Recording a course

Do this for every course, one recording per course:

1. Open the start floor and stand at a spot you can find on the map. Face along a corridor, so you know which way the
   phone points on the map.
2. *Diagnostics → Raw sensor recording → Start recording*.
3. Tap **Arrived on… <start floor>** right away. The replay needs this first marker.
4. Start navigation (*Explore → Start navigation*) from your known position, pick the destination and follow the route.
5. Tap the markers **as it happens**:

   | When | Tap |
   |---|---|
   | First foot on the stairs | *Start stairs up* / *Start stairs down* |
   | Lift doors close | *Enter lift up* / *Enter lift down* |
   | Off the stairs onto the landing, or lift doors open on the new floor | *Arrived on… <that floor>* |
   | Standing and waiting (at a lift, at a checkpoint) | *Standing still* (optional) |

   Tap *Arrived on…* once for each floor you actually get off at. A lift ride with no stop in between needs one
   *Enter lift* and one *Arrived on…*.
6. At the destination, write down whether the route got you there (see §5) and tap *End navigation*.
7. *Stop recording*, then **Save recording… right away.** The next *Start recording* overwrites the last one.
   Name the file after the course and phone, e.g. `stairs-gf-f4-pixel.csv`.

## 3. Courses

| Course | What it measures | Notes |
|---|---|---|
| **GF → F4 by stairs** | Four stair climbs in a row: latency, missed changes | Walk at a normal pace. Tap *Arrived on…* on each landing you pass (F1, F2, F3, F4) |
| **B1 → F3 by lift** | A lift ride of four floors with no stop in between | One *Enter lift up* and one *Arrived on… F3* |
| **Step-free B1 → F3** | Step-free routing | Turn on *Step-free routes*; the route must use the lift only |
| **Corridor, 20 minutes** | False changes | Walk up and down a corridor that passes the stairs at least 6 times, without using them. One *Arrived on…* at the start, nothing else |
| Return trips (optional) | Going down | F4 → GF by stairs, F3 → B1 by lift |

Run each course on both phones if you have them. A phone without a barometer has to rely on dwell cues and Wi-Fi, so
expect it to be slower to change floor.

## 4. After the walk: export

- **Recordings:** the CSV files from *Save recording…* (one per course).
- **Sessions:** *Export & backup → Save complete database JSON*. It holds the positioning sessions and the floor change
  log (`floorTransitionEvents`, export schema 4 and later). *Analysis CSV files → Floor change log* gives the same events as CSV.

Copy them to the computer.

## 5. Replay on the computer

Put the files in `app/src/test/resources/field/`. For each recording, add a JSON file next to it with the same name:

```
app/src/test/resources/field/
  c-block.json                 the complete database JSON (shared by all the walks)
  stairs-gf-f4-pixel.csv       a recording
  stairs-gf-f4-pixel.json      what the replay needs to know about it
  lift-b1-f3-pixel.csv
  lift-b1-f3-pixel.json
```

`stairs-gf-f4-pixel.json`:

```json
{
  "database": "c-block.json",
  "startFloorId": "<the id in the first ARRIVED row of the CSV>",
  "startX": 12.0,
  "startY": 5.0,
  "startHeadingRadians": 0.0
}
```

- `startX`, `startY`: your start in that floor's map metres (the *Live locate* start, also the session's first estimate
  in the database JSON).
- `startHeadingRadians`: the way the phone faced on the map when recording started: `0` = right, `1.5708` = up,
  `3.1416` = left, `-1.5708` = down. Turns after that come from the phone's gyro.
- The floor id is the text after `ARRIVED` in the CSV's first marker row.

Optional keys:

| Key | Default | Meaning |
|---|---|---|
| `trace` | the JSON's own name with `.csv` | Which recording to use. Two JSON files can share one recording |
| `dropBarometer` | `false` | Replay without the pressure rows, to compare a phone without a barometer on the same walk |
| `useUncheckedLinks` | `false` | Also use database links not checked on site, like the app option |
| `strideMetres` | `0.7` | Step length |
| `startStdMetres` | `1.0` | How sure you are of the start position |
| `seeds` | `[11, 23, 37]` | One replay per seed. The particle filter is random, so look at all of them |
| `wifiAnchors` | none | `[{"bssid": "aa:bb:…", "floorId": "…", "x": 0, "y": 0}]`. A strong access point stands for "here, on this floor" (BSSIDs are under *Diagnostics → Visible Wi-Fi access points*). Without anchors, Wi-Fi is ignored |
| `floors`, `links` | none | Write floors (`{"id", "level", "walkable": [[[x, y], …]]}`) and links by hand instead of using `database`, or add to it |
| `elevations` | from `database` | Floor heights in metres, `{"<floorId>": 2.7, …}`, on top of the database's floor heights. Used only when every linked floor has one |
| `thresholds` | none | Threshold overrides as the Diagnostics editor stores them, e.g. `{"vertical.floorHeightMetres": 2.8}`; copy them from the session's CONFIG event to replay with the same values |

Run the replay from the repository root:

```
./gradlew.bat testDebugUnitTest --tests "com.turn.fieldtest.core.replay.FieldReplayTest"
```

The report is written to `app/build/reports/field-replay/report.txt`, with one line per recording and seed. With no
recordings in the folder, the test is skipped. The folder is not ignored by git: commit recordings only if you want
them in the repository.

**Limits of the replay.** It reproduces floor tracking (barometer, dwell cues, links, floor decision), not the full
live position: heading comes from the gyro alone, the phone is assumed upright, and Wi-Fi is used only through
`wifiAnchors`, not the fingerprint database. Use the live session and its export for horizontal accuracy.

## 6. What to look at

| Metric | Where | Target |
|---|---|---|
| Floor accuracy | Report `accuracy`: share of time on the right floor, not counting time on the stairs or in the lift | ≥ 0.95 |
| Transition latency | Report `latencies s`: from *Arrived on…* to the app showing that floor | ≤ 6 s each (with a barometer) |
| False changes | Report `false changes (/h)`, and the corridor course in particular | 0 |
| Reverts and missed changes | Report `reverts`, `missed` | 0 |
| Route success | Your notes: did the route reach the destination, did the banner name the right link, did the next floor's leg take over after the ride | Every course |
| Barometer versus none | The same course on both phones, or `dropBarometer` on one recording | Without a barometer: the right floor within about 3 fresh Wi-Fi scans after arrival |

When a result misses a target, the database JSON's floor change log shows why. A VERTICAL_EVENT with no APPLIED
means the climb happened away from a link (check the link position and radius). REJECTED followed by PENDING_EXPIRED
means the height was never explained. WIFI_CROSS_FLOOR_VETOED means Wi-Fi pointed at another floor while the barometer
was flat.

## 7. Home testing (two-storey house)

A house is a good first test: storeys are low (about 2.6-2.8 m), each floor is mapped in its own Walk Mapper session,
and furniture hides many corners. Set it up in this order.

1. **Map each floor with the Walk Mapper** (WALK_MAPPER.md). Where a wardrobe, sofa or bed hides a corner, either aim
   at the wall or ceiling straight above the corner (it is projected down to the floor; corner tools only, wall and door endpoints keep the 2.5 m limit), or use **Hidden corner (two
   walls)**: tap two or more visible points along each of the two walls, then *Place corner*. Both kinds are flagged in
   the quality report; check them with a tape. Save each floor into the same building (level 0 and level 1).
2. **Set the storey height.** *Tools → Link floors → Floor heights*: measure floor to floor (e.g. count the steps and
   multiply by the riser height, or tape the stairwell), type it into *Storey height*, tap *Fill all floors* and *Save
   heights*. Stairs and lift rides are then judged against your real storey, so a 2.7 m climb is one floor (the standard
   3.5 m storey reads it as 0.77 of a floor and a barometer that under-reads can miss it). The live status line ends with
   "floor heights set" when a session uses them. *Clear heights* goes back to the standard storey.
3. **Align the floors at the stairs.** *Link floors → Align two floors*: on the ground floor tap two spots you can also
   find upstairs (the two corners of the stairwell opening, or the bottom and top newel posts in plan), then the same two
   spots, in the same order, on the upper floor. The screen shows the turn and shift between the two maps, the residual
   (aim for under 0.2 m) and a warning if the two distances differ by more than 5 % (one floor's scale is off, or the
   picks are not the same spots). *Save alignment*.
4. **Link the stairs.** *New link*, tap the foot of the stairs on the ground floor, then use *Place To directly
   above/below From* and move the point to the top landing if needed. Each link card then says how far apart its ends
   are in plan: under 1 m for a lift, a few metres for a straight flight. Mark the link checked on site.
5. **Thresholds (optional).** *Diagnostics → Floor detection thresholds* shows every floor-detection value with its
   default and allowed range. A change is used from the next *Live locate* start (a running session keeps its values) and
   is written into that session's CONFIG floor event, so a recording always says which thresholds it ran with. *Reset to
   defaults* removes every override. Change one value at a time and replay the same recording with `thresholds` to
   compare.
6. **Courses.** Record stairs up, stand 10 s, stairs down, and a 5 minute walk around the ground floor past the foot of
   the stairs (false changes), with the markers as in §2. Targets are those of §6.
