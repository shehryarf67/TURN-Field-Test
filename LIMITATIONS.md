# Limitations and abstention rules

TURN is a research instrument. It must expose uncertainty and failure rather than manufacture a smooth blue dot.

See [IMPLEMENTATION_AUDIT.md](IMPLEMENTATION_AUDIT.md) for unfinished implementation, including the fixed pilot map, QR UI, import/restore and multi-floor runtime. Recovery options described below are architecture capabilities; only fresh Wi-Fi recovery is currently connected in the physical screen.

## Radio

- Android can throttle application-requested Wi-Fi scans, so PDR—not Wi-Fi—provides frequent updates between fresh fixes.
- A successful API read may still contain cached observations; timestamp/freshness handling is part of the dataset.
- RSSI changes across phone models, grip/orientation, crowds, stock, doors and access-point configuration.
- BSSIDs can disappear, move or be replaced. A radio map requires monitoring and recalibration.
- Sparse or symmetric signal patterns can be ambiguous. TURN reports neighbour disagreement and can abstain.
- BLE code has no real-hardware validation until fixed beacons are obtained.

## PDR and fusion

- Step detection can miss or double-count unusual walking patterns.
- Stride length changes with user, speed and surface.
- Relative heading drifts; magnetic north is not assumed reliable indoors.
- Map matching removes impossible paths but cannot invent an absolute position.
- Elevators/escalators may provide few or no steps. Floor change needs topology plus Wi-Fi, QR, optional barometer or manual confirmation.
- A particle filter can collapse if its motion/noise model is wrong. TURN records and surfaces this state.

## Map and evaluation

- A hand-measured plan has coordinate error, and that error contributes to reported positioning error.
- Incorrect walls/walkable regions can force an otherwise plausible estimate to the wrong place.
- Reference points are not independent test points.
- Emulator replay proves code paths, not Wi-Fi/PDR accuracy.
- A result from one building or phone does not establish general performance.

## Abstain or request recovery when

- there is no fresh radio evidence and particle spread exceeds the configured threshold;
- weighted neighbours strongly disagree on floor;
- the live signal vector is outside the training distribution;
- map constraints reject the complete cloud;
- a required sensor becomes unavailable;
- no verified transition explains a proposed floor change.

Recovery options are a QR scan, manual point/floor confirmation, or waiting for a fresh Wi-Fi observation.
