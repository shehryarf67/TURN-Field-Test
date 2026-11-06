# AR camera startup and usability

AR mapping uses standard Google ARCore capability checks, camera permission and the Google Play Services for AR installation/update flow. There are no phone-brand or phone-model allowlists. Depth is optional; supported phones without depth use guided floor/corner marking. Phones without ARCore support show a simple explanation and retain measured drawing and image/package import.

Open a saved project, then Tools → Create a map with your phone. The primary Start camera button is below the preview. The first scan automatically creates an empty room. Allow Camera when prompted. Move slowly, tap the physical floor, confirm it, and tap a point ahead to establish map direction. Mark actual room corners; review and verify the measurements before publishing.

For a saved draft with existing points, choose Reference A and Reference B at least 0.5 m apart before starting another camera section. These must be the same physical corners used to align the resumed scan. Permission approval is retried after the activity resumes. Starting a new camera section always returns from 2D preview to the live camera. Preview initialization errors and missing camera frames produce explicit messages.

The mapper now has a clearer empty state, rounded preview, full-width camera action, support/status card and Capture/Review/Verify & save tabs. Existing outline editing, measured fallback, alignment, calibration, verification, exports and local drafts remain available. No positioning or survey records are reset by these UI changes.

A physical AR camera session must still be checked on the target phone; emulator UI checks cannot establish camera tracking quality.

Verification (2026-11-04): 155 JVM tests pass; 36 distinct Android emulator cases verified across the suite and the mapper workflow rerun. The mapper checks cover the enabled camera action on a supported-device UI fixture and a complete measured capture/calibration/publication flow. Build and lint pass with zero lint errors. A native swipe was also checked manually in the installed mapper. The normal APK retains the signing certificate used for the 3 November download.
