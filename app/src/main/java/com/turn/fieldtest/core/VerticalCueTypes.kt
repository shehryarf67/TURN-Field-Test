package com.turn.fieldtest.core

// Shared vertical-cue vocabulary used by ParticleFilter's floor transitions (phase 2 step 4).
// Step 3 (VerticalMotion.kt) declares these same two enums with identical entries; when both steps are on
// one branch, delete this file and keep the declarations in VerticalMotion.kt.

/** Which way a vertical movement went. UNKNOWN matches either direction. */
enum class VerticalDirection { UP, DOWN, UNKNOWN }

/** The kind of link a vertical movement looked like. STAIRS also covers escalators and ramps; ANY matches every kind. */
enum class VerticalKindHint { STAIRS, LIFT, ANY }
