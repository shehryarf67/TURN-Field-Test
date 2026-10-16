# TURN Field Test

TURN is an offline-first Android indoor-navigation prototype for one real building floor, with retained research tools for Wi-Fi fingerprinting and uncertainty-aware positioning:

`QR/manual/Wi-Fi fix -> PDR prediction -> map constraint -> Wi-Fi correction -> fused blue dot`

Navigate shows the fused blue dot, uncertainty and route. Raw PDR, Wi-Fi fixes and particles are optional debug overlays. A separate stabilized research output remains pending. Evaluation ground truth remains separate from the estimator; a user-declared known start is an explicit initialization anchor.

> **Research prototype:** Demo mode uses deterministic prerecorded data. Build and unit-test verification do not establish emulator UI behavior or physical-device accuracy.

Begin with the [university-floor prototype guide](docs/UNIVERSITY_FLOOR_PROTOTYPE.md) for the current Projects → Map setup → Survey → Navigate workflow. The earlier [partner setup guide](PARTNER_SETUP_AND_FIELD_GUIDE.md) remains useful for toolchain and phone setup; its screen/feature descriptions predate this revamp.

No existing floor plan? [Walk Mapper](docs/WALK_MAPPER.md) adds optional native ARCore capture, guided room/door editing, measured drawing, verification and saving a new TURN floor with a real PNG and safe path proposals. Camera accuracy and wall candidates require physical testing; unsupported phones keep measured drawing and all existing TURN workflows.

The [original implementation audit](IMPLEMENTATION_AUDIT.md) is historical. Image import/calibration, map and graph/destination editing, surveys, known-start or Wi-Fi initialization, tracking, A* routing, checkpoints, persistence and JSON/CSV export are connected. Physical-phone accuracy still requires testing on the selected university floor. Multi-floor runtime, QR camera workflows and backup restore are outside this prototype slice.

The revamp retains [Phase 1's persistent project selection](docs/PHASE_1_PROJECT_CONTEXT.md) and adds a shared zoomable map, saved navigation graph/POIs, wall-safe A* routing, durable survey completion, and a clean live blue-dot view with optional research overlays. Room's additive 1 → 2 → 3 migrations preserve existing records; schema 3 adds Walk Mapper drafts.

## What is included

- Metric venue and floor-plan data model, including walkable regions, walls, vertical transitions, POIs, QR anchors, reference points and checkpoints.
- One-active-floor metric editor with PNG/JPEG import, editable dimensions, two-point calibration, blank-map/reset controls and persistent polygon, walls, QR anchors and reference points.
- Version-aware Android Wi-Fi scanner with fresh/stale result handling.
- Wi-Fi survey sessions that retain raw readings and aggregate median, mean, standard deviation, range and detection rate.
- Weighted k-nearest-neighbour Wi-Fi positioning with floor voting, missing-signal handling, normalization and confidence.
- PDR from step sensors and relative orientation, with stride calibration and explicit source availability.
- Seeded particle-filter fusion with wall/walkable constraints, Wi-Fi correction, QR recovery and global relocalization.
- Wi-Fi-only, PDR-only and fused evaluation records.
- Optional BLE architecture and parsers, disabled until beacon hardware is configured.
- Room persistence, JSON/CSV exchange, deterministic demo/replay data and JVM tests.
- Persistent manual path/destination editing and A* routes with live remaining distance.
- Declared known-location or fresh Wi-Fi initialization, relative map-heading alignment, blue dot and uncertainty overlays.

## Surface Laptop setup

The project contains no native C/C++, Docker, WSL or Unix-only build steps. It is designed for Android Studio on Windows x64 or ARM64 and uses the checked-in Gradle wrapper.

### Install the toolchain

1. Install the current stable Android Studio for Windows.
2. In Android Studio's SDK Manager, install:
   - Android SDK Platform 35
   - Android SDK Build-Tools 35.x
   - Android SDK Platform-Tools
3. Select a compatible JDK 17 or 21 for Gradle. Do not use JDK 25 with the current Android Gradle plugin.
4. Open this repository, allow Gradle sync to finish, and select the `app` run configuration.

`local.properties` is machine-specific and intentionally ignored. Android Studio creates it with the local SDK path.

### PowerShell commands

From the repository root:

```powershell
.\gradlew.bat test
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
.\gradlew.bat assembleDebug
```

The debug APK is written to:

```text
app\build\outputs\apk\debug\app-debug.apk
```

### Physical phone

Real Wi-Fi, QR and motion-sensor testing requires an Android phone. An emulator cannot expose representative nearby access points or walking sensors.

1. Enable Developer options and USB debugging on the phone.
2. Connect it to the Surface Laptop by USB and accept the debugging prompt.
3. Verify it appears in Android Studio, or run `adb devices`.
4. Install from Android Studio, or run:

```powershell
.\gradlew.bat installDebug
```

## First launch

TURN has two explicit sources:

- **Demo mode:** deterministic simulated venue, Wi-Fi and sensor events. Every screen is labelled `SIMULATED DATA`.
- **Real device mode:** Android Wi-Fi and sensor APIs only. It never silently falls back to simulation.

New installations open in Real Device mode. TURN restores the last saved mode and physical project on later launches. Use Demo explicitly for the labelled replay. In Real Device, open an existing project or create a venue and floor before map setup; grant hardware permissions when requested.

## Real field workflow

1. Measure a small public pilot area and draw a simple plan.
2. Create a venue/floor, import the sketch, calibrate two image points against a measured distance, and define the origin.
3. Draw walkable regions and walls in metric coordinates.
4. Add physical reference points every 2-3 m and place matching removable labels on site.
5. Use **Radio diagnostics** to verify fresh Wi-Fi scans and sensor availability.
6. At each reference point, use **Survey** to collect multiple independent scan snapshots. Repeat with different phones/orientations/times.
7. Save the map and connected paths/destinations, then use **Navigate**. Choose your known start or wait for a confident fresh Wi-Fi fix. Enter the map direction you are facing before starting. Select a destination to see its A* route. PDR updates between scans; fresh Wi-Fi observations correct drift.
8. At separate checkpoints that were not training points, use **Evaluate** to capture error and floor correctness.
9. Export the anonymized results for analysis.

See [FIELD_TEST_PROTOCOL.md](FIELD_TEST_PROTOCOL.md) for the controlled experimental procedure.

## Wi-Fi permissions and throttling

TURN derives physical position from nearby radio data, so it requests location/nearby-device access honestly. Location services must also be enabled on Android versions that require them for scan results.

Android may throttle application-requested Wi-Fi scans. TURN checks `startScan()`, listens for `SCAN_RESULTS_AVAILABLE_ACTION`, reads `EXTRA_RESULTS_UPDATED`, records result timestamps, and labels cached observations. A cached result is never counted as a new survey sample or correction.

The application does not promise high-rate Wi-Fi updates. PDR propagates the position between genuinely fresh radio corrections. If scan throttling is disabled in Developer options for a controlled experiment, record that fact in the session; it is not representative of normal deployment.

## BLE status

BLE support is compiled but off by default. Until a researcher enables BLE and registers beacons, TURN does not request Bluetooth permissions or start a BLE scan and shows `BLE not configured - Wi-Fi + PDR active`. Fake BLE packets exist only in labelled Demo mode.

## Data and privacy

- All operational data is local Room storage.
- No account, cloud backend or participant identity is required.
- Venue radio identifiers and floor plans may be sensitive. Android backup is disabled and exports are explicit user actions.
- Test runs use anonymous session IDs.
- Training fingerprints and test observations are separate records.

See [DATA_DICTIONARY.md](DATA_DICTIONARY.md) and [LIMITATIONS.md](LIMITATIONS.md).

## Repository map

```text
app/src/main/java/com/turn/fieldtest/
  core/       Pure positioning, geometry, PDR, particle-filter and BLE logic
  data/       Room entities, DAOs, database and repositories
  platform/   Android Wi-Fi, sensor, optional BLE, QR and file adapters
  ui/         Compose screens, state and visualizations
```

## Verification boundary

Automated checks can verify deterministic algorithms, storage contracts, parsing, state transitions and Compose structure. Only a real-phone walk can verify Wi-Fi permissions, scan cadence, RSSI stability, step/heading behavior and positioning accuracy. Record those results instead of replacing them with demo claims.
