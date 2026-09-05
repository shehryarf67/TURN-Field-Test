# TURN implementation audit — 2026-09-05

This is a source review and completion checklist, not a report of physical-phone accuracy. The original acceptance criteria are not all met yet.

## Fixed during this review

- Android 10+ step sensors now have the declared and on-demand Physical activity runtime permission. Wi-Fi-only surveys do not request it. Sensor registration success is checked, and denied registration cannot select an inactive source.
- Real Data/export is connected to Room and the document picker: complete database JSON plus fingerprints, observations, snapshots, survey metadata, PDR events, position estimates, corrections and test-sample CSV. Stored data is unchanged by export.
- Real Evaluate accepts independent measured pilot checkpoints, freezes estimates before database work, persists truth separately, and reports per-method error/failure summaries. It never feeds checkpoints into positioning or survey aggregation.
- Pilot polygon, walls and reference points save transactionally and reload when entering Real Device mode. Invalid/crossing polygons and outside points are rejected. Existing point IDs cannot silently acquire new coordinates.
- New reference points receive collision-resistant IDs. The old count-based scheme could reuse an existing ID and overwrite a fingerprint location.
- Wi-Fi k, missing RSSI, normalization, stride, particle count and theme now persist through DataStore. The app deliberately starts in Demo mode after a process restart.
- Unimplemented accelerometer fallback no longer appears as a working setting. Real venues no longer show simulated coverage or readiness statistics. Unwired image/calibration controls no longer report success.
- Filter loss clears the current fused position/confidence. Explicit recovery preserves the original raw-PDR baseline and records relocalization. First initialization waits for sufficient Wi-Fi confidence and floor agreement.
- Fingerprint candidates with no shared non-excluded BSSID are rejected, even when all RSSI values are weak and close to the missing-signal default. PDR abstains when no heading sensor is active.
- Foreground exit stops UI collection flags and hardware; callbacks from permission prompts remain available when the prompt temporarily leaves the activity.

## Remaining gaps, in priority order

| Priority | Gap | Practical impact / next acceptance check |
|---|---|---|
| High | Complete venue and floor editor | Physical mode is still fixed to one 42 × 28 m ground-floor pilot. Implement create/rename/delete, dimensions, image import, two-point scale calibration, point editing and persistent QR/transition topology. |
| High | QR camera workflow and manual anchors | Payload codec, generator and analyzer exist, but physical initialization/recovery UI is still absent. Connect registered-anchor validation, camera lifecycle, PNG export and logged corrections. |
| High | Backup import/restore and multi-phone exchange | JSON/CSV export works, but no safe preview/conflict-resolution/restore UI exists. Complete transactional import validation and test restoring a fresh installation. Image URIs alone are not portable image backups. |
| High | Physical-device verification | Test permission denial/retry, actual scan throttling, sensor availability, a measured walk, app backgrounding and exported file contents on at least two phones. No phone validation has been performed. |
| Medium | Full independent evaluation | Current capture is one ground-floor pilot, with current-session summaries. Add historical/per-device/per-floor analysis, confidence calibration dashboard, separate stabilized estimate, complete neighbour/event details and verified latency measurements. |
| Medium | Hardware heading and stride calibration | Relative game-vector / gyro tracking needs grip/tilt tests. Gyro z fallback assumes a face-up phone. Controlled stride-calibration UI, counter validation totals and robust fallback detection remain incomplete. |
| Medium | Floor transitions | Core topology exists, but physical runtime uses one floor and no configured transitions. Do not claim multi-floor tracking or barometer-assisted transitions yet. |
| Medium | Survey curation | AP exclusion controls and long-term completeness need repository wiring and persistent summaries. Repeated surveys are stored, but cross-device matching currently merges aggregate medians by point. |
| Medium | Live scan and failure lineage | Survey raw snapshots persist, but live scans do not yet have a separate raw snapshot owner. Persist rejected/unlike scans, sensor failures and complete correction timing without creating training records. |
| Medium | Replay and UI tests | Unit tests cover core algorithms and adapters. Add executable Compose/Room instrumentation tests and run the complete demo workflow on an emulator. Build success does not prove UI runtime behavior. |
| Medium | Runtime structure and durability | Split the large runtime ViewModel into survey, positioning, map and evaluation coordinators. Add lifecycle/concurrent-event tests, process-death recovery of interrupted sessions and backpressure/error handling. |
| Medium | Database lifecycle | Schema is v1 with no destructive fallback. Export Room schemas and add actual migration tests before the next schema change. Large JSON/CSV exports currently materialize database rows in memory. |
| Later | BLE hardware integration | Parsers/adapters exist and remain disabled. Registration UI and real fusion experiments await actual beacon hardware. |

## Verification

The previous GitHub build at commit `1883e8c` passed tests, lint and APK assembly. This review adds regression tests for activity permission versions, physical export schema/lineage, simulated-data rejection, polygon validity and evaluation failures/statistics. Current build results are recorded in the commit handoff and GitHub Actions.

The next field trial should remain small: a measured pilot corridor, several trained reference points, a separate checkpoint walk, and immediate export verification. A successful prototype build is not the full original acceptance milestone.
