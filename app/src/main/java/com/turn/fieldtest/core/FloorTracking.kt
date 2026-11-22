package com.turn.fieldtest.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs

/** Coordinator tunables that [FloorDetectionConfig] does not hold (kept separate so that class stays as it is). */
@Serializable
data class FloorTrackingConfig(
    /** How long "Wi-Fi suggests another floor" keeps the floor UNCERTAIN after repeated vetoes, unless resolved sooner. */
    val wifiUncertainSeconds: Double = 120.0,
    /** How long an applied event's floor counts let the decision switch to a floor no portal joins directly. */
    val lastEventFloorsSeconds: Double = 120.0,
    /** onTick is honoured at most this often. */
    val tickSeconds: Double = 1.0,
) {
    init {
        require(wifiUncertainSeconds > 0.0)
        require(lastEventFloorsSeconds > 0.0)
        require(tickSeconds > 0.0)
    }
}

/** What the coordinator says about a Wi-Fi match that points at another floor. */
enum class CrossFloorWifi { ALLOW, DEFER, VETO }

/** Where an absolute fix came from (each one is allowed to change floor without a link, invariant I2). */
enum class FloorFixSource { MANUAL, QR, WIFI_INIT, USER_RELOCALIZE, WIFI_CROSS_FLOOR }

/** Event types persisted 1:1 in floor_transition_events (schema 7, plan Step 10). */
enum class FloorTrackingEventType {
    CONFIG, VERTICAL_EVENT, DWELL_CUE, APPLIED, REJECTED, PENDING_EXPIRED, CONFIRMED, UNCERTAIN, ABSOLUTE_FIX,
    WIFI_CROSS_FLOOR_RELOCALIZATION, WIFI_CROSS_FLOOR_DEFERRED, WIFI_CROSS_FLOOR_VETOED, BAROMETER_HEALTH,
}

/** One coordinator event; the fields mirror the floor_transition_events columns (see FloorEventLog). */
data class FloorTrackingEvent(
    val type: FloorTrackingEventType,
    val atNanos: Long,
    val fromFloorId: String? = null,
    val toFloorId: String? = null,
    val movedShare: Double? = null,
    val floorVoteAfter: Double? = null,
    val heightDeltaMetres: Double? = null,
    val pressureHpa: Double? = null,
    val barometerHealth: BarometerHealth,
    val detail: String = "",
)

/**
 * Ties the barometer, the vertical-motion detector, the no-barometer dwell cues and the floor decision to a
 * multi-floor [ParticleFilter] (plan Step 5). Pure core: no clock, no Android. All times are elapsed-realtime nanos,
 * the base sensor timestamps share. The filter is passed into each call because the caller replaces it on
 * relocalisation; a null or lost filter makes evidence calls no-ops (they are logged, never thrown).
 *
 * Only for sessions with a usable topology: build it with [createOrNull], which returns null for
 * [FloorTopology.NONE] so single-floor sessions keep today's code path untouched (invariant I1).
 *
 * - Barometer events go to [ParticleFilter.applyVerticalEvidence]. APPLIED anchors the barometer and marks CHANGING.
 *   REJECTED keeps the unexplained height pending (no anchor, drift compensation held) and marks UNCERTAIN; Wi-Fi that
 *   agrees with that height can then relocalise; if nothing resolves it within the pending window it is anchored away.
 * - Without a healthy barometer, stairs and lift dwell cues seed a share of the hypotheses through captured portals.
 * - Wi-Fi pointing at another floor passes [crossFloorWifi] (ALLOW, DEFER or VETO), see [correctWithWifi].
 * - [decide] runs the hysteresis; the display must follow its floor only (invariant I3).
 */
class FloorTrackingCoordinator(
    val topology: FloorTopology,
    private val config: FloorDetectionConfig = FloorDetectionConfig(),
    barometerPresent: Boolean,
    startFloorId: String,
    private val tracking: FloorTrackingConfig = FloorTrackingConfig(),
) {
    init {
        require(!topology.isEmpty) { "A floor tracking coordinator needs a multi-floor topology (single-floor sessions have none)" }
        require(startFloorId in topology.levels) { "Start floor $startFloorId is not in the session topology" }
    }

    private class Pending(val heightMetres: Double, val untilNanos: Long)

    private val barometer = BarometerTracker(config.barometer, barometerPresent)
    private val detector = VerticalMotionDetector(config.vertical)
    private val cues = MotionContextTracker(
        config.cues, topology.portals.associate { it.index to CuePortal(it.kind, it.travelTimeSeconds) },
    )
    private val decision = FloorDecision(topology, config.decision, startFloorId)
    private val stepTimes = ArrayDeque<Long>()
    private val events = mutableListOf<FloorTrackingEvent>()

    private var lastNanos = Long.MIN_VALUE
    private var lastTickNanos = Long.MIN_VALUE
    private var lastHealth: BarometerHealth = if (barometerPresent) BarometerHealth.STALE else BarometerHealth.UNAVAILABLE
    private var hasFix = false
    private var pending: Pending? = null
    private var lastEventFloors: Set<Int>? = null
    private var lastEventFloorsUntil = Long.MIN_VALUE
    private var probationUntil = Long.MIN_VALUE
    private var strongFloor: String? = null
    private var strongCount = 0
    private var vetoFloor: String? = null
    private var vetoCount = 0
    private var lastState: FloorState? = null

    init {
        val json = Json.encodeToString(FloorDetectionConfig.serializer(), config)
        emit(FloorTrackingEventType.CONFIG, 0L, detail = "barometerPresent=$barometerPresent; config=$json")
    }

    /** Barometer health at the newest input time. */
    val barometerHealth: BarometerHealth get() = barometer.health(lastNanos.coerceAtLeast(0L))

    /** Newest accepted raw pressure (for PdrEventEntity.pressureHpa in multi-floor sessions). */
    val latestPressureHpa: Double? get() = barometer.latestHpa

    /**
     * Pass to [ParticleFilter.predictStep]: true while a vertical segment is open (moving or settling), whatever its kind.
     * Deviation from the plan, which excluded lifts: a lift event settles about 10 s after the ride, while the user is
     * already walking out of the car, so the damping is what keeps lift particles within portal memory until then.
     */
    val stairsActive: Boolean get() = detector.moving

    /** The confirmed floor (what the display shows). */
    val confirmedFloorId: String get() = decision.floorId

    /** True while a REJECTED barometric height is still unexplained. */
    val heightPending: Boolean get() = pending != null

    /** One barometer sample. Returns the filter's transition result when a settled vertical event was applied or rejected. */
    fun onPressure(tsNanos: Long, hpa: Double, filter: ParticleFilter?): FloorTransitionResult? {
        see(tsNanos)
        val state = barometer.add(tsNanos, hpa) ?: return null
        val event = detector.onBarometer(state, stepsInWindow(tsNanos)) ?: return null
        emit(
            FloorTrackingEventType.VERTICAL_EVENT, tsNanos, heightDeltaMetres = event.heightDeltaMetres,
            detail = "${event.direction} ${event.floorCandidates.sorted().joinToString("/")} floor(s) ${event.kind}",
        )
        if (filter == null || filter.isLost) {
            reject(tsNanos, "no particles to move")
            return null
        }
        val t = config.transition
        val result = filter.applyVerticalEvidence(
            VerticalEvidence(event.direction, event.floorCandidates, event.kind, t.barometricShare, t.outOfPortalLikelihood, t.unverifiedFactor),
        )
        if (result.accepted) applied(tsNanos, filter, event.floorCandidates, event.heightDeltaMetres)
        else reject(tsNanos, "height changed away from a floor link")
        return result
    }

    /**
     * One accepted step, after [ParticleFilter.predictStep]. Without a healthy barometer a dwell cue may seed floor
     * hypotheses; returns that seeding's result.
     */
    fun onStep(tsNanos: Long, filter: ParticleFilter?): FloorTransitionResult? {
        see(tsNanos)
        stepTimes.addLast(tsNanos)
        pruneSteps(tsNanos)
        if (filter == null || filter.isLost) return null
        if (barometer.health(tsNanos) == BarometerHealth.OK) {
            cues.reset() // the barometer is authoritative; dwell cues start afresh if it goes away
            return null
        }
        val cue = cues.onStep(tsNanos, filter.portalWeights()) ?: return null
        val share = if (cue.kind == VerticalKindHint.LIFT) config.cues.liftSeedShare else config.cues.stairsSeedShare
        emit(FloorTrackingEventType.DWELL_CUE, tsNanos, detail = "${cue.kind} portals ${cue.portalIndices.sorted()}")
        val result = filter.seedFloorHypotheses(cue.kind, cue.portalIndices, share, config.transition.unverifiedFactor)
        if (result.accepted) {
            val floors = cue.portalIndices.mapNotNull { topology.portals.getOrNull(it) }.map { abs(it.levelDelta) }.toSet()
            applied(tsNanos, filter, floors, null)
        } else {
            emit(FloorTrackingEventType.REJECTED, tsNanos, detail = "dwell cue found no particle in its links")
        }
        return result
    }

    /**
     * Housekeeping at up to about 1 Hz (extra calls are ignored): barometer health changes, pending-height expiry and
     * lift stillness for the dwell cue. Never moves particles; a lift cue is reported on the next step.
     */
    fun onTick(nowNanos: Long, filter: ParticleFilter?) {
        see(nowNanos)
        if (lastTickNanos != Long.MIN_VALUE && nowNanos - lastTickNanos < nanos(tracking.tickSeconds)) return
        lastTickNanos = nowNanos
        val health = barometer.health(nowNanos)
        if (health != lastHealth) {
            emit(FloorTrackingEventType.BAROMETER_HEALTH, nowNanos, detail = "$lastHealth -> $health")
            lastHealth = health
        }
        pending?.let {
            if (nowNanos >= it.untilNanos) {
                clearPending(nowNanos)
                decision.clearUncertain()
                emit(FloorTrackingEventType.PENDING_EXPIRED, nowNanos, heightDeltaMetres = it.heightMetres,
                    detail = "unexplained height anchored away")
            }
        }
        if (nowNanos >= lastEventFloorsUntil) lastEventFloors = null
        if (health != BarometerHealth.OK && filter != null && !filter.isLost) cues.onTick(nowNanos, filter.portalWeights())
    }

    /**
     * Policy for a Wi-Fi match whose floor differs from the confirmed (displayed) floor. It counts consecutive fresh
     * strong matches on the same other floor; any other result resets the count.
     * - A match on the filter's leading floor ([current], else the confirmed floor), or a stale or floorless match, is
     *   ALLOW: the filter needs no cross-floor permission for it.
     * - A match on the confirmed floor while the filter still leads on another one (a moment around a floor change) is a
     *   quiet DEFER: no veto, no count and no UNCERTAIN, and the filter only reweights towards it.
     * Phase 4 fix: the vetoes compared against the leading floor alone, so Wi-Fi agreeing with the displayed floor right
     * after a change could be vetoed and mark the floor uncertain.
     */
    fun crossFloorWifi(match: WifiMatchResult, current: ParticleSummary?, nowNanos: Long): CrossFloorWifi {
        see(nowNanos)
        val target = match.estimatedFloorId
        val from = decision.floorId
        val leading = current?.floorId ?: from
        if (match.freshness != WifiScanFreshness.FRESH || target == null || target == leading) {
            resetWifiCounts()
            return CrossFloorWifi.ALLOW
        }
        if (target == from) {
            resetWifiCounts()
            return CrossFloorWifi.DEFER
        }
        val w = config.wifi
        val strong = !match.unlikeDatabase && match.confidence >= w.strongConfidence && match.floorAgreement >= w.strongFloorAgreement
        if (!strong) {
            resetWifiCounts()
            return CrossFloorWifi.DEFER
        }
        if (strongFloor == target) strongCount++ else { strongFloor = target; strongCount = 1 }
        val p = pending
        val needed: Int? = when {
            barometer.health(nowNanos) != BarometerHealth.OK -> w.matchesWithoutBarometer
            p != null -> if (heightConsistent(p, from, target)) w.matchesWithConsistentHeight else null
            nowNanos < probationUntil -> w.matchesInProbation
            else -> null
        }
        if (needed == null) {
            if (vetoFloor == target) vetoCount++ else { vetoFloor = target; vetoCount = 1 }
            emit(FloorTrackingEventType.WIFI_CROSS_FLOOR_VETOED, nowNanos, fromFloorId = from, toFloorId = target,
                detail = "strong match $strongCount on $target vetoed (${if (p != null) "pending height disagrees" else "barometer flat"}); veto $vetoCount")
            if (vetoCount >= w.vetoesBeforeUncertain) {
                decision.markUncertain("Wi-Fi suggests another floor", nowNanos + nanos(tracking.wifiUncertainSeconds))
            }
            return CrossFloorWifi.VETO
        }
        vetoFloor = null
        vetoCount = 0
        if (strongCount >= needed) return CrossFloorWifi.ALLOW
        emit(FloorTrackingEventType.WIFI_CROSS_FLOOR_DEFERRED, nowNanos, fromFloorId = from, toFloorId = target,
            detail = "strong match $strongCount of $needed on $target")
        return CrossFloorWifi.DEFER
    }

    /**
     * Whether a Wi-Fi match may (re)initialise the filter, e.g. after FILTER_LOST: before any fix (session start), when
     * the user asked to relocalise, on the confirmed floor, or when [crossFloorWifi] allows it. Otherwise the caller
     * shows "Wi-Fi suggests Floor X · waiting for confirmation".
     */
    fun allowWifiInit(match: WifiMatchResult, nowNanos: Long, userRequested: Boolean): Boolean {
        see(nowNanos)
        if (!hasFix || userRequested || match.estimatedFloorId == decision.floorId) return true
        return crossFloorWifi(match, null, nowNanos) == CrossFloorWifi.ALLOW
    }

    /**
     * Applies [match] to [filter] under the cross-floor policy: a lost filter is rebuilt only if [allowWifiInit] allows
     * it (then with crossFloorRelocalizationAllowed = true, as Step 4 requires), otherwise the flag is
     * `crossFloorWifi(...) == ALLOW`. A cross-floor GLOBAL_RELOCALIZATION is recorded as an absolute fix.
     */
    fun correctWithWifi(
        filter: ParticleFilter, match: WifiMatchResult, nowNanos: Long,
        userRequested: Boolean = false, allowGlobalRelocalization: Boolean = true,
    ): WifiCorrectionResult {
        val prior = filter.summary()
        val allowed = if (filter.isLost) allowWifiInit(match, nowNanos, userRequested)
            else crossFloorWifi(match, prior, nowNanos) == CrossFloorWifi.ALLOW
        val result = filter.correctWithWifi(match, allowGlobalRelocalization, crossFloorRelocalizationAllowed = allowed)
        onWifiCorrection(prior, result, nowNanos)
        return result
    }

    /**
     * For callers that run [ParticleFilter.correctWithWifi] themselves: a GLOBAL_RELOCALIZATION onto a floor other than
     * the confirmed one is an absolute fix (anchor, decision reset, WIFI_CROSS_FLOOR_RELOCALIZATION).
     */
    fun onWifiCorrection(prior: ParticleSummary?, result: WifiCorrectionResult, nowNanos: Long) {
        see(nowNanos)
        val floor = result.summary?.floorId ?: return
        if (result.kind != WifiCorrectionKind.GLOBAL_RELOCALIZATION || floor == decision.floorId) return
        val from = decision.floorId
        absoluteFix(floor, nowNanos)
        emit(FloorTrackingEventType.WIFI_CROSS_FLOOR_RELOCALIZATION, nowNanos, fromFloorId = from, toFloorId = floor,
            detail = "from ${prior?.floorId ?: "no estimate"}")
    }

    /** Manual start, QR, Wi-Fi init (start or user Relocalize): the floor is confirmed at once (invariant I2). */
    fun onAbsoluteFix(floorId: String, source: FloorFixSource, nowNanos: Long) {
        require(floorId in topology.levels) { "Floor $floorId is not in the session topology" }
        see(nowNanos)
        val from = decision.floorId
        absoluteFix(floorId, nowNanos)
        probationUntil = when (source) {
            FloorFixSource.WIFI_INIT, FloorFixSource.USER_RELOCALIZE -> nowNanos + nanos(config.wifi.probationSeconds)
            FloorFixSource.MANUAL, FloorFixSource.QR -> Long.MIN_VALUE
            FloorFixSource.WIFI_CROSS_FLOOR -> probationUntil
        }
        emit(FloorTrackingEventType.ABSOLUTE_FIX, nowNanos, fromFloorId = from, toFloorId = floorId, detail = source.name)
    }

    /** The confirmed floor for display (invariant I3). Call after each update of the filter. */
    fun decide(filter: ParticleFilter?, nowNanos: Long): FloorDecisionResult {
        see(nowNanos)
        val votes = filter?.floorVotes().orEmpty()
        val result = decision.update(votes, nowNanos, lastEventFloors, detector.moving)
        if (result.changedFrom != null) {
            lastEventFloors = null
            emit(FloorTrackingEventType.CONFIRMED, nowNanos, fromFloorId = result.changedFrom, toFloorId = result.floorId,
                floorVoteAfter = result.vote)
        }
        if (result.state == FloorState.UNCERTAIN && lastState != FloorState.UNCERTAIN) {
            emit(FloorTrackingEventType.UNCERTAIN, nowNanos, toFloorId = result.floorId, floorVoteAfter = result.vote,
                detail = result.uncertainReason.orEmpty())
        }
        lastState = result.state
        return result
    }

    /** Events since the last drain, in order (persisted in phase 4). */
    fun drainEvents(): List<FloorTrackingEvent> = events.toList().also { events.clear() }

    // ----- internals ---------------------------------------------------------------------------------------

    private fun applied(tsNanos: Long, filter: ParticleFilter, floors: Set<Int>, heightDelta: Double?) {
        pending = null
        barometer.driftCompensationHeld = false
        if (barometer.latestHpa != null) barometer.anchor(tsNanos.coerceAtLeast(0L))
        decision.markChanging(tsNanos + nanos(config.decision.changingHoldSeconds))
        lastEventFloors = floors
        lastEventFloorsUntil = tsNanos + nanos(tracking.lastEventFloorsSeconds)
        val detail = filter.events.lastOrNull()?.detail.orEmpty()
        val moved = Regex("Moved ([0-9.]+)").find(detail)?.groupValues?.get(1)?.toDoubleOrNull()
        emit(FloorTrackingEventType.APPLIED, tsNanos, fromFloorId = decision.floorId, movedShare = moved,
            heightDeltaMetres = heightDelta, detail = detail)
    }

    private fun reject(tsNanos: Long, reason: String) {
        val height = barometer.heightSinceAnchorMetres
        val h = config.vertical.floorHeightMetres
        if (pending != null && abs(height) < config.vertical.minimumFloorFraction * h) {
            // Back at the anchored height (e.g. up and down again away from links): nothing is unexplained any more.
            clearPending(tsNanos)
            decision.clearUncertain()
            emit(FloorTrackingEventType.REJECTED, tsNanos, heightDeltaMetres = height, detail = "$reason; back at the anchored height")
            return
        }
        val until = tsNanos + nanos(config.wifi.pendingWindowSeconds)
        pending = Pending(height, until)
        barometer.driftCompensationHeld = true
        decision.markUncertain(reason, until)
        emit(FloorTrackingEventType.REJECTED, tsNanos, heightDeltaMetres = height, detail = reason)
    }

    private fun absoluteFix(floorId: String, nowNanos: Long) {
        hasFix = true
        if (barometer.latestHpa != null) barometer.anchor(nowNanos.coerceAtLeast(0L))
        clearPending(nowNanos)
        detector.reset()
        cues.reset()
        decision.reset(floorId, nowNanos)
        lastEventFloors = null
        resetWifiCounts()
    }

    private fun clearPending(nowNanos: Long) {
        if (pending != null && barometer.latestHpa != null) barometer.anchor(nowNanos.coerceAtLeast(0L))
        pending = null
        barometer.driftCompensationHeld = false
    }

    private fun heightConsistent(p: Pending, from: String, target: String): Boolean {
        val levels = topology.levels
        val fromLevel = levels[from] ?: return false
        val toLevel = levels[target] ?: return false
        val h = config.vertical.floorHeightMetres
        return abs(p.heightMetres - (toLevel - fromLevel) * h) <= config.wifi.pendingHeightToleranceFloors * h
    }

    private fun resetWifiCounts() {
        strongFloor = null
        strongCount = 0
        vetoFloor = null
        vetoCount = 0
    }

    private fun stepsInWindow(tsNanos: Long): Int {
        val from = tsNanos - nanos(config.vertical.rateWindowSeconds)
        return stepTimes.count { it > from && it <= tsNanos }
    }

    private fun pruneSteps(tsNanos: Long) {
        val keepFrom = tsNanos - nanos(config.vertical.rateWindowSeconds + 1.0)
        while (stepTimes.isNotEmpty() && stepTimes.first() < keepFrom) stepTimes.removeFirst()
    }

    private fun see(nanos: Long) {
        if (nanos > lastNanos) lastNanos = nanos
    }

    private fun emit(
        type: FloorTrackingEventType, atNanos: Long, fromFloorId: String? = null, toFloorId: String? = null,
        movedShare: Double? = null, floorVoteAfter: Double? = null, heightDeltaMetres: Double? = null, detail: String = "",
    ) {
        events += FloorTrackingEvent(
            type, atNanos, fromFloorId, toFloorId, movedShare, floorVoteAfter, heightDeltaMetres, barometer.latestHpa,
            if (lastNanos == Long.MIN_VALUE) lastHealth else barometer.health(lastNanos.coerceAtLeast(0L)), detail,
        )
    }

    private fun nanos(seconds: Double) = (seconds * 1e9).toLong()

    companion object {
        /** Null for [FloorTopology.NONE]: the session is single-floor and runs today's code (invariant I1). */
        fun createOrNull(
            topology: FloorTopology, config: FloorDetectionConfig, barometerPresent: Boolean, startFloorId: String,
            tracking: FloorTrackingConfig = FloorTrackingConfig(),
        ): FloorTrackingCoordinator? =
            if (topology.isEmpty || startFloorId !in topology.levels) null
            else FloorTrackingCoordinator(topology, config, barometerPresent, startFloorId, tracking)
    }
}
