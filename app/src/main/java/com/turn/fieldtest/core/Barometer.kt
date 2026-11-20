package com.turn.fieldtest.core

import kotlin.math.abs
import kotlin.math.exp

enum class BarometerHealth { UNAVAILABLE, STALE, OK }

/**
 * Barometer reading after smoothing. [smoothedHpa] has absorbed discontinuities removed; [verticalRateMps] is
 * positive upwards; [discontinuity] marks the sample on which a door/HVAC step was absorbed.
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
 * median then an EMA; sharp steps on flat pressure are absorbed as discontinuities; slow weather drift is followed
 * during quiet periods unless [driftCompensationHeld].
 */
class BarometerTracker(private val config: BarometerConfig, private val sensorPresent: Boolean) {
    private data class Entry(val timestampNanos: Long, val median: Double, val ema: Double, val rateMps: Double)

    private val window = ArrayList<Pair<Long, Double>>()
    private val history = ArrayDeque<Entry>()
    private var lastTimestamp: Long? = null
    private var lastState: BarometerState? = null
    private var ema = Double.NaN
    private var offsetHpa = 0.0
    private var referenceHpa = Double.NaN
    private var followUntilNanos = Long.MIN_VALUE

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
            window.add(window.indexOfFirst { it.first > timestampNanos }.let { if (it < 0) window.size else it }, timestampNanos to hpa)
            if (window.size > config.medianWindow) window.removeAt(0)
            return lastState?.copy(discontinuity = false)
        }
        if (last != null && timestampNanos - last > nanos(config.gapResetSeconds)) {
            window.clear()
            history.clear()
            ema = Double.NaN
        }
        lastTimestamp = timestampNanos
        latestHpa = hpa
        window.add(timestampNanos to hpa)
        if (window.size > config.medianWindow) window.removeAt(0)

        val raw = median(window.map { it.second }) - offsetHpa
        val step = absorbedStep(timestampNanos, raw)
        val corrected = raw - (step ?: 0.0)
        val discontinuity = step != null

        val previous = history.lastOrNull()
        val dt = previous?.let { (timestampNanos - it.timestampNanos) / 1e9 } ?: 0.0
        ema = if (ema.isNaN() || previous == null) corrected else ema + (1.0 - exp(-dt / config.emaTauSeconds)) * (corrected - ema)
        if (referenceHpa.isNaN()) followUntilNanos = timestampNanos + nanos(config.warmUpSeconds)
        if (timestampNanos <= followUntilNanos) referenceHpa = ema
        compensateDrift(timestampNanos, dt)
        val rate = verticalRate(timestampNanos)
        history.addLast(Entry(timestampNanos, corrected, ema, rate))
        val keep = nanos(maxOf(config.driftQuietSeconds, config.rateWindowSeconds + config.stepWindowSeconds) + 1.0)
        while (history.size >= 2 && history[1].timestampNanos <= timestampNanos - keep) history.removeFirst()
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

    /** The step to absorb into the offset, or null when this change is not a sharp step on flat pressure. */
    private fun absorbedStep(timestampNanos: Long, corrected: Double): Double? {
        val reference = history.lastOrNull { it.timestampNanos <= timestampNanos - nanos(config.stepWindowSeconds) } ?: return null
        val jump = corrected - reference.ema
        if (abs(jump) <= config.stepRejectionHpa || abs(reference.rateMps) > config.stepFlatRateMps) return null
        val recent = history.filter { it.timestampNanos >= reference.timestampNanos }.map { it.median } + corrected
        val sharpest = recent.zipWithNext { a, b -> abs(b - a) }.maxOrNull() ?: 0.0
        if (sharpest < config.stepSharpFraction * abs(jump)) return null
        // The median sits at an extreme of the post-step samples; their mean is an unbiased size of the step.
        val afterStep = window.map { it.second - offsetHpa }.filter { abs(it - reference.ema) > abs(jump) / 2.0 }
        val size = afterStep.average() - reference.ema
        offsetHpa += size
        return size
    }

    private fun compensateDrift(timestampNanos: Long, dt: Double) {
        if (driftCompensationHeld || dt <= 0.0) return
        val start = history.lastOrNull { it.timestampNanos <= timestampNanos - nanos(config.driftQuietSeconds) } ?: return
        val rateHpaPerSecond = (ema - start.ema) / ((timestampNanos - start.timestampNanos) / 1e9)
        if (abs(rateHpaPerSecond) * metresPerHpa(ema) > config.driftQuietRateMps) return
        referenceHpa += rateHpaPerSecond * dt
        referenceHpa += (ema - referenceHpa) * (1.0 - exp(-dt / config.driftTauSeconds))
    }

    private fun verticalRate(timestampNanos: Long): Double {
        val start = history.lastOrNull { it.timestampNanos <= timestampNanos - nanos(config.rateWindowSeconds) }
            ?: history.firstOrNull()?.takeIf { timestampNanos - it.timestampNanos >= ONE_SECOND } ?: return 0.0
        return -(ema - start.ema) * metresPerHpa(ema) / ((timestampNanos - start.timestampNanos) / 1e9)
    }

    private fun height(): Double =
        if (ema.isNaN() || referenceHpa.isNaN()) 0.0 else -(ema - referenceHpa) * metresPerHpa(ema)

    /** Metres per hPa at this pressure: P / scale height hPa per metre, about 0.112 hPa/m at 945 hPa. */
    private fun metresPerHpa(hpa: Double) = config.scaleHeightMetres / hpa

    private companion object {
        const val ONE_SECOND = 1_000_000_000L
        fun nanos(seconds: Double) = (seconds * 1e9).toLong()
        fun median(values: List<Double>): Double {
            val sorted = values.sorted()
            return if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
        }
    }
}
