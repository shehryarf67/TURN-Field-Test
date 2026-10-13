package com.turn.fieldtest.core

/** Associate delayed step callbacks with measured heading at the step's timestamp, not at delivery. */
class StepHeadingHistory {
    private data class Reading(val timestampNanos: Long, val headingRadians: Double)
    private val readings = java.util.ArrayDeque<Reading>()
    private var alignmentNanos = 0L

    fun reset(alignedAtNanos: Long) {
        readings.clear(); alignmentNanos = alignedAtNanos
    }

    fun record(timestampNanos: Long, headingRadians: Double) {
        if (!headingRadians.isFinite() || timestampNanos < alignmentNanos ||
            readings.peekLast()?.let { timestampNanos <= it.timestampNanos } == true) return
        readings.addLast(Reading(timestampNanos, headingRadians))
        while (readings.size > 2048 || timestampNanos - readings.first.timestampNanos > 10_000_000_000L) readings.removeFirst()
    }

    fun headingForStep(stepNanos: Long): Double? {
        if (stepNanos < alignmentNanos) return null
        val past = readings.lastOrNull { it.timestampNanos <= stepNanos }
        val reading = past ?: readings.firstOrNull { it.timestampNanos - stepNanos in 0L..200_000_000L }
        return reading?.takeIf { MapHeading.hasFreshHeading(stepNanos, it.timestampNanos, alignmentNanos) }?.headingRadians
    }
}
