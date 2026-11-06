# Map-first navigation

Explore presents a room search and destination card over the floor map. Imported mapper room interiors can be tapped directly; the room outline is highlighted and associated with its destination entrance. The room association is recovered from the source JSON already persisted in Room, using stable imported destination IDs, so renaming a destination preserves selection. Legacy image-only maps retain destination-marker taps and search. No room boundaries are guessed from an image.

Choose a destination, then a known start to preview the existing A* graph route before turning tracking on. The preview does not create a live estimate or write synthetic positioning data. A missing destination connection, unsaved map edits, or an unreachable chosen start has an explicit explanation. Automatic Wi-Fi initialization still waits for real, confident fresh evidence. Manual facing-direction alignment remains available before starting and during a session.

When real tracking has an initialized fused position, the camera switches to an approximately 18 m view and follows position updates, with a blue route/dot, translucent heading sector and metric uncertainty. The floor stays map-up; portrait and landscape use the same sensor coordinate frame. Camera animation affects only rendering. Pan or pinch to explore and pause following; the location button recenters and resumes follow. Fit floor map returns to the overview. Existing full-screen view, optional research overlays, survey, exports, Room persistence and AR tools remain available.

Room hit areas are ignored if the floor dimensions no longer match the imported coordinate frame. Recalibrated image-only projects must use registered places or receive updated room geometry. Route and room selection do not permit walking through unwalkable geometry or bypass graph validation.

The preview shares live routing's validated saved graph instead of rebuilding it from rounded display coordinates. Main app tabs collapse while real navigation is active in Explore and return when tracking stops; tools remain accessible from Map options.

Selecting a known start for a destination returns to the route map. Inspect the preview, then tap Navigate here to confirm your facing direction and start tracking. Choosing position-only tracking retains the direct start/direction flow.

Final verification: the 41-case Android suite passed, followed by a 14-case navigation/rotation/persistence rerun covering the final start-to-preview-to-tracking flow. All 155 JVM tests, APK builds and lint checks passed. Native portrait/landscape previews are recorded in the task output; physical sensor accuracy must still be evaluated on site.

Validation (2026-11-06): 155 JVM tests, 41 Android emulator tests, build and lint passed (zero lint errors). Checks cover room-interior taps, renamed room associations after database reopen, routes from the supplied anchors, pre-tracking route previews, unavailable paths, fused-position camera following, focus-mode tab restoration and full-screen rotation without stopping the session. A native C-F4 room selection and 35.2 m route preview were also checked in portrait and landscape. Physical walking accuracy remains an on-site validation task.
