package com.turn.fieldtest.core

/**
 * One stretch of ground truth that starts at [startNanos] (elapsed-realtime base) and lasts until the next one.
 * A null [floorId] means the walker is between floors, moving by [transitKind] ([up] when known).
 */
data class FloorTruth(val startNanos: Long, val floorId: String?, val transitKind: ConnectorKind? = null, val up: Boolean? = null) {
    init { require(floorId == null || floorId.isNotBlank()) { "floorId must not be blank" } }
}

/** A CONFIRMED floor decision at [atNanos]. Repeats of the same floor are allowed and are not changes. */
data class ConfirmedFloor(val atNanos: Long, val floorId: String)

/**
 * How well the confirmed floor followed the truth.
 *
 * - [accuracy]: share of evaluated time on the right floor (time in transit and its grace window is not evaluated;
 *   time before the first decision counts as wrong). NaN when nothing could be evaluated.
 * - [latenciesSeconds]: per real floor change, seconds from ARRIVED to the first confirmed decision for the new floor
 *   (negative when confirmed before the walker marked arrival).
 * - [falseChangesPerHour]: confirmed changes to a floor that was neither the truth nor an end of the transit under way.
 * - [reverts]: confirmed changes undone within the revert window.
 * - [missedChanges]: real floor changes never confirmed before the next change or the end of the trace.
 */
data class FloorTrackMetrics(
    val accuracy: Double,
    val latenciesSeconds: List<Double>,
    val falseChangesPerHour: Double,
    val reverts: Int,
    val falseChanges: Int = 0,
    val missedChanges: Int = 0,
)

/** Scores a run's CONFIRMED floor decisions against marker ground truth. Pure; used by replay tests and field traces. */
object FloorTrackEvaluator {
    const val REVERT_SECONDS = 30.0
    private const val NANOS = 1_000_000_000.0

    /** A truth floor change: ends [departure] and [arrival], scored leniently over [windowStart, windowEnd). */
    private class Change(val departure: String?, val arrival: String?, val arrivalNanos: Long, val windowStart: Long, val windowEnd: Long)

    fun evaluate(
        truth: List<FloorTruth>, confirmed: List<ConfirmedFloor>, endNanos: Long,
        transitGraceSeconds: Double = 5.0, revertSeconds: Double = REVERT_SECONDS,
    ): FloorTrackMetrics {
        require(truth.isNotEmpty()) { "truth must not be empty" }
        require(transitGraceSeconds in 0.0..600.0) { "transitGraceSeconds must be 0-600" }
        require(revertSeconds in 0.0..3600.0) { "revertSeconds must be 0-3600" }
        val segments = truth.sortedBy { it.startNanos }
        val start = segments.first().startNanos
        require(endNanos >= start) { "endNanos must not precede the first truth" }
        val grace = (transitGraceSeconds * NANOS).toLong()
        val decisions = confirmed.sortedBy { it.atNanos }.fold(mutableListOf<ConfirmedFloor>()) { acc, c ->
            if (acc.lastOrNull()?.floorId != c.floorId) acc += c; acc
        }

        // A transit (or a direct floor jump) is one change; its window covers grace before the start and after arrival.
        val changes = mutableListOf<Change>()
        var lastFloor: String? = null
        var transitStart: Long? = null
        for (s in segments) {
            if (s.floorId == null) { if (transitStart == null) transitStart = s.startNanos; continue }
            if (transitStart != null || (lastFloor != null && s.floorId != lastFloor)) {
                changes += Change(lastFloor, s.floorId, s.startNanos, (transitStart ?: s.startNanos) - grace, s.startNanos + grace)
            }
            lastFloor = s.floorId; transitStart = null
        }
        if (transitStart != null) changes += Change(lastFloor, null, endNanos, transitStart - grace, endNanos)

        fun truthAt(t: Long): String? = segments.lastOrNull { it.startNanos <= t }?.floorId
        fun confirmedAt(t: Long): String? = decisions.lastOrNull { it.atNanos <= t }?.floorId
        fun inWindow(t: Long) = changes.firstOrNull { t >= it.windowStart && t < it.windowEnd }

        // Accuracy: piecewise-constant over the breakpoints of truth, decisions and windows.
        val cuts = (segments.map { it.startNanos } + decisions.map { it.atNanos } +
            changes.flatMap { listOf(it.windowStart, it.windowEnd) } + start + endNanos)
            .filter { it in start..endNanos }.distinct().sorted()
        var evaluated = 0L; var correct = 0L
        cuts.zipWithNext { a, b ->
            val floor = truthAt(a)
            if (floor != null && inWindow(a) == null) {
                evaluated += b - a
                if (confirmedAt(a) == floor) correct += b - a
            }
        }

        // Latency: first time in the change's span (until the next change's window) that the confirmed floor is the arrival.
        val latencies = mutableListOf<Double>(); var missed = 0
        changes.forEachIndexed { i, c ->
            if (c.departure == null || c.arrival == null || c.departure == c.arrival) return@forEachIndexed
            val spanEnd = changes.getOrNull(i + 1)?.windowStart ?: endNanos
            val hit = if (confirmedAt(c.windowStart) == c.arrival) c.windowStart
                else decisions.firstOrNull { it.atNanos > c.windowStart && it.atNanos < spanEnd && it.floorId == c.arrival }?.atNanos
            if (hit == null) missed++ else latencies += (hit - c.arrivalNanos) / NANOS
        }

        // False changes and reverts, over confirmed changes (the first decision is the start, not a change).
        var falseChanges = 0; var reverts = 0
        val revertNanos = (revertSeconds * NANOS).toLong()
        for (i in 1 until decisions.size) {
            val c = decisions[i]
            if (c.atNanos in start..endNanos) {
                val window = inWindow(c.atNanos)
                val explained = c.floorId == truthAt(c.atNanos) || (window != null && (c.floorId == window.departure || c.floorId == window.arrival))
                if (!explained) falseChanges++
            }
            val next = decisions.getOrNull(i + 1)
            if (c.atNanos <= endNanos && next != null && next.floorId == decisions[i - 1].floorId && next.atNanos - c.atNanos <= revertNanos) reverts++
        }

        val hours = (endNanos - start) / NANOS / 3600.0
        return FloorTrackMetrics(
            accuracy = if (evaluated > 0) correct.toDouble() / evaluated else Double.NaN,
            latenciesSeconds = latencies,
            falseChangesPerHour = if (hours > 0) falseChanges / hours else 0.0,
            reverts = reverts, falseChanges = falseChanges, missedChanges = missed,
        )
    }
}
