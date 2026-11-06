# Guided room mapping

The main mapper now follows Capture → Review → Verify & save. It uses the existing scan ViewModel, ARCore view, geometry validation, Room draft repository and publication path. Advanced retains coordinate editing, wall cleanup, room linking, alignment corrections, thresholds, draft management and exports. No existing floor or fingerprint data is cleared.

## Quick start

1. Open a saved project, then Tools → Create a map with your phone.
2. Tap Start camera and allow Camera access. Move slowly in good light.
3. Tap the actual floor, confirm it, then tap a floor point at least 0.5 m ahead. This sets map direction, not compass north.
4. Aim the centre cross at each wall/floor corner and tap Mark corner, in perimeter order. You can also tap the actual corner in the camera view. View outline lets you inspect the result; Show camera returns to capture.
5. Tap Pause & review. Check the corners and Confirm outline. Use Edit corners, walls & doors when corrections are needed.
6. Tap Check measurements. Select numbered corners and enter a tape-measured distance. If scale needs correction, record a scale reference and Apply scale correction. Record an independent check using a different pair afterwards.
7. Resolve any blocking checks, enter a floor name, then Save floor plan → Open saved floor. Review proposed paths in Map setup before navigation.

If AR is unavailable, Use measured drawing keeps the same review/verification path. Choose Name room, then enter measured X/Y corners. To resume any stopped camera section with saved geometry, select two physically identifiable saved corners at least 0.5 m apart and use those exact locations as the floor origin and forward reference. Never substitute guessed alignment. Interrupted sections require explicit review.

The AR button requests a real centre-camera hit test. It does not invent a corner when tracking or a surface is unavailable. Depth remains optional. Physical camera tracking and walking accuracy require on-phone testing.

## Navigation map interaction

Finger pan/pinch now accumulates against the current manual viewport directly, bypassing follow-camera animation. The first gesture starts from the currently displayed camera offset and pauses following. Position updates cannot pull the map back while browsing; the location button resumes following. Route strokes use density-aware dimensions: active route 8 dp plus a 3 dp white casing, preview route 5 dp plus casing, independent of zoom.

Verification (6 November 2026): 155 JVM tests passed; 15 portrait Android checks passed, including guided capture, actual measured publication, destination selection and accumulated dragging/recentering. Four focused checks passed again with a confirmed landscape configuration (914 × 411 dp). Debug APK builds; lint reports zero errors and 27 warnings. The normal debug APK retains the prior download's signing certificate. These checks do not establish physical camera or walking accuracy.
