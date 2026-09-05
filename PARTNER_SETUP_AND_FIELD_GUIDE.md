# TURN partner setup and field guide

This is the complete hand-off guide for installing, configuring and using the TURN Android research prototype. Read it once before collecting data and keep it open during the first field session.

TURN is an offline, foreground-only research application. It creates Wi-Fi fingerprints at known map points and later compares live Wi-Fi scans with those fingerprints. Between fresh Wi-Fi scans, Android motion sensors provide PDR updates and a particle filter constrains movement to the configured walkable map.

## 1. Before you begin

You need:

- a Windows Surface Laptop or another Windows computer;
- permission to access the private GitHub repository;
- Android Studio and a compatible JDK 17 selected for Gradle;
- an Android phone running Android 8.0/API 26 or newer;
- a USB data cable;
- permission to survey the chosen university area;
- a tape, measuring wheel or laser distance meter;
- removable physical labels for reference points and independent test checkpoints.

An iPhone cannot run this application. An Android emulator can run Demo mode, but it cannot validate real Wi-Fi scanning, step detection, relative heading or BLE behavior.

Do not collect participant names, phone numbers or other identities. Building plans and BSSIDs can be sensitive; keep the repository private and share exports only with the authorized project team.

## 2. Get access to the repository

The repository owner must:

1. Open the private TURN repository on GitHub.
2. Open **Settings → Collaborators and teams**.
3. Invite each project partner using the correct GitHub username.

Each partner must accept the invitation before cloning.

Install Git for Windows if necessary. Then open PowerShell in the folder where the project should live and run:

```powershell
git clone https://github.com/shehryarf67/TURN-Field-Test.git
Set-Location .\TURN-Field-Test
```

If Git asks for a password, use GitHub's browser sign-in or a personal access token. A normal GitHub account password is not accepted for Git operations over HTTPS.

## 3. Install Android Studio and the Android SDK

1. Download and install the current stable Android Studio for Windows.
2. Keep the standard Android SDK and bundled JDK options enabled.
3. Open Android Studio.
4. Open **More Actions → SDK Manager** from the welcome screen, or **Tools → SDK Manager** from an open project.
5. Install these SDK components:

   - Android SDK Platform 35;
   - Android SDK Build-Tools 35.x;
   - Android SDK Platform-Tools;
   - Android SDK Command-line Tools, if available.

6. Accept the Android SDK licences.
7. In Android Studio, open the cloned `TURN-Field-Test` folder—not the `app` subfolder.
8. Allow Gradle sync and dependency downloads to complete.

Select JDK 17 in **Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK**. Use Android Studio's bundled runtime only if its version is compatible; Studio releases can bundle a different Java version. For PowerShell builds, set `JAVA_HOME` to the same JDK 17 installation. The local verification for this project uses JDK 17.

The project uses the checked-in Gradle wrapper. Do not install a separate global Gradle version. Android Studio normally creates the machine-specific `local.properties` file automatically. This file must never be committed.

### Verify from PowerShell

Open the Android Studio terminal or PowerShell in the repository root:

```powershell
.\gradlew.bat test
.\gradlew.bat lintDebug
.\gradlew.bat assembleDebug
```

Expected result: each command ends with `BUILD SUCCESSFUL`.

The debug APK is created at:

```text
app\build\outputs\apk\debug\app-debug.apk
```

If PowerShell reports that Java cannot be found, open the project in Android Studio and use Android Studio's bundled JDK 17 under **Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK**.

## 4. Prepare an Android phone

### Enable developer mode

1. Open the phone's **Settings → About phone**.
2. Tap **Build number** seven times.
3. Return to Settings and open **Developer options**.
4. Enable **USB debugging**.
5. Connect the phone to the laptop with a USB data cable.
6. Select file transfer/data mode if the phone asks.
7. Accept the RSA debugging prompt on the phone. Select **Always allow from this computer** only on a trusted team laptop.

Verify the connection:

```powershell
adb devices
```

The device must be listed as `device`, not `unauthorized` or `offline`.

### Install TURN

The easiest method is Android Studio:

1. Select the physical phone in the device selector.
2. Select the `app` run configuration.
3. Press **Run**.

Alternatively, after building the APK:

```powershell
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

`-r` updates an existing installation while retaining its local Room database, provided both APKs use the same signing key. Keep building updates on the same development machine. APKs built by different teammates or fresh CI runners may have different debug signing keys. If installation reports `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, obtain an update from the original signing machine; do not uninstall a phone containing unexported research data. Uninstalling or clearing app storage deletes locally collected fingerprints and sessions.

## 5. Understand the two data modes

TURN starts in **DEMO / SIMULATED** mode.

- Demo mode uses prerecorded Wi-Fi and motion traces. Every screen is labelled `SIMULATED DATA`.
- Real Device mode accepts only `WifiManager` and Android sensor data. It never silently substitutes demo data.

Use Demo mode first to learn the screens. Switch modes from the top status control on a wide display or from **Settings** on a phone.

In Real Device mode, keep the app visible. TURN intentionally stops radio and motion collection when it leaves the foreground. It does not perform hidden background person tracking.

## 6. Grant the required permissions

When switching to Real Device mode or requesting a scan, approve:

- coarse and fine location;
- nearby Wi-Fi devices on Android versions that require it;
- Physical activity when starting a diagnostic walk or live PDR session on Android 10 or newer;
- camera only when QR scanning is used.

Also enable the phone's main **Wi-Fi** and **Location** switches. Android can deny Wi-Fi scan results when Location services are off even if the app permission is granted.

BLE is disabled until the team owns, registers and explicitly enables compatible beacons. TURN must not request Bluetooth scan permission during normal Wi-Fi + PDR testing.

If a permission was denied permanently, open **Settings → Apps → TURN Field Test → Permissions** and enable it manually.

## 7. Prepare the physical floor and metric map

An official architectural plan is not required. A simple measured sketch is enough for a pilot.

1. Choose a small public corridor or wing for the first experiment.
2. Choose a stable origin, preferably the southwest/lower-left corner of the sketch.
3. Use TURN's metric convention:

   - `(0, 0)` is the lower-left origin;
   - `+x` points right on the map;
   - `+y` points up on the map;
   - heading `0°` points along `+x`;
   - heading `90°` points along `+y`.

4. Measure the main corridor lengths and widths with a real measuring tool.
5. Draw the corridor outline, junctions, doors, walls, stairs and lifts. Architectural decoration is unnecessary.
6. Open **Floor-plan editor** and enter or place the walkable polygon, wall segments, reference points and QR anchors in metres.
7. Keep every reference point inside walkable space.
8. Place a matching removable physical label at each reference point.

The included version contains a 42 m × 28 m pilot workspace with reference points such as `RP-G-01`, `RP-G-04`, `RP-G-07` and `RP-G-10`. Edit the draft to match the actual measured pilot before treating results as research evidence.

### Current map-editor boundary

In Real Device mode, press **Save** in the floor editor to persist the pilot polygon, walls and reference points to Room. They reload when you switch to Real Device after restarting the app. Save also validates the polygon and rejects reference points outside walkable space. Stop Survey and Live locate before editing. A saved reference-point ID identifies one coordinate; use a new point ID when relocating a marker.

The current editor still uses a fixed 42 × 28 metre, single-ground-floor workspace. Image import, two-point image calibration, editable dimensions, full venue management and vertical-transition editing remain unfinished. Their controls no longer report a successful operation. QR anchor draft edits are not included in the pilot Save operation. Keep a separate measured drawing and coordinate notebook. See [IMPLEMENTATION_AUDIT.md](IMPLEMENTATION_AUDIT.md) before planning a larger campaign.

Do not claim final building-scale validation until custom venue editing and image import are completed and retested.

## 8. Plan reference points correctly

Reference points are training locations. Checkpoints are independent test locations.

- Start with reference points about 2–3 metres apart.
- Add more around corners, junctions, stairs and weak-signal areas.
- Use stable IDs and never reuse an ID for another coordinate.
- Physically stand on the matching marker while collecting its fingerprint.
- Do not use test checkpoints as training reference points.
- For meaningful `k = 4` matching, collect fingerprints at at least four distinct reference points. More points normally improve coverage, but poor measurements can reduce quality.

Record the floor, x/y coordinate, phone model, orientation, time and crowd condition for every survey session.

## 9. Check radios and sensors before surveying

Open **Radio diagnostics** in Real Device mode.

### Wi-Fi check

1. Press **Request scan**.
2. Confirm that BSSIDs, RSSI values, frequencies/channels and ages appear.
3. Confirm the result says fresh/updated before collecting.
4. Note the visible AP count.
5. If the result is cached or stale, wait until TURN's displayed next permitted request time and try again.

A successful `startScan()` request does not guarantee a new scan. Android may return cached results or throttle requests. TURN retains stale results for lineage but never counts them as independent fingerprint samples or particle-filter corrections.

### Motion check

1. Confirm that Step detector is available.
2. Confirm that Game rotation vector is selected when available; gyroscope is the fallback relative-heading source.
3. Press **Start walk** and grant **Physical activity** if prompted. A denied permission prevents step tracking; Wi-Fi surveying remains available.
4. Walk a measured straight line.
5. Compare detected steps and estimated distance with the real walk.
6. Turn left and right and confirm the relative heading changes in the expected direction.
7. Stop the diagnostic walk.

The magnetometer is not treated as reliable indoor heading truth.

## 10. Collect real Wi-Fi fingerprints

Repeat the following procedure at every reference point.

1. Switch to **Real Device** mode.
2. Open **Survey**.
3. Under **Survey context**, select the exact reference-point chip matching the physical marker.
4. Check the displayed x/y coordinate against the field notebook.
5. Select the phone orientation and crowd condition.
6. Add a short note if anything unusual is happening.
7. Stand exactly on the physical marker and hold the phone in the recorded orientation.
8. Press **Begin collection**.
9. Keep TURN visible and remain at the marker.
10. Wait until the fresh-snapshot target is reached. The default target is 12 independent fresh scans.
11. Cached/stale results may increase the `Cached ignored` count but must not advance the fresh target.
12. Review:

    - fresh snapshots;
    - raw observation count;
    - distinct BSSID count;
    - per-BSSID median and mean RSSI;
    - standard deviation and range;
    - detection rate;
    - unstable access-point warnings.

13. Allow TURN to finish automatically at the target, or press **Pause collection** to close the bounded session early.
14. Move to the next physical marker, select its reference-point chip and repeat.

TURN identifies signals by BSSID, not SSID. Multiple access points can broadcast the same SSID, so SSID is descriptive metadata only.

### Repeat surveys

For defensible results, repeat points:

- facing different directions;
- at quiet and busy times;
- on at least two Android phone models where possible;
- on more than one day if the timetable permits.

TURN keeps device, Android, orientation, crowd, timestamp and session metadata separate. Do not merge devices manually or delete raw observations simply because they look inconvenient.

## 11. Start real live location tracking

Collect fingerprints at multiple points before using Live Locate.

1. Open **Live locate** in Real Device mode.
2. Stand at a mapped part of the surveyed area.
3. Point the top of the phone along map `+x`. This explicitly defines the first route direction; TURN does not silently snap heading to magnetic north.
4. Press **Start physical session**.
5. Keep the phone direction steady until a fresh Wi-Fi scan arrives.
6. TURN compares the live BSSID/RSSI vector with stored fingerprints using weighted k-nearest neighbours.
7. When the first sufficiently confident match is obtained, TURN initializes:

   - the Wi-Fi-only position;
   - raw PDR at the Wi-Fi coordinate;
   - the particle cloud around that coordinate and floor.

8. Begin walking naturally while keeping TURN visible.
9. Watch the map layers:

   - amber line: raw PDR trajectory;
   - cyan rings: fresh Wi-Fi-only fixes;
   - blue line/dot: fused particle-filter estimate;
   - blue confidence circle: estimated uncertainty;
   - optional blue specks: a sample of the particle cloud;
   - green region/outline: configured walkable area;
   - dark segments: walls.

10. Each accepted step advances raw PDR using the configured stride and relative heading.
11. The particle filter applies movement uncertainty and rejects particles that leave walkable space or cross walls.
12. Every genuinely fresh Wi-Fi scan reweights the cloud and corrects accumulated drift.
13. Cached/stale Wi-Fi is shown in diagnostics but is ignored by fusion.
14. If a strong Wi-Fi match is far from the current cloud, TURN records an explicit global relocalization instead of silently jumping.
15. Press **Request Wi-Fi scan** only when needed; Android may still throttle it.
16. Press **Relocalize on next fresh scan** if the filter is visibly lost or the session was initialized in the wrong place.
17. Press **Stop** at the end of the walk. This closes the Room positioning and sensor sessions.

### What the live metrics mean

- **Fused x/y:** current particle-filter location in metres.
- **Wi-Fi x/y:** latest weighted-kNN absolute estimate.
- **Raw PDR x/y:** step-integrated location with no Wi-Fi correction.
- **Floor confidence:** particle weight supporting the selected floor.
- **Confidence radius:** estimated uncertainty from particle spread, not from hidden test truth.
- **Match distance:** RMS RSSI-vector distance; lower is usually a closer fingerprint match.
- **Nearest fingerprints:** reference IDs that influenced the Wi-Fi estimate.
- **Rejected particle moves:** map-matching interventions.
- **Latest result age:** age of Android's newest Wi-Fi observation.

If TURN says the scan is unlike the database, do not force a confident interpretation. Check that the correct venue was surveyed, Wi-Fi is enabled, access points have not changed and enough reference points exist nearby.

## 12. What is stored locally

TURN stores data in a Room database inside the app's private storage, including:

- venues, floors and reference points;
- Wi-Fi survey sessions;
- every scan snapshot and its freshness status;
- every raw BSSID/RSSI observation;
- aggregated fingerprints;
- sensor and positioning sessions;
- accepted/rejected PDR events;
- Wi-Fi-only, raw-PDR and fused position estimates;
- Wi-Fi correction, map-constraint and relocalization provenance;
- future evaluation, QR and BLE records.

Raw survey data is not sent to a server. Data collected on one phone is not automatically synchronized to another phone or teammate.

### Export physical data after each session

1. Stop the Survey or Live locate session and wait for it to close.
2. Open **Data/export** in **Real Device** mode.
3. Select **Save complete database JSON**.
4. Choose a destination using Android's document picker and wait for **Exported**.
5. Also export the CSV datasets needed for analysis: fingerprints, raw observations, scan snapshots, survey metadata, PDR events, positions, correction events and independent test samples.
6. Copy the files to your team's research-data folder on the laptop. Open the JSON or CSV to confirm it contains the expected session IDs before deleting anything from the phone.

The JSON contains all stored database tables, including raw lineage. It references image URIs without embedding image bytes; preserve original floor-plan images separately. CSV files include every entity field and join through IDs. A dataset with no records exports a header-only CSV. A cancelled or failed export leaves the database unchanged. Exporting a large accumulated database requires memory proportional to its size; use short pilot sessions initially.

The app does not yet expose an import/restore workflow. Keep the JSON and the installed app data; a backup file is readable research data, but restoring it through the app is not yet verified.

### Capture independent checkpoints

1. Physically mark checkpoints separate from training reference points and measure their x/y coordinates independently.
2. Start **Live locate**, obtain a fix and walk to a checkpoint.
3. Keep the live session running and open **Evaluate**.
4. Enter the checkpoint code and measured x/y. The current physical workflow evaluates ground floor `FL-G` only.
5. Press **Capture test sample**. The estimates are frozen at button-press time, then stored with independent truth in evaluation tables. The checkpoint never corrects the live estimate and no test scan is added to the fingerprint database.
6. Repeat at other checkpoints. Reusing a checkpoint code requires the same coordinate. A checkpoint at a training reference point is rejected.
7. Review Wi-Fi-only, raw-PDR and fused mean, median, p90, maximum, within-3m/5m, floor correctness and failure rate.
8. Stop Live locate, then export test samples and the complete JSON.

Missing estimates are retained as failures, with null errors rather than zero. Distance statistics use available estimates; within-distance and floor percentages use all captures. Horizontal error and floor correctness remain separate. Wi-Fi-only is the latest available fresh fix and may be older than PDR/fused output. The current on-screen summary covers the active session; past samples remain in the database exports. Multi-floor evaluation, richer per-device dashboards and a separately stabilized trajectory are still pending.

Treat each phone's app storage and exported JSON as research records. Do not uninstall TURN, clear storage or reset the phone after collection until the team has verified its copies and a restore workflow.

## 13. BLE and QR status

### BLE

BLE scanner abstractions, iBeacon parsing, Eddystone UID parsing, fake test sources and fingerprint interfaces exist. Real BLE remains disabled because no beacons are registered.

- Do not enable BLE for the Wi-Fi phase.
- Do not place simulated BLE observations in a real session.
- When physical beacons are obtained, record their stable IDs and measured coordinates before enabling experiments.

### QR

Versioned QR payload validation, generation and CameraX/ML Kit scanning adapters exist. The live pilot currently initializes from fresh Wi-Fi; final physical QR UI wiring is a remaining 0.1 milestone. A QR anchor is an explicit fix at scan time, not a continuously broadcasting beacon.

## 14. Troubleshooting

### No phone appears in Android Studio or `adb devices`

- Use a data-capable cable, not a charge-only cable.
- Change the USB mode to File transfer.
- Accept the phone's RSA prompt.
- Install the manufacturer's Windows USB driver if necessary.
- Run `adb kill-server`, then `adb start-server`.
- Disconnect and reconnect the phone.

### Gradle cannot find the Android SDK

- Open SDK Manager and install Platform 35/Build-Tools 35.
- Reopen the repository root in Android Studio.
- Check that `local.properties` contains a valid `sdk.dir` generated for that laptop.
- Never copy another partner's absolute SDK path into Git.

### Java or Gradle version errors

- Select Android Studio's bundled JDK 17.
- Use `.\gradlew.bat`; do not use an unrelated globally installed Gradle.
- Close duplicate Gradle builds if Windows reports locked jars.
- Retry with a single worker if the laptop is memory constrained:

```powershell
.\gradlew.bat test --no-daemon --max-workers=1
```

### TURN shows permission denied

- Grant coarse and fine location.
- Grant nearby Wi-Fi permission where shown.
- Enable the system Location switch.
- Enable Wi-Fi.
- Return to TURN and request a new scan.

### Scans remain cached or throttled

- Wait for the next permitted request time shown by TURN.
- Keep the app foregrounded.
- Do not press the scan button repeatedly.
- Record whether Android Developer options have scan throttling disabled. Do not silently change this setting between trials.

### No PDR movement

- Check Radio diagnostics for a physical step detector.
- Confirm the live session obtained an absolute Wi-Fi fix first.
- Walk with the phone held normally.
- If the device lacks the required sensor, record it as unsupported instead of fabricating movement.

### The blue dot stops or reports filter lost

- Check that the configured walkable polygon and walls match the real corridor.
- Check that the phone was initially pointed along map `+x`.
- Request a fresh Wi-Fi scan.
- Use **Relocalize on next fresh scan**.
- Keep the failure and correction events in the research record.

### Wi-Fi says unlike database

- Verify that fingerprints exist for the current area.
- Verify the selected reference-point coordinates.
- Survey more distinct nearby points.
- Check whether campus access points moved, disappeared or changed BSSID.
- Repeat surveys for this phone model and orientation.

## 15. Team Git workflow

Before working:

```powershell
git switch main
git pull --ff-only
git switch -c yourname/short-task-name
```

Before opening a pull request:

```powershell
.\gradlew.bat test
.\gradlew.bat lintDebug
.\gradlew.bat assembleDebug
git status
git add <only-the-files-you-intended-to-change>
git commit -m "describe the completed change"
git push -u origin yourname/short-task-name
```

Do not commit:

- `local.properties`;
- `.tooling`, `.gradle`, `build` or `app/build` folders;
- APKs, signing keys or passwords;
- raw field exports containing venue radio data;
- unrelated personal files.

GitHub Actions reruns unit tests, lint and debug APK assembly on pushes and pull requests. A green workflow verifies the software build, not physical indoor-positioning accuracy.

## 16. Minimum pilot checklist

Before the first real tracking walk, confirm all boxes:

- [ ] Repository cloned and Gradle build passes.
- [ ] Physical Android phone is authorized through USB debugging.
- [ ] TURN is in Real Device mode.
- [ ] Coarse/fine location and nearby Wi-Fi permissions are granted.
- [ ] System Wi-Fi and Location are on.
- [ ] Pilot map dimensions and coordinate convention match the physical sketch.
- [ ] Walkable geometry and walls are plausible.
- [ ] At least four physical reference points are marked and fingerprinted.
- [ ] Each survey reached its fresh-snapshot target or documents why it did not.
- [ ] Radio diagnostics shows fresh scans and changing timestamps.
- [ ] Step detector and relative-heading source are available.
- [ ] The phone is pointed along map `+x` when Live Locate starts.
- [ ] The first live Wi-Fi fix names plausible neighbouring reference points.
- [ ] PDR moves between Wi-Fi corrections.
- [ ] Map matching rejects an intentionally impossible wall crossing in a controlled test.
- [ ] The session is stopped cleanly before leaving TURN.
- [ ] A complete JSON and the required CSV files were exported and checked on the laptop.
- [ ] No one uninstalls or clears the app before the team has verified its data copies and restore procedure.

## 17. Further technical references

- [README.md](README.md): project overview and concise setup.
- [FIELD_TEST_PROTOCOL.md](FIELD_TEST_PROTOCOL.md): controlled research procedure.
- [ARCHITECTURE.md](ARCHITECTURE.md): Wi-Fi, PDR, map matching and particle-filter design.
- [DATA_DICTIONARY.md](DATA_DICTIONARY.md): stored data and units.
- [LIMITATIONS.md](LIMITATIONS.md): failure modes and abstention rules.

When reporting results, always distinguish simulated verification from physical-phone validation and keep training fingerprints separate from independent checkpoint truth.
