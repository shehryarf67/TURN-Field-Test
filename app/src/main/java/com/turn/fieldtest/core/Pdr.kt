package com.turn.fieldtest.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

enum class StepSource {
    STEP_DETECTOR,
    STEP_COUNTER,
    ACCELEROMETER_FALLBACK,
}

data class StepSignal(
    val timestampNanos: Long,
    val source: StepSource,
    /** Cumulative device counter, only meaningful for [StepSource.STEP_COUNTER]. */
    val cumulativeCounter: Long? = null,
) {
    init {
        require(timestampNanos >= 0L) { "Step timestamp must not be negative" }
        if (source == StepSource.STEP_COUNTER) {
            require(cumulativeCounter != null && cumulativeCounter >= 0L) {
                "Step-counter signals need a non-negative cumulative value"
            }
        }
    }
}

enum class StepRejectionReason {
    NONE,
    NO_MOVEMENT_SOURCE,
    NON_PRIMARY_SOURCE,
    COUNTER_VALIDATION_ONLY,
    DUPLICATE_WITHIN_REFRACTORY_PERIOD,
    OUT_OF_ORDER,
}

data class StepDecision(
    val acceptedForMovement: Boolean,
    val reason: StepRejectionReason,
    val activeMovementSource: StepSource?,
    val validatedCounterSteps: Long,
)

/**
 * Selects exactly one movement source. The step counter validates session totals by default and
 * can only drive movement when explicitly opted in and no preferred detector/fallback exists.
 */
class StepProcessor(
    availableSources: Set<StepSource>,
    private val refractoryPeriodNanos: Long = 250_000_000L,
    private val allowCounterAsMovement: Boolean = false,
) {
    init {
        require(refractoryPeriodNanos >= 0L)
    }

    private val sources = availableSources.toSet()
    val activeMovementSource: StepSource? = when {
        StepSource.STEP_DETECTOR in sources -> StepSource.STEP_DETECTOR
        StepSource.ACCELEROMETER_FALLBACK in sources -> StepSource.ACCELEROMETER_FALLBACK
        allowCounterAsMovement && StepSource.STEP_COUNTER in sources -> StepSource.STEP_COUNTER
        else -> null
    }

    private var lastAcceptedTimestampNanos: Long? = null
    private var counterBaseline: Long? = null
    private var mostRecentCounter: Long? = null

    val validatedCounterSteps: Long
        get() {
            val baseline = counterBaseline ?: return 0L
            return ((mostRecentCounter ?: baseline) - baseline).coerceAtLeast(0L)
        }

    fun process(signal: StepSignal): StepDecision {
        if (signal.source == StepSource.STEP_COUNTER) updateCounter(signal.cumulativeCounter!!)
        val active = activeMovementSource
            ?: return decision(false, StepRejectionReason.NO_MOVEMENT_SOURCE)
        if (signal.source != active) {
            val reason = if (signal.source == StepSource.STEP_COUNTER) {
                StepRejectionReason.COUNTER_VALIDATION_ONLY
            } else {
                StepRejectionReason.NON_PRIMARY_SOURCE
            }
            return decision(false, reason)
        }

        val previous = lastAcceptedTimestampNanos
        if (previous != null && signal.timestampNanos < previous) {
            return decision(false, StepRejectionReason.OUT_OF_ORDER)
        }
        if (previous != null && signal.timestampNanos - previous <= refractoryPeriodNanos) {
            return decision(false, StepRejectionReason.DUPLICATE_WITHIN_REFRACTORY_PERIOD)
        }
        lastAcceptedTimestampNanos = signal.timestampNanos
        return decision(true, StepRejectionReason.NONE)
    }

    fun reset() {
        lastAcceptedTimestampNanos = null
        counterBaseline = null
        mostRecentCounter = null
    }

    private fun updateCounter(value: Long) {
        val previous = mostRecentCounter
        if (previous != null && value < previous) {
            // A reboot/sensor reset starts a new validation baseline; it never generates steps.
            counterBaseline = value
        } else if (counterBaseline == null) {
            counterBaseline = value
        }
        mostRecentCounter = value
    }

    private fun decision(accepted: Boolean, reason: StepRejectionReason): StepDecision = StepDecision(
        acceptedForMovement = accepted,
        reason = reason,
        activeMovementSource = activeMovementSource,
        validatedCounterSteps = validatedCounterSteps,
    )
}

data class StrideModel(
    val baseStrideMetres: Double = 0.75,
    val sessionScale: Double = 1.0,
) {
    init {
        require(baseStrideMetres.isFinite() && baseStrideMetres > 0.0)
        require(sessionScale.isFinite() && sessionScale in MIN_SCALE..MAX_SCALE)
    }

    val currentStrideMetres: Double get() = baseStrideMetres * sessionScale

    fun calibrated(knownDistanceMetres: Double, acceptedSteps: Int): StrideModel {
        require(knownDistanceMetres.isFinite() && knownDistanceMetres > 0.0)
        require(acceptedSteps > 0)
        val measuredStride = knownDistanceMetres / acceptedSteps
        return copy(sessionScale = (measuredStride / baseStrideMetres).coerceIn(MIN_SCALE, MAX_SCALE))
    }

    companion object {
        const val MIN_SCALE: Double = 0.6
        const val MAX_SCALE: Double = 1.4

        /** Conservative initial estimate; field calibration should supersede it. */
        fun fromHeight(heightMetres: Double): StrideModel {
            require(heightMetres.isFinite() && heightMetres in 1.0..2.5)
            return StrideModel(baseStrideMetres = heightMetres * 0.413)
        }
    }
}

data class PdrState(
    val position: MetricPoint,
    /** Relative map heading: 0 along +x, PI/2 along +y. */
    val headingRadians: Double,
    val acceptedStepCount: Int = 0,
    val estimatedDistanceMetres: Double = 0.0,
)

data class PdrStepEvent(
    val signal: StepSignal,
    val decision: StepDecision,
    val stateBefore: PdrState,
    val stateAfter: PdrState,
    val strideMetres: Double,
)

/** Frequent relative-motion tracker. Absolute correction belongs to the particle filter. */
class PdrTracker(
    initialPosition: MetricPoint,
    initialHeadingRadians: Double,
    val stepProcessor: StepProcessor,
    var strideModel: StrideModel = StrideModel(),
) {
    var state: PdrState = PdrState(initialPosition, normalizeRadians(initialHeadingRadians))
        private set

    fun setHeading(headingRadians: Double) {
        require(headingRadians.isFinite())
        state = state.copy(headingRadians = normalizeRadians(headingRadians))
    }

    fun applyRelativeHeadingChange(deltaRadians: Double) {
        require(deltaRadians.isFinite())
        setHeading(state.headingRadians + deltaRadians)
    }

    fun processStep(signal: StepSignal, headingRadians: Double = state.headingRadians): PdrStepEvent {
        require(headingRadians.isFinite())
        val before = state
        val decision = stepProcessor.process(signal)
        if (decision.acceptedForMovement) {
            val heading = normalizeRadians(headingRadians)
            val stride = strideModel.currentStrideMetres
            state = PdrState(
                position = MetricPoint(
                    x = before.position.x + stride * cos(heading),
                    y = before.position.y + stride * sin(heading),
                ),
                headingRadians = heading,
                acceptedStepCount = before.acceptedStepCount + 1,
                estimatedDistanceMetres = before.estimatedDistanceMetres + stride,
            )
        }
        return PdrStepEvent(
            signal = signal,
            decision = decision,
            stateBefore = before,
            stateAfter = state,
            strideMetres = if (decision.acceptedForMovement) strideModel.currentStrideMetres else 0.0,
        )
    }

    fun reset(position: MetricPoint, headingRadians: Double) {
        stepProcessor.reset()
        state = PdrState(position, normalizeRadians(headingRadians))
    }
}

fun normalizeRadians(angle: Double): Double {
    require(angle.isFinite())
    var normalized = angle % (2.0 * PI)
    if (normalized <= -PI) normalized += 2.0 * PI
    if (normalized > PI) normalized -= 2.0 * PI
    return normalized
}
