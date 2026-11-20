package com.turn.fieldtest.core

import kotlinx.serialization.Serializable

/**
 * Every floor-detection threshold of a multi-floor session, snapshotted per session (the CONFIG event).
 * Particle-level transition knobs (portal slack, memory, exits, stratified resampling) live in [ParticleFilterConfig].
 */
@Serializable
data class FloorDetectionConfig(
    val barometer: BarometerConfig = BarometerConfig(),
    val vertical: VerticalMotionConfig = VerticalMotionConfig(),
    val cues: CueConfig = CueConfig(),
    val transition: TransitionConfig = TransitionConfig(),
    val wifi: WifiFloorConfig = WifiFloorConfig(),
    val decision: DecisionConfig = DecisionConfig(),
) {
    init {
        require(barometer.rateWindowSeconds == vertical.rateWindowSeconds) {
            "The detector looks back over the same window the tracker measures its rate on."
        }
        require(barometer.scaleHeightMetres == vertical.scaleHeightMetres) {
            "The detector converts pressure to height as the tracker does."
        }
    }
}

/** Sample hygiene, smoothing, step rejection and drift compensation for [BarometerTracker]. */
@Serializable
data class BarometerConfig(
    val minimumHpa: Double = 300.0,
    val maximumHpa: Double = 1100.0,
    /** Samples up to this much older than the newest one are re-sorted; older ones are dropped. */
    val outOfOrderToleranceSeconds: Double = 0.05,
    val medianWindow: Int = 5,
    val emaTauSeconds: Double = 1.0,
    /** A longer silence re-seeds the smoothing; the anchor is kept. */
    val gapResetSeconds: Double = 5.0,
    val scaleHeightMetres: Double = 8434.0,
    /** Until the first explicit anchor, the zero follows the smoothed pressure this long after the first sample. */
    val warmUpSeconds: Double = 2.0,
    /** The vertical rate is measured over this window. */
    val rateWindowSeconds: Double = 4.0,
    /** A change larger than this within [stepWindowSeconds], on otherwise flat pressure, is a discontinuity. */
    val stepRejectionHpa: Double = 0.15,
    val stepWindowSeconds: Double = 1.0,
    /** "Otherwise flat": the rate over the [rateWindowSeconds] before the step stays at or below this (no motion). */
    val stepFlatRateMps: Double = 0.12,
    /**
     * "Sharp": a step happens within [stepSharpSeconds]. To become a candidate, the median changes by at least this
     * share of the change within that span (at least one sample interval); to be confirmed, the raw means over that
     * span either side of the jump differ by at least this share of the confirmed size. A ride is a ramp, not a jump.
     */
    val stepSharpFraction: Double = 0.5,
    /** The span a step happens within; the jump is where the raw means this long either side of it differ most. */
    val stepSharpSeconds: Double = 0.2,
    /**
     * A step is absorbed tentatively and judged this long after its jump; a step that is not a level shift at the jump
     * (the start of a lift ride or a fast climb) is handed back to the height. Must exceed [stepLevelWindowSeconds]
     * plus the median delay, so the judging window is complete and a step straight back has been seen.
     */
    val stepConfirmSeconds: Double = 2.0,
    /**
     * Raw samples this long either side of the jump (never across another step) are fitted with a line with one common
     * slope plus a shift at the jump. A ride is continuous, so its fitted shift is near zero however it bends.
     */
    val stepLevelWindowSeconds: Double = 1.0,
    /** A fitted shift of at least this is a level shift whatever follows: a ride may start right after a real step. */
    val stepJumpHpa: Double = 0.16,
    /** A smaller fitted shift, down to this share of the step (or of [stepRejectionHpa]), is one only if flat after. */
    val stepLevelShiftFraction: Double = 0.5,
    /** "Flat after": the trend from the jump until judged (or the next step), in its direction, is at most this. */
    val stepFlatAfterRateMps: Double = 0.5,
    /**
     * A step back while a step is pending closes a pulse (a door opening and closing) when the level after it is within
     * this share of the rise from the level before; both steps are kept and the offset ends where it began.
     */
    val stepPulseMatchFraction: Double = 0.5,
    val staleAfterSeconds: Double = 3.0,
    val driftTauSeconds: Double = 300.0,
    val driftQuietSeconds: Double = 20.0,
    val driftQuietRateMps: Double = 0.03,
) {
    init {
        require(minimumHpa > 0.0 && maximumHpa > minimumHpa)
        require(outOfOrderToleranceSeconds in 0.0..1.0)
        require(medianWindow in 1..15)
        require(emaTauSeconds > 0.0)
        require(gapResetSeconds > 0.0)
        require(scaleHeightMetres > 0.0)
        require(warmUpSeconds >= 0.0)
        require(rateWindowSeconds in 1.0..30.0)
        require(stepRejectionHpa > 0.0)
        require(stepWindowSeconds > 0.0)
        require(stepFlatRateMps >= 0.0)
        require(stepSharpFraction in 0.0..1.0)
        require(stepSharpSeconds > 0.0 && stepSharpSeconds < stepWindowSeconds)
        require(stepLevelWindowSeconds > 0.0 && stepConfirmSeconds > stepLevelWindowSeconds)
        require(stepJumpHpa > 0.0)
        require(stepLevelShiftFraction in 0.0..1.0)
        require(stepFlatAfterRateMps >= 0.0)
        require(stepPulseMatchFraction in 0.0..1.0)
        require(staleAfterSeconds > 0.0)
        require(driftTauSeconds > 0.0)
        require(driftQuietSeconds > 0.0)
        require(driftQuietRateMps >= 0.0)
    }
}

/** [VerticalMotionDetector]: IDLE -> MOVING -> SETTLING -> IDLE on the barometric vertical rate. */
@Serializable
data class VerticalMotionConfig(
    /** Nominal storey height until per-venue elevations exist. */
    val floorHeightMetres: Double = 3.5,
    /** How far back the segment start is taken when motion is first noticed; equals the tracker's rate window. */
    val rateWindowSeconds: Double = 4.0,
    val enterRateMps: Double = 0.12,
    val exitRateMps: Double = 0.05,
    val settleSeconds: Double = 3.0,
    val minimumFloorFraction: Double = 0.6,
    val integerTolerance: Double = 0.35,
    val liftMinimumRateMps: Double = 0.6,
    val liftMaximumSteps: Int = 2,
    /** STAIRS when at least this share of moving samples has more than [liftMaximumSteps] steps in the window. */
    val stairsStepShare: Double = 0.5,
    val maximumSegmentSeconds: Double = 180.0,
    val returnToStartMetres: Double = 0.5,
    /** Converts the smoothed pressure to height; equals the tracker's, so segments do not depend on its anchor. */
    val scaleHeightMetres: Double = 8434.0,
) {
    init {
        require(floorHeightMetres > 0.0)
        require(scaleHeightMetres > 0.0)
        require(rateWindowSeconds in 1.0..30.0)
        require(exitRateMps > 0.0 && enterRateMps > exitRateMps)
        require(settleSeconds >= 0.0)
        require(minimumFloorFraction in 0.0..1.0)
        require(integerTolerance in 0.0..0.5)
        require(liftMinimumRateMps > 0.0)
        require(liftMaximumSteps >= 0)
        require(stairsStepShare in 0.0..1.0)
        require(maximumSegmentSeconds > settleSeconds)
        require(returnToStartMetres >= 0.0)
    }
}

/** No-barometer dwell cues of [MotionContextTracker] and the share of hypotheses they seed. */
@Serializable
data class CueConfig(
    val portalWeightThreshold: Double = 0.5,
    val stairsMinimumSteps: Int = 6,
    val stairsMinimumSeconds: Double = 5.0,
    /** The stairs dwell also lasts at least this fraction of the link's travel time. */
    val stairsTravelTimeFraction: Double = 0.4,
    val liftMinimumSeconds: Double = 8.0,
    val liftMaximumSteps: Int = 1,
    val stairsSeedShare: Double = 0.2,
    val liftSeedShare: Double = 0.3,
    val cooldownSeconds: Double = 20.0,
) {
    init {
        require(portalWeightThreshold in 0.0..1.0)
        require(stairsMinimumSteps > 0)
        require(stairsMinimumSeconds >= 0.0)
        require(stairsTravelTimeFraction >= 0.0)
        require(liftMinimumSeconds > 0.0)
        require(liftMaximumSteps >= 0)
        require(stairsSeedShare in 0.0..1.0)
        require(liftSeedShare in 0.0..1.0)
        require(cooldownSeconds >= 0.0)
    }
}

/** What a barometric event or a dwell cue asks of the particle filter. */
@Serializable
data class TransitionConfig(
    val barometricShare: Double = 0.85,
    val outOfPortalLikelihood: Double = 0.1,
    val seedOutOfPortalLikelihood: Double = 1.0,
    val unverifiedFactor: Double = 0.5,
) {
    init {
        require(barometricShare in 0.0..1.0)
        require(outOfPortalLikelihood in 0.0..1.0)
        require(seedOutOfPortalLikelihood in 0.0..1.0)
        require(unverifiedFactor in 0.0..1.0)
    }
}

/** Gate on Wi-Fi that points at another floor ([ParticleFilterConfig.crossFloorLikelihood] stays as it is). */
@Serializable
data class WifiFloorConfig(
    val strongConfidence: Double = 0.68,
    val strongFloorAgreement: Double = 0.85,
    /** Consecutive strong matches needed without a healthy barometer, or during initial probation. */
    val matchesWithoutBarometer: Int = 3,
    val matchesInProbation: Int = 3,
    /** Consecutive strong matches needed when a pending barometric height agrees with the floor. */
    val matchesWithConsistentHeight: Int = 2,
    /** A pending height agrees when it is within this fraction of a floor of the expected one. */
    val pendingHeightToleranceFloors: Double = 0.5,
    val probationSeconds: Double = 120.0,
    val vetoesBeforeUncertain: Int = 4,
    val pendingWindowSeconds: Double = 120.0,
) {
    init {
        require(strongConfidence in 0.0..1.0)
        require(strongFloorAgreement in 0.0..1.0)
        require(matchesWithoutBarometer > 0)
        require(matchesInProbation > 0)
        require(matchesWithConsistentHeight > 0)
        require(pendingHeightToleranceFloors in 0.0..1.0)
        require(probationSeconds >= 0.0)
        require(vetoesBeforeUncertain > 0)
        require(pendingWindowSeconds > 0.0)
    }
}

/** Hysteresis that turns floor votes into the confirmed (displayed) floor. */
@Serializable
data class DecisionConfig(
    val switchVote: Double = 0.75,
    val switchUpdates: Int = 3,
    val switchSeconds: Double = 2.0,
    val uncertainVote: Double = 0.6,
    val hardSwitchVote: Double = 0.02,
    val announceVote: Double = 0.8,
    val changingHoldSeconds: Double = 10.0,
    val bannerSeconds: Double = 6.0,
) {
    init {
        require(switchVote in 0.5..1.0)
        require(switchUpdates > 0)
        require(switchSeconds >= 0.0)
        require(uncertainVote in 0.0..1.0)
        require(hardSwitchVote in 0.0..1.0 && hardSwitchVote < uncertainVote)
        require(announceVote in 0.0..1.0)
        require(changingHoldSeconds >= 0.0)
        require(bannerSeconds >= 0.0)
    }
}
