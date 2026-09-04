# TURN architecture

## Runtime data flow

```text
QR / manual point / confident Wi-Fi
                 |
                 v
        particle initialization
                 |
steps + relative heading --> PDR prediction
                 |
metric floor geometry ----> wall/walkable constraint
                 |
fresh BSSID/RSSI vector ---> fingerprint likelihood + floor vote
                 |
                 v
      resample / relocalize / summarize
                 |
                 v
 x, y, floor, confidence, provenance, correction log
```

Ground truth enters only the evaluation writer after an estimate exists. It is not exposed to any positioning interface.

## Layers

### Core

Pure Kotlin logic with no Android dependencies:

- metric geometry and coordinate transforms;
- Wi-Fi fingerprint aggregation and weighted kNN;
- step/heading/stride PDR state;
- seeded particle filter and map constraints;
- confidence and cluster summaries;
- QR payload validation;
- optional BLE advertisement parsing.

The core is deterministic when supplied a seed, clock and event stream. This allows recorded field sessions to become regression tests.

### Data

Room stores normalized raw and derived records. Raw observations are immutable evidence; aggregates can be rebuilt. Training reference points and evaluation checkpoints have separate entity types and foreign-key paths to prevent accidental leakage.

### Platform

Small interfaces isolate Android APIs:

- `WifiScanner` turns scan requests/broadcasts into timestamped fresh or stale snapshots.
- `SensorSource` emits typed step, orientation and pressure events with availability.
- `BleScanner` remains feature-gated and stopped by default.
- QR and Storage Access Framework adapters exchange validated payloads/files.

Demo implementations emit labelled prerecorded data. Real implementations never silently fall back to demo events.

### UI

Compose screens consume immutable state from ViewModels/Flows. The map canvas transforms metric coordinates to display coordinates at the final rendering boundary. Every estimate exposes source, freshness and confidence.

## Wi-Fi fingerprinting

An aggregated fingerprint maps BSSID to median RSSI at a known `(x, y, floor)`. A live scan is compared over the union of identifiers, substituting a configured weak value for missing signals. Optional offset normalization reduces a phone-wide RSSI bias.

Weighted kNN:

1. compute distance from live vector to every eligible reference point;
2. select `k` smallest distances across candidate floors;
3. convert distance to inverse-distance weight, treating an exact match specially;
4. vote on floor by total weight;
5. average coordinates among neighbours on the winning floor;
6. derive confidence from neighbour spread, match residual, floor agreement and freshness.

The Wi-Fi-only estimate remains visible even when fusion is active.

## PDR

One accepted step advances a state using estimated stride and relative heading. Step detector is the primary event; step counter validates totals. Relative orientation prefers game rotation vector/gyroscope behavior and requires an initial map-direction alignment. Magnetic heading is optional evidence, never assumed truth.

Stride and heading offsets are small session parameters calibrated only against trusted anchors or a controlled known-distance exercise.

## Particle filter

A particle carries position, floor, heading correction, stride scale and weight.

- **Initialize:** sample around a QR/manual/Wi-Fi fix.
- **Predict:** apply each PDR step with hypothesis-specific noise.
- **Constrain:** reject wall crossings, non-walkable endpoints and invalid floor changes.
- **Correct:** score particles against fresh Wi-Fi fingerprint evidence.
- **Recover:** compare the local cloud with a global radio-map match and explicitly relocalize when justified.
- **Summarize:** choose the strongest consistent cluster and expose whole-cloud spread/floor agreement.

If all particles fail, the filter reports loss. It does not snap to ground truth.

## Floor transitions

PDR stays on the current floor unless the state is inside a configured vertical-transition region. Wi-Fi votes, QR, optional barometer change or manual confirmation can then change floor probability. Low agreement produces an abstention prompt.

## Future BLE

BLE implements the same observation contract as Wi-Fi: stable identifier, RSSI, timestamp and freshness. iBeacon uses UUID/major/minor; Eddystone UID uses namespace/instance. A feature flag and registered-beacon inventory are prerequisites for real scanning. When enabled later, modality distances must be normalized before combination so packet volume does not dominate Wi-Fi.

## Security and privacy

- Offline by default; no user account.
- Android backup disabled for Room and settings.
- Explicit file export only.
- Anonymous run/session identifiers.
- No hidden background tracking.
- Floor plans and BSSIDs are treated as venue-sensitive research data.
