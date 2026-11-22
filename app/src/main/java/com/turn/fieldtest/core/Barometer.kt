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
    /** [absorbedHpa] is how much of the step the offset holds so far: all of it, or nothing for a hidden return. */
    private class PendingStep(val absorbedNanos: Long, val jumpNanos: Long, val sizeHpa: Double, val absorbedHpa: Double = sizeHpa) {
        val sign get() = if (sizeHpa > 0.0) 1.0 else -1.0
    }

    private class Candidate(val jumpNanos: Long, val sizeHpa: Double)

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
        // Raw samples reach back to stepConfirmSeconds before the first pending step, however long its cluster lasts.
        val stepSpan = config.rateWindowSeconds + config.stepWindowSeconds + 2 * config.stepConfirmSeconds
        val keepFrom = minOf(
            timestampNanos - nanos(maxOf(config.driftQuietSeconds, stepSpan) + 1.0),
            (pending.firstOrNull()?.jumpNanos ?: Long.MAX_VALUE) - nanos(config.stepConfirmSeconds + 1.0),
        )
        prune(history, keepFrom) { it.timestampNanos }
        prune(rawSamples, keepFrom) { it.first }
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

    /**
     * A sharp step on flat pressure that may be a discontinuity, or null. [corrected] is the offset-free median. While
     * a step is pending, only a step back counts, and it needs to undo only most of the pending step: the return of a
     * small door pulse can stay just below [BarometerConfig.stepRejectionHpa].
     */
    private fun stepCandidate(timestampNanos: Long, corrected: Double): Candidate? {
        val span = nanos(config.stepWindowSeconds)
        val reference = history.lastOrNull { it.timestampNanos <= timestampNanos - span } ?: return null
        val jump = corrected - reference.ema
        val open = pending.lastOrNull()
        val stepsBack = open != null && (jump > 0.0) != (open.sizeHpa > 0.0)
        // A step on in the same direction joins the cluster only after it stepped back (a second door pulse); right
        // after a lone step it would be a ride carrying on, which must stay height.
        val returned = pending.size >= 2 && (pending[pending.size - 2].sizeHpa > 0.0) != (open!!.sizeHpa > 0.0)
        if (open != null && !stepsBack && !returned) return null
        val threshold = if (stepsBack) minOf(config.stepRejectionHpa, (1.0 - config.stepPulseMatchFraction) * abs(open!!.sizeHpa))
        else config.stepRejectionHpa
        if (abs(jump) <= threshold) return null
        if (abs(reference.rateMps) > config.stepFlatRateMps) return null
        // The median may show a first step late (a noisy sample can split it), so its jump is searched for from up to
        // stepWindowSeconds before the reference, never before the last step. A later edge of a cluster is searched for
        // only where the median window can have seen it, so an earlier, hidden edge of the same pulse is not taken for it.
        val from = if (open != null) maxOf(open.jumpNanos, window.first().first - nanos(config.stepSharpSeconds))
        else maxOf(lastStepNanos, reference.timestampNanos - span)
        val sign = if (jump > 0.0) 1.0 else -1.0
        val jumpAt = locateJump(from, Long.MAX_VALUE, sign) ?: return null
        val recent = history.filter { it.timestampNanos >= reference.timestampNanos }.map { it.timestampNanos to it.median }
        if (sharpestChange(recent + (timestampNanos to corrected)) < config.stepSharpFraction * abs(jump)) return null
        val jumpNanos = rawSamples[jumpAt].first
        // The median sits at an extreme of the post-step samples; raw means either side of the jump size it unbiased.
        val before = rawBetween(from, jumpNanos)
        val after = rawBetween(jumpNanos, Long.MAX_VALUE)
        if (before.isEmpty()) return null
        val size = mean(after) - mean(before)
        return Candidate(jumpNanos, size).takeIf { sign * size > config.stepLevelShiftFraction * config.stepRejectionHpa }
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
     * Judges the pending steps once the newest is [BarometerConfig.stepConfirmSeconds] old; true when a step was
     * confirmed. Each pending step jumped within that time of the one before it (a later one is only a candidate
     * while the earlier ones wait), so the edges of a door pulse, or of several, are judged together.
     */
    private fun settle(nowNanos: Long): Boolean {
        val newest = pending.lastOrNull() ?: return false
        if (nowNanos < newest.jumpNanos + nanos(config.stepConfirmSeconds)) return false
        return judge()
    }

    /**
     * Judges the pending steps as one cluster of edges, on raw samples that never reach back across the last confirmed
     * step. The level before is the mean over up to [BarometerConfig.stepConfirmSeconds] before the first edge; the level
     * after is the level right after the last edge ([levelAfter]).
     *
     * Every edge before the last must be a level shift ([isLevelShift]) between its neighbours. Door pulses (one,
     * several, or overpressure then underpressure) are sharp jumps between flat plateaus, however short; a lift ride
     * that a noisy median splits into edges is a ramp between them, so the cluster ends before the first edge that is not
     * a jump: the ride starts there. An edge within 2 x [BarometerConfig.stepSharpSeconds] of the one before that is not a
     * jump is the same jump seen again by the median, and is merged into it.
     *
     * Then the last edge must be a level shift in its own direction; if it is not (the start of a ride or a fast climb),
     * it is dropped and the cluster without it is judged again. Kept edges are re-sized to the levels between them (the
     * mean of each plateau), so they add up to the level after minus the level before: pulses that came and went add
     * nothing. Dropped steps are handed back to the height retroactively, so a whole ride is measured.
     */
    private fun judge(): Boolean {
        val edges = pending.toMutableList()
        val hidden = hiddenReturn(edges)
        if (hidden != null) edges += hidden
        var i = 0
        while (i < edges.size - 1) {
            val previousNanos = if (i > 0) edges[i - 1].jumpNanos else lastStepNanos
            if (isLevelShift(edges[i], previousNanos, edges[i + 1].jumpNanos)) {
                i++
            } else if (i > 0 && edges[i].jumpNanos - previousNanos < nanos(2 * config.stepSharpSeconds)) {
                edges.removeAt(i)
            } else {
                edges.subList(i, edges.size).clear()
            }
        }
        var sizes = emptyList<Double>()
        while (edges.isNotEmpty()) {
            val first = edges.first()
            val last = edges.last()
            val previousNanos = edges.getOrNull(edges.size - 2)?.jumpNanos ?: lastStepNanos
            val before = rawBetween(maxOf(lastStepNanos, first.jumpNanos - nanos(config.stepConfirmSeconds)), first.jumpNanos)
            val after = rawBetween(last.jumpNanos, Long.MAX_VALUE)
            if (before.isEmpty() || after.isEmpty()) {
                edges.clear()
                break
            }
            val levels = mutableListOf(mean(before))
            for (k in 0 until edges.size - 1) levels += mean(rawBetween(edges[k].jumpNanos, edges[k + 1].jumpNanos))
            levels += levelAfter(last.jumpNanos, after)
            val lastSize = levels.last() - levels[levels.size - 2]
            if (last === hidden || last.sign * lastSize > 0.0 && isLevelShift(last, previousNanos, Long.MAX_VALUE)) {
                sizes = (0 until edges.size).map { levels[it + 1] - levels[it] }
                break
            }
            edges.removeAt(edges.lastIndex)
        }
        for (step in pending + listOfNotNull(hidden)) {
            val k = edges.indexOf(step)
            val delta = if (k >= 0) sizes[k] - step.absorbedHpa else -step.absorbedHpa
            offsetHpa += delta
            shiftSince(step.absorbedNanos, -delta)
        }
        pending.clear()
        if (edges.isEmpty()) return false
        lastStepNanos = edges.last().jumpNanos
        return true
    }

    /**
     * The step back of the last of [edges] when it never became a candidate (a short or small door pulse can hide its
     * return from the median), or null. The raw pressure must step back sharply (the raw means over
     * [BarometerConfig.stepSharpSeconds] either side differ by [BarometerConfig.stepSharpFraction] of the step), then stay
     * flat (no trend above [BarometerConfig.stepFlatAfterRateMps] over [BarometerConfig.stepLevelWindowSeconds]) within
     * [BarometerConfig.stepPulseMatchFraction] of the step of the level before the first edge, which the pressure stood
     * away from until then (a cluster that already returned has no step back to hide). A ride that starts the
     * other way keeps moving, so it is never taken for one. It joins the cluster as an edge the offset does not hold yet.
     */
    private fun hiddenReturn(edges: List<PendingStep>): PendingStep? {
        val first = edges.firstOrNull() ?: return null
        val last = edges.last()
        val back = locateJump(last.jumpNanos, Long.MAX_VALUE, -last.sign) ?: return null
        val rise = last.sign * last.sizeHpa
        if (-last.sign * sharpShift(back, last.jumpNanos, Long.MAX_VALUE) < config.stepSharpFraction * rise) return null
        val backNanos = rawSamples[back].first
        val after = rawBetween(backNanos, backNanos + nanos(config.stepLevelWindowSeconds))
        val before = rawBetween(maxOf(lastStepNanos, first.jumpNanos - nanos(config.stepConfirmSeconds)), first.jumpNanos)
        if (after.size < 2 || before.isEmpty() || abs(trendMps(after)) > config.stepFlatAfterRateMps) return null
        val levelBefore = mean(before)
        // The pressure stood away from the level before until the step back; a cluster that already returned has none.
        val plateau = rawBetween(last.jumpNanos, backNanos)
        if (plateau.isEmpty() || abs(mean(plateau) - levelBefore) < (1.0 - config.stepPulseMatchFraction) * rise) return null
        if (abs(mean(after) - levelBefore) > config.stepPulseMatchFraction * rise) return null
        return PendingStep(backNanos, backNanos, -last.sign * rise, absorbedHpa = 0.0)
    }

    /**
     * The level right after a jump, from the raw samples [after] it (from the jump until now). When the pressure held
     * level after the jump (no trend above [BarometerConfig.stepFlatAfterRateMps] either way) it is their mean;
     * otherwise it is a line over [BarometerConfig.stepLevelWindowSeconds] after the jump, taken at the jump, so a ride
     * that starts soon after does not count.
     */
    private fun levelAfter(jumpNanos: Long, after: List<Pair<Long, Double>>): Double {
        if (abs(trendMps(after)) <= config.stepFlatAfterRateMps) return mean(after)
        val near = after.filter { it.first < jumpNanos + nanos(config.stepLevelWindowSeconds) }
        val sinceJump = (near.map { it.first }.average() - jumpNanos) / 1e9
        return mean(near) - slopePerSecond(near) * sinceJump
    }

    /**
     * Raw samples within [BarometerConfig.stepLevelWindowSeconds] either side of the jump at [jumpNanos], within
     * [fromNanos, untilNanos) (the neighbouring steps), fitted with one common slope plus a level shift at the jump.
     * A ramp, a kink or a curve gives a fitted shift near zero; only a jump gives a large one.
     */
    private fun levelShift(jumpNanos: Long, fromNanos: Long, untilNanos: Long): Double? {
        val span = nanos(config.stepLevelWindowSeconds)
        val before = rawBetween(maxOf(jumpNanos - span, fromNanos), jumpNanos)
        val after = rawBetween(jumpNanos, minOf(jumpNanos + span, untilNanos))
        if (before.isEmpty() || after.isEmpty()) return null
        fun seconds(sample: Pair<Long, Double>) = (sample.first - jumpNanos) / 1e9
        val tBefore = before.map(::seconds).average()
        val tAfter = after.map(::seconds).average()
        val pBefore = mean(before)
        val pAfter = mean(after)
        var sxx = 0.0
        var sxy = 0.0
        for ((samples, t0, p0) in listOf(Triple(before, tBefore, pBefore), Triple(after, tAfter, pAfter))) {
            for (sample in samples) {
                sxx += (seconds(sample) - t0) * (seconds(sample) - t0)
                sxy += (seconds(sample) - t0) * (sample.second - p0)
            }
        }
        val slope = if (sxx > 0.0) sxy / sxx else 0.0
        return pAfter - pBefore - slope * (tAfter - tBefore)
    }

    /**
     * True when [step] is a level shift between [fromNanos] and [untilNanos] (its neighbouring steps, or now). It must
     * be sharp, and the fitted shift at its jump ([levelShift]), in its direction, must be at least
     * [BarometerConfig.stepJumpHpa]: then it is one whatever the pressure does next (a ride may start right after a real
     * step). A smaller one, down to [BarometerConfig.stepLevelShiftFraction] of the step, is one only if the pressure is
     * flat around it: no trend above [BarometerConfig.stepFlatAfterRateMps] before it, nor after it in its direction,
     * as a ride would show on one side or the other.
     */
    private fun isLevelShift(step: PendingStep, fromNanos: Long, untilNanos: Long): Boolean {
        val jump = step.sign * (levelShift(step.jumpNanos, fromNanos, untilNanos) ?: return false)
        if (jump < config.stepJumpHpa) {
            val confirm = nanos(config.stepConfirmSeconds)
            val before = rawBetween(maxOf(step.jumpNanos - confirm, fromNanos), step.jumpNanos)
            val after = rawBetween(step.jumpNanos, untilNanos)
            // A stretch cut short by an earlier step (a pulse plateau) is too short to show a trend.
            val flatBefore = fromNanos > step.jumpNanos - confirm || abs(trendMps(before)) <= config.stepFlatAfterRateMps
            val flat = flatBefore && step.sign * trendMps(after) <= config.stepFlatAfterRateMps
            if (jump < config.stepLevelShiftFraction * maxOf(step.sign * step.sizeHpa, config.stepRejectionHpa) || !flat) return false
        }
        return isSharp(step, fromNanos, untilNanos, jump)
    }

    /** Least-squares vertical trend of raw samples in m/s, positive when pressure rises; 0 for fewer than two. */
    private fun trendMps(samples: List<Pair<Long, Double>>): Double =
        if (samples.size < 2) 0.0 else slopePerSecond(samples) * metresPerHpa(samples[0].second)

    /** The raw means over [BarometerConfig.stepSharpSeconds] either side of the jump differ by [BarometerConfig.stepSharpFraction] of [sizeHpa]. */
    private fun isSharp(step: PendingStep, fromNanos: Long, untilNanos: Long, sizeHpa: Double): Boolean =
        step.sign * sharpShift(rawSamples.indexOfFirst { it.first >= step.jumpNanos }, fromNanos, untilNanos) >=
            config.stepSharpFraction * sizeHpa

    private fun rawBetween(fromNanos: Long, untilNanos: Long) = rawSamples.filter { it.first >= fromNanos && it.first < untilNanos }


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

        fun mean(samples: List<Pair<Long, Double>>) = samples.sumOf { it.second } / samples.size

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
