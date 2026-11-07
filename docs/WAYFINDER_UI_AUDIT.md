# Wayfinder UI consistency review

Reviewed against `design/ui-previews/01-wayfinder.html` and the Explore/Buildings redesign in commit `c87e81d`. The route-planner layout, room rendering, room selection and Buildings workflow were retained.

## Changes

- Shared blue/white palette, muted page background, 20 dp cards, consistent padding and quieter headers across supporting pages.
- Tools now uses the same soft-blue icon tiles and outlined surfaces as Buildings. Its workflow description follows start → destination → direction.
- Research cards, metrics and status pills share the same visual language. Positive/warning text remains readable on light surfaces, and long diagnostic statuses wrap rather than compete for one row.
- Settings uses outlined radio-style data-mode choices rather than large filled blocks, and avoids repeating the Settings title. Real/simulated data labels remain explicit.
- Export keeps a clear primary database-backup action and secondary outlined CSV actions. Export formats and document-picker callbacks are unchanged.
- Survey uses one page scroll container in short landscape windows so collection controls remain reachable.
- AR capture/review/verification uses matching cards, stage chips and primary button shapes. Its activity now synchronizes Android system bars with the selected theme, as the main activity already did.
- Full-screen navigation uses Wayfinder room labels/rotation, a compact header, safe system-bar insets and a separate landscape control area. Heading/step diagnostics remain available when debug overlays are enabled.
- Cancelling the map-based ahead-point picker returns to direction selection while retaining the start and destination.

## Emulator evidence

The screenshot runner covers 19 page/state views in light and dark mode, portrait and landscape: Explore, route preview, direction selection, live layout, Buildings, Tools, all four Map setup tabs, Survey, diagnostics, evaluation, export, Settings, full screen and all three AR stages.

Screenshots use isolated UI fixtures and the supplied C-F4 geometry. The live-layout fixture does not send positioning data to a repository or sensor service. AR shows the real camera-start/measured-drawing UI, without claiming to test physical AR tracking. Android frames and dialog windows are synchronized before capture; PNGs are copied out of test-app storage so test teardown cannot erase the gallery.

Validation includes 170 JVM tests, the existing Canvas and mapper workflows, the 13 prototype UI checks, and the added ahead-picker cancellation check. Across the regression runs, 38 distinct Android checks passed. Final build and lint pass; lint retains 27 existing warnings and reports zero errors.

Room schema, repositories, survey freshness handling, hardware services, PDR and particle-filter algorithms were not changed by this review. Existing maps, fingerprints and recordings are preserved. Physical Wi-Fi, walking and AR accuracy still require on-phone testing.
