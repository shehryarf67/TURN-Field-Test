package com.turn.fieldtest.core

import kotlin.math.abs

/** What the displayed floor means right now. */
enum class FloorState { CONFIRMED, CHANGING, UNCERTAIN }

/**
 * The confirmed (displayed) floor after one [FloorDecision.update]. [vote] is that floor's share of the filter weight;
 * [changedFrom] is the previous confirmed floor when this update switched; [uncertainReason] explains UNCERTAIN.
 */
data class FloorDecisionResult(
    val floorId: String,
    val vote: Double,
    val state: FloorState,
    val changedFrom: String?,
    val uncertainReason: String?,
)

/**
 * Hysteresis between the particle filter's floor votes and the floor the user sees (invariant I3: the display follows
 * only this output, never the instantaneous argmax).
 *
 * - Switch: another floor's vote stays at or above [DecisionConfig.switchVote] for [DecisionConfig.switchUpdates]
 *   consecutive updates spanning at least [DecisionConfig.switchSeconds], and that floor is joined to the confirmed one
 *   by a portal or its level difference is one of the last applied event's floor counts.
 * - Ties are never broken by id: near 50/50 the confirmed floor holds.
 * - Hard switch to the clear argmax when the confirmed floor's vote falls below [DecisionConfig.hardSwitchVote].
 * - State: UNCERTAIN while marked (an unexplained height, Wi-Fi that keeps pointing elsewhere); else CHANGING while
 *   the caller reports vertical motion or within a marked changing window; else UNCERTAIN below
 *   [DecisionConfig.uncertainVote]; else CONFIRMED.
 *
 * Pure and clock-free: callers pass elapsed-realtime nanos.
 */
class FloorDecision(
    private val topology: FloorTopology,
    private val config: DecisionConfig,
    initialFloorId: String,
) {
    var floorId: String = initialFloorId
        private set

    private var candidate: String? = null
    private var candidateSince = 0L
    private var candidateUpdates = 0
    private var changingUntil = Long.MIN_VALUE
    private var uncertainUntil = Long.MIN_VALUE
    private var uncertainReason: String? = null

    /** An absolute fix: the floor is confirmed at once and every hold is cleared. [nowNanos] is kept for the plan API. */
    @Suppress("UNUSED_PARAMETER")
    fun reset(floorId: String, nowNanos: Long) {
        require(floorId.isNotBlank())
        this.floorId = floorId
        clearCandidate()
        changingUntil = Long.MIN_VALUE
        clearUncertain()
    }

    /** CHANGING until [untilNanos] (e.g. 10 s after an applied transition). An applied transition resolves uncertainty. */
    fun markChanging(untilNanos: Long) {
        changingUntil = maxOf(changingUntil, untilNanos)
        clearUncertain()
    }

    fun markUncertain(reason: String, untilNanos: Long) {
        require(reason.isNotBlank())
        uncertainUntil = untilNanos
        uncertainReason = reason
    }

    fun clearUncertain() {
        uncertainUntil = Long.MIN_VALUE
        uncertainReason = null
    }

    /**
     * [votes] are the filter's normalised floor weights (empty when it is lost). [lastEventFloors] are the floor counts
     * of the last applied vertical event, if it is still recent. [moving] is true while vertical motion is under way.
     */
    fun update(votes: Map<String, Double>, nowNanos: Long, lastEventFloors: Set<Int>?, moving: Boolean = false): FloorDecisionResult {
        var changedFrom: String? = null
        val currentVote = votes[floorId] ?: 0.0
        val others = votes.filterKeys { it != floorId }.entries.sortedByDescending { it.value }
        val best = others.firstOrNull()
        val clearBest = best != null && (others.size < 2 || others[1].value < best.value)
        when {
            clearBest && currentVote < config.hardSwitchVote && best!!.value > currentVote -> {
                changedFrom = switchTo(best.key)
            }
            best != null && best.value >= config.switchVote && reachable(best.key, lastEventFloors) -> {
                if (candidate == best.key) {
                    candidateUpdates++
                } else {
                    candidate = best.key
                    candidateSince = nowNanos
                    candidateUpdates = 1
                }
                if (candidateUpdates >= config.switchUpdates && nowNanos - candidateSince >= nanos(config.switchSeconds)) {
                    changedFrom = switchTo(best.key)
                }
            }
            else -> clearCandidate()
        }
        val vote = votes[floorId] ?: 0.0
        val marked = nowNanos < uncertainUntil
        if (!marked) uncertainReason = null
        val state = when {
            marked -> FloorState.UNCERTAIN
            moving || nowNanos < changingUntil -> FloorState.CHANGING
            vote < config.uncertainVote -> FloorState.UNCERTAIN
            else -> FloorState.CONFIRMED
        }
        val reason = when {
            state != FloorState.UNCERTAIN -> null
            marked -> uncertainReason
            votes.isEmpty() -> "no position estimate"
            else -> "floor vote is low"
        }
        return FloorDecisionResult(floorId, vote, state, changedFrom, reason)
    }

    private fun reachable(target: String, lastEventFloors: Set<Int>?): Boolean {
        if (topology.adjacent(floorId, target)) return true
        val from = topology.levels[floorId] ?: return false
        val to = topology.levels[target] ?: return false
        return lastEventFloors?.contains(abs(to - from)) == true
    }

    private fun switchTo(target: String): String {
        val previous = floorId
        floorId = target
        clearCandidate()
        return previous
    }

    private fun clearCandidate() {
        candidate = null
        candidateUpdates = 0
    }

    private fun nanos(seconds: Double) = (seconds * 1e9).toLong()
}
