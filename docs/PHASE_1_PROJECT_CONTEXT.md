# Phase 1 — Saved projects and persistence safeguards

This is the first implementation phase of the university-floor revamp. It establishes the project context required by later map, graph, survey and navigation work.

## Current changes

- The physical Projects screen creates a venue with one floor, opens saved projects and displays durable point/fingerprint/session counts. Counts of points with fingerprints are not claims that a target-length survey has completed.
- Legacy venue, floor, reference-point and session IDs remain unchanged. New projects and reference points receive distinct storage IDs, so two projects can each use the label RP-01.
- DataStore restores mode and project selection. A missing saved project does not silently fall back to another floor. An existing single project is discoverable when no selection preference exists.
- Survey, positioning and checkpoint records use the active project. Wi-Fi matching is restricted to the active floor. A live session uses a validated map read from Room; unsaved editor changes must first be saved or discarded.
- Incomplete setup can be saved before drawing a polygon. Collection still requires a valid walkable area and a saved point.
- Project switching and map editing are locked through session startup and completion. Save failures can be retried. Wi-Fi and sensor processing are serialized with session completion.
- Measured floors cannot be rescaled silently. Unchanged displayed survey coordinates retain their original double precision in Room. Existing notes, anchor direction and unchanged wall metadata are preserved.
- Maps with multiple enabled walkable regions or custom origins can be opened without being flattened by the single-region editor; editing those configurations remains disabled.

## Database compatibility

Room remains version **1**, database name **turn-field-test.db**. No tables or columns changed, no records are reseeded, and destructive migration fallback remains disabled. The exported schema is committed under `app/schemas/com.turn.fieldtest.data.local.TurnDatabase/1.json`.

The schema identity hash is `a8c2341942ea2a0397244637212a40e7`, matching the pre-revamp generated Room implementation. The application ID and project signing configuration are unchanged. Future graph tables require an explicit v1 migration and migration tests.

## Verification

Run the JVM regression suite, lint and both APK builds:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
```

The Android `ProjectPersistenceTest` uses a uniquely named test database created from the exported v1 schema. It seeds legacy IDs, image configuration, geometry, anchors, raw Wi-Fi lineage, aggregated fingerprints, PDR events and positioning estimates, closes and reopens the database, then creates a separate project and checks the original records. It also checks invalid creation leaves no partial rows. It never opens the operational TURN database.

Execute it on a dedicated test device/emulator:

```powershell
.\gradlew.bat connectedDebugAndroidTest
```

Compiling this fixture is not evidence of executing it. Physical Wi-Fi, sensor behavior and UI interactions require device validation.

## Device acceptance sequence

1. Use an upgrade signed with the same key as the existing installation. Keep the existing app installed and its storage intact.
2. Open the original project and note its reference points, saved fingerprint counts and session counts.
3. Create another venue/floor. Import a plan, set dimensions and save before drawing geometry. Reopen it to check this partial setup persists.
4. Draw the walkable area and add RP-01. Save. Switch back to the original project and confirm its records are unchanged.
5. Close/reopen TURN and verify the chosen project and mode return. Switch explicitly to Demo and back to check physical and replay data stay separate.
6. Collect a physical survey. While starting/running/closing, attempt project switching and editing; both must be blocked. Check that scale editing is locked after measurements exist.
7. Edit the map without saving and attempt Live locate; it must request a save/reload. Save, start, and confirm the physical session uses the selected floor and never substitutes a demo position.

## Still pending

Persistent navigation graph/POI editing, A*, route progress, the large shared map viewport, the survey-completion redesign and manual/QR initialization are later phases. No claim of floor-level positioning accuracy or completed end-to-end navigation is made by this phase.
