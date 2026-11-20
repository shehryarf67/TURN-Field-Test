package com.turn.fieldtest.core

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

enum class VerticalDirection { UP, DOWN, UNKNOWN }

/** STAIRS matches stairs, escalators and ramps; LIFT matches lifts; ANY matches every link kind. */
enum class VerticalKindHint { STAIRS, LIFT, ANY }

/** A settled barometric height change; [floorCandidates] are floor counts (never 0), two when ambiguous. */
data class VerticalEvent(
    val direction: VerticalDirection,
    val floorCandidates: Set<Int>,
    val kind: VerticalKindHint,
    val heightDeltaMetres: Double,
    val startNanos: Long,
    val endNanos: Long,
) {
    init {
        require(floorCandidates.isNotEmpty() && floorCandidates.all { it > 0 })
        require(heightDeltaMetres.isFinite())
        require(endNanos >= startNanos)
    }
}

/**
 * Finds floor changes in the barometric height: IDLE -> MOVING when |rate| reaches the enter threshold, MOVING ->
 * SETTLING when it drops to the exit threshold, and an event once settling has held for the settle time; only a
 * rate back at the enter threshold resumes MOVING (hysteresis against rate noise). Call [reset] after re-anchoring
 * the barometer mid-segment.
 */
class VerticalMotionDetector(private val config: VerticalMotionConfig) {
    /** BLOCKED follows an over-long segment: nothing starts until the rate has dropped to the exit threshold. */
    private enum class Phase { IDLE, MOVING, SETTLING, BLOCKED }

    private val heights = ArrayDeque<Pair<Long, Double>>()
    private var phase = Phase.IDLE
    private var startNanos = 0L
    private var startHeight = 0.0
    private var settleSince = 0L
    private var peakRate = 0.0
    private var maxSteps = 0
    private var movingSamples = 0
    private var walkingSamples = 0

    /** True while a segment is open (moving or settling). */
    val moving: Boolean get() = phase == Phase.MOVING || phase == Phase.SETTLING

    /** Provisional kind of the open segment, null when idle. */
    val movingKind: VerticalKindHint? get() = if (moving) kind() else null

    /** [stepsInWindow] is the number of accepted steps in the rate window ending at the sample's timestamp. */
    fun onBarometer(state: BarometerState, stepsInWindow: Int): VerticalEvent? {
        require(stepsInWindow >= 0)
        val now = state.timestampNanos
        val height = state.heightSinceAnchorMetres
        val rate = abs(state.verticalRateMps)
        heights.addLast(now to height)
        val lookback = nanos(config.rateWindowSeconds)
        while (heights.size >= 2 && heights[1].first <= now - lookback) heights.removeFirst()
        when (phase) {
            Phase.IDLE -> if (rate >= config.enterRateMps) {
                val start = heights.first()
                startNanos = start.first
                startHeight = start.second
                peakRate = 0.0
                maxSteps = 0
                movingSamples = 0
                walkingSamples = 0
                phase = Phase.MOVING
                accumulate(rate, stepsInWindow)
            }
            Phase.BLOCKED -> if (rate <= config.exitRateMps) phase = Phase.IDLE
            Phase.MOVING, Phase.SETTLING -> {
                if (now - startNanos > nanos(config.maximumSegmentSeconds)) {
                    phase = Phase.BLOCKED
                    return null
                }
                if (phase == Phase.MOVING) {
                    accumulate(rate, stepsInWindow)
                    if (rate <= config.exitRateMps) {
                        phase = Phase.SETTLING
                        settleSince = now
                    }
                } else if (rate >= config.enterRateMps) {
                    phase = Phase.MOVING
                    accumulate(rate, stepsInWindow)
                } else if (now - settleSince >= nanos(config.settleSeconds)) {
                    return finish(now, height)
                }
            }
        }
        return null
    }

    fun reset() {
        heights.clear()
        phase = Phase.IDLE
    }

    /**
     * Kind statistics. Steps count toward the lift test only at lift speed: the smoothed rate lags the ride by about a
     * second, so samples on its tail already see the user walking out.
     */
    private fun accumulate(rate: Double, steps: Int) {
        peakRate = maxOf(peakRate, rate)
        if (rate >= config.liftMinimumRateMps) maxSteps = maxOf(maxSteps, steps)
        if (rate < config.enterRateMps) return
        movingSamples++
        if (steps > config.liftMaximumSteps) walkingSamples++
    }

    private fun kind() = when {
        peakRate >= config.liftMinimumRateMps && maxSteps <= config.liftMaximumSteps -> VerticalKindHint.LIFT
        movingSamples > 0 && walkingSamples >= config.stairsStepShare * movingSamples -> VerticalKindHint.STAIRS
        else -> VerticalKindHint.ANY
    }

    private fun finish(now: Long, height: Double): VerticalEvent? {
        phase = Phase.IDLE
        val delta = height - startHeight
        if (abs(delta) <= config.returnToStartMetres) return null
        val floors = abs(delta) / config.floorHeightMetres
        if (floors < config.minimumFloorFraction) return null
        val nearest = floors.roundToInt()
        val candidates = if (abs(floors - nearest) > config.integerTolerance) {
            setOf(floor(floors).toInt(), ceil(floors).toInt()) - 0
        } else {
            setOf(maxOf(1, nearest))
        }
        val direction = if (delta > 0.0) VerticalDirection.UP else VerticalDirection.DOWN
        return VerticalEvent(direction, candidates, kind(), delta, startNanos, now)
    }
}

/** A no-barometer hint that much of the filter weight dwelt in links of this [kind]. */
data class DwellCue(val kind: VerticalKindHint, val portalIndices: Set<Int>, val atNanos: Long) {
    init {
        require(kind != VerticalKindHint.ANY)
        require(portalIndices.isNotEmpty())
    }
}

/** What [MotionContextTracker] needs to know about a portal (index as in the session's floor topology). */
data class CuePortal(val kind: ConnectorKind, val travelTimeSeconds: Double) {
    init {
        require(travelTimeSeconds.isFinite() && travelTimeSeconds >= 0.0)
    }
}

/**
 * No-barometer dwell cues. Stairs: enough consecutive steps for long enough with most weight in stairs, escalator or
 * ramp portals. Lift: a long, nearly step-free dwell in a lift portal, reported on the first step out. Each cue is
 * followed by a cooldown. `portalWeight` maps portal index to the filter weight captured there (now or in memory).
 */
class MotionContextTracker(private val config: CueConfig, private val portals: Map<Int, CuePortal>) {
    private var cooldownUntil = Long.MIN_VALUE
    private var runStart: Long? = null
    private var runSteps = 0
    private val runPortals = mutableSetOf<Int>()
    private var liftSince: Long? = null
    private var liftSteps = 0
    private val liftPortals = mutableSetOf<Int>()

    fun onStep(tsNanos: Long, portalWeight: Map<Int, Double>): DwellCue? {
        val since = liftSince
        if (since != null && tsNanos - since >= nanos(config.liftMinimumSeconds) && liftSteps <= config.liftMaximumSteps) {
            val cue = DwellCue(VerticalKindHint.LIFT, liftPortals.toSet(), tsNanos)
            val coolingDown = tsNanos < cooldownUntil
            clearDwell()
            if (!coolingDown) {
                cooldownUntil = tsNanos + nanos(config.cooldownSeconds)
                return cue
            }
        }
        if (liftSince != null && ++liftSteps > config.liftMaximumSteps) clearLift()
        updateLift(tsNanos, portalWeight)

        val stairs = captured(portalWeight) { it != ConnectorKind.LIFT }
        if (stairs.sumOf { portalWeight.getValue(it) } < config.portalWeightThreshold) {
            clearRun()
            return null
        }
        val start = runStart ?: tsNanos.also { runStart = it }
        runSteps++
        runPortals += stairs
        val travel = runPortals.minOf { portals.getValue(it).travelTimeSeconds }
        val needed = maxOf(config.stairsMinimumSeconds, config.stairsTravelTimeFraction * travel)
        if (runSteps < config.stairsMinimumSteps || tsNanos - start < nanos(needed) || tsNanos < cooldownUntil) return null
        val cue = DwellCue(VerticalKindHint.STAIRS, runPortals.toSet(), tsNanos)
        clearDwell()
        cooldownUntil = tsNanos + nanos(config.cooldownSeconds)
        return cue
    }

    /** Lift stillness accumulates between steps; call at up to about 1 Hz. */
    fun onTick(nowNanos: Long, portalWeight: Map<Int, Double>) = updateLift(nowNanos, portalWeight)

    /** Forgets dwells and the cooldown, e.g. after an absolute fix. */
    fun reset() {
        clearDwell()
        cooldownUntil = Long.MIN_VALUE
    }

    private fun clearDwell() {
        clearRun()
        clearLift()
    }

    private fun updateLift(nowNanos: Long, portalWeight: Map<Int, Double>) {
        val lift = captured(portalWeight) { it == ConnectorKind.LIFT }
        if (lift.sumOf { portalWeight.getValue(it) } < config.portalWeightThreshold) {
            clearLift()
            return
        }
        if (liftSince == null) {
            liftSince = nowNanos
            liftSteps = 0
        }
        liftPortals += lift
    }

    private fun captured(portalWeight: Map<Int, Double>, kind: (ConnectorKind) -> Boolean) =
        portalWeight.filter { (index, weight) -> weight > 0.0 && portals[index]?.kind?.let(kind) == true }.keys

    private fun clearRun() {
        runStart = null
        runSteps = 0
        runPortals.clear()
    }

    private fun clearLift() {
        liftSince = null
        liftSteps = 0
        liftPortals.clear()
    }
}

private fun nanos(seconds: Double) = (seconds * 1e9).toLong()
