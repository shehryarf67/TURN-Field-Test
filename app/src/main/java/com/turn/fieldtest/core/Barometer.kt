package com.turn.fieldtest.core

import kotlin.math.abs
import kotlin.math.exp

enum class BarometerHealth { UNAVAILABLE, STALE, OK }

/**
 * Barometer reading after smoothing. [smoothedHpa] has absorbed discontinuities removed; [verticalRateMps] is
 * positive upwards; [discontinuity] marks the sample on which a door/HVAC step was confirmed and absorbed for good.
 */
data class BarometerState(
    val timestampNanos: Long,
    val smoothedHpa: Double,
    val heightSinceAnchorMetres: Double,
    val verticalRateMps: Double,
    val health: BarometerHealth,
    val discontinuity: Boolean,
)

/**
 * Turns raw pressure samples into a height relative to an anchor. It works on sensor timestamps only: samples up to
 * [BarometerConfig.outOfOrderToleranceSeconds] late join the median window, older ones are dropped. Smoothing is a
 * median then an EMA; slow weather drift is followed during quiet periods unless [driftCompensationHeld].
 *
 * Step rejection: a sharp step on flat pressure is absorbed into the offset at once, but only tentatively. A door or
 * HVAC step is a level shift, so for [BarometerConfig.stepConfirmSeconds] afterwards the raw pressure must stay at the
 * new level (no trend in the direction of the step), and one sample interval must carry most of the change. Otherwise
 * it was the start of a lift ride or a fast climb, and the step is handed back to the height retroactively, so the
 * whole ride is measured.
 */
class BarometerTracker(private val config: BarometerConfig, private val sensorPresent: Boolean) {
    private class Entry(val timestampNanos: Long, var median: Double, var ema: Double, var rateMps: Double)

    /** A tentatively absorbed step. [preLevelHpa] is raw (offset-free) pressure; [sizeHpa] is part of the offset. */
    private class PendingStep(
        val absorbedNanos: Long,
        val referenceNanos: Long,
        val postStartNanos: Long,
        val preLevelHpa: Double,
        val sizeHpa: Double,
    )

    private class Candidate(val referenceNanos: Long, val referenceHpa: Double, val postStartNanos: Long, val sizeHpa: Double)

    private val window = ArrayList<Pair<Long, Double>>()
    private val rawSamples = ArrayList<Pair<Long, Double>>()
    private val history = ArrayList<Entry>()
    private var lastTimestamp: Long? = null
    private var lastState: BarometerState? = null
    private var ema = Double.NaN
    private var offsetHpa = 0.0
    private var referenceHpa = Double.NaN
    private var followUntilNanos = Long.MIN_VALUE
    private var pending: PendingStep? = null

    /** Set while a height change is unexplained (pending); drift compensation must not erase it. */
    var driftCompensationHeld: Boolean = false

    /** Newest accepted raw reading. */
    var latestHpa: Double? = null
        private set

    val heightSinceAnchorMetres: Double get() = height()

    /** Returns the state after this sample, or null when the sample is rejected. */
    fun add(timestampNanos: Long, hpa: Double): BarometerState? {
        if (!sensorPresent || !hpa.isFinite() || hpa < config.minimumHpa || hpa > config.maximumHpa) return null
        val last = lastTimestamp
        if (last != null && timestampNanos <= last) {
            if (last - timestampNanos > nanos(config.outOfOrderToleranceSeconds)) return null
            insertSorted(window, timestampNanos to hpa)
            if (window.size > config.medianWindow) window.removeAt(0)
            insertSorted(rawSamples, timestampNanos to hpa)
            return lastState?.copy(discontinuity = false)
        }
        if (last != null && timestampNanos - last > nanos(config.gapResetSeconds)) {
            window.clear()
            rawSamples.clear()
            history.clear()
            ema = Double.NaN
            pending = null
        }
        lastTimestamp = timestampNanos
        latestHpa = hpa
        window.add(timestampNanos to hpa)
        if (window.size > config.medianWindow) window.removeAt(0)
        rawSamples.add(timestampNanos to hpa)

        val rawHpa = median(window.map { it.second })
        val open = pending
        val candidate = stepCandidate(timestampNanos, rawHpa - offsetHpa)
            ?.takeIf { open == null || (it.sizeHpa > 0.0) != (open.sizeHpa > 0.0) }
        var discontinuity = false
        if (open != null) {
            if (candidate != null) {
                // A step straight back (a door opening and closing) shows the first one was a level shift.
                confirm(open, refine = false)
                discontinuity = true
            } else if (timestampNanos >= open.postStartNanos + nanos(config.stepConfirmSeconds)) {
                if (isLevelShift(open)) {
                    confirm(open, refine = true)
                    discontinuity = true
                } else {
                    revert(open)
                }
            }
        }
        if (candidate != null) {
            pending = PendingStep(timestampNanos, candidate.referenceNanos, candidate.postStartNanos,
                candidate.referenceHpa + offsetHpa, candidate.sizeHpa)
            offsetHpa += candidate.sizeHpa
        }
        val corrected = rawHpa - offsetHpa

        val previous = history.lastOrNull()
        val dt = previous?.let { (timestampNanos - it.timestampNanos) / 1e9 } ?: 0.0
        ema = if (ema.isNaN() || previous == null) corrected else ema + (1.0 - exp(-dt / config.emaTauSeconds)) * (corrected - ema)
        if (referenceHpa.isNaN()) followUntilNanos = timestampNanos + nanos(config.warmUpSeconds)
        if (timestampNanos <= followUntilNanos) referenceHpa = ema
        compensateDrift(timestampNanos, dt)
        val rate = verticalRate(timestampNanos, ema, history.size)
        history.add(Entry(timestampNanos, corrected, ema, rate))
        val keep = nanos(maxOf(config.driftQuietSeconds, config.rateWindowSeconds + config.stepWindowSeconds + config.stepConfirmSeconds) + 1.0)
        prune(history, timestampNanos - keep) { it.timestampNanos }
        prune(rawSamples, timestampNanos - keep) { it.first }
        return BarometerState(timestampNanos, ema, height(), rate, BarometerHealth.OK, discontinuity).also { lastState = it }
    }

    /**
     * Zeroes the height at the current smoothed pressure. Before this is first called, the anchor follows the
     * smoothed pressure for [BarometerConfig.warmUpSeconds] after the first sample, so one noisy sample is not the zero.
     */
    fun anchor(timestampNanos: Long) {
        require(timestampNanos >= 0L)
        if (ema.isNaN()) return
        referenceHpa = ema
        followUntilNanos = Long.MIN_VALUE
    }

    fun health(nowNanos: Long): BarometerHealth {
        if (!sensorPresent) return BarometerHealth.UNAVAILABLE
        val last = lastTimestamp ?: return BarometerHealth.STALE
        return if (nowNanos - last > nanos(config.staleAfterSeconds)) BarometerHealth.STALE else BarometerHealth.OK
    }

    /** A sharp step on flat pressure that may be a discontinuity, or null. [corrected] is the offset-free median. */
    private fun stepCandidate(timestampNanos: Long, corrected: Double): Candidate? {
        val reference = history.lastOrNull { it.timestampNanos <= timestampNanos - nanos(config.stepWindowSeconds) } ?: return null
        val jump = corrected - reference.ema
        if (abs(jump) <= config.stepRejectionHpa || abs(reference.rateMps) > config.stepFlatRateMps) return null
        val recent = history.filter { it.timestampNanos >= reference.timestampNanos }.map { it.median } + corrected
        val sharpest = recent.zipWithNext { a, b -> abs(b - a) }.maxOrNull() ?: 0.0
        if (sharpest < config.stepSharpFraction * abs(jump)) return null
        // The median sits at an extreme of the post-step samples; their mean is an unbiased size of the step.
        val afterStep = window.filter { abs(it.second - offsetHpa - reference.ema) > abs(jump) / 2.0 }
        val size = afterStep.map { it.second - offsetHpa }.average() - reference.ema
        return Candidate(reference.timestampNanos, reference.ema, afterStep.minOf { it.first }, size)
    }

    /**
     * A level shift stays flat after the step (a ride keeps trending the same way) and is sharp: the raw samples
     * just either side of the largest jump already differ by most of the change from the level before to the level
     * after. A ride is a ramp, so even a short fast one spreads its change over several samples.
     */
    private fun isLevelShift(step: PendingStep): Boolean {
        val after = rawSamples.filter { it.first >= step.postStartNanos }
        if (after.size < 2) return true
        val sign = if (step.sizeHpa > 0.0) 1.0 else -1.0
        val trendMps = sign * slopePerSecond(after) * metresPerHpa(after.first().second)
        if (trendMps > config.stepFlatAfterRateMps) return false
        val change = after.map { it.second }.average() - step.preLevelHpa
        val around = rawSamples.filter { it.first >= step.referenceNanos }.map { it.second }
        if (around.size < 2) return true
        val jumpAt = (1 until around.size).maxBy { abs(around[it] - around[it - 1]) }
        val before = around.subList(maxOf(0, jumpAt - SHIFT_SAMPLES), jumpAt).average()
        val afterJump = around.subList(jumpAt, minOf(around.size, jumpAt + SHIFT_SAMPLES)).average()
        return sign * (afterJump - before) >= config.stepLevelShiftFraction * abs(change)
    }

    /** Keeps the step absorbed; with [refine], re-sizes it from the level the raw pressure settled at. */
    private fun confirm(step: PendingStep, refine: Boolean) {
        pending = null
        if (!refine) return
        val after = rawSamples.filter { it.first >= step.postStartNanos }
        if (after.isEmpty()) return
        val delta = after.map { it.second }.average() - step.preLevelHpa - step.sizeHpa
        offsetHpa += delta
        shiftSince(step.absorbedNanos, -delta)
    }

    /** Hands a tentatively absorbed step back to the height, as if it had never been absorbed. */
    private fun revert(step: PendingStep) {
        pending = null
        offsetHpa -= step.sizeHpa
        shiftSince(step.absorbedNanos, step.sizeHpa)
    }

    /** Adds [deltaHpa] to the corrected pressure from [fromNanos] on, so rates and drift see a continuous signal. */
    private fun shiftSince(fromNanos: Long, deltaHpa: Double) {
        if (!ema.isNaN()) ema += deltaHpa
        val first = history.indexOfFirst { it.timestampNanos >= fromNanos }
        if (first < 0) return
        for (i in first until history.size) {
            history[i].median += deltaHpa
            history[i].ema += deltaHpa
        }
        for (i in first until history.size) history[i].rateMps = verticalRate(history[i].timestampNanos, history[i].ema, i)
    }

    private fun compensateDrift(timestampNanos: Long, dt: Double) {
        if (driftCompensationHeld || dt <= 0.0) return
        val start = history.lastOrNull { it.timestampNanos <= timestampNanos - nanos(config.driftQuietSeconds) } ?: return
        val rateHpaPerSecond = (ema - start.ema) / ((timestampNanos - start.timestampNanos) / 1e9)
        if (abs(rateHpaPerSecond) * metresPerHpa(ema) > config.driftQuietRateMps) return
        referenceHpa += rateHpaPerSecond * dt
        referenceHpa += (ema - referenceHpa) * (1.0 - exp(-dt / config.driftTauSeconds))
    }

    /** The rate at [timestampNanos] with smoothed pressure [emaHpa], measured against the first [before] history entries. */
    private fun verticalRate(timestampNanos: Long, emaHpa: Double, before: Int): Double {
        val earlier = history.subList(0, before)
        val start = earlier.lastOrNull { it.timestampNanos <= timestampNanos - nanos(config.rateWindowSeconds) }
            ?: earlier.firstOrNull()?.takeIf { timestampNanos - it.timestampNanos >= ONE_SECOND } ?: return 0.0
        return -(emaHpa - start.ema) * metresPerHpa(emaHpa) / ((timestampNanos - start.timestampNanos) / 1e9)
    }

    private fun height(): Double =
        if (ema.isNaN() || referenceHpa.isNaN()) 0.0 else -(ema - referenceHpa) * metresPerHpa(ema)

    /** Metres per hPa at this pressure: P / scale height hPa per metre, about 0.112 hPa/m at 945 hPa. */
    private fun metresPerHpa(hpa: Double) = config.scaleHeightMetres / hpa

    private companion object {
        const val ONE_SECOND = 1_000_000_000L

        /** Raw samples averaged on each side of the largest jump when confirming a level shift. */
        const val SHIFT_SAMPLES = 3
        fun nanos(seconds: Double) = (seconds * 1e9).toLong()
        fun median(values: List<Double>): Double {
            val sorted = values.sorted()
            return if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
        }

        fun insertSorted(list: MutableList<Pair<Long, Double>>, sample: Pair<Long, Double>) =
            list.add(list.indexOfFirst { it.first > sample.first }.let { if (it < 0) list.size else it }, sample)

        /** Drops leading items while the second one is no newer than [cutoffNanos], keeping one sample before it. */
        fun <T> prune(list: MutableList<T>, cutoffNanos: Long, timestamp: (T) -> Long) {
            val drop = (1 until list.size).firstOrNull { timestamp(list[it]) > cutoffNanos }?.minus(1) ?: (list.size - 1).coerceAtLeast(0)
            if (drop > 0) list.subList(0, drop).clear()
        }

        /** Least-squares slope of the pressure over time, in hPa per second. */
        fun slopePerSecond(samples: List<Pair<Long, Double>>): Double {
            val t0 = samples.first().first
            val ts = samples.map { (it.first - t0) / 1e9 }
            val meanT = ts.average()
            val meanP = samples.map { it.second }.average()
            val sxx = ts.sumOf { (it - meanT) * (it - meanT) }
            if (sxx <= 0.0) return 0.0
            return ts.indices.sumOf { (ts[it] - meanT) * (samples[it].second - meanP) } / sxx
        }
    }
}
