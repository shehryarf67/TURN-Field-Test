package com.turn.fieldtest.data.repository

import com.turn.fieldtest.core.FloorTrackingEvent
import com.turn.fieldtest.core.FloorTrackingEventType
import com.turn.fieldtest.data.local.FloorTransitionEventEntity

/**
 * Maps one coordinator event onto its floor_transition_events row (plan Step 10). The coordinator works in
 * elapsed-realtime nanos; the wall-clock time is derived from one (epoch, elapsed) pair read at drain time.
 * The CONFIG event is emitted at construction with no sensor time, so it gets none and is stamped "now".
 * The id is the session plus the sequence, so a re-imported export merges onto the same rows.
 */
fun FloorTrackingEvent.toEntity(
    positioningSessionId: String, sequence: Long, nowEpochMillis: Long, nowElapsedNanos: Long,
): FloorTransitionEventEntity {
    val sensorNanos = atNanos.takeIf { type != FloorTrackingEventType.CONFIG && it > 0L }
    val epoch = sensorNanos?.let { nowEpochMillis - (nowElapsedNanos - it).coerceAtLeast(0L) / 1_000_000L } ?: nowEpochMillis
    return FloorTransitionEventEntity(
        id = floorEventId(positioningSessionId, sequence),
        positioningSessionId = positioningSessionId,
        sequence = sequence,
        type = type.name,
        fromFloorId = fromFloorId,
        toFloorId = toFloorId,
        linkId = null,
        movedShare = movedShare,
        floorVoteAfter = floorVoteAfter,
        heightDeltaMetres = heightDeltaMetres,
        pressureHpa = pressureHpa,
        barometerHealth = barometerHealth.name,
        sensorTimestampNanos = sensorNanos,
        timestampEpochMillis = epoch,
        detail = detail,
    )
}

fun floorEventId(positioningSessionId: String, sequence: Long) = "FTE-$positioningSessionId-$sequence"

/**
 * Numbers a multi-floor session's coordinator events from 0 (the CONFIG event comes first) and holds them until the
 * positioning session row exists, because the coordinator is built, and emits CONFIG, before the session is saved.
 * Not thread-safe: the caller drains under its event lock.
 */
class FloorEventLog {
    private var nextSequence = 0L
    private val waiting = mutableListOf<FloorTransitionEventEntity>()

    /** Rows ready to write: everything held so far plus [events], or nothing yet while [sessionId] is null. */
    fun add(
        events: List<FloorTrackingEvent>, sessionId: String?, nowEpochMillis: Long, nowElapsedNanos: Long,
    ): List<FloorTransitionEventEntity> {
        events.forEach { waiting += it.toEntity(sessionId.orEmpty(), nextSequence++, nowEpochMillis, nowElapsedNanos) }
        if (sessionId == null || waiting.isEmpty()) return emptyList()
        return waiting.map { if (it.positioningSessionId == sessionId) it else
            it.copy(id = floorEventId(sessionId, it.sequence), positioningSessionId = sessionId) }
            .also { waiting.clear() }
    }

    /** A new session (or none) starts again at sequence 0 with nothing held. */
    fun reset() {
        nextSequence = 0L
        waiting.clear()
    }
}
