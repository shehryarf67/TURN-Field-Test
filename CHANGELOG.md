# Changelog

## Unreleased — field-readiness fixes

- Add persistent PNG/JPEG floor-plan import, editable dimensions and two-point metric calibration.
- Add blank-map, undo, finish-polygon, exact reference-point entry and confirmed saved-map deletion workflows.
- Remove demo geometry leakage into new physical maps and show imported plans in Live Locate.
- Decouple survey startup from pilot-map mutation and show automatic Wi-Fi throttle waiting/retry status.
- Add physical-activity permission and verify sensor registration.
- Connect physical Room JSON/CSV export and independent checkpoint evaluation.
- Persist pilot geometry/reference points and algorithm settings.
- Reject invalid maps and prevent reference-point ID reuse.
- Preserve raw PDR during recovery and clear lost fused positions.
- Remove misleading success indicators and document outstanding acceptance gaps.

## 0.1.0

- Initial TURN research application.
- Manual metric venue/floor definition and survey-point workflow.
- Real Wi-Fi and sensor adapters plus deterministic demo sources.
- Weighted-kNN, PDR, map matching and particle-filter fusion.
- Ground-truth evaluation and data exchange.
- Feature-gated future BLE support.
