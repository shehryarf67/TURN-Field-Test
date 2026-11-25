package com.turn.fieldtest.core.replay

import com.turn.fieldtest.core.BarometerState
import com.turn.fieldtest.core.BarometerTracker
import com.turn.fieldtest.core.FloorDetectionConfig
import com.turn.fieldtest.core.VerticalEvent
import com.turn.fieldtest.core.VerticalMotionDetector
import java.util.Random
import kotlin.math.exp
import kotlin.math.roundToLong

data class PressureSample(val timestampNanos: Long, val hpa: Double)

/** A finished synthetic trace: barometer samples, accepted step times and named instants, all in sensor nanos. */
class PressureTrace(val samples: List<PressureSample>, val stepNanos: List<Long>, private val marks: Map<String, Long>) {
    fun mark(name: String): Long = marks.getValue(name)

    /** Steps in the window of [windowSeconds] ending at [timestampNanos], as the coordinator counts them. */
    fun stepsInWindow(timestampNanos: Long, windowSeconds: Double): Int {
        val from = timestampNanos - nanos(windowSeconds)
        return stepNanos.count { it > from && it <= timestampNanos }
    }
}

/** What feeding a trace through a tracker and a detector produced. */
data class PressureReplay(val states: List<BarometerState>, val events: List<VerticalEvent>)

/** Runs [trace] in sensor-time order through a fresh [BarometerTracker] and [VerticalMotionDetector]. */
fun replayPressure(trace: PressureTrace, config: FloorDetectionConfig = FloorDetectionConfig(),
                   judge: com.turn.fieldtest.core.FloorCountJudge? = null): PressureReplay {
    val tracker = BarometerTracker(config.barometer, sensorPresent = true)
    val detector = VerticalMotionDetector(config.vertical, judge)
    val states = mutableListOf<BarometerState>()
    val events = mutableListOf<VerticalEvent>()
    for (sample in trace.samples) {
        val state = tracker.add(sample.timestampNanos, sample.hpa) ?: continue
        states += state
        detector.onBarometer(state, trace.stepsInWindow(state.timestampNanos, config.vertical.rateWindowSeconds))?.let { events += it }
    }
    return PressureReplay(states, events)
}

/**
 * Seeded builder of synthetic barometer and step traces (default 5 Hz, Gaussian noise of 0.03 hPa at 945 hPa).
 * Segments append in time order; height maps to pressure through the barometric formula, plus weather drift,
 * door/HVAC offsets and noise. Step 6 can wrap the same timeline into full raw sensor traces.
 */
class SyntheticPressure(
    seed: Long,
    private val sampleHz: Double = 5.0,
    private val noiseHpa: Double = 0.03,
    private val baseHpa: Double = 945.0,
    private val scaleHeightMetres: Double = 8434.0,
    startNanos: Long = 1_000_000_000L,
) {
    private val random = Random(seed)
    private val samples = mutableListOf<PressureSample>()
    private val steps = mutableListOf<Long>()
    private val marks = linkedMapOf<String, Long>()
    private var nextSampleNanos = startNanos
    private var heightMetres = 0.0
    private var offsetHpa = 0.0
    private var driftBaseHpa = 0.0
    private var driftSinceNanos = startNanos
    private var driftHpaPerSecond = 0.0

    var nowNanos: Long = startNanos
        private set

    /** Weather drift from now on, in hPa per hour (positive = pressure rising). */
    fun drift(hpaPerHour: Double) = apply {
        driftBaseHpa = driftAt(nowNanos)
        driftSinceNanos = nowNanos
        driftHpaPerSecond = hpaPerHour / 3600.0
    }

    fun walk(seconds: Double, stepHz: Double = 1.8) = segment(seconds, 0.0, stepHz)

    fun stand(seconds: Double) = segment(seconds, 0.0, 0.0)

    /** A height change of [metres] spread linearly over [seconds]. */
    fun climb(metres: Double, seconds: Double, stepHz: Double = 1.5) = segment(seconds, metres, stepHz)

    /** Stairs up (positive) or down (negative) by whole floors, stepping at 1.5 Hz. */
    fun stairs(floors: Int, secondsPerFloor: Double = 12.0, floorHeightMetres: Double = 3.5, stepHz: Double = 1.5) =
        climb(floors * floorHeightMetres, kotlin.math.abs(floors) * secondsPerFloor, stepHz)

    /** A step-free lift ride at constant speed. */
    fun lift(floors: Int, speedMps: Double = 1.0, floorHeightMetres: Double = 3.5) =
        climb(floors * floorHeightMetres, kotlin.math.abs(floors) * floorHeightMetres / speedMps, 0.0)

    /** An instantaneous, persistent pressure offset (door, HVAC). */
    fun pressureStep(deltaHpa: Double) = apply { offsetHpa += deltaHpa }

    /** A pressure offset that lasts [seconds] (door opening and closing). */
    fun pulse(deltaHpa: Double, seconds: Double) = pressureStep(deltaHpa).stand(seconds).pressureStep(-deltaHpa)

    /** Time passes without barometer samples (sensor dropped); steps continue at [stepHz]. */
    fun silence(seconds: Double, stepHz: Double = 0.0) = apply {
        val end = nowNanos + nanos(seconds)
        addSteps(end, stepHz)
        nowNanos = end
        nextSampleNanos = end + period()
    }

    fun mark(name: String) = apply { marks[name] = nowNanos }

    fun build() = PressureTrace(samples.toList(), steps.toList(), marks.toMap())

    private fun segment(seconds: Double, metres: Double, stepHz: Double) = apply {
        require(seconds > 0.0)
        val start = nowNanos
        val end = start + nanos(seconds)
        val startHeight = heightMetres
        while (nextSampleNanos <= end) {
            val t = nextSampleNanos
            val height = startHeight + metres * (t - start).toDouble() / (end - start)
            val hpa = baseHpa * exp(-height / scaleHeightMetres) + driftAt(t) + offsetHpa + random.nextGaussian() * noiseHpa
            samples += PressureSample(t, hpa)
            nextSampleNanos += period()
        }
        addSteps(end, stepHz)
        heightMetres = startHeight + metres
        nowNanos = end
    }

    private fun addSteps(end: Long, stepHz: Double) {
        if (stepHz <= 0.0) return
        var t = nowNanos + nanos(0.5 / stepHz)
        while (t < end) {
            steps += t
            t += nanos(1.0 / stepHz)
        }
    }

    private fun driftAt(t: Long) = driftBaseHpa + driftHpaPerSecond * (t - driftSinceNanos) / 1e9

    private fun period() = nanos(1.0 / sampleHz)
}

fun nanos(seconds: Double): Long = (seconds * 1e9).roundToLong()
