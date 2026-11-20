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
 * Step rejection: a sharp step on flat pressure is absorbed into the offset as soon as the median sees it, but only
 * tentatively; its jump is located on the raw samples. [BarometerConfig.stepConfirmSeconds] after the jump it is judged
 * by the jump itself, on raw samples that never reach back across an earlier step. It must be sharp (most of it within
 * [BarometerConfig.stepSharpSeconds]) and, within [BarometerConfig.stepLevelWindowSeconds] either side, a line with a
 * common slope plus a shift at the jump must put a clear shift there. A lift ride or fast climb is continuous, so that
 * fitted shift is near zero however the ride bends, while a door or HVAC step keeps its full size even when a ride
 * starts right after it. Only a borderline shift also needs the pressure to be flat around it. A step that fails is
 * handed back to the height retroactively, so the whole ride is measured. A step back to the level before a
 * still-pending step closes a pulse (a door opening and closing): both are kept and cancel exactly; if the step back
 * never became a candidate, the pulse is handed back whole instead. All windows are in seconds, so the behaviour does
 * not depend on the sensor's sample rate.
 */
class BarometerTracker(private val config: BarometerConfig, private val sensorPresent: Boolean) {
    private class Entry(val timestampNanos: Long, var median: Double, var ema: Double, var rateMps: Double)

    /** A tentatively absorbed step: [jumpNanos] is the first raw sample after the jump; [sizeHpa] is part of the offset. */
    private class PendingStep(val absorbedNanos: Long, val jumpNanos: Long, val sizeHpa: Double) {
        val sign get() = if (sizeHpa > 0.0) 1.0 else -1.0
    }

    private class Candidate(val jumpNanos: Long, val sizeHpa: Double)

    /** Change of the raw-pressure means across a jump; [fittedShiftHpa] is what is left once a common slope is removed. */
    private class LevelShift(val changeHpa: Double, val fittedShiftHpa: Double)

    private val window = ArrayList<Pair<Long, Double>>()
    private val rawSamples = ArrayList<Pair<Long, Double>>()
    private val history = ArrayList<Entry>()
    private var lastTimestamp: Long? = null
    private var lastState: BarometerState? = null
    private var ema = Double.NaN
    private var offsetHpa = 0.0
    private var referenceHpa = Double.NaN
    private var followUntilNanos = Long.MIN_VALUE
    private val pending = ArrayList<PendingStep>()

    /** Jump time of the newest confirmed step; no judging window reaches back across it. */
    private var lastStepNanos = Long.MIN_VALUE

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
            pending.clear()
            lastStepNanos = Long.MIN_VALUE
        }
        lastTimestamp = timestampNanos
        latestHpa = hpa
        window.add(timestampNanos to hpa)
        if (window.size > config.medianWindow) window.removeAt(0)
        rawSamples.add(timestampNanos to hpa)

        val discontinuity = settle(timestampNanos)
        val rawHpa = median(window.map { it.second })
        val candidate = stepCandidate(timestampNanos, rawHpa - offsetHpa)
            ?.takeIf { pending.isEmpty() || (it.sizeHpa > 0.0) != (pending.last().sizeHpa > 0.0) }
        if (candidate != null) {
            pending += PendingStep(timestampNanos, candidate.jumpNanos, candidate.sizeHpa)
            offsetHpa += candidate.sizeHpa
        }
        val corrected = rawHpa - offsetHpa

        val previous = history.lastOrNull()
        val dt = previous?.let { (timestampNanos - it.timestampNanos) / 1e9 } ?: 0.0
        ema = if (ema.isNaN() || previous == null) corrected else ema + alpha(dt) * (corrected - ema)
        if (referenceHpa.isNaN()) followUntilNanos = timestampNanos + nanos(config.warmUpSeconds)
        if (timestampNanos <= followUntilNanos) referenceHpa = ema
        compensateDrift(timestampNanos, dt)
        val rate = verticalRate(timestampNanos, ema, history.size)
        history.add(Entry(timestampNanos, corrected, ema, rate))
        // Raw samples reach back to the stepConfirmSeconds before a step whose judgement waited for the step back.
        val stepSpan = config.rateWindowSeconds + config.stepWindowSeconds + 3 * config.stepConfirmSeconds
        val keep = nanos(maxOf(config.driftQuietSeconds, stepSpan) + 1.0)
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
        val span = nanos(config.stepWindowSeconds)
        val reference = history.lastOrNull { it.timestampNanos <= timestampNanos - span } ?: return null
        val jump = corrected - reference.ema
        if (abs(jump) <= config.stepRejectionHpa || abs(reference.rateMps) > config.stepFlatRateMps) return null
        val recent = history.filter { it.timestampNanos >= reference.timestampNanos }.map { it.timestampNanos to it.median }
        if (sharpestChange(recent + (timestampNanos to corrected)) < config.stepSharpFraction * abs(jump)) return null
        // The median may show a step late (a noisy sample can split it), so the jump is searched for from the pending
        // step it would close, or from up to stepWindowSeconds before the reference, never before the last step.
        val from = pending.lastOrNull()?.jumpNanos ?: maxOf(lastStepNanos, reference.timestampNanos - span)
        val jumpAt = locateJump(from, Long.MAX_VALUE, if (jump > 0.0) 1.0 else -1.0) ?: return null
        val jumpNanos = rawSamples[jumpAt].first
        // The median sits at an extreme of the post-step samples; raw means either side of the jump size it unbiased.
        val before = rawSamples.filter { it.first in from until jumpNanos }
        val after = rawSamples.filter { it.first >= jumpNanos }
        if (before.isEmpty()) return null
        return Candidate(jumpNanos, after.map { it.second }.average() - before.map { it.second }.average())
    }

    /** The largest change of the median within [BarometerConfig.stepSharpSeconds] (and at least one sample interval). */
    private fun sharpestChange(medians: List<Pair<Long, Double>>): Double {
        val span = nanos(config.stepSharpSeconds)
        var sharpest = 0.0
        for (k in medians.indices) {
            for (i in k - 1 downTo 0) {
                if (i < k - 1 && medians[k].first - medians[i].first > span) break
                sharpest = maxOf(sharpest, abs(medians[k].second - medians[i].second))
            }
        }
        return sharpest
    }

    /**
     * Index of the first raw sample after the jump: where the raw mean over [BarometerConfig.stepSharpSeconds] after a
     * sample boundary (at least one sample) most exceeds the mean over as long before it, in the direction [sign]. Only
     * boundaries within [fromNanos, untilNanos) count, so the search never crosses another step.
     */
    private fun locateJump(fromNanos: Long, untilNanos: Long, sign: Double): Int? =
        (1 until rawSamples.size).filter { rawSamples[it - 1].first >= fromNanos && rawSamples[it].first < untilNanos }
            .maxByOrNull { sign * sharpShift(it, fromNanos, untilNanos) }

    /**
     * Raw mean over [BarometerConfig.stepSharpSeconds] from sample [index] on, minus the mean over as long before it,
     * each side at least one sample and within [fromNanos, untilNanos).
     */
    private fun sharpShift(index: Int, fromNanos: Long, untilNanos: Long): Double {
        val span = nanos(config.stepSharpSeconds)
        val t = rawSamples[index].first
        var before = 0.0
        var nBefore = 0
        for (i in index - 1 downTo 0) {
            if (rawSamples[i].first < fromNanos || (nBefore > 0 && t - rawSamples[i].first > span)) break
            before += rawSamples[i].second
            nBefore++
        }
        var after = 0.0
        var nAfter = 0
        for (i in index until rawSamples.size) {
            if (rawSamples[i].first >= untilNanos || (nAfter > 0 && rawSamples[i].first - t >= span)) break
            after += rawSamples[i].second
            nAfter++
        }
        return if (nBefore == 0 || nAfter == 0) 0.0 else after / nAfter - before / nBefore
    }

    /**
     * Judges the pending steps whose time has come, oldest first; true when one was confirmed. A step is due
     * [BarometerConfig.stepConfirmSeconds] after its jump. While the step after it (opposite, by construction) is
     * still pending, it waits for that one, so a pulse is judged as a whole.
     */
    private fun settle(nowNanos: Long): Boolean {
        val confirm = nanos(config.stepConfirmSeconds)
        var confirmed = false
        while (pending.isNotEmpty()) {
            val step = pending[0]
            val next = pending.getOrNull(1)
            if (nowNanos < (next ?: step).jumpNanos + confirm) break
            if (next != null && closePulse(step, next, pending.getOrNull(2)?.jumpNanos ?: Long.MAX_VALUE)) {
                confirmed = true
                continue
            }
            val size = judge(step, lastStepNanos, next?.jumpNanos ?: Long.MAX_VALUE)
            if (size != null) {
                confirm(step, size)
                confirmed = true
            } else {
                revert(step)
            }
        }
        return confirmed
    }

    /**
     * Raw samples within [BarometerConfig.stepLevelWindowSeconds] either side of the jump at [jumpNanos], within
     * [fromNanos, untilNanos) (the neighbouring steps), fitted with one common slope plus a level shift at the jump.
     * A ramp, a kink or a curve gives a fitted shift near zero; only a jump gives a large one.
     */
    private fun levelShift(jumpNanos: Long, fromNanos: Long, untilNanos: Long): LevelShift? {
        val span = nanos(config.stepLevelWindowSeconds)
        val from = maxOf(jumpNanos - span, fromNanos)
        val until = minOf(jumpNanos + span, untilNanos)
        val before = rawSamples.filter { it.first in from until jumpNanos }
        val after = rawSamples.filter { it.first in jumpNanos until until }
        if (before.isEmpty() || after.isEmpty()) return null
        fun seconds(sample: Pair<Long, Double>) = (sample.first - jumpNanos) / 1e9
        val tBefore = before.map(::seconds).average()
        val tAfter = after.map(::seconds).average()
        val pBefore = before.map { it.second }.average()
        val pAfter = after.map { it.second }.average()
        var sxx = 0.0
        var sxy = 0.0
        for ((samples, t0, p0) in listOf(Triple(before, tBefore, pBefore), Triple(after, tAfter, pAfter))) {
            for (sample in samples) {
                sxx += (seconds(sample) - t0) * (seconds(sample) - t0)
                sxy += (seconds(sample) - t0) * (sample.second - p0)
            }
        }
        val slope = if (sxx > 0.0) sxy / sxx else 0.0
        return LevelShift(pAfter - pBefore, pAfter - pBefore - slope * (tAfter - tBefore))
    }

    /**
     * The step's confirmed size, or null when it is not a level shift. Raw samples come from [fromNanos] (the previous
     * step) until [untilNanos] (the next one, or now). The step must be sharp: the raw means over
     * [BarometerConfig.stepSharpSeconds] either side of the jump differ by at least [BarometerConfig.stepSharpFraction]
     * of the size (a short fast ride is a ramp of a second or more, not a jump). Then a fitted shift at the jump of at
     * least [BarometerConfig.stepJumpHpa] is one whatever the pressure does next (a ride may start right after a real
     * step). A smaller one, down to [BarometerConfig.stepLevelShiftFraction] of the step, is one only if the pressure is
     * flat around it: no trend above [BarometerConfig.stepFlatAfterRateMps] before it, nor after it in its direction, as
     * a ride would show on one side or the other.
     *
     * The size is the level after minus the mean before (up to [BarometerConfig.stepConfirmSeconds]). When the pressure
     * held level after the jump (no trend either way), the level after is the mean since the jump; otherwise it is a
     * line over the judging window after the jump, taken at the jump, so a ride that starts soon after does not count.
     */
    private fun judge(step: PendingStep, fromNanos: Long, untilNanos: Long): Double? {
        if (stepsBackUnseen(step, fromNanos, untilNanos)) return null
        val shift = levelShift(step.jumpNanos, fromNanos, untilNanos) ?: return null
        val jump = step.sign * shift.fittedShiftHpa
        val after = rawSamples.filter { it.first >= step.jumpNanos && it.first < untilNanos }
        val beforeFrom = maxOf(step.jumpNanos - nanos(config.stepConfirmSeconds), fromNanos)
        val before = rawSamples.filter { it.first in beforeFrom until step.jumpNanos }
        val afterMps = step.sign * trendMps(after)
        if (jump < config.stepJumpHpa) {
            val floor = config.stepLevelShiftFraction * maxOf(step.sign * step.sizeHpa, config.stepRejectionHpa)
            // A stretch cut short by an earlier step (a pulse's plateau) is too short to show a trend.
            val cutShort = fromNanos > step.jumpNanos - nanos(config.stepConfirmSeconds)
            val flatBefore = cutShort || abs(trendMps(before)) <= config.stepFlatAfterRateMps
            val flat = afterMps <= config.stepFlatAfterRateMps && flatBefore
            if (jump < floor || !flat) return null
        }
        val levelBefore = before.map { it.second }.average()
        val size = if (abs(afterMps) <= config.stepFlatAfterRateMps) {
            after.map { it.second }.average() - levelBefore
        } else {
            val near = after.filter { it.first < step.jumpNanos + nanos(config.stepLevelWindowSeconds) }
            val sinceJump = (near.map { it.first }.average() - step.jumpNanos) / 1e9
            near.map { it.second }.average() - slopePerSecond(near) * sinceJump - levelBefore
        }
        return size.takeIf { step.sign * it > 0.0 && isSharp(step, fromNanos, untilNanos, step.sign * it) }
    }

    /**
     * The raw pressure stepped back sharply to about the level before [step] (within
     * [BarometerConfig.stepPulseMatchFraction] of the rise) without that step back ever becoming a candidate, as a
     * short or small door pulse can hide from the median. The pulse then came and went in the height, so [step] is
     * handed back: no net change either way.
     */
    private fun stepsBackUnseen(step: PendingStep, fromNanos: Long, untilNanos: Long): Boolean {
        val back = locateJump(step.jumpNanos, untilNanos, -step.sign) ?: return false
        val rise = step.sign * step.sizeHpa
        if (-step.sign * sharpShift(back, step.jumpNanos, untilNanos) < config.stepSharpFraction * rise) return false
        val span = nanos(config.stepLevelWindowSeconds)
        val backNanos = rawSamples[back].first
        val before = rawSamples.filter { it.first in maxOf(step.jumpNanos - span, fromNanos) until step.jumpNanos }
        val after = rawSamples.filter { it.first in backNanos until minOf(untilNanos, backNanos + span) }
        if (before.isEmpty() || after.isEmpty()) return false
        val level = after.map { it.second }.average() - before.map { it.second }.average()
        return abs(level) <= config.stepPulseMatchFraction * rise
    }

    /** Least-squares vertical trend of raw samples in m/s, positive when pressure rises; 0 for fewer than two. */
    private fun trendMps(samples: List<Pair<Long, Double>>): Double =
        if (samples.size < 2) 0.0 else slopePerSecond(samples) * metresPerHpa(samples[0].second)

    private fun isSharp(step: PendingStep, fromNanos: Long, untilNanos: Long, sizeHpa: Double): Boolean =
        step.sign * sharpShift(rawSamples.indexOfFirst { it.first >= step.jumpNanos }, fromNanos, untilNanos) >=
            config.stepSharpFraction * sizeHpa

    /**
     * Closes a pulse and returns true when [next] steps back to the level before [step] (a door opening and closing):
     * [step] rose sharply from that level, [next] is itself a sharp level shift judged from [step]'s jump on, and the
     * level after it is within [BarometerConfig.stepPulseMatchFraction] of the rise from the level before. A ride that
     * starts the other way soon after a real step is a ramp, so it never closes one. Both steps are kept: the plateau
     * takes its measured level and the offset ends where it began.
     */
    private fun closePulse(step: PendingStep, next: PendingStep, untilNanos: Long): Boolean {
        val opening = levelShift(step.jumpNanos, lastStepNanos, next.jumpNanos) ?: return false
        val rise = step.sign * opening.changeHpa
        if (rise <= 0.0 || !isSharp(step, lastStepNanos, next.jumpNanos, rise)) return false
        val back = judge(next, step.jumpNanos, untilNanos) ?: return false
        if (abs(opening.changeHpa + back) > config.stepPulseMatchFraction * rise) return false
        pending.remove(step)
        pending.remove(next)
        offsetHpa -= step.sizeHpa + next.sizeHpa
        shiftSince(step.absorbedNanos, step.sizeHpa - opening.changeHpa)
        shiftSince(next.absorbedNanos, opening.changeHpa + next.sizeHpa)
        lastStepNanos = next.jumpNanos
        return true
    }

    /** Keeps the step absorbed, re-sized to the level change measured around the jump. */
    private fun confirm(step: PendingStep, sizeHpa: Double) {
        pending.removeAt(0)
        val delta = sizeHpa - step.sizeHpa
        offsetHpa += delta
        shiftSince(step.absorbedNanos, -delta)
        lastStepNanos = step.jumpNanos
    }

    /** Hands a tentatively absorbed step back to the height, as if it had never been absorbed. */
    private fun revert(step: PendingStep) {
        pending.removeAt(0)
        offsetHpa -= step.sizeHpa
        shiftSince(step.absorbedNanos, step.sizeHpa)
    }

    /**
     * Adds [deltaHpa] to the corrected pressure from [fromNanos] on and recomputes the EMA and rates from there, so
     * the smoothed pressure is exactly what it would have been with that correction all along.
     */
    private fun shiftSince(fromNanos: Long, deltaHpa: Double) {
        if (deltaHpa == 0.0) return
        val first = history.indexOfFirst { it.timestampNanos >= fromNanos }
        if (first < 0) return
        for (i in first until history.size) {
            history[i].median += deltaHpa
            history[i].ema = if (i == 0) history[i].median else {
                val dt = (history[i].timestampNanos - history[i - 1].timestampNanos) / 1e9
                history[i - 1].ema + alpha(dt) * (history[i].median - history[i - 1].ema)
            }
        }
        ema = history.last().ema
        for (i in first until history.size) history[i].rateMps = verticalRate(history[i].timestampNanos, history[i].ema, i)
    }

    private fun alpha(dtSeconds: Double) = 1.0 - exp(-dtSeconds / config.emaTauSeconds)

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

        fun nanos(seconds: Double) = (seconds * 1e9).toLong()
        fun median(values: List<Double>): Double {
            val sorted = values.sorted()
            return if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
        }

        fun insertSorted(list: MutableList<Pair<Long, Double>>, sample: Pair<Long, Double>) =
            list.add(list.indexOfFirst { it.first > sample.first }.let { if (it < 0) list.size else it }, sample)

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

        /** Drops leading items while the second one is no newer than [cutoffNanos], keeping one sample before it. */
        fun <T> prune(list: MutableList<T>, cutoffNanos: Long, timestamp: (T) -> Long) {
            val drop = (1 until list.size).firstOrNull { timestamp(list[it]) > cutoffNanos }?.minus(1) ?: (list.size - 1).coerceAtLeast(0)
            if (drop > 0) list.subList(0, drop).clear()
        }
    }
}
