# Phase 2 implementation plan: tracking follows real floor changes

## 0. How this plan was assembled

**Base design:** *safety*, which both judges scored highest (33 and 32). Its main rules stay as they are:
- **Legacy gate.** If the start floor has no usable link, the session runs today's code.
- **Link-only transitions.** Particles change floor only through a saved link.
- **Reachability-checked portal capture.** A particle counts as inside a link area only if it can walk to the endpoint without leaving walkable space or crossing a wall.
- **Gated Wi-Fi.** Wi-Fi cannot move the cloud to another floor unchecked.
- **One additive table.**
- **A golden test written before any change.**

**Grafts that fix the flaws the judges found in the base:**

| Flaw in *safety* | Graft (source) | Where |
|---|---|---|
| The barometer event fires after the pressure settles (about 7 s). Particles have walked out of the link area, so real climbs end REJECTED. | Per-particle **portal memory** plus **stride damping on stairs** (*evidence*) | Step 4 |
| The barometer is re-anchored after a REJECTED event, so the height evidence is lost and the session gets stuck | A REJECTED event keeps an **unexplained height** and goes UNCERTAIN. Wi-Fi that agrees with the barometer can then resolve it (inverts *evidence*'s barometer-disagreement veto) | Step 5 |
| Wrong first floor from Wi-Fi plus a flat barometer means Wi-Fi is vetoed forever | An **initial probation** after a Wi-Fi start. Repeated vetoed matches set UNCERTAIN, which shows the Relocalize prompt | Step 5 |
| After FILTER_LOST, the next Wi-Fi init can rebuild the cloud on any floor with no check | A concrete init gate, plus a new `DEFERRED_CROSS_FLOOR` result instead of FILTER_LOST | Steps 4, 7 |
| The 15 % of weight left on the old floor is wiped out by ordinary resampling | **Floor-stratified, posterior-preserving resampling** in multi-floor sessions only (*evidence*) | Step 4 |
| The checkpoint truth floor comes from the displayed floor, so floor accuracy confirms itself | An **explicit checkpoint floor** chosen by the researcher (*product*) | Step 9 |
| `followLiveFloor` duplicates the field list in `loadProject` | Extract **`applyFloorDisplay`** from `loadProject` (*product*) | Step 8 |
| Unverified links are silently ignored | Status text names the ignored links. When the user allows unchecked links, they move half as many hypotheses (*evidence*/*product*) | Steps 2, 9 |
| No route from synthetic traces to future field recordings | **RawSensorTrace reader**, round-tripped through the real `RawSensorRecorder`, a replay harness and a `FloorTrackEvaluator` (*product*) | Step 6 |
| Live destination name lost when the displayed floor changes | `LiveDestination` reference (*product*) | Step 8 |
| NavigationMap stops following the dot after a floor swap | Add `activeProject` to the keys of the follow effect (*product*) | Step 9 |

**Ideas rejected, and why:**
- *evidence*'s base fork prior (0.05 on every stair-landing entry). It breaks the rule in doc §2.2: "Without any vertical evidence, a particle stays on its floor."
- *evidence*'s factorised Wi-Fi likelihood in every mode. It is not bit-identical on one floor.
- *evidence*/*product*'s continuous barometer likelihood. It depends on absolute elevations (level × 3.5 m), which a wrong anchor or a mezzanine breaks.
- *evidence*'s always-on accelerometer registration.
- *product*'s extraction of `LiveTrackingPipeline`, its in-session FloorSwitcher, its Diagnostics threshold editor and its `crossFloorLikelihood` of 0.05.
- *product*'s change to `evaluateAtCheckpoint` (null horizontal error on a wrong floor). FieldEvaluationTest.kt:12-15 pins `5.0`.
- Changing the WIFI_ONLY/RAW_PDR `floorConfidence` values. That would change single-floor research data.

Every step below lands as its own commit. Line references were checked against HEAD `16d5d54`.

---

## 1. Invariants (they define "done")

**I1. Single-floor parity.** A session is MULTI_FLOOR only if all of these hold:
- `floorTrackingEnabled` is on;
- the start floor has at least one *usable* link (enabled, verified unless unchecked links are allowed, both floors loadable, endpoints walkable, `ConnectorRules.problems` clean).

Otherwise the session is SINGLE_FLOOR and runs today's code exactly:
- `sessionMap = saved.metricMap()` (ProjectRepository.kt:69-76).
- The same magnetic map (TurnRuntimeViewModel.kt:1165-1166).
- The same fingerprint filter (:1913).
- `ParticleFilter` built without a topology. Every new filter branch returns before touching `random` (ParticleFilter.kt:178, systematicResample :617, gaussian :629).
- No coordinator, no new rows, no new UI.

**I2. Floors change only through links.** Particles change floor only through `applyVerticalEvidence` or `seedFloorHypotheses` on a captured portal. The only exceptions are explicit *absolute fixes*, and each one is persisted as an event:
- manual start;
- QR;
- user Relocalize, then Wi-Fi init;
- Wi-Fi init at session start;
- gated cross-floor Wi-Fi relocalisation (Step 5 policy).

`injectAroundWifi` never puts particles on another floor in multi-floor sessions. `requestFloorTransition` (:404-442) is legacy and is not called by the live session.

**I3. The display follows only the confirmed floor** (the FloorDecision output), never the instantaneous argmax.

**I4. Data compatibility.** Room changes are additive (schema 7, one table). Export schema 4 still imports 1-3. FUSED, WIFI_ONLY and RAW_PDR rows keep today's semantics.

---

## 2. Implementation steps

### Step 1: Characterisation (golden) tests on today's code

**Files (new):**
- `app/src/test/java/com/turn/fieldtest/core/ParticleFilterGoldenTest.kt`
- `app/src/test/resources/golden/particle_filter_v1.json`

**What it does:**
- Fixed seeds, on a 30×20 m single-floor map with 3 walls, 6 fingerprints and a 0.6 m magnetic map.
- Scenarios:
  - (a) `initialize`, then 40 `predictStep`s that hit walls (exercises MAP_CONSTRAINT and the replenish at :246);
  - (b) fresh Wi-Fi correction with injection (:309, :564-585);
  - (c) magnetic correction plus ESS resample (:385);
  - (d) `forceResample` (:451);
  - (e) strong distant Wi-Fi giving GLOBAL_RELOCALIZATION (:292-295);
  - (f) weight collapse giving relocalisation or FILTER_LOST (:323-336).
- Asserts the exact particle lists, comparing `doubleToLongBits` of x, y, weight and previousX/Y plus floorId, and the summaries, against the snapshot.
- The snapshot is written by running once with `-Dgolden.update=true` at `16d5d54`.
- After Step 4 the same test runs with `ParticleFilter(map, cfg, seed)` **and** `ParticleFilter(map, cfg, seed, FloorTopology.NONE)`.

**Done when:** the test is green on HEAD and committed before any production change.

---

### Step 2: FloorTopology (pure core)

**File (new):** `app/src/main/java/com/turn/fieldtest/core/FloorTopology.kt`

```kotlin
data class FloorLinkInput(val id: String, val kind: ConnectorKind, val fromFloorId: String, val from: MetricPoint,
  val toFloorId: String, val to: MetricPoint, val radiusMetres: Double, val bidirectional: Boolean,
  val enabled: Boolean, val verifiedOnSite: Boolean, val travelTimeSeconds: Double, val label: String)
data class FloorPortal(val index: Int, val linkId: String, val kind: ConnectorKind, val fromFloorId: String,
  val fromCentre: MetricPoint, val toFloorId: String, val toCentre: MetricPoint, val radiusMetres: Double,
  val levelDelta: Int, val travelTimeSeconds: Double, val verifiedOnSite: Boolean, val label: String)
class FloorTopology(val levels: Map<String, Int>, val portals: List<FloorPortal>) {
  val isEmpty: Boolean
  fun portalsFrom(floorId: String): List<FloorPortal>
  fun adjacent(a: String, b: String): Boolean
  /** Within radius+slack of fromCentre AND floor.isPathInsideWalkableSpace(fromCentre,p) && !floor.crossesWall(fromCentre,p). Sorted by distance, then linkId. */
  fun capture(floor: MapFloor, point: MetricPoint, slackMetres: Double): List<FloorPortal>
  companion object { val NONE: FloorTopology }
}
data class TopologyBuild(val topology: FloorTopology, val sessionFloorIds: Set<String>, val excluded: List<ExcludedLink>)
data class ExcludedLink(val linkId: String, val label: String, val reason: ExclusionReason) // DISABLED, UNVERIFIED, FLOOR_MISSING, ENDPOINT_NOT_WALKABLE, RULES
object FloorTopologyBuilder {
  fun build(startFloorId: String, floors: Map<String, MapFloor>, levels: Map<String, Int>,
            links: List<FloorLinkInput>, useUnverifiedLinks: Boolean): TopologyBuild
}
```

**Rules:**
- A bidirectional link gives 2 directed portals; a one-way link gives 1 (from → to). This deliberately does not reuse `VerticalTransition.supports` (Geometry.kt:300), which is always two-way. Core `VerticalTransition` and `MetricMap.transitionFor` are untouched, so ParticleFilterTest.kt:119-143 stays green.
- Links are rechecked with `ConnectorRules.problems` (FloorConnectors.kt:40-49), using the session's MapFloors.
- `levelDelta = levels[to] - levels[from]`.
- The session floor set is an undirected BFS from the start floor over the usable portals. If that set is `{start}`, the result is `FloorTopology.NONE`.
- `capture` uses MapFloor.isPathInsideWalkableSpace and crossesWall (Geometry.kt:255, :246). This closes the critic's "accepted through a wall" risk for 5 m circles.

**Tests (new):** `app/src/test/.../core/FloorTopologyTest.kt`
- 2 or 1 portals for bidirectional and one-way links.
- Each exclusion reason is reported.
- Unverified links: excluded with the flag off, included with it on.
- BFS gives the floor set, and an unlinked floor stays out.
- An unusable start floor gives NONE.
- `capture` is deterministic and refuses a point behind a wall inside the radius.
- Fixture: **asymmetric frames**, with stairs at (2,2) on F1 and (15,8) on F2. This replaces the hidden-frame-bug fixture at ParticleFilterTest.kt:306-317.

---

### Step 3: Barometer and vertical-motion cues (pure core)

**Files (new):**
- `core/FloorDetectionConfig.kt`: one `@Serializable` root `FloorDetectionConfig(barometer, vertical, cues, transition, wifi, decision)`. Every sub-config has `require` range checks in the style of ParticleFilterConfig :56-76. Defaults are in §3.
- `core/Barometer.kt`:

```kotlin
enum class BarometerHealth { UNAVAILABLE, STALE, OK }
data class BarometerState(val timestampNanos: Long, val smoothedHpa: Double, val heightSinceAnchorMetres: Double,
  val verticalRateMps: Double, val health: BarometerHealth, val discontinuity: Boolean)
class BarometerTracker(config: BarometerConfig, sensorPresent: Boolean) {
  fun add(timestampNanos: Long, hpa: Double): BarometerState?   // null = rejected sample
  fun anchor(timestampNanos: Long)
  fun health(nowNanos: Long): BarometerHealth
  val latestHpa: Double?; val heightSinceAnchorMetres: Double
}
```

How `BarometerTracker` processes samples:
- It works on `sensorTimestampNanos` only, never on arrival order. Samples up to 50 ms out of order are re-sorted; older ones are dropped.
- It drops values that are not finite or outside 300-1100 hPa.
- Smoothing is a 5-sample median, then an EMA with `alpha = 1 - exp(-dt/tau)`.
- A gap longer than 5 s re-seeds the smoothing and keeps the anchor.
- **Step rejection** (from *evidence*) covers door, HVAC and stairwell pressurisation steps. Every window is in seconds, so it behaves the same at any sample rate (SENSOR_DELAY_NORMAL is only a hint; phones deliver 5-25 Hz).
  - *Candidate:* the median moves more than 0.15 hPa from the smoothed pressure 1 s earlier while the 4 s rate was flat (≤ 0.12 m/s), and half of that change happens within 0.2 s. The jump is located on the raw samples (the sample boundary where the raw means over 0.2 s either side differ most), and the step is absorbed into the offset at once, sized from raw means either side of the jump; a raw size under 0.075 hPa is no step. This is only tentative. While a step is pending, only a step back becomes a candidate (it needs to undo only half of the pending step), or, once the pressure has stepped back, a step on in the same direction (a second door pulse). The pending steps form one cluster.
  - *Judgement,* 2 s after the newest edge of the cluster, on raw samples that never reach back across the last confirmed step. An edge is a level shift when it is sharp (the raw means over 0.2 s either side of the jump differ by at least half its size) and a line with one common slope plus a shift at the jump, fitted within 1 s either side (never across a neighbouring edge), has a shift of at least 0.16 hPa; a smaller shift, down to half the step, counts only if the pressure is flat around it (no trend above 0.5 m/s before it, nor after it in its direction). A lift ride or fast climb is continuous, so its fitted shift is near zero however it bends; a door step keeps its full size even if a ride starts right after it. Every edge before the last must be a level shift between its neighbours: door pulses are jumps between flat plateaus, however short, while a ride that a noisy median splits into edges is a ramp between them, so the cluster ends before the first edge that is not a jump and the ride is handed back whole. An edge within 0.4 s of the one before that is not a jump is the same jump seen again, and is merged into it. If the last edge stepped back sharply without that step back ever becoming a candidate (a short or small door pulse can hide it from the median), and the pressure then stays flat within half the step of the level before the cluster, the step back joins the cluster as a hidden edge. Then the last edge must be a level shift in its own direction, or it is dropped and the rest judged again.
  - *Outcome:* the kept edges are re-sized to the levels between them (the mean of each plateau; the level before is the mean in the 2 s before the first edge, the level after is the mean since the last edge when the pressure held level, otherwise a line over the 1 s after it taken at the jump, so a ride that follows is not counted). They add up to the net change, so one or several door pulses, or an overpressure then an underpressure, add nothing to the height. A confirmed cluster is flagged as a `discontinuity`. Dropped steps are handed back to the height retroactively (smoothed pressure and rates are recomputed as if they had never been absorbed), so a whole lift ride is measured, including after a step in the opposite direction.
- Height = −ΔP / (P/8434), so hPa per metre ≈ 0.112 at 945 hPa.
- Leaky drift compensation (τ 300 s) runs only after 20 s with |rate| ≤ 0.03 m/s **and** no pending unexplained height.

- `core/VerticalMotion.kt`:

```kotlin
enum class VerticalDirection { UP, DOWN, UNKNOWN }
enum class VerticalKindHint { STAIRS, LIFT, ANY }
data class VerticalEvent(val direction: VerticalDirection, val floorCandidates: Set<Int>, val kind: VerticalKindHint,
  val heightDeltaMetres: Double, val startNanos: Long, val endNanos: Long)
class VerticalMotionDetector(config: VerticalMotionConfig) {   // IDLE -> MOVING -> SETTLING -> IDLE
  fun onBarometer(state: BarometerState, stepsInWindow: Int): VerticalEvent?
  val moving: Boolean; val movingKind: VerticalKindHint?; fun reset()
}
data class DwellCue(val kind: VerticalKindHint, val portalIndices: Set<Int>, val atNanos: Long)
class MotionContextTracker(config: CueConfig) {
  fun onStep(tsNanos: Long, portalWeight: Map<Int, Double>): DwellCue?   // stairs dwell
  fun onTick(nowNanos: Long, portalWeight: Map<Int, Double>): Unit        // lift stillness accumulates
  fun reset()
}
```

How the detector decides:
- It enters MOVING when |rate over 4 s| ≥ 0.12 m/s.
- It moves to SETTLING when |rate| ≤ 0.05 m/s, and emits on settle once that has held for 3 s.
- It emits only if |Δh| ≥ 0.6 × floorHeight.
- The floor count is n = round(|Δh|/h). If the residual is above 0.35 of a floor, the event is ambiguous with candidates {⌊x⌋, ⌈x⌉} (excluding 0).
- Kind: LIFT if the peak rate is ≥ 0.6 m/s with ≤ 2 steps, STAIRS if steps continued, otherwise ANY.
- A segment longer than 180 s, or one that returns within 0.5 m of its start, emits nothing.

The no-barometer cues:
- **STAIRS dwell cue:** ≥ 6 consecutive accepted steps **and** ≥ max(5 s, 0.4 × travelTimeSeconds) with ≥ 0.5 of the filter weight captured in a STAIRS, ESCALATOR or RAMP portal (current or in memory, see Step 4). Walking straight through a link area emits nothing.
- **LIFT dwell cue:** ≥ 8 s with ≤ 1 step and ≥ 0.5 weight in a LIFT portal. It is emitted on the **first step afterwards**, when the user walks out.
- A 20 s cooldown follows each cue.
- No sensor registration changes in phase 2.

**Tests (new),** using synthetic traces from Step 6's `SyntheticTraces` (5 Hz, σ 0.03 hPa, seeded):

`BarometerTrackerTest`:
- rejects NaN, out-of-range and >50 ms out-of-order samples;
- a gap resets the smoothing;
- a 0.39 hPa ramp at 945 hPa gives 3.5 ± 0.3 m;
- `anchor()` zeroes the height;
- a 0.3 hPa door step becomes a discontinuity, not height;
- a fast lift ride is height, not a discontinuity;
- 10 min at 1.5 hPa/h gives |h| < 0.6 m;
- STALE after 3 s; UNAVAILABLE when the sensor is absent.

`VerticalMotionDetectorTest`:
- one floor of stairs up gives exactly one UP {1} STAIRS event, within settle + 1 s;
- two floors gives {2};
- a lift down 2 floors with no steps gives LIFT;
- **no event** for: a door pulse, a persistent 0.15 hPa HVAC step, a 1.75 m mezzanine, 30 min of noise plus drift, or a ramp that reverses halfway;
- no event, and no floor-sized height, for ±0.2/±0.3 hPa door pulses of 0.4-1.2 s at 5, 8, 10, 15 and 25 Hz;
- lifts of ±1/±2 floors at 1.0/1.6/2.5 m/s are never absorbed as a door step, at 5, 10 and 25 Hz;
- a ±0.3 hPa step 0.5-3 s before a 2-floor lift ride in the same direction gives exactly {2} floors, at 5, 10 and 25 Hz;
- two ±0.3 hPa door pulses back to back, of the same sign or opposite, give no event at 5, 8 and 10 Hz;
- a ±0.3 hPa step 0.3-1 s before a fast (2.5 m/s) 1- or 2-floor ride in the opposite direction keeps the whole ride, at 5, 8 and 10 Hz;
- a 1.5-floor change gives {1, 2}.

`MotionContextTrackerTest`:
- 6 steps over 5 s in a stairs portal give one cue, then the cooldown holds;
- walking through in 3 s gives none;
- an 8 s lift dwell followed by a step gives LIFT;
- steps outside portals give nothing.

---

### Step 4: ParticleFilter additions, all inert without a topology

**File:** `core/ParticleFilter.kt`

1. **Constructor (:173-177).** Add a last parameter `floorTopology: FloorTopology = FloorTopology.NONE`.
   - Add `private val multiFloor = !floorTopology.isEmpty`.
   - When `multiFloor`, init requires every portal's floors to be in `metricMap.floors`. This prevents the `getValue` crash at :476.
   - Callers at TurnRuntimeViewModel.kt:1826 and :2102, and all tests, compile unchanged.

2. **Particle (:12-32).** Add defaulted portal memory: `val portalIndex: Int = -1, val stepsSincePortal: Int = Int.MAX_VALUE`.
   - `copy()` carries it through resampling.
   - It is written only when `multiFloor`.

3. **predictStep (:198).** Becomes `predictStep(strideMetres, measuredHeadingRadians, stairsActive: Boolean = false)`. When `multiFloor`:
   - If `stairsActive && p.stepsSincePortal <= portalMemorySteps`, the stride is multiplied by `stairsStrideFactor` (0.5). The random draws are unchanged, only `d` is scaled.
   - After an ALLOWED move, `capture(...)` updates `portalIndex` and `stepsSincePortal` (0 if captured, otherwise +1, saturating).
   - The legacy path keeps the same statements.

4. **New public API:**

```kotlin
data class VerticalEvidence(val direction: VerticalDirection, val floorCandidates: Set<Int>, val kind: VerticalKindHint,
  val share: Double, val outOfPortalLikelihood: Double, val unverifiedFactor: Double)
fun applyVerticalEvidence(evidence: VerticalEvidence): FloorTransitionResult
fun seedFloorHypotheses(kind: VerticalKindHint, portalIndices: Set<Int>, share: Double, unverifiedFactor: Double): FloorTransitionResult
fun portalWeights(): Map<Int, Double>          // weight per portal, current capture or memory
fun summary(floorId: String): ParticleSummary? // not cached; null when floor has no weight
fun floorVotes(): Map<String, Double>
```

Both transition functions return `FloorTransitionResult(false, 0, summary())` immediately when `!multiFloor`. They share `private fun transitionThroughPortals(...)`, which works like this:
- **Eligible particle:** captured now (`capture` on its floor), or `stepsSincePortal <= portalMemorySteps` with a remembered portal from its floor.
- **Portal filter:** direction must match `sign(levelDelta)` (UNKNOWN matches both), `|levelDelta|` must be in the candidates, and the kind must be compatible (STAIRS hint means STAIRS, ESCALATOR or RAMP; LIFT means LIFT; ANY means all). One-way links already have no reverse portal.
- **Split:**
  - The stayer keeps `w·(1−s)`.
  - Each mover gets `w·s/k` across its k compatible targets, where `s = share × (portal.verifiedOnSite ? 1 : unverifiedFactor)`.
  - The mover's exit is sampled from N(toCentre, min(r/2, 1 m)), accepted only if walkable and reachable from toCentre (`isPathInsideWalkableSpace && !crossesWall`), with at most 20 tries and toCentre as the fallback. toCentre is walkable because ConnectorRules guarantees it, unlike the centroid at :417.
  - The mover gets `previousX/Y = exit`, `portalIndex = the reverse portal or -1`, and `stepsSincePortal = 0`.
- **Uncaptured particles:** weight × `outOfPortalLikelihood` (1.0 for seeding).
- **Chained floors:** if no direct portal matches n floors, the movers run up to n (≤ 4) one-hop passes from their exits. Movers that cannot continue stay on the intermediate floor with their weight.
- **Nothing eligible:** a FLOOR_TRANSITION_REJECTED event is added, `mutableParticles` is untouched (cache intact), and it returns `accepted=false`.
- **Otherwise:** `resample(normalize(all), particleCount)` and a FLOOR_TRANSITION event whose detail lists the link ids and the moved share.

5. **Resampling.** Add `private fun resample(input, count) = if (!multiFloor) systematicResample(input, count) else floorStratifiedResample(input, count)` and use it at :246, :341, :385 and :451. `requestFloorTransition` (:435) stays on `systematicResample`. The stratified version:
   - computes floor weights W_f;
   - drops floors with W_f below `floorPruneWeight` (1e-3), always keeping the argmax;
   - sets n_f = max(10, largest remainder of W_f·N), taking the excess from the largest floor;
   - runs systematic resampling within each floor, iterating floors in sorted id order;
   - gives each output weight W_f/n_f, so resampling never changes the floor posterior.

6. **correctWithWifi (:268).** Add `crossFloorRelocalizationAllowed: Boolean = true`, read only when `multiFloor`:
   - **Relocalisation (:292-295):** if `current.floorId != wifiFloor && !allowed`, do not relocalise; fall through to reweighting.
   - **Injection (:309):** `injectAroundWifi` runs only if `wifiFloor == current?.floorId`.
   - **Likelihood (:310-318):** a neighbour on another floor contributes `neighbour.weight * crossFloorLikelihood`, with **no spatial term**, because the floors' frames are not shared.
   - **Collapse (:323-336):** if the cross-floor case is not allowed, restore the pre-correction particles and return the new `WifiCorrectionKind.DEFERRED_CROSS_FLOOR` (enum at :130-136), with event `CROSS_FLOOR_WIFI_DEFERRED` added to FilterEventType (:92-103).
   - The legacy path keeps the same statements in the same order (proved by the golden test).

7. **summary() (:457-515).** Move the body verbatim into `private fun summarize(normalized, floorKey, vote)`. `summary()` still computes the argmax with the same tie-break, `thenBy { it.key }` (:463-465), and caches it.

8. **Unchanged:** `correctWithMagnetic` (already per floor, :366; previousX/Y reset on exit keeps frames separate), `correctWithQr`, `initialize`, and `requestFloorTransition`, which gets a KDoc line: "Legacy; not used by live sessions."

**ParticleFilterConfig additions (:34-77),** used only when `multiFloor`:
- `portalSlackMetres = 1.0`
- `portalMemorySteps = 10`
- `stairsStrideFactor = 0.5`
- `exitSpreadMaxMetres = 1.0`
- `exitSampleTries = 20`
- `maxHops = 4`
- `minParticlesPerFloor = 10`
- `floorPruneWeight = 1e-3`

Each gets a `require`.

**Tests:**
- `ParticleFilterGoldenTest`: passes with both constructors.
- Existing `ParticleFilterTest` (:119-143), `WifiCorrectionScenarioTest` and `WifiPositioningTest` pass unedited.
- New `ParticleFilterFloorTest` (asymmetric two- and three-floor fixtures):
  1. Only eligible particles move; about 15 % of the weight stays on F1; exits are walkable, within min(r/2, 1 m)·3 of (15,8), and not stacked; previousX/Y equals the exit; the next `predictStep` does not lose the filter.
  2. A particle 6 steps past the landing (memory) still moves; one 15 steps past does not.
  3. No eligible particle gives `accepted=false`, the list is identical, and the last event is REJECTED.
  4. A portal circle crossing a wall does not capture far-side particles.
  5. A one-way link refuses DOWN and accepts UP.
  6. 2-floor UP: through a direct LIFT portal; through two chained stair hops; with only one hop, movers stop on the intermediate floor.
  7. An unverified portal moves half the share.
  8. Stratified resampling keeps a 3 % floor alive through 50 forced resamples, preserves W_f to 1e-12, and prunes a floor below 1e-3.
  9. `stairsActive` halves the stride only for particles with memory.
  10. Multi-floor `correctWithWifi` with allowed=false: a strong other-floor match does not reinitialise, injects nothing on that floor, and collapse gives DEFERRED_CROSS_FLOOR with the particles unchanged. With allowed=true: GLOBAL_RELOCALIZATION.
  11. `summary(argmaxFloor) == summary()`; `summary(other)` uses only that floor; a weightless floor gives null.
  12. The constructor rejects a topology that references a missing floor.
  13. **Invariant:** 1000 random steps with Wi-Fi (allowed=false) and magnetic input but no vertical evidence: no particle ever changes floor.

---

### Step 5: FloorDecision and FloorTrackingCoordinator (pure core)

**Files (new):** `core/FloorDecision.kt` and `core/FloorTracking.kt`

```kotlin
enum class FloorState { CONFIRMED, CHANGING, UNCERTAIN }
data class FloorDecisionResult(val floorId: String, val vote: Double, val state: FloorState,
  val changedFrom: String?, val uncertainReason: String?)
class FloorDecision(topology: FloorTopology, config: DecisionConfig) {
  fun reset(floorId: String, nowNanos: Long)
  fun markChanging(untilNanos: Long); fun markUncertain(reason: String, untilNanos: Long)
  fun update(votes: Map<String, Double>, nowNanos: Long, lastEventFloors: Set<Int>?): FloorDecisionResult
}
```

**FloorDecision rules:**
- **Switch:** only if the candidate's vote is ≥ 0.75 for 3 consecutive updates **and** ≥ 2 s, and the candidate is adjacent through a portal or within the last applied event's floor count.
- **Ties:** never broken by id. Near 50/50 the decision holds the current floor.
- **Hard switch** to the argmax if the confirmed floor's vote is below 0.02.
- **UNCERTAIN** when the vote is below 0.6, or when marked.
- **CHANGING** while the detector is MOVING, and for 10 s after an APPLIED transition.

```kotlin
sealed interface FloorTrackingEvent  // persisted 1:1 (Step 10 types)
enum class CrossFloorWifi { ALLOW, DEFER, VETO }
class FloorTrackingCoordinator(val topology: FloorTopology, private val config: FloorDetectionConfig,
                               barometerPresent: Boolean, startFloorId: String) {
  val barometerHealth: BarometerHealth; val latestPressureHpa: Double?; val stairsActive: Boolean
  fun onPressure(tsNanos: Long, hpa: Double, filter: ParticleFilter?): FloorTransitionResult?
  fun onStep(tsNanos: Long, filter: ParticleFilter?): FloorTransitionResult?
  fun onTick(nowNanos: Long, filter: ParticleFilter?): FloorTransitionResult?   // ≤1 Hz: lift dwell, STALE, window expiry
  fun crossFloorWifi(match: WifiMatchResult, current: ParticleSummary?, nowNanos: Long): CrossFloorWifi
  fun allowWifiInit(match: WifiMatchResult, nowNanos: Long, userRequested: Boolean): Boolean
  fun onAbsoluteFix(floorId: String, source: String, nowNanos: Long)
  fun decide(filter: ParticleFilter, nowNanos: Long): FloorDecisionResult
  fun drainEvents(): List<FloorTrackingEvent>
}
```

All times use the elapsedRealtimeNanos base; sensor timestamps share it (SensorSource.kt:165). The filter is passed into each call because the ViewModel replaces it on relocalisation.

**Barometer event:**
- Calls `filter.applyVerticalEvidence(share = 0.85, outOfPortal = 0.1, unverifiedFactor = 0.5)`.
- **APPLIED:** `barometer.anchor()` and `decision.markChanging(+10 s)`.
- **REJECTED** (fix for the anchor flaw):
  - do **not** anchor;
  - keep `pendingHeight = event.Δh` and `pendingFloors = candidates` for `pendingWindowSeconds` (120 s);
  - `decision.markUncertain("height changed away from a floor link")`.
  - If the window expires unresolved, anchor and log `PENDING_EXPIRED`.

**Dwell cue:**
- With the barometer not OK, a dwell cue calls `filter.seedFloorHypotheses(kind, portals, share = 0.2 stairs / 0.3 lift)`.
- With the barometer OK there is no seeding; the barometer is authoritative.

**`stairsActive`:** true while the detector is MOVING and its kind is not LIFT.

**Cross-floor Wi-Fi policy** (`crossFloorWifi`). It counts consecutive fresh strong matches (confidence ≥ 0.68, `floorAgreement` ≥ 0.85) on the same other floor F\*. Any other result resets the count.

| Situation | Needed | Otherwise |
|---|---|---|
| Barometer OK and a pending height is consistent with F\* (\|pendingHeight − (level(F\*)−level(cur))·h\| ≤ 0.5·h) | 2 matches | DEFER |
| Barometer OK and the pending height is inconsistent | n/a | VETO |
| Barometer OK, flat, but in **initial probation** (≤ 120 s after a Wi-Fi-initialised start with no manual or QR fix since) | 3 | DEFER |
| Barometer OK, flat, outside probation | n/a | VETO |
| Barometer UNAVAILABLE or STALE | 3 | DEFER |

- After **4 consecutive VETOs** on the same floor: `markUncertain("Wi-Fi suggests another floor")`, which shows the Relocalize prompt (fix for the stuck-start flaw).
- An ALLOW that is then applied as GLOBAL_RELOCALIZATION counts as an absolute fix: anchor, `decision.reset(F*)`, and a `WIFI_CROSS_FLOOR_RELOCALIZATION` event.

**`allowWifiInit`** (fix for re-init after FILTER_LOST):
- Allowed if there is no confirmed floor yet (session start), or `userRequested`, or the match floor equals the last confirmed floor, or `crossFloorWifi(...) == ALLOW`.
- Otherwise the VM shows "Wi-Fi suggests Floor X · waiting for confirmation".

**Tests (new):** `FloorDecisionTest`
- 0.55/0.45 alternating never switches.
- 0.8 for 3 updates and 2 s switches once; 0.8 for 1.5 s does not.
- A non-adjacent floor does not switch without a matching event.
- A hard switch happens below 0.02.
- `reset` is immediate.

`FloorTrackingCoordinatorTest` (a real ParticleFilter and an asymmetric 3-floor map; scenarios in Step 6).

---

### Step 6: Trace reader, synthetic traces, replay harness, evaluator

**Files (new):**
- `app/src/main/java/com/turn/fieldtest/platform/sensors/RawSensorTrace.kt` (pure JVM). It parses exactly the CSV that `RawSensorRecorder` writes (header at RawSensorRecorder.kt:54) into `TraceRow.Sensor | WifiScan | Marker`. Rows with an unknown source are skipped and a wrong header is rejected. `TraceMarkers` grammar: `VERTICAL_START <STAIRS|LIFT|ESCALATOR|RAMP> <UP|DOWN>`, `ARRIVED <floorId>`, `STILL`. `truth(rows): List<FloorTruth>`.
- `core/FloorTrackEvaluator.kt`: `evaluate(truth: List<FloorTruth>, confirmed: List<ConfirmedFloor>, endNanos, transitGraceSeconds = 5.0): FloorTrackMetrics(accuracy, latenciesSeconds, falseChangesPerHour, reverts)`. A change reversed within 30 s counts as a revert.
- `app/src/test/.../core/replay/SyntheticTraces.kt`. A seeded builder that **writes through `RawSensorRecorder`**. Scenarios:
  - flat walk: steps at 1.8 Hz;
  - stairs up or down per floor: steps at 1.5 Hz, −0.39 hPa over the link travel time;
  - lift: no steps, 8 s per floor;
  - weather drift of 1-1.5 hPa/h;
  - door or HVAC steps of 0.1-0.4 hPa;
  - barometer dropped mid-trace;
  - wrong-floor Wi-Fi bursts;
  - markers for ground truth.
- `app/src/test/.../core/replay/FloorReplayHarness.kt`. It feeds rows in sensor-time order into `FloorTrackingCoordinator` and a `ParticleFilter`. Heading comes from the scenario script, and Wi-Fi rows go to a matcher over fixture fingerprints. It collects CONFIRMED decisions for the evaluator. Field recordings will use the same path in phase 4.

**Tests:**
- `RawSensorTraceTest`: round trip of samples, markers and Wi-Fi batches; header rejection. It extends the recorder test at FloorConnectorsTest.kt:91-110.
- `FloorTrackEvaluatorTest`: hand-built timelines.
- `MultiFloorReplayTest` (asymmetric frames; one stair link F1↔F2, one lift F1↔F3, F2↔F3 stairs). **As built it has four scenarios only, by the user's decision to save time, each run with a barometer over three seeds:** (1) walk to the stairs, climb one floor, walk on; (2) a two-floor lift ride; (3) near miss: walking past right beside the stairs on the same floor with door and HVAC pressure blips, no change; (4) strong Wi-Fi for another floor while the user stays put, vetoed, no change, then UNCERTAIN. Phase 4 added the no-barometer variants of (a) and (b). The original list below is kept for the rest:
  - (a) Walk, stairs up, walk off with a barometer: exactly one CONFIRMED F1→F2 within 6 s of ARRIVED and 0 false changes. Without a barometer, with F2 fingerprints: F2 within 3 fresh scans.
  - (b) A two-floor lift with a barometer gives F3. Without one, the dwell cue plus Wi-Fi resolve it.
  - (c) 20 min of corridor walking past the stairs 6 times, plus drift and HVAC steps: 0 confirmed changes and 0 reverts.
  - (d) A flat barometer plus 5 strong F2 Wi-Fi matches away from the stairs, outside probation: no change, `WIFI_CROSS_FLOOR_VETOED` recorded, then UNCERTAIN.
  - (e) A pressure ramp far from any portal gives REJECTED with no change. Then 2 strong Wi-Fi matches on the barometer-consistent floor relocalise (pending path).
  - (f) A Wi-Fi start on the wrong floor plus flat pressure: corrected within probation after 3 matches.
  - (g) The barometer goes STALE mid-session: degrades to motion cues without throwing.
  - (h) The same building with links disabled gives `FloorTopology.NONE`, and the run equals the single-floor run bit for bit.

The latency and false-change thresholds are loose, because the traces are synthetic.

---

### Steps 5 and 6 as built (API deviations)

- **Files.** `core/FloorDecision.kt`, `core/FloorTracking.kt`; test helpers `test/.../core/replay/SyntheticTraces.kt` (`SyntheticWalk`, `SyntheticTrace`) and `FloorReplayHarness.kt` (test sources, as planned); tests `FloorDecisionTest`, `replay/MultiFloorReplayTest`.
- **FloorDecision** takes the initial floor: `FloorDecision(topology, config, initialFloorId)`. `update(votes, nowNanos, lastEventFloors, moving = false)` gets the detector's MOVING flag instead of the coordinator marking CHANGING on every call. Added `clearUncertain()` and `val floorId`. Precedence: marked UNCERTAIN, then CHANGING (moving or within the hold), then UNCERTAIN below 0.6, then CONFIRMED. `markChanging` (an applied transition) clears an UNCERTAIN mark. The hard switch needs a unique argmax (ties are never broken by id).
- **Coordinator construction.** `FloorTrackingCoordinator(topology, config = FloorDetectionConfig(), barometerPresent, startFloorId, tracking = FloorTrackingConfig())` requires a non-empty topology; `FloorTrackingCoordinator.createOrNull(...)` returns null for `FloorTopology.NONE`, so the VM keeps the legacy path (I1). New tunables live in the separate `@Serializable FloorTrackingConfig` (Wi-Fi uncertainty hold 120 s, last-event floor memory 120 s, tick 1 s) so `FloorDetectionConfig` stays unchanged.
- **Events.** `FloorTrackingEvent` is one flat data class (`type: FloorTrackingEventType`, `atNanos`, from/to floor, `movedShare`, `floorVoteAfter`, `heightDeltaMetres`, `pressureHpa`, `barometerHealth`, `detail`) that maps 1:1 onto the Step 10 row, not a sealed interface. The CONFIG event (config JSON plus barometer presence) is emitted at construction.
- **Absolute fixes** take `FloorFixSource { MANUAL, QR, WIFI_INIT, USER_RELOCALIZE, WIFI_CROSS_FLOOR }` instead of a string. WIFI_INIT and USER_RELOCALIZE start the 120 s probation; MANUAL and QR end it.
- **Wi-Fi.** Added `correctWithWifi(filter, match, nowNanos, userRequested = false, allowGlobalRelocalization = true)`, which applies the policy: a lost filter is rebuilt only if `allowWifiInit` allows it (then with `crossFloorRelocalizationAllowed = true`), otherwise the flag is `crossFloorWifi(...) == ALLOW`. A cross-floor GLOBAL_RELOCALIZATION is recorded as an absolute fix (`onWifiCorrection(prior, result, nowNanos)` does this for callers that call the filter themselves). Same-floor, stale or floorless matches return ALLOW; weak other-floor matches return DEFER and reset the count.
- **`onTick` returns Unit.** It never moves particles; the lift cue is reported on the next step, as the cue tracker is designed.
- **`stairsActive` is true for any open vertical segment, lifts included** (the plan excluded lifts). A lift event settles about 10 s after the ride, while the user is already walking out of the car; without the damping, lift particles left portal memory before the event (found by scenario 2).
- **REJECTED** keeps the barometer's whole height since the anchor as the pending height (so several rejected segments add up), holds drift compensation, and clears the pending state if a later rejected segment brings the height back within 0.6 of a floor of the anchor.
- **Replay harness.** Heading and stride come from the caller (the scenario script); Wi-Fi rows go through a caller-supplied matcher (the tests use a fixture BSSID table, not the k-NN matcher). Magnetic correction is not replayed.

---

### Step 7: Session building and ViewModel wiring

**ProjectRepository.kt:118.** `suspend fun load(context: ProjectContext, persistRepairs: Boolean = true): SavedFloorMap`. With `false`, the code returns the same repaired in-memory copy but skips `saveDestinations` (:132) and `upsertReferencePoint` (:134). Existing callers are unchanged.

**New:** `data/repository/SessionBuilding.kt`

```kotlin
data class SessionBuilding(val start: ProjectContext, val floorMaps: Map<String, SavedFloorMap>, val metricMap: MetricMap,
  val topology: FloorTopology, val floorIds: Set<String>, val names: Map<String, String>, val levels: Map<String, Int>,
  val excluded: List<ExcludedLink>, val skippedFloors: List<Pair<String, String>>) { val multiFloor get() = !topology.isEmpty }
fun assembleSessionBuilding(start: SavedFloorMap, others: List<SavedFloorMap>, connectors: List<FloorConnector>,
  floorTrackingEnabled: Boolean, useUnverifiedLinks: Boolean): SessionBuilding   // pure, JVM-tested
class SessionBuildingLoader(private val projects: ProjectRepository, private val connectors: ConnectorRepository) {
  suspend fun load(context: ProjectContext, floorTrackingEnabled: Boolean, useUnverifiedLinks: Boolean): SessionBuilding
}
```

How the loader works:
- It loads the start floor with `projects.load(context)`, exactly as at :1161.
- If tracking is enabled, it reads `connectors(venueId)` (ConnectorRepository.kt:25-33) and loads only the floors those links reference, with `persistRepairs = false`. Each load is wrapped in `runCatching`, as `refreshFloorLinks` does at :466-480.
- Floors whose `metricMap()` throws (:71) are skipped with a reason.
- It then runs the topology builder.
- **Single-floor:** `metricMap = start.metricMap()`, the same object shape as today.
- **Multi-floor:** `MetricMap(floors = session floors' MapFloors)`. `verticalTransitions` stays empty; portals live only in FloorTopology.

**ViewModel changes in `TurnRuntimeViewModel.kt`:**

- **New fields next to :152-175:** `sessionBuilding`, `sessionContext: ProjectContext?` (frozen), `sessionGraphs: Map<String, NavigationGraph>`, `floorCoordinator: FloorTrackingCoordinator?`, `liveTrailsByFloor: MutableMap<String, FloorTrails>`, `floorEventSequence`, `userRelocalizePending`.
- **beginRealLive :1161-1166:**
  - `building = loader.load(context, appState.floorTrackingEnabled, appState.useUncheckedFloorLinks)`;
  - `sessionMap = building.metricMap`;
  - `sessionGraphs` = one NavigationGraph per session floor, built as at :1164;
  - `sessionGraph = sessionGraphs[context.floorId]`;
  - `sessionMagneticMaps` = each session floor's non-empty `magneticRepository.map(id)` (identical to :1165-1166 for one floor);
  - `sessionContext = context`;
  - `floorCoordinator = if (building.multiFloor) FloorTrackingCoordinator(building.topology, FloorDetectionConfig(), barometerPresent, context.floorId) else null`, where `barometerPresent = sensorSource.state.value.availability.any { it.type == BAROMETER && it.available }` (SensorSource.kt:56-62);
  - the CONFIG event (config JSON plus barometer presence) is persisted after the session row is saved (:1193-1196).
- **Filter construction at :1826 and :2102:** pass `floorTopology = building.topology` only when `floorCoordinator != null`.
- **loadPilotFingerprints :1909-1924:** use `sessionContext` instead of the `activeContext` getter (:129). Replace `it.floorId == context.floorId` with `it.floorId in sessionBuilding.floorIds`. The :1807 walkability check then works for every session floor.
- **Wi-Fi init at :1821-1839:** in multi-floor sessions, first `if (!coordinator.allowWifiInit(match, now, userRelocalizePending)) { status; return }`. After `initialize`, call `coordinator.onAbsoluteFix(match floor, "WIFI_INIT")` and set `userRelocalizePending = false`.
- **:1873:** `correctWithWifi(match, crossFloorRelocalizationAllowed = coordinator?.crossFloorWifi(match, prior, now)?.let { it == ALLOW } ?: true)`. Add a `DEFERRED_CROSS_FLOOR` branch to both `when`s (:1877-1889) with "Wi-Fi suggests another floor · waiting for more evidence". On GLOBAL_RELOCALIZATION, call `coordinator.onAbsoluteFix`.
- **handleSensorSample :2142:** a PRESSURE branch: `if (liveRunning) coordinator?.onPressure(ts, values[0], particleFilter)?.let { updateRealParticleUi() }`. Also at most one `onTick` per second on any sample. All of this stays under the existing eventMutex (:344-350); the barometer runs at about 5 Hz, SENSOR_DELAY_NORMAL (SensorSource.kt:142).
- **processLiveStep :2363:** `predictStep(stride, heading, stairsActive = coordinator?.stairsActive == true)`. After the magnetic correction (:2368), call `coordinator?.onStep(ts, filter)`. `PdrEventEntity.pressureHpa = coordinator?.latestPressureHpa` (:2338-2355, column Entities.kt:328, multi-floor only).
- **initializeManual :2100** and QR: call `coordinator?.onAbsoluteFix`.
- **relocalizeWithNextWifi :1111-1127:** set `userRelocalizePending = true`.
- **alignLiveHeading :1068:** `floorId = appState.realLiveFloorId ?: sessionContext.floorId`.
- **New `persistFloorEvents()`:** drains coordinator events after each coordinator call or Wi-Fi correction and inserts them through the new DAO (Step 10).
- **finishActiveLive :2030-2039:** after a successful save only (keeping the retry behaviour at :2025-2029), also clear `sessionBuilding`, `sessionContext`, `sessionGraph` and `sessionGraphs`, `sessionMagneticMaps`, `floorCoordinator`, `liveTrailsByFloor`, `appState.liveFloorTracking` and `appState.liveDestination`. Today `sessionGraph` and `sessionMagneticMaps` leak.

**Tests:**
- JVM `SessionBuildingAssemblyTest`:
  - one floor, no links, only unverified links (flag off), or tracking disabled: `metricMap == start.metricMap()` by data-class equality, topology NONE, floor set {start};
  - a floor without a walkable polygon is skipped together with its links;
  - an unchecked link with the flag on gives multi-floor.
- androidTest `SessionBuildingLoaderTest`: two C floors imported as in `FloorConnectorPersistenceTest.building()`, one stair link saved, verified. Result: multi-floor with 2 floors. DAO row counts are equal before and after loading the non-start floor.

---

### Step 8: Display follows the confirmed floor

1. **Extract `private fun applyFloorDisplay(saved: SavedFloorMap, context: ProjectContext)`** from `loadProject` (:584-662 and :666-669). It covers:
   - regions, polygons, walls, anchors and calibration;
   - reference points;
   - floor metadata (:612-621);
   - `activeProject` (:658);
   - nodes, edges, destinations and areas (:659-662);
   - `savedNavigationGraph`, `mapHasMeasurements` and `mapEditorSupported` (:666-668);
   - `markMapSaved` (:669).

   `loadProject` keeps the settings write (:601), `mapSetupSection` (:583), the selection, start and route reset (:663-665), the survey and live resets (:670-699) and the refreshes (:700-705), and calls `applyFloorDisplay` in place of the moved lines. Its behaviour is unchanged, which the existing tests cover.
2. **New `private fun followLiveFloor(floorId)`:**
   - calls `applyFloorDisplay(sessionBuilding.floorMaps[floorId], ProjectContext(venueId, floorId))`. There is no DB read or write and no live reset;
   - publishes that floor's trails from `liveTrailsByFloor`;
   - sets `appState.realRawPdrTrail` only if `floorId == rawPdrFloorId` (raw PDR has no floor model and stays on its start floor).
   - The settings snapshotFlow (:282-304) then remembers the followed floor. This is accepted and documented.
3. **updateRealParticleUi :2042-2069**, multi-floor branch:
   - `d = coordinator.decide(filter, now)`;
   - `s = filter.summary(d.floorId) ?: filter.summary()`;
   - `realLiveFloorId = d.floorId`, position, confidence and uncertainty from `s`, `realLiveFloorConfidence = d.vote`;
   - the particle cloud keeps only particles on `d.floorId`;
   - the fused trail is appended to `liveTrailsByFloor[d.floorId]`;
   - Wi-Fi fixes go to `liveTrailsByFloor[match floor]`;
   - publish `appState.liveFloorTracking`;
   - if `d.changedFrom != null`, call `followLiveFloor(d.floorId)` and set `announcedAtEpochMillis`.
   - The single-floor branch is today's code, untouched.
4. **Route:**
   - `selectNavigationDestination` (:2071) also records `appState.liveDestination = LiveDestination(id, floorId = activeProject.floorId, name)` while live.
   - In **updateRoute :2077-2098**, multi-floor: if `liveDestination.floorId != realLiveFloorId`, clear the route and show the status in §5. Otherwise route on `sessionGraphs[realLiveFloorId]`.
   - Arriving back on the destination floor recomputes the route automatically on the next update.
   - Single-floor sessions keep `sessionGraph ?: savedNavigationGraph`.
5. **CanvasNavigationScreen.kt:103:** `selected` falls back to `state.liveDestination` for the name, so LiveCard keeps the destination after a floor swap.

**Tests:**
- JVM `ApplyFloorDisplayTest` (if the VM helpers can be tested on the JVM; otherwise androidTest): the display-field subset after `followLiveFloor` equals what `loadProject` sets for the same SavedFloorMap, and the live fields are untouched.

---

### Step 9: UI

State in **TurnAppState.kt**, next to :552:

```kotlin
data class LiveFloorUi(val floorId: String, val floorName: String, val level: Int, val confidence: Double, val state: FloorState,
  val barometer: BarometerHealth, val announcedAtEpochMillis: Long?, val sessionFloors: List<Pair<String, String>>)
var liveFloorTracking by mutableStateOf<LiveFloorUi?>(null)   // null ⇒ every screen renders exactly as today
var liveDestination by mutableStateOf<LiveDestination?>(null)
var liveSessionKey by mutableStateOf<String?>(null)
val screenStateKey: Any? get() = liveSessionKey ?: activeProject
var floorTrackingEnabled by mutableStateOf(true)      // in-memory, like magneticCorrectionsEnabled
var useUncheckedFloorLinks by mutableStateOf(false)
```

- **CanvasNavigationScreen.kt:87-97:** the `rememberSaveable` keys change from `state.activeProject` to `state.screenStateKey`. `liveSessionKey` is set only in multi-floor sessions, so screen state is otherwise identical to today, and a floor follow does not close sheets or reset `positionOnly` or `wifiStartChosen`.
- **LiveCard pill row (:479-489):** a `FloorChip` when `liveFloorTracking != null`.
- **Top overlay Column (:220-243):** a sibling `LiveFloorBanner` Surface for 6 s after a change (liveRegion Polite). When UNCERTAIN it stays up with a "Relocalize" action that calls the existing `relocalizeWithNextWifi`. `topInset` (:196) grows only while it shows.
- **FullscreenNavigationScreen.kt:48:** header suffix. `navigationConfidence` (:89-97) adds "Floor uncertain" only when `liveFloorTracking?.state == UNCERTAIN`.
- **NavigationMap.kt:330-348:** draw the dot, circle and cone only if `realLiveFloorId == null || realLiveFloorId == activeProject.floorId`. The follow `LaunchedEffect` (:87-92) gains `state.activeProject` in its keys. Zoom and pan stay keyed per floor (:52-54) for fresh framing.
- **RealLiveLocateScreen.kt:100, :112:** show the tracked floor's name (looked up in `state.projects`), not `state.floorName`.
- **Options sheet (near the magnetic switch at :458):** switches "Multi-floor tracking" and "Use unchecked floor links", both disabled while live. A barometer status line is shown while live in multi-floor sessions.
- **Evaluation (fix for the self-confirming flaw):**
  - `CheckpointInput(code, x, y, floorId: String? = null)` (RealEvaluateScreen.kt:25).
  - The screen shows "Checkpoint floor" chips only when `liveFloorTracking?.sessionFloors.size > 1`. The default is the previous checkpoint's floor, else the session start floor, **never** the displayed floor. The x/y bounds use the chosen floor's size.
  - `captureCheckpoint` (:177-254): `trueFloor = input.floorId ?: sessionContext.floorId`. Bounds come from `sessionBuilding.floorMaps[trueFloor].floor` instead of `appState.floorWidthMetres` (:181-183). The code and reference-point checks (:229-235) and the saved checkpoint (:243) use `trueFloor`.
  - Capture is refused while the state is CHANGING.
  - `evaluateAtCheckpoint` is unchanged.

**Tests:**
- androidTest `LiveFloorUiTest` (state set directly, like CanvasWorkflowTest):
  - the chip shows "Floor 4 · 92%";
  - UNCERTAIN shows "Floor uncertain" and the banner's Relocalize calls the action;
  - the dot is hidden when `realLiveFloorId != activeProject.floorId`;
  - a simulated follow with the options sheet open keeps the sheet and `positionOnly`;
  - the fullscreen suffix appears;
  - with `liveFloorTracking == null`, no chip or banner appears and `liveNavigationShowsTurnByTurnBannerAndStats` (:136) passes unchanged.
- Extend `BuildingFloorsUiTest` (:107) so the switcher is locked when `liveRunning` is set.
- Extend `PrototypeWorkflowTest.settingsCannotChangeAlgorithmDuringTracking…` (:203) so both new switches are disabled while live.
- JVM: `floorChipText()` and `floorBannerText()` formatting.

---

### Steps 7 to 9 as built (progress)

**Status: Steps 7, 8 and 9 are done.** Steps 10 and 11 moved to phase 4 and are now done (below).

- **Step 7.** `data/repository/SessionBuilding.kt` holds `SessionBuilding`, the pure `assembleSessionBuilding`, `SessionBuilding.singleFloor()`, `floorTrackingStartStatus` (§5 start status) and `SessionBuildingLoader`. `ProjectRepository.load(context, persistRepairs = true)`; other floors load with `false`. The VM builds the coordinator with `FloorTrackingCoordinator.createOrNull`; if it returns null the building is reduced with `singleFloor()`, so the legacy path never sees other floors. Wi-Fi corrections go through `coordinator.correctWithWifi` (which applies the cross-floor gate and records the absolute fix) instead of a separate `crossFloorRelocalizationAllowed` call. Coordinator events are drained after every call; the latest is kept in memory and, since phase 4, all are persisted (Step 10). QR has no live initialisation path in the VM today, so only manual start, Wi-Fi init and user relocalise call `onAbsoluteFix`.
- **Step 8.** `applyFloorDisplay` is extracted from `loadProject`; `followLiveFloor` swaps the displayed floor from the frozen building with no database access and no live reset. The display follows whenever the decision's floor differs from the displayed floor (`floorToFollow`), which after the first decision only happens on a confirmed change (I3). Fused trails and Wi-Fi fixes are kept per floor; raw PDR stays on its start floor. `LiveDestination` keeps the destination's name and floor; on another floor the route shows the §5 text.
- **Step 9.** `ui/LiveFloor.kt` holds `LiveFloorUi`, `SessionFloorUi`, `LiveDestination`, `FloorTrails` and the pure text and display helpers; `ui/screens/LiveFloorViews.kt` holds `FloorChip`, `LiveFloorBanner` and `FloorTrackingOptions`. `LiveFloorUi` also carries the session floors' sizes (checkpoint bounds) and the start floor (checkpoint default). `liveSessionKey` is a `ProjectContext`. "Multi-floor tracking" (default on) and "Use unchecked floor links" (default off) are saved with the other preferences in DataStore. Capture is refused while the floor is CHANGING.
- **Tests.** JVM `SessionBuildingAssemblyTest` (legacy parity by data-class equality, unchecked/disabled links, skipped floors, start status) and `LiveFloorTest` (chip, banner, suffix, follow and dot decisions, checkpoint default, screen key). androidTest `LiveFloorUiTest` (chip, banner and Relocalize, no floor UI in single-floor sessions, locked and toggling switches, options sheet survives a follow, fullscreen suffix, checkpoint floor chips); `CanvasWorkflowTest.magneticCorrectionSwitchIsLockedDuringTracking` now counts three locked switches. Not built: the androidTest `SessionBuildingLoaderTest` and `ApplyFloorDisplayTest` (the VM is not testable on the JVM); the androidTests compile but were not run on a device.

---

### Step 10: Schema 7, export 4

> **Moved to phase 4** by the user's decision. **Done in phase 4** (see "Steps 10 and 11 as built" below).

**Entity:**

```kotlin
@Entity(tableName = "floor_transition_events",
  foreignKeys = [ForeignKey(PositioningSessionEntity::class, ["id"], ["positioningSessionId"], onDelete = CASCADE),
    ForeignKey(FloorEntity::class, ["id"], ["fromFloorId"], onDelete = SET_NULL),
    ForeignKey(FloorEntity::class, ["id"], ["toFloorId"], onDelete = SET_NULL),
    ForeignKey(VerticalTransitionEntity::class, ["id"], ["linkId"], onDelete = SET_NULL)],
  indices = [Index(value = ["positioningSessionId", "sequence"], unique = true), Index("fromFloorId"), Index("toFloorId"), Index("linkId")])
@Serializable data class FloorTransitionEventEntity(@PrimaryKey val id: String, val positioningSessionId: String, val sequence: Long,
  val type: String, val fromFloorId: String?, val toFloorId: String?, val linkId: String?, val movedShare: Double?,
  val floorVoteAfter: Double?, val heightDeltaMetres: Double?, val pressureHpa: Double?, val barometerHealth: String,
  val sensorTimestampNanos: Long?, val timestampEpochMillis: Long, val detail: String)
```

**Event types:** CONFIG (sequence 0; detail is the `FloorDetectionConfig` JSON plus barometer presence), VERTICAL_EVENT, DWELL_CUE, APPLIED, REJECTED, PENDING_EXPIRED, CONFIRMED, UNCERTAIN, ABSOLUTE_FIX, WIFI_CROSS_FLOOR_RELOCALIZATION, WIFI_CROSS_FLOOR_DEFERRED, WIFI_CROSS_FLOOR_VETOED, BAROMETER_HEALTH. Rows are written only in multi-floor sessions. The CONFIG row means no new session columns are needed.

**Database:**
- `FROM_6_TO_7`: `CREATE TABLE IF NOT EXISTS` plus 4 indices, appended to `ALL` (TurnDatabase.kt:129).
- `SCHEMA_VERSION = 7` (:60). Register the entity and the DAO (`FloorTransitionEventDao.insert`, `forSession`). Export `app/schemas/7.json`.
- Destructive fallback stays off (:70-73).

**Export:**
- `TurnDatabaseExport.floorTransitionEvents: List<FloorTransitionEventEntity> = emptyList()`; `CURRENT_EXPORT_SCHEMA = 4` (ExportModels.kt:116); versions 1..4 accepted (:41).
- Validation: the session exists, and the floor ids and link id exist or are null.
- BackupDao createExport and mergeValidated (:68-131): a query, and an upsert placed after positioning sessions.
- `ResearchDataExport` dataset `floor_transition_events` (:11-21).

**Tests:**
- androidTest `FloorTransitionMigrationTest` (MigrationTestHelper, as in FloorConnectorPersistenceTest.kt:18): v6 rows (including vertical_transitions with label and verifiedOnSite) survive; the table and indices exist; `PRAGMA foreign_key_check` is clean; a session delete cascades.
- JVM or androidTest `TransferAndValidationTest` and `ResearchDataExportTest` additions: the dataset is present; schema-3 JSON imports with an empty list; unknown session or floor ids are rejected.

---

### Step 11: Diagnostics markers and documentation

> **Moved to phase 4** by the user's decision (marker buttons and the MULTI_FLOOR_NAVIGATION.md update). **Done in phase 4** (see "Steps 10 and 11 as built" below).

- Diagnostics → Raw sensor recording gets marker buttons that call the existing `RawSensorRecorder.marker` (:37) with the Step 6 grammar: "Start stairs up", "Start stairs down", "Enter lift", "Standing still", and "Arrived on…" with chips for the building's floors.
- Update `docs/MULTI_FLOOR_NAVIGATION.md` §6:
  - phase 2 progress;
  - the meaning of the event types;
  - FUSED rows keep the argmax floor, and the displayed floor is in `floor_transition_events`;
  - the settings snapshot persists the followed floor;
  - recommend link radii that cover the stairwell landing.

### Steps 10 and 11 as built (phase 4)

**Status: Steps 10 and 11 are done.**

- **Step 10, database.** `FloorTransitionEventEntity` (Entities.kt) exactly as specified; `SCHEMA_VERSION = 7`; `TurnDatabaseMigrations.FROM_6_TO_7` creates the table and its 4 indices and is appended to `ALL`; `app/schemas/.../7.json` exported. `FloorTransitionEventDao.insert` is an `@Upsert` (a retried write is harmless) and `forSession` orders by sequence.
- **Step 10, mapping and VM.** `data/repository/FloorEventLog.kt`: `FloorTrackingEvent.toEntity` maps 1:1; the wall-clock time comes from one (epoch, elapsed-realtime) pair read at drain time; CONFIG (emitted at construction, `atNanos = 0`) gets no sensor time; `linkId` is null because the coordinator does not name the link. Row ids are `FTE-<session>-<sequence>`, so a re-imported export merges onto the same rows. `FloorEventLog` numbers events from 0 and holds them until the session row exists, because the coordinator (and CONFIG) is built before the session is saved. `drainFloorEvents()` (every existing drain point) now passes events to the log and writes the released rows on `Dispatchers.IO`; failures are logged only. The start path drains once more right after `activePositioningSession` is set, which writes CONFIG. The log resets with each new coordinator and in `clearMultiFloorSession`. Single-floor sessions have no coordinator and write nothing.
- **Step 10, export.** `TurnDatabaseExport.floorTransitionEvents` (default empty), `CURRENT_EXPORT_SCHEMA = 4` (1..4 accepted; older fields unchanged). Validation: unique ids, unique (session, sequence), and a known session and known-or-null floors and link. BackupDao reads them in `createExport` and upserts them after positioning sessions and corrections in `mergeValidated`. `ResearchDataset.FLOOR_TRANSITIONS` ("Floor change log").
- **Step 11.** *Diagnostics → Raw sensor recording* has `RawRecordingMarkers` (DiagnosticsScreen.kt) calling `TurnRuntimeViewModel.addRawMarker` → `RawSensorRecorder.marker`: Start stairs up/down, **Enter lift up/down** (the grammar needs a direction, so "Enter lift" is one button per direction), Standing still, and "Arrived on…" chips for the open building's floors in level order (floor ids the grammar cannot carry are skipped). All are disabled unless a recording runs. `docs/MULTI_FLOOR_NAVIGATION.md` §6 has the phase 4 entry.
- **Tests.** JVM `FloorTransitionExportTest` (1:1 mapping and wall clock, CONFIG held then numbered from 0, schema-4 round trip, schema-3 import with an empty list, unknown session/floor/link and duplicate sequences rejected, CSV dataset). androidTest `FloorTransitionMigrationTest` (v6 rows including link label and verifiedOnSite survive; table and 4 indices exist; `foreign_key_check` clean; session delete cascades; DAO round trip in sequence order, export includes the rows, a deleted floor nulls `toFloorId` and `linkId`) and `RawRecordingMarkersUiTest` (disabled until recording; buttons write the grammar; only this building's floors). The androidTests compile but were not run on a device.

### Phase 4 field tooling and review fixes (as built)

- **Field replay.** `core/replay/FieldReplay.kt` and `FieldReplayTest` (JVM, test sources). Each `*.json` with a `startFloorId` in `app/src/test/resources/field/` names a raw sensor CSV and the building: floors, walls and links from a complete database JSON (`database`), or written by hand, plus optional Wi-Fi anchors. Each recording is replayed per seed through `FloorReplayHarness` and scored by `FloorTrackEvaluator` against its `TraceMarkers`; the report goes to stdout and `app/build/reports/field-replay/report.txt`. Heading comes from the game rotation vector (else the rotation vector) relative to `startHeadingRadians`; `dropBarometer` replays the same walk without pressure rows. The test is skipped when the folder holds no recordings. Layout and how to run it: `docs/FIELD_TEST_PROTOCOL.md`.
- **Replay scenarios.** `MultiFloorReplayTest` adds the no-barometer variants of (a) stairs up and (b) the two-floor lift: dwell cues and three strong scans of the arrival floor's access point resolve the floor, with no VERTICAL_EVENT, one confirmed change, 0 false changes and 0 missed, within 20 s (synthetic, loose). Six scenarios now.
- **Review fixes.**
  - `crossFloorWifi` compares against the confirmed floor. A match on the filter's leading floor is ALLOW as before; a match on the confirmed floor while the filter still leads elsewhere is a quiet DEFER (no veto, no count, no UNCERTAIN; the filter only reweights). Before, Wi-Fi agreeing with the displayed floor right after a change could be vetoed and mark the floor uncertain.
  - `ParticleFilter.summary(floorId)` is cached until the particles change, so the 1 Hz multi-floor tick on Main no longer recomputes the O(n²) cluster summary.
  - `MultiFloorRouter` remembers which graphs validate and where each link end attaches (per step-free choice). The session router is already built once per session, so live re-planning no longer re-runs `validationErrors()` for every floor on each particle update. Results are unchanged (tested against a fresh router).
  - `updateRoute` in a multi-floor session uses only the tracked floor's own graph (`liveRouteGraph`); a floor without one shows "This floor has no saved paths · add paths in Map setup" instead of drawing the start floor's graph in the wrong frame. Single-floor sessions are unchanged.

---

## 3. Defaults (all in `FloorDetectionConfig` / `ParticleFilterConfig`, with require checks, snapshotted per session)

| Group | Value |
|---|---|
| Barometer | valid 300-1100 hPa; out-of-order tolerance 50 ms; median 5; EMA τ 1.0 s; gap reset 5 s; scale height 8434 m; step candidate > 0.15 hPa within 1 s while flat (≤ 0.12 m/s), half of it within 0.2 s; judged 2 s after the jump on ±1 s of raw samples, kept when the fitted shift is ≥ 0.16 hPa, or ≥ half the step with no trend above 0.5 m/s around it; a step back within half the rise closes a pulse; STALE after 3 s; drift τ 300 s after 20 s with \|rate\| ≤ 0.03 m/s |
| Vertical detector | floor height 3.5 m (doc §4.3 pending); rate window 4 s; enter 0.12 m/s; exit 0.05 m/s; settle 3 s; minimum 0.6 floor (2.1 m); integer tolerance 0.35; lift ≥ 0.6 m/s with ≤ 2 steps; max segment 180 s; return-to-start 0.5 m |
| No-barometer cues | stairs: ≥ 6 steps and ≥ max(5 s, 0.4·travelTime) with ≥ 0.5 portal weight; lift: ≥ 8 s with ≤ 1 step, emitted on the next step; seed share 0.2 stairs / 0.3 lift; cooldown 20 s |
| Transition | barometric share 0.85; outside-portal likelihood 0.1 (1.0 for seeds); unverified factor 0.5; slack 1.0 m; portal memory 10 steps; stairs stride factor 0.5; exit σ min(r/2, 1 m); 20 exit tries; max 4 hops; ≥ 10 particles per floor; prune below 1e-3 |
| Wi-Fi across floors | `crossFloorLikelihood` 0.01 (unchanged, :45); strong ≥ 0.68 with agreement ≥ 0.85; 3 consecutive matches (no barometer, or probation); 2 when the pending height is barometer-consistent; otherwise vetoed; probation 120 s after a Wi-Fi start; 4 vetoes give UNCERTAIN; pending window 120 s; cross-floor injection always off; init gate unchanged at 0.55/0.7 (:1822) |
| Decision | switch at ≥ 0.75 for 3 updates and ≥ 2 s, adjacent floors only; uncertain below 0.6; hard switch below 0.02; announce "You're on" at ≥ 0.8; CHANGING lasts while moving plus 10 s; banner 6 s |
| Links | enabled only; verified only unless "Use unchecked floor links" is on; one-way gives one portal; kill switch `floorTrackingEnabled` |

---

## 4. Behaviour by case

- **Single-floor building, unlinked start floor, all links disabled, only unchecked links with the toggle off, or tracking off:**
  - SINGLE_FLOOR mode, byte-identical to today (I1);
  - no rows in the new table;
  - `pressureHpa` is not written;
  - no new UI;
  - the only visible change is a status line when links were ignored.
- **No barometer (UNAVAILABLE), or one that goes STALE mid-session:**
  - stairs and lift dwell cues seed 20-30 % hypotheses through captured portals;
  - Wi-Fi from all session floors plus per-floor magnetic maps decide;
  - cross-floor relocalisation needs 3 agreeing strong scans;
  - the chip line shows the barometer status.
  - Expect slower changes (Android scan throttling at about 30 s).
- **Healthy barometer:**
  - it alone drives transitions, applied at settle, with portal memory;
  - a REJECTED event goes UNCERTAIN and the Wi-Fi gate opens for barometer-consistent floors;
  - flat pressure vetoes cross-floor Wi-Fi outside probation.
- **Unverified links (toggle on):** they define the session floors and move half the share.
- **One-way links:** transitions only in the saved direction. The other direction gives REJECTED, then UNCERTAIN, then recovery through Wi-Fi or Relocalize.
- **Disabled links:** never used.

---

## 5. UI text

| Where | Text |
|---|---|
| Chip | "Floor 4 · 92%" · "Changing floor…" · "Floor uncertain" (amber) |
| Banner | "You're on Floor 4" (vote ≥ 0.8) · "Probably on Floor 4 · Not right? Relocalize" · "Not sure which floor · Relocalize" |
| Fullscreen header | "Floor 4 · floor 92%" |
| navigationConfidence | "Floor uncertain" |
| Route | "Destination is on Floor 3 · directions between floors come in a later update" |
| Wi-Fi | "Wi-Fi suggests Floor 5 · waiting for more evidence" |
| Start status | "Multi-floor tracking · 3 floors · 2 links · barometer ready" / "… · no barometer: floor from Wi-Fi and stairs/lift" / "1 unchecked link ignored · check it on site or allow unchecked links" / "Floor tracking off: no checked links from this floor" |
| Options | "Multi-floor tracking" (subtitle: "Follow stairs and lifts through saved floor links"); "Use unchecked floor links" (subtitle: "Links not checked on site move fewer hypotheses"); "Barometer: ready / not on this phone / no recent readings" |
| Evaluate | "Checkpoint floor" |

Floor names come from the saved floor names; "Floor 4" above is an example.

---

## 6. Deferred

- **Phase 3:**
  - cross-floor routing, connector edges, floor-leg instructions and banners;
  - cross-floor search;
  - accessible-only routing;
  - in-session browsing of other floors (the FloorSwitcher stays locked, FloorSwitcher.kt:31);
  - highlighting the link in use on the map.
- **Phase 4:**
  - ~~Step 10 (schema 7 `floor_transition_events`, export schema 4) and Step 11 (Diagnostics marker buttons, docs), moved here by the user's decision~~ (done);
  - ~~the field replay tool, the field test protocol and the no-barometer stairs and lift scenarios~~ (done, see "Phase 4 field tooling and review fixes");
  - the remaining Step 6 replay scenarios (pending-height resolution, Wi-Fi start probation, STALE barometer, links-disabled parity, the 20-minute corridor walk);
  - threshold tuning from C-block recordings through the Step 6 harness;
  - a Diagnostics threshold editor or DataStore override;
  - per-venue floor height, `floors.elevationMetres` and reference pressure on reference points (§2.6);
  - per-phone-class metrics UI;
  - wrong-floor horizontal-error semantics (FieldEvaluation.kt:14);
  - the WIFI_ONLY/RAW_PDR `floorConfidence` fix (:1953, :2416);
  - `mapConstrained*` fields.
- **Later:**
  - a user floor picker that reseeds at link exits;
  - a learned classifier;
  - the accelerometer lift cue and sensor-registration changes;
  - exposing the Wi-Fi floor votes;
  - a session fingerprint cache;
  - per-link frame rotation;
  - the magnetic backup gap;
  - multi-floor demo mode.

---

## 7. Residual risks to watch

- **Every threshold is synthetic.** Phase 2 is not done in the field until the §6 recordings are replayed.
- **Link areas must cover the landings.** Portal memory and the 1 m slack cannot rescue a link point drawn far from the stairwell.
- **Wider Wi-Fi radio map.** The union of BSSIDs across floors (WifiPositioning.kt:263) lowers `floorAgreement` near stairwells, so the init gate at :1822 will reject more often in linked buildings.
- **Map rotation can flip on a floor swap.** `mapQuarterTurns` (NavigationMap.kt:69-70) depends on each floor's aspect ratio; heading itself stays in map frame.
- **Per-sample cost.** Work under eventMutex grows by about 5 Hz of O(1) barometer work, a 1 Hz tick, and per-step portal capture of about O(500 × floor portals). Monitor on low-end phones.
