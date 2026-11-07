package com.turn.fieldtest.core

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Orientation-invariant magnetic features in microtesla: total field strength and its component along
 * gravity. The horizontal direction is deliberately unused, because it depends on the walking heading.
 */
data class MagneticFeature(val magnitude: Double, val vertical: Double) {
    val finite: Boolean get() = magnitude.isFinite() && vertical.isFinite()

    companion object {
        /** [field] and [gravity] are in the same device frame; [gravity] only supplies the vertical axis. */
        fun from(field: DoubleArray, gravity: DoubleArray): MagneticFeature? {
            if (field.size < 3 || gravity.size < 3) return null
            val g = sqrt(gravity[0] * gravity[0] + gravity[1] * gravity[1] + gravity[2] * gravity[2])
            if (!g.isFinite() || g < 3.0) return null // free fall or no gravity estimate yet
            val magnitude = sqrt(field[0] * field[0] + field[1] * field[1] + field[2] * field[2])
            val vertical = (field[0] * gravity[0] + field[1] * gravity[1] + field[2] * gravity[2]) / g
            return MagneticFeature(magnitude, vertical).takeIf { it.finite }
        }
    }
}

/** One step's magnetic evidence: the mean feature measured since the previous step. */
data class MagneticObservation(
    val feature: MagneticFeature,
    val sampleCount: Int,
    /** False when calibration changed or data was missing, so the change from the previous step is unusable. */
    val continuous: Boolean,
)

/**
 * Collects timestamped features and hands each (possibly delayed) step the mean of the samples taken
 * since the previous step, mirroring how [StepHeadingHistory] matches heading to a step's own time.
 */
class MagneticStepWindow(private val maxSpanNanos: Long = 2_500_000_000L) {
    private data class Reading(val timestampNanos: Long, val feature: MagneticFeature, val discontinuity: Boolean)
    private val readings = java.util.ArrayDeque<Reading>()
    private var lastStepNanos: Long? = null
    private var pendingDiscontinuity = true

    fun reset() { readings.clear(); lastStepNanos = null; pendingDiscontinuity = true }

    /** Marks a calibration jump or accuracy change: the next step must not be compared with the previous one. */
    fun markDiscontinuity(timestampNanos: Long) {
        if (readings.peekLast()?.let { timestampNanos < it.timestampNanos } == true) return
        pendingDiscontinuity = true
    }

    fun record(timestampNanos: Long, feature: MagneticFeature) {
        if (!feature.finite || readings.peekLast()?.let { timestampNanos <= it.timestampNanos } == true) return
        readings.addLast(Reading(timestampNanos, feature, pendingDiscontinuity))
        pendingDiscontinuity = false
        while (readings.size > 4096 || timestampNanos - readings.first.timestampNanos > 10_000_000_000L) readings.removeFirst()
    }

    fun takeForStep(stepNanos: Long): MagneticObservation? {
        val previous = lastStepNanos
        lastStepNanos = stepNanos
        val from = max(previous ?: Long.MIN_VALUE, stepNanos - maxSpanNanos)
        val window = readings.filter { it.timestampNanos in (from + 1)..stepNanos }
        while (readings.isNotEmpty() && readings.first.timestampNanos <= stepNanos) readings.removeFirst()
        if (window.isEmpty()) { pendingDiscontinuity = true; return null }
        val continuous = previous != null && stepNanos - previous <= maxSpanNanos && window.none { it.discontinuity }
        return MagneticObservation(
            MagneticFeature(window.map { it.feature.magnitude }.average(), window.map { it.feature.vertical }.average()),
            window.size, continuous,
        )
    }
}

/** Running mean/variance (Welford) of both features for one map cell. */
data class MagneticCellStats(
    val count: Int = 0,
    val meanMagnitude: Double = 0.0, val m2Magnitude: Double = 0.0,
    val meanVertical: Double = 0.0, val m2Vertical: Double = 0.0,
) {
    fun add(feature: MagneticFeature): MagneticCellStats {
        val n = count + 1
        val dm = feature.magnitude - meanMagnitude; val mm = meanMagnitude + dm / n
        val dv = feature.vertical - meanVertical; val mv = meanVertical + dv / n
        return MagneticCellStats(n, mm, m2Magnitude + dm * (feature.magnitude - mm), mv, m2Vertical + dv * (feature.vertical - mv))
    }

    /** Variance shrunk towards a prior so a single pass is not mistaken for a perfectly known cell. */
    fun variance(m2: Double, priorVariance: Double, priorWeight: Double = 2.0) =
        (m2 + priorVariance * priorWeight) / (max(count - 1, 0) + priorWeight)
}

data class MagneticExpectation(val feature: MagneticFeature, val varianceMagnitude: Double, val varianceVertical: Double, val count: Int)

data class MagneticCellKey(val x: Int, val y: Int)

/** Sparse per-floor magnetic map on a square grid in metres. */
class MagneticFingerprintMap(
    val floorId: String,
    cells: Map<MagneticCellKey, MagneticCellStats> = emptyMap(),
    val cellSizeMetres: Double = CELL_SIZE_METRES,
    private val priorVariance: Double = 1.5 * 1.5,
) {
    private val mutableCells = cells.toMutableMap()
    val cells: Map<MagneticCellKey, MagneticCellStats> get() = mutableCells
    val isEmpty: Boolean get() = mutableCells.isEmpty()

    fun keyFor(point: MetricPoint) = MagneticCellKey(floor(point.x / cellSizeMetres).toInt(), floor(point.y / cellSizeMetres).toInt())
    fun centreOf(key: MagneticCellKey) = MetricPoint((key.x + 0.5) * cellSizeMetres, (key.y + 0.5) * cellSizeMetres)

    fun add(point: MetricPoint, feature: MagneticFeature) {
        if (!feature.finite) return
        val key = keyFor(point)
        mutableCells[key] = (mutableCells[key] ?: MagneticCellStats()).add(feature)
    }

    /** Expected feature at [point]: its own cell, else a distance-weighted blend of the surrounding cells. */
    fun expectationAt(point: MetricPoint): MagneticExpectation? {
        val key = keyFor(point)
        mutableCells[key]?.let { return it.expectation() }
        var weight = 0.0; var magnitude = 0.0; var vertical = 0.0; var varMag = 0.0; var varVert = 0.0; var count = 0
        for (dx in -1..1) for (dy in -1..1) {
            val neighbour = MagneticCellKey(key.x + dx, key.y + dy)
            val stats = mutableCells[neighbour] ?: continue
            val w = 1.0 / max(centreOf(neighbour).distanceTo(point), 0.1)
            val e = stats.expectation()
            weight += w; magnitude += w * e.feature.magnitude; vertical += w * e.feature.vertical
            varMag += w * e.varianceMagnitude; varVert += w * e.varianceVertical; count += stats.count
        }
        if (weight == 0.0) return null
        // Interpolated cells are less certain than measured ones.
        return MagneticExpectation(MagneticFeature(magnitude / weight, vertical / weight),
            varMag / weight + priorVariance, varVert / weight + priorVariance, count)
    }

    private fun MagneticCellStats.expectation() = MagneticExpectation(MagneticFeature(meanMagnitude, meanVertical),
        variance(m2Magnitude, priorVariance), variance(m2Vertical, priorVariance), count)

    companion object { const val CELL_SIZE_METRES = 0.6 }
}

sealed interface MagneticWalkResult {
    data class Accepted(val samples: List<Pair<MetricPoint, MagneticFeature>>, val strideMetres: Double, val lengthMetres: Double) : MagneticWalkResult
    data class Rejected(val reason: String) : MagneticWalkResult
}

/**
 * Turns a straight walk between two surveyed points into positioned magnetic samples. Steps are spread
 * evenly along the line by count, so the result does not depend on a stride estimate; the implied stride
 * is only checked for plausibility. Features are interpolated between steps so the profile is continuous.
 */
object MagneticPathSurvey {
    fun build(
        floor: MapFloor, from: MetricPoint, to: MetricPoint, observations: List<MagneticObservation?>,
        sampleSpacingMetres: Double = 0.3,
    ): MagneticWalkResult {
        val length = from.distanceTo(to)
        if (length < 2.0) return MagneticWalkResult.Rejected("Choose two points at least 2 m apart")
        if (!floor.isPathInsideWalkableSpace(from, to) || floor.crossesWall(from, to))
            return MagneticWalkResult.Rejected("The straight line between these points leaves walkable space or crosses a wall; pick points along one corridor")
        val steps = observations.size
        if (steps < 3) return MagneticWalkResult.Rejected("Only $steps steps were detected; walk the whole way at a steady pace")
        val stride = length / steps
        if (stride !in 0.3..1.2) return MagneticWalkResult.Rejected("%d steps for %.1f m (%.2f m per step) does not look like a straight walk; try again".format(steps, length, stride))
        val missing = observations.count { it == null }
        if (missing > steps * 0.2) return MagneticWalkResult.Rejected("Magnetometer data was missing for $missing of $steps steps; keep the app in front and try again")
        // Each step's mean covers the half step before it: place it at fraction (i + 0.5) / steps.
        val anchored = observations.mapIndexedNotNull { i, obs ->
            obs?.let { (i + 0.5) / steps to it.feature }
        }
        val disturbed = anchored.count { (_, f) -> f.magnitude !in PLAUSIBLE_MAGNITUDE }
        if (disturbed > anchored.size * 0.2) return MagneticWalkResult.Rejected("Magnetic readings were implausible on $disturbed steps (strong interference nearby?); move away from it and try again")
        val usable = anchored.filter { (_, f) -> f.magnitude in PLAUSIBLE_MAGNITUDE }
        if (usable.size < 3) return MagneticWalkResult.Rejected("Too few usable magnetic readings")
        val samples = mutableListOf<Pair<MetricPoint, MagneticFeature>>()
        fun at(t: Double) = MetricPoint(from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t)
        val count = max(2, (length / sampleSpacingMetres).roundToInt())
        for (i in 0..count) {
            val t = i.toDouble() / count
            val after = usable.indexOfFirst { it.first >= t }
            val feature = when {
                after == -1 -> usable.last().second
                after == 0 -> usable.first().second
                else -> {
                    val (t0, f0) = usable[after - 1]; val (t1, f1) = usable[after]
                    val u = if (t1 - t0 < 1e-9) 0.0 else (t - t0) / (t1 - t0)
                    MagneticFeature(f0.magnitude + (f1.magnitude - f0.magnitude) * u, f0.vertical + (f1.vertical - f0.vertical) * u)
                }
            }
            samples += at(t) to feature
        }
        return MagneticWalkResult.Accepted(samples, stride, length)
    }

    /** Earth's field is 25–65 µT; well outside that range a reading is dominated by a nearby object. */
    val PLAUSIBLE_MAGNITUDE = 15.0..90.0
}

/** Tunable, conservative magnetic likelihood; magnetic evidence is local and never initialises tracking. */
data class MagneticCorrectionConfig(
    val sensorNoiseMicroTesla: Double = 1.0,
    /** Device offsets and map drift make absolute values only loosely comparable across phones. */
    val absoluteBiasMicroTesla: Double = 6.0,
    /** Probability mass for readings the map cannot explain (people, trolleys, lifts, laptops). */
    val outlierProbability: Double = 0.15,
    /** Consecutive steps share errors; tempering stops the filter becoming over-confident. */
    val likelihoodExponent: Double = 0.5,
    /** Below this share of particle weight on mapped cells, magnetic evidence is not applied. */
    val minimumMappedWeight: Double = 0.5,
) {
    init {
        require(sensorNoiseMicroTesla > 0 && absoluteBiasMicroTesla >= 0)
        require(outlierProbability in 0.0..1.0 && likelihoodExponent in 0.0..1.0 && minimumMappedWeight in 0.0..1.0)
    }
}

object MagneticLikelihood {
    /**
     * Likelihood of [observation] for a particle that moved from [previous] to [current], given the previous
     * step's [previousObservation]. Uses the step-to-step change (device offsets cancel) and a loose absolute
     * term; each term is a robust Gaussian-plus-outlier mixture. Returns null when the map has nothing here.
     */
    fun evaluate(
        map: MagneticFingerprintMap, previous: MetricPoint, current: MetricPoint,
        observation: MagneticObservation, previousObservation: MagneticObservation?, config: MagneticCorrectionConfig,
    ): Double? {
        val now = map.expectationAt(current) ?: return null
        val noise = config.sensorNoiseMicroTesla * config.sensorNoiseMicroTesla
        fun robust(residual: Double, variance: Double): Double =
            (1.0 - config.outlierProbability) * kotlin.math.exp(-residual * residual / (2.0 * variance)) + config.outlierProbability
        val bias = config.absoluteBiasMicroTesla * config.absoluteBiasMicroTesla
        var likelihood = robust(observation.feature.magnitude - now.feature.magnitude, now.varianceMagnitude + noise + bias) *
            robust(observation.feature.vertical - now.feature.vertical, now.varianceVertical + noise + bias)
        val before = map.expectationAt(previous)
        if (previousObservation != null && observation.continuous && before != null) {
            val observedMagnitude = observation.feature.magnitude - previousObservation.feature.magnitude
            val observedVertical = observation.feature.vertical - previousObservation.feature.vertical
            likelihood *= robust(observedMagnitude - (now.feature.magnitude - before.feature.magnitude),
                now.varianceMagnitude + before.varianceMagnitude + 2 * noise)
            likelihood *= robust(observedVertical - (now.feature.vertical - before.feature.vertical),
                now.varianceVertical + before.varianceVertical + 2 * noise)
        }
        return likelihood
    }

    fun plausible(observation: MagneticObservation) = observation.feature.magnitude in MagneticPathSurvey.PLAUSIBLE_MAGNITUDE &&
        abs(observation.feature.vertical) <= observation.feature.magnitude + 1e-6
}
