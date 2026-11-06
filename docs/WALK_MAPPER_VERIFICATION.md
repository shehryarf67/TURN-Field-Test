# Walk Mapper verification

Verified on 2026-10-16. The implementation is on `feature/walk-mapper`, based on main `f3b831a`. Tests use synthetic fixtures and dedicated test databases, not a user's saved floor. No physical phone database was opened, replaced or cleared during development.

## Automated checks

The verification run builds the debug app and Android test APK, runs all JVM tests and Android lint, then installs the matching APK pair on the isolated API-35 x86_64 emulator `TURN_Prototype_Test`.

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
adb -s <test-emulator> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <test-emulator> install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s <test-emulator> shell am instrument -w com.turn.fieldtest.test/androidx.test.runner.AndroidJUnitRunner
```

The final results and physical-testing boundary are recorded below. The development build uses an isolated signing key/runtime; build from the user's normal Android Studio setup with the existing signing key for an in-place phone update.

- **153 JVM tests passed**, including all 119 existing tests. New tests cover floor projection/inverses, rotation/translation/scale composition, camera quarter-turn projection without mirroring, duplicate/stale depth timestamps, raw unsigned depth/confidence and independent image strides, synthetic plane fitting with outliers, fresh wall support, wall merge/snap/split, shared-wall openings, real A* routing through doors, invalid room topology, calibration independence/retained lineage, coordinate-safe undo, lost-session guards and multi-region editor preservation.
- **27 Android tests passed**, including the existing motion recording, populated-v1 migration, project persistence, display heading, navigation rotation and prototype workflow tests. New tests cover populated schema 2 → 3, draft interruption/reopen, multi-room map publication with a real readable PNG, valid graph/A*, new reference-point ownership, repeated independent project identities, and actual measured-fallback UI capture/verification/publication.
- Publication failure is injected at navigation-node insertion, after venue/floor/asset/geometry writes. The test checks the expected injected error, compares complete database exports before/after and checks the private image directory. It therefore exercises rollback rather than accepting an earlier unrelated failure.
- **Android lint: 0 errors, 9 existing warnings.** Debug application and instrumentation APKs assemble successfully. A Windows JDK 17/SDK 35 environment was used; no project-wide toolchain upgrade was required.
- The actual main-app entry **Map Setup → Scan building with phone** was opened separately on the emulator. It reported ARCore unavailable and exposed measured drawing; the normal Projects/Map workflow remained available.

These checks validate implementation behavior on fixtures, including serialization/Room integration. They do not establish ARCore camera accuracy, structural wall recognition, dimensional accuracy, Fold7 hinge/camera behavior, Vivo capabilities, FPS, battery use or thermal performance.

## Phase status

| Phase | Delivered | Verification boundary |
| --- | --- | --- |
| A — ARCore foundation | Optional SDK, runtime checks/install/permission flow, GL camera, local floor/orientation, bounded pose history, pause/loss cleanup | Builds; unsupported-device entry and pure state guards tested. Real camera, rotation/folding and native lifecycle need physical testing. |
| B — guided capture | Ordered corners, room names, plane/raw-depth wall candidates, confirmation/rejection, explicit openings, measured fallback | Depth projection/fitting tested on synthetic evidence; measured UI works end to end. Physical hits/wall detection remain experimental. |
| C — reconstruction | Multi-room drafts, alignment transforms, explicit wall cleanup, numeric corrections, scale calibration and independent checks | Pure geometry/transform/topology/lineage tests pass. Whole-building drift is not automatically corrected. |
| D — TURN integration | Additive Room migration, private PNG, separate mapped project, regions/POIs/safe path proposals, existing editor and survey/navigation compatibility | Android save/reopen, rollback, graph/A* and reference-point ownership tests pass. Some graphs require manual completion. |
| E — hardening | Bounded processing/storage, local exports, failure feedback, tests and physical acceptance guide | Automated software checks pass. No physical accuracy or performance acceptance result has been recorded. |

## Important findings fixed during verification

- Parallel/identical overlapping room outlines needed interior and edge-midpoint checks in addition to vertex/crossing tests.
- The existing database has a unique `(venueId, levelNumber)` floor index. Alternate ground-floor layouts now publish into a separate named mapped project and retain the actual source level, preserving the original constraint and all old data. They are not silently stored as fictitious higher floors.
- Multiple publication runs use independent project, floor, region, wall, POI and graph identities.
- Switching editable rooms retains untouched polygon JSON precision and does not make the map dirty just because selection changed. Saving retains every other enabled room; explicit removal still removes the intended draft regions.
- Applied calibration records cannot be erased and reused as independent validation, including scale corrections of exactly 1. Camera-section loss callbacks are scoped to their source section. Raw-depth acquisition errors do not leave the geometry worker permanently busy; plane/guided capture remains available.
- Old accumulated wall points require support from the current fresh depth frame before being reported again. Numeric measured input uses the current map frame after calibration, and undo/section corrections keep camera history in the same frame as the reviewed geometry. Both door endpoints must match the boundaries of all linked rooms.
- Acquired images close before asynchronous processing. Native anchors, GL resources and sessions have explicit pause/disposal paths. Declined AR installation can be retried explicitly.

## Complete changed-file inventory

Existing application/configuration files:

- `README.md`
- `app/build.gradle.kts`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/res/xml/data_extraction_rules.xml`
- `app/src/main/java/com/turn/fieldtest/MainActivity.kt`
- `app/src/main/java/com/turn/fieldtest/data/local/TurnDatabase.kt`
- `app/src/main/java/com/turn/fieldtest/data/export/BackupDao.kt`
- `app/src/main/java/com/turn/fieldtest/data/export/ExportModels.kt`
- `app/src/main/java/com/turn/fieldtest/ui/TurnApp.kt`
- `app/src/main/java/com/turn/fieldtest/ui/TurnAppState.kt`
- `app/src/main/java/com/turn/fieldtest/ui/TurnRuntimeViewModel.kt`
- `app/src/main/java/com/turn/fieldtest/ui/screens/MapSetupScreen.kt`
- `app/src/main/java/com/turn/fieldtest/ui/screens/NavigationMap.kt`
- `app/src/main/java/com/turn/fieldtest/ui/screens/FloorEditorScreen.kt`
- `docs/UNIVERSITY_FLOOR_PROTOTYPE.md`

New implementation files under `app/src/main/java/com/turn/fieldtest/mapper/`:

- `WalkMapperActivity.kt`
- `WalkMapperViewModel.kt`
- `ar/ArScanView.kt`
- `core/CaptureGuard.kt`
- `core/DepthGeometry.kt`
- `core/ScanGeometry.kt`
- `core/ScanModels.kt`
- `data/ScanDraftEntity.kt`
- `data/ScanRepository.kt`
- `ui/ScanPreview.kt`
- `ui/WalkMapperScreen.kt`

New schema, tests and guides:

- `app/schemas/com.turn.fieldtest.data.local.TurnDatabase/3.json`
- `app/src/test/java/com/turn/fieldtest/mapper/ScanCoreTest.kt`
- `app/src/test/java/com/turn/fieldtest/mapper/DepthGeometryTest.kt`
- `app/src/test/java/com/turn/fieldtest/mapper/MultiRegionEditorTest.kt`
- `app/src/androidTest/java/com/turn/fieldtest/data/WalkMapperPersistenceTest.kt`
- `app/src/androidTest/java/com/turn/fieldtest/ui/WalkMapperWorkflowTest.kt`
- `docs/WALK_MAPPER.md`
- `docs/WALK_MAPPER_VERIFICATION.md`

## Next physical test

Use the bedroom → corridor → optional second-room procedure in [WALK_MAPPER.md](WALK_MAPPER.md). Keep original physical measurements separate; reserve additional checks that were not used for calibration. Log every correction/failure and time spent. Verify camera permissions/install rejection, real usable depth, plane-only operation, background/reopen, portrait/landscape and folding. Repeat capability/fallback checks on Vivo rather than assuming ARCore support. Until this is done, native AR capture/wall candidates and mapping accuracy remain experimental.
