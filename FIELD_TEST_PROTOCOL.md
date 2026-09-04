# TURN real-building field-test protocol

This protocol separates training data from independent evaluation and records the conditions needed to reproduce a result.

## 1. Freeze the pilot

Before collecting radio data, record:

- venue/floor and permission to test in public areas;
- test boundary and approximate area;
- phones, Android versions and app build;
- whether Android Wi-Fi scan throttling is enabled;
- planned reference-point spacing;
- date/time and expected crowd condition.

Start with one corridor or wing, 15-25 reference points and at least 10 independent checkpoints. Expand only after the pipeline works end to end.

## 2. Make a metric plan

1. Sketch corridor boundaries, turns, doors and vertical transitions. Architectural detail is unnecessary.
2. Choose a stable origin such as the southwest corner: metric x increases right and metric y increases up. Screen y is inverted only while rendering.
3. Measure major lengths with a laser, measuring wheel or tape. Do not estimate every distance by eye.
4. Import the sketch and calibrate two image points against a measured distance.
5. Draw walkable regions and blocking walls.
6. Add stairs/lifts/escalators and align shared transitions across floors.
7. Walk the plan once and correct obvious geometry errors.

Record the measuring tool and approximate plan uncertainty.

## 3. Mark reference and test points

- Place removable physical labels at known coordinates.
- Reference points build the fingerprint database; use IDs such as `F1-RP-001`.
- Checkpoints grade the system; use IDs such as `F1-CP-001`.
- Do not put a checkpoint at the same coordinate as a reference point.
- Include easy and difficult places: straight corridors, junctions, corners, weak-coverage zones and vertical transitions.
- Photograph or diagram marker locations only if venue permission allows it.

## 4. Check hardware

On each phone:

1. Open **Radio diagnostics**.
2. Grant location/nearby-device permissions and enable Location and Wi-Fi.
3. Verify a fresh Wi-Fi result, its timestamp and visible BSSID count.
4. Verify step detector/counter and relative-orientation source.
5. Walk a known 10-20 m line and compare detected steps/distance.
6. Turn approximately 90 degrees and verify relative-heading response.
7. Record missing sensors and selected fallbacks.

Do not proceed if all Wi-Fi observations are cached or timestamps do not change.

## 5. Calibrate PDR

1. Mark a straight measured path.
2. Initialize direction along the path.
3. Walk naturally at least three times.
4. Fit a conservative session stride scale from known start/end distance.
5. Do not use later checkpoint truth to tune stride or heading.
6. Preserve the uncalibrated baseline for comparison.

## 6. Collect fingerprints

At each reference point:

1. Select the exact reference-point ID before scanning.
2. Stand on the marker without blocking the phone antenna deliberately.
3. Hold the phone in the recorded orientation.
4. Collect a bounded survey window.
5. Require genuinely fresh Wi-Fi snapshots; cached results remain raw records but do not increase the independent-scan count.
6. Add orientation/crowd/obstruction notes.
7. Inspect the summary and save the session.

Repeat the floor in both directions and, where feasible, at another time. For device generality, repeat on at least two Android phones. Do not merge devices invisibly; retain device metadata and report per-device results.

## 7. Freeze the radio map

Before evaluation:

- inspect BSSID detection rate and RSSI standard deviation;
- flag unstable, mobile or rarely detected access points;
- document any excluded BSSID and reason;
- generate aggregated median fingerprints;
- export and checksum the training dataset;
- do not add evaluation scans to it.

## 8. Run live fused walks

For every run:

1. Start a new anonymous positioning session.
2. Initialize using the assigned QR, manual point, or a confident Wi-Fi fix.
3. Initialize route direction explicitly.
4. Walk the prescribed route naturally.
5. Do not interact with the estimator except at predefined recovery points.
6. At each checkpoint, tap **Capture test sample** while standing on the marker.
7. Record Wi-Fi-only, PDR-only and fused estimates together.
8. If TURN asks to re-anchor, follow the predefined rule and retain the failure event.

Run each route multiple times and counterbalance direction/order where practical.

## 9. Report without leakage

For each method and overall, report:

- number of attempted and successful samples;
- mean, median, p90 and maximum horizontal error;
- percentage within 3 m and 5 m;
- floor correctness;
- failure/no-estimate rate;
- error versus time since fresh Wi-Fi;
- confidence coverage;
- per-phone and per-floor results;
- re-anchor/relocalization count.

Keep horizontal error separate from floor correctness. Include confidence intervals or run-to-run spread. Do not remove difficult samples after seeing their error; document hardware failures separately.

## 10. Required comparisons

- Wi-Fi weighted kNN only.
- PDR only from the same initialization.
- PDR + map constraints.
- Full Wi-Fi + PDR + map-constraint particle filter.
- Raw versus device-offset-normalized RSSI.
- Raw fused output versus stabilized display output.

When beacons become available, add BLE-only and combined Wi-Fi/BLE experiments as a separately versioned phase.
