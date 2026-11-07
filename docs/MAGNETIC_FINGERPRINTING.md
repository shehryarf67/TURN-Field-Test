# Magnetic fingerprinting

TURN fuses four sources: a known start or Wi-Fi fix, PDR steps, map constraints, and now **magnetic evidence**. Steel in the building bends Earth's field into a stable, location-specific pattern. Wi-Fi says roughly where you are (3–5 m); the magnetic pattern refines *where along the corridor*. Magnetic evidence never initialises tracking on its own.

## Survey (Wi-Fi survey → Magnetic paths)

1. Save the map with survey points along each corridor (Map setup → Points).
2. Open **Wi-Fi survey → Magnetic paths**. Tap the start point, then the end point (one straight corridor stretch, at least 2 m).
3. Stand at the start, tap **Start walk**, walk straight at a normal pace holding the phone as you would when navigating, and tap **Finish** at the end.
4. Walk each stretch in both directions, ideally with more than one phone. Mapped cells appear as green squares.

A walk is rejected, with the reason shown and recorded, when the line leaves walkable space or crosses a wall, too few steps were detected, the implied stride is not 0.3–1.2 m, magnetometer data is missing on over 20 % of steps, or over 20 % of readings are implausible (strong nearby interference).

## How it works

- **Sensors:** uncalibrated magnetometer where available (avoids Android's recalibration jumps; bias changes are detected), otherwise the calibrated one; gravity sensor, or a low-passed accelerometer on phones without one. Both at about 16 Hz. Phones without a magnetometer keep Wi-Fi + PDR and say so.
- **Features:** field strength and its vertical component, which do not depend on how the phone is held. The horizontal direction is never used.
- **Per step:** readings since the previous step are averaged, matched by sensor timestamp so delayed step events get the right readings.
- **Map:** 0.6 m cells with running mean and variance; single-pass cells get a prior variance, gaps are interpolated from neighbours with extra uncertainty.
- **Tracking:** after each step moves the particles, each particle is weighted by how well the map explains (a) the step-to-step change in the features, which cancels each phone's constant offset, and (b) the absolute values, with a 6 µT allowance for device differences.

## Uncertainty and interference

- Each term is a robust Gaussian + 15 % outlier mixture, so passing people, trolleys, lifts or laptops cannot drag the estimate.
- Implausible readings (outside 15–90 µT) are skipped; calibration or accuracy changes break step-to-step comparison.
- Evidence is skipped when under half the particle weight is on surveyed cells, and particles on unsurveyed cells are treated neutrally.
- The likelihood is tempered (exponent 0.5) because consecutive steps share errors.
- **Explore → ⋮ → Magnetic corrections** turns it off for comparison runs; it is locked during a session so each recording uses one setting.

## Data

Room schema 4 adds `magnetic_cells` and `magnetic_walks` (every walk, accepted or rejected, with device model and reason) via an additive 3 → 4 migration. Deleting a saved map clears the floor's magnetic data. Magnetic tables are not yet part of the JSON/CSV research export.

Accuracy gains must be measured on the real floor with evaluation checkpoints, comparing sessions with the switch on and off.
