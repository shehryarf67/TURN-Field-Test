package com.turn.fieldtest.core

import java.util.Locale
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

data class Particle(
    val x: Double,
    val y: Double,
    val floorId: String,
    val headingOffsetRadians: Double,
    val strideScale: Double,
    val weight: Double,
    /** Position before the latest step; magnetic evidence compares the change across that step. */
    val previousX: Double = x,
    val previousY: Double = y,
    /**
     * Portal memory, written only by a multi-floor filter: the index in [FloorTopology.portals] of the last
     * portal whose area held the particle (-1 for none), and the steps taken since it left that area.
     */
    val portalIndex: Int = -1,
    val stepsSincePortal: Int = Int.MAX_VALUE,
) {
    init {
        require(x.isFinite() && y.isFinite())
        require(floorId.isNotBlank())
        require(headingOffsetRadians.isFinite())
        require(strideScale.isFinite() && strideScale > 0.0)
        require(weight.isFinite() && weight >= 0.0)
        require(portalIndex >= -1)
        require(stepsSincePortal >= 0)
    }

    val position: MetricPoint get() = MetricPoint(x, y)
}

data class ParticleFilterConfig(
    val particleCount: Int = 500,
    val initialPositionStdMetres: Double = 1.25,
    val qrPositionStdMetres: Double = 0.20,
    val initialHeadingOffsetStdRadians: Double = 8.0 * PI / 180.0,
    val initialStrideScaleStd: Double = 0.04,
    val movementStdMetres: Double = 0.08,
    val headingNoiseStdRadians: Double = 4.0 * PI / 180.0,
    val headingOffsetRandomWalkStdRadians: Double = 0.5 * PI / 180.0,
    val strideScaleRandomWalkStd: Double = 0.004,
    val correctionSpatialSigmaMetres: Double = 3.0,
    val crossFloorLikelihood: Double = 0.01,
    val resampleEffectiveSizeRatio: Double = 0.55,
    val strongWifiConfidence: Double = 0.68,
    val globalRelocalizationDistanceMetres: Double = 8.0,
    val minimumRelocalizationStdMetres: Double = 0.35,
    val maximumRelocalizationStdMetres: Double = 4.0,
    val clusterRadiusMetres: Double = 3.0,
    /** Share of particles re-drawn around each confident fresh Wi-Fi fix before reweighting. */
    val wifiInjectionFraction: Double = 0.1,
    val wifiInjectionMinimumConfidence: Double = 0.4,
    // Multi-floor only: read only when the filter has a non-empty FloorTopology.
    /** Extra distance beyond a portal's radius that still counts as inside its area. */
    val portalSlackMetres: Double = 1.0,
    /** Steps after leaving a portal area during which a particle still counts as having used it. */
    val portalMemorySteps: Int = 10,
    /** Stride multiplier on stairs, for particles with portal memory, while stairs movement is active. */
    val stairsStrideFactor: Double = 0.5,
    /** Upper bound of the exit spread; the spread is min(radius / 2, this). */
    val exitSpreadMaxMetres: Double = 1.0,
    val exitSampleTries: Int = 20,
    /** Most one-hop passes a mover makes to cover a multi-floor change through chained links. */
    val maxHops: Int = 4,
    /** Floor-stratified resampling keeps at least this many particles on every floor it keeps. */
    val minParticlesPerFloor: Int = 10,
    /** Floor-stratified resampling drops floors below this weight, except the strongest. */
    val floorPruneWeight: Double = 1e-3,
) {
    init {
        require(particleCount > 0)
        require(initialPositionStdMetres >= 0.0)
        require(qrPositionStdMetres >= 0.0)
        require(initialHeadingOffsetStdRadians >= 0.0)
        require(initialStrideScaleStd >= 0.0)
        require(movementStdMetres >= 0.0)
        require(headingNoiseStdRadians >= 0.0)
        require(headingOffsetRandomWalkStdRadians >= 0.0)
        require(strideScaleRandomWalkStd >= 0.0)
        require(correctionSpatialSigmaMetres > 0.0)
        require(crossFloorLikelihood in 0.0..1.0)
        require(resampleEffectiveSizeRatio in 0.0..1.0)
        require(strongWifiConfidence in 0.0..1.0)
        require(globalRelocalizationDistanceMetres > 0.0)
        require(minimumRelocalizationStdMetres >= 0.0)
        require(maximumRelocalizationStdMetres >= minimumRelocalizationStdMetres)
        require(clusterRadiusMetres > 0.0)
        require(wifiInjectionFraction in 0.0..0.5)
        require(wifiInjectionMinimumConfidence in 0.0..1.0)
        require(portalSlackMetres.isFinite() && portalSlackMetres >= 0.0)
        require(portalMemorySteps >= 0)
        require(stairsStrideFactor.isFinite() && stairsStrideFactor > 0.0 && stairsStrideFactor <= 1.0)
        require(exitSpreadMaxMetres.isFinite() && exitSpreadMaxMetres >= 0.0)
        require(exitSampleTries >= 1)
        require(maxHops in 1..8)
        require(minParticlesPerFloor >= 1)
        require(floorPruneWeight.isFinite() && floorPruneWeight >= 0.0 && floorPruneWeight < 0.5)
    }
}

enum class AbsoluteFixSource {
    MANUAL,
    WIFI,
    QR,
}

data class AbsoluteFix(
    val position: MetricPoint,
    val floorId: String,
    val source: AbsoluteFixSource,
    val initialHeadingOffsetRadians: Double = 0.0,
)

enum class FilterEventType {
    INITIALIZED,
    PDR_PREDICTION,
    MAP_CONSTRAINT_APPLIED,
    WIFI_CORRECTION,
    GLOBAL_RELOCALIZED,
    MAGNETIC_CORRECTION,
    QR_CORRECTION,
    FLOOR_TRANSITION,
    FLOOR_TRANSITION_REJECTED,
    FILTER_LOST,
    /** Multi-floor only: Wi-Fi pointed at another floor and the caller did not allow a cross-floor jump. */
    CROSS_FLOOR_WIFI_DEFERRED,
}

data class FilterEvent(
    val sequence: Long,
    val type: FilterEventType,
    val rejectedParticleCount: Int = 0,
    val detail: String,
)

data class ParticleSummary(
    val position: MetricPoint,
    val floorId: String,
    val floorConfidence: Double,
    val confidence: Double,
    val uncertaintyRadiusMetres: Double,
    val spatialSpreadMetres: Double,
    val clusterParticleCount: Int,
)

data class PredictionResult(
    val accepted: Boolean,
    val rejectedParticleCount: Int,
    val survivingParticleCountBeforeReplenishment: Int,
    val constraints: Map<MovementConstraint, Int>,
    val summary: ParticleSummary?,
)

enum class WifiCorrectionKind {
    APPLIED,
    GLOBAL_RELOCALIZATION,
    IGNORED_NOT_FRESH,
    IGNORED_NO_ESTIMATE,
    FILTER_LOST,
    /** Multi-floor only: the match needs a jump to another floor that was not allowed; particles are unchanged. */
    DEFERRED_CROSS_FLOOR,
}

data class WifiCorrectionResult(
    val kind: WifiCorrectionKind,
    val resampled: Boolean,
    val effectiveParticleCountBeforeResampling: Double,
    val summary: ParticleSummary?,
)

enum class MagneticCorrectionKind {
    APPLIED,
    /** No magnetic map for the floor the filter believes it is on. */
    IGNORED_NO_MAP,
    /** Most of the cloud is on unsurveyed cells, so the evidence cannot discriminate. */
    IGNORED_UNMAPPED_AREA,
    /** The reading is implausible for Earth's field (strong nearby interference). */
    IGNORED_DISTURBED,
    IGNORED_NO_PARTICLES,
}

data class MagneticCorrectionResult(
    val kind: MagneticCorrectionKind,
    val mappedWeight: Double,
    val resampled: Boolean,
    val summary: ParticleSummary?,
)

data class FloorTransitionResult(
    val accepted: Boolean,
    val rejectedParticleCount: Int,
    val summary: ParticleSummary?,
)

/**
 * Evidence that the user changed floor, e.g. a settled barometer event. [share] of each eligible particle's
 * weight moves through compatible portals ([unverifiedFactor] scales it for links not checked on site);
 * particles in no compatible portal are multiplied by [outOfPortalLikelihood].
 */
data class VerticalEvidence(
    val direction: VerticalDirection,
    val floorCandidates: Set<Int>,
    val kind: VerticalKindHint,
    val share: Double,
    val outOfPortalLikelihood: Double,
    val unverifiedFactor: Double,
) {
    init {
        require(floorCandidates.isNotEmpty() && floorCandidates.all { it > 0 }) { "Floor candidates must be positive" }
        require(share in 0.0..1.0)
        require(outOfPortalLikelihood in 0.0..1.0)
        require(unverifiedFactor in 0.0..1.0)
    }
}

/**
 * Seeded particle-filter fusion. It deliberately has no clock, persistence or Android dependency;
 * callers persist returned [FilterEvent] values with their own timestamps.
 *
 * With a non-empty [floorTopology] the filter tracks several floors: particles change floor only through
 * the topology's portals ([applyVerticalEvidence], [seedFloorHypotheses]) or an absolute fix, resampling keeps
 * every floor's posterior, and Wi-Fi cannot move the cloud to another floor unless the caller allows it.
 * Without one ([FloorTopology.NONE]) every multi-floor branch is skipped and behaviour is today's, bit for bit.
 */
class ParticleFilter(
    private val metricMap: MetricMap,
    private val config: ParticleFilterConfig = ParticleFilterConfig(),
    seed: Long = 0L,
    private val floorTopology: FloorTopology = FloorTopology.NONE,
) {
    private val random = Random(seed)
    private val multiFloor = !floorTopology.isEmpty
    private var cachedSummary: ParticleSummary? = null
    /** Per-floor summaries since the last particle update (phase 4: the 1 Hz live tick asked for them uncached). */
    private val cachedFloorSummaries = mutableMapOf<String, ParticleSummary?>()
    private var mutableParticles: List<Particle> = emptyList()
        set(value) { field = value; cachedSummary = null; cachedFloorSummaries.clear() }
    private val mutableEvents = mutableListOf<FilterEvent>()
    private var nextEventSequence = 1L

    init {
        if (multiFloor) {
            val missing = floorTopology.portals.flatMap { listOf(it.fromFloorId, it.toFloorId) }
                .filter { it !in metricMap.floors }.distinct()
            require(missing.isEmpty()) { "Floor topology references floors missing from the map: $missing" }
        }
    }

    val particles: List<Particle> get() = mutableParticles.toList()
    val events: List<FilterEvent> get() = mutableEvents.toList()
    val isLost: Boolean get() = mutableParticles.isEmpty()

    fun initialize(
        fix: AbsoluteFix,
        positionStdMetres: Double = config.initialPositionStdMetres,
    ): ParticleSummary {
        initializeParticles(fix, positionStdMetres)
        addEvent(FilterEventType.INITIALIZED, detail = "Initialized from ${fix.source}")
        return requireNotNull(summary())
    }

    /**
     * Moves every particle by one step. [stairsActive] (multi-floor only) shortens the step by
     * [ParticleFilterConfig.stairsStrideFactor] for particles that were recently in a portal, so they are
     * still near the link when the barometer settles.
     */
    fun predictStep(strideMetres: Double, measuredHeadingRadians: Double, stairsActive: Boolean = false): PredictionResult {
        require(strideMetres.isFinite() && strideMetres > 0.0)
        require(measuredHeadingRadians.isFinite())
        if (mutableParticles.isEmpty()) {
            addEvent(FilterEventType.FILTER_LOST, detail = "PDR prediction requested without particles")
            return PredictionResult(false, 0, 0, emptyMap(), null)
        }

        val rejectedByConstraint = linkedMapOf<MovementConstraint, Int>()
        val survivors = mutableListOf<Particle>()
        mutableParticles.forEach { particle ->
            val headingOffset = normalizeRadians(
                particle.headingOffsetRadians + gaussian(config.headingOffsetRandomWalkStdRadians),
            )
            val strideScale = (particle.strideScale + gaussian(config.strideScaleRandomWalkStd))
                .coerceIn(StrideModel.MIN_SCALE, StrideModel.MAX_SCALE)
            val heading = measuredHeadingRadians + headingOffset + gaussian(config.headingNoiseStdRadians)
            val sampledDistance = max(0.0, strideMetres * strideScale + gaussian(config.movementStdMetres))
            val distance = if (multiFloor && stairsActive && particle.stepsSincePortal <= config.portalMemorySteps) {
                sampledDistance * config.stairsStrideFactor
            } else {
                sampledDistance
            }
            val candidate = MetricPoint(
                particle.x + distance * cos(heading),
                particle.y + distance * sin(heading),
            )
            val constraint = metricMap.movementConstraint(particle.floorId, particle.position, candidate)
            if (constraint == MovementConstraint.ALLOWED) {
                val moved = particle.copy(
                    x = candidate.x,
                    y = candidate.y,
                    headingOffsetRadians = headingOffset,
                    strideScale = strideScale,
                    previousX = particle.x,
                    previousY = particle.y,
                )
                survivors += if (multiFloor) updatePortalMemory(moved) else moved
            } else {
                rejectedByConstraint[constraint] = rejectedByConstraint.getOrDefault(constraint, 0) + 1
            }
        }
        val rejectedCount = mutableParticles.size - survivors.size
        if (survivors.isEmpty()) {
            mutableParticles = emptyList()
            addEvent(
                FilterEventType.FILTER_LOST,
                rejectedParticleCount = rejectedCount,
                detail = "Every proposed movement violated map constraints",
            )
            return PredictionResult(false, rejectedCount, 0, rejectedByConstraint, null)
        }

        mutableParticles = if (survivors.size < config.particleCount) {
            resample(normalizeWeights(survivors), config.particleCount)
        } else {
            normalizeWeights(survivors)
        }
        if (rejectedCount > 0) {
            addEvent(
                FilterEventType.MAP_CONSTRAINT_APPLIED,
                rejectedParticleCount = rejectedCount,
                detail = "Rejected impossible PDR proposals and replenished survivors",
            )
        } else {
            addEvent(FilterEventType.PDR_PREDICTION, detail = "Propagated particles by one step")
        }
        return PredictionResult(
            accepted = true,
            rejectedParticleCount = rejectedCount,
            survivingParticleCountBeforeReplenishment = survivors.size,
            constraints = rejectedByConstraint,
            summary = summary(),
        )
    }

    /**
     * [crossFloorRelocalizationAllowed] is read only by a multi-floor filter. When false, a match on a floor other
     * than the current estimate's never relocalises, injects or rebuilds the cloud there: it only reweights, and if
     * the weights would collapse the particles are kept and [WifiCorrectionKind.DEFERRED_CROSS_FLOOR] is returned.
     */
    fun correctWithWifi(
        match: WifiMatchResult,
        allowGlobalRelocalization: Boolean = true,
        crossFloorRelocalizationAllowed: Boolean = true,
    ): WifiCorrectionResult {
        if (match.freshness != WifiScanFreshness.FRESH) {
            addEvent(
                FilterEventType.WIFI_CORRECTION,
                detail = "Ignored ${match.freshness} Wi-Fi evidence; only fresh scans may correct the filter",
            )
            return WifiCorrectionResult(
                WifiCorrectionKind.IGNORED_NOT_FRESH,
                resampled = false,
                effectiveParticleCountBeforeResampling = effectiveParticleCount(),
                summary = summary(),
            )
        }
        val wifiPosition = match.estimatedPosition
        val wifiFloor = match.estimatedFloorId
        if (wifiPosition == null || wifiFloor == null || match.neighbours.isEmpty() || match.unlikeDatabase || match.spatiallyAmbiguous) {
            return WifiCorrectionResult(WifiCorrectionKind.IGNORED_NO_ESTIMATE, false, 0.0, summary())
        }

        val current = summary()
        val isStrong = !match.unlikeDatabase && match.confidence >= config.strongWifiConfidence
        val cloudCannotExplainMatch = current == null || current.floorId != wifiFloor ||
            current.position.distanceTo(wifiPosition) > config.globalRelocalizationDistanceMetres
        // Multi-floor only. Without a current floor (lost filter) any rebuild would pick a floor, so it is blocked too.
        val crossFloorBlocked = multiFloor && !crossFloorRelocalizationAllowed && current?.floorId != wifiFloor
        if (allowGlobalRelocalization && isStrong && cloudCannotExplainMatch && !crossFloorBlocked) {
            relocateFromWifi(match)
            return WifiCorrectionResult(
                WifiCorrectionKind.GLOBAL_RELOCALIZATION,
                resampled = true,
                effectiveParticleCountBeforeResampling = 0.0,
                summary = summary(),
            )
        }
        if (mutableParticles.isEmpty()) {
            if (crossFloorBlocked && allowGlobalRelocalization && isStrong) return deferCrossFloor(wifiFloor)
            addEvent(FilterEventType.FILTER_LOST, detail = "Wi-Fi evidence was not strong enough to relocalize")
            return WifiCorrectionResult(WifiCorrectionKind.FILTER_LOST, false, 0.0, null)
        }

        val evidenceStrength = match.confidence.coerceIn(0.05, 1.0)
        val beforeCorrection = mutableParticles
        if (!multiFloor || wifiFloor == current?.floorId) injectAroundWifi(match, wifiPosition, wifiFloor)
        val corrected = mutableParticles.map { particle ->
            val likelihood = if (multiFloor) multiFloorWifiLikelihood(particle, match) else match.neighbours.sumOf { neighbour ->
                val sigma = config.correctionSpatialSigmaMetres * (1.0 + neighbour.distance / 25.0)
                val squaredDistance = particle.position.distanceTo(neighbour.position).squared()
                val spatial = exp(-squaredDistance / (2.0 * sigma.squared()))
                val floorFactor = if (particle.floorId == neighbour.floorId) 1.0
                else config.crossFloorLikelihood
                neighbour.weight * spatial * floorFactor
            }
            val blendedLikelihood = (1.0 - evidenceStrength) + evidenceStrength * likelihood
            particle.copy(weight = particle.weight * max(blendedLikelihood, 1e-300))
        }
        val total = corrected.sumOf { it.weight }
        if (!total.isFinite() || total <= 1e-300) {
            if (crossFloorBlocked) {
                mutableParticles = beforeCorrection
                return deferCrossFloor(wifiFloor)
            }
            if (allowGlobalRelocalization && isStrong) {
                relocateFromWifi(match)
                return WifiCorrectionResult(
                    WifiCorrectionKind.GLOBAL_RELOCALIZATION,
                    true,
                    0.0,
                    summary(),
                )
            }
            mutableParticles = emptyList()
            addEvent(FilterEventType.FILTER_LOST, detail = "Particle weights collapsed during Wi-Fi correction")
            return WifiCorrectionResult(WifiCorrectionKind.FILTER_LOST, false, 0.0, null)
        }

        mutableParticles = normalizeWeights(corrected)
        val effectiveCount = effectiveParticleCount()
        val shouldResample = effectiveCount < config.resampleEffectiveSizeRatio * config.particleCount
        if (shouldResample) mutableParticles = resample(mutableParticles, config.particleCount)
        addEvent(FilterEventType.WIFI_CORRECTION, detail = "Applied ${match.freshness} Wi-Fi likelihood")
        return WifiCorrectionResult(
            kind = WifiCorrectionKind.APPLIED,
            resampled = shouldResample,
            effectiveParticleCountBeforeResampling = effectiveCount,
            summary = summary(),
        )
    }

    /**
     * Reweights particles by how well the magnetic map explains the latest step's reading. Particles on
     * unsurveyed cells receive the mean likelihood of mapped ones, so gaps in the map neither reward nor
     * punish them. Evidence is skipped when the cloud is mostly unmapped or the reading is disturbed.
     */
    fun correctWithMagnetic(
        maps: Map<String, MagneticFingerprintMap>,
        observation: MagneticObservation,
        previousObservation: MagneticObservation?,
        magneticConfig: MagneticCorrectionConfig = MagneticCorrectionConfig(),
    ): MagneticCorrectionResult {
        if (mutableParticles.isEmpty()) return MagneticCorrectionResult(MagneticCorrectionKind.IGNORED_NO_PARTICLES, 0.0, false, null)
        if (!MagneticLikelihood.plausible(observation))
            return MagneticCorrectionResult(MagneticCorrectionKind.IGNORED_DISTURBED, 0.0, false, summary())
        val likelihoods = mutableParticles.map { p ->
            val map = maps[p.floorId]?.takeUnless { it.isEmpty } ?: return@map null
            MagneticLikelihood.evaluate(map, MetricPoint(p.previousX, p.previousY), p.position, observation,
                previousObservation, magneticConfig)
        }
        if (mutableParticles.none { maps[it.floorId]?.isEmpty == false })
            return MagneticCorrectionResult(MagneticCorrectionKind.IGNORED_NO_MAP, 0.0, false, summary())
        val total = mutableParticles.sumOf { it.weight }
        val mappedWeight = mutableParticles.indices.filter { likelihoods[it] != null }.sumOf { mutableParticles[it].weight } /
            total.coerceAtLeast(1e-300)
        if (mappedWeight < magneticConfig.minimumMappedWeight)
            return MagneticCorrectionResult(MagneticCorrectionKind.IGNORED_UNMAPPED_AREA, mappedWeight, false, summary())
        val known = likelihoods.filterNotNull()
        val neutral = known.average()
        val corrected = mutableParticles.mapIndexed { i, p ->
            val l = (likelihoods[i] ?: neutral).coerceAtLeast(1e-12)
            p.copy(weight = p.weight * Math.pow(l, magneticConfig.likelihoodExponent))
        }
        mutableParticles = normalizeWeights(corrected)
        val resample = effectiveParticleCount() < config.resampleEffectiveSizeRatio * config.particleCount
        if (resample) mutableParticles = resample(mutableParticles, config.particleCount)
        addEvent(FilterEventType.MAGNETIC_CORRECTION, detail = "Applied magnetic likelihood on %.0f%% mapped weight".format(mappedWeight * 100))
        return MagneticCorrectionResult(MagneticCorrectionKind.APPLIED, mappedWeight, resample, summary())
    }

    fun correctWithQr(
        floorId: String,
        position: MetricPoint,
        initialHeadingOffsetRadians: Double = 0.0,
    ): ParticleSummary {
        initializeParticles(
            AbsoluteFix(position, floorId, AbsoluteFixSource.QR, initialHeadingOffsetRadians),
            config.qrPositionStdMetres,
        )
        addEvent(FilterEventType.QR_CORRECTION, detail = "Collapsed cloud around QR anchor")
        return requireNotNull(summary())
    }

    /**
     * A floor change is accepted only for particles currently inside a configured transition.
     * Legacy; not used by live sessions (multi-floor sessions use [applyVerticalEvidence] and [seedFloorHypotheses]).
     */
    fun requestFloorTransition(targetFloorId: String): FloorTransitionResult {
        require(targetFloorId.isNotBlank())
        if (mutableParticles.isEmpty()) {
            addEvent(FilterEventType.FLOOR_TRANSITION_REJECTED, detail = "Filter has no particles")
            return FloorTransitionResult(false, 0, null)
        }
        val transitioned = mutableListOf<Particle>()
        var rejected = 0
        mutableParticles.forEach { particle ->
            val transition = metricMap.transitionFor(particle.floorId, targetFloorId, particle.position)
            if (transition == null) {
                rejected++
            } else {
                val destination = transition.destinationCentre(targetFloorId)
                transitioned += particle.copy(
                    x = destination.x,
                    y = destination.y,
                    floorId = targetFloorId,
                    previousX = destination.x,
                    previousY = destination.y,
                )
            }
        }
        if (transitioned.isEmpty()) {
            addEvent(
                FilterEventType.FLOOR_TRANSITION_REJECTED,
                rejectedParticleCount = rejected,
                detail = "No particle was inside a configured vertical transition",
            )
            return FloorTransitionResult(false, rejected, summary())
        }
        mutableParticles = systematicResample(normalizeWeights(transitioned), config.particleCount)
        addEvent(
            FilterEventType.FLOOR_TRANSITION,
            rejectedParticleCount = rejected,
            detail = "Transitioned through configured topology to $targetFloorId",
        )
        return FloorTransitionResult(true, rejected, summary())
    }

    fun effectiveParticleCount(): Double {
        val squaredWeightSum = mutableParticles.sumOf { it.weight.squared() }
        return if (squaredWeightSum > 0.0) 1.0 / squaredWeightSum else 0.0
    }

    fun forceResample(): ParticleSummary? {
        if (mutableParticles.isNotEmpty()) {
            mutableParticles = resample(normalizeWeights(mutableParticles), config.particleCount)
        }
        return summary()
    }

    /** Uses the highest-density spatial cluster on the strongest weighted floor. */
    fun summary(): ParticleSummary? {
        cachedSummary?.let { return it }
        if (mutableParticles.isEmpty()) return null
        val normalized = normalizeWeights(mutableParticles)
        val floorVotes = normalized.groupBy { it.floorId }
            .mapValues { (_, rows) -> rows.sumOf { it.weight } }
        val selectedFloor = floorVotes.entries
            .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
            .first()
        return summarize(normalized, selectedFloor.key, selectedFloor.value).also { cachedSummary = it }
    }

    /**
     * The same summary as [summary] but restricted to [floorId]'s particles, whatever the strongest floor is.
     * Cached until the particles next change. Null when the filter is lost or [floorId] holds no weight.
     */
    fun summary(floorId: String): ParticleSummary? {
        if (mutableParticles.isEmpty()) return null
        if (floorId in cachedFloorSummaries) return cachedFloorSummaries[floorId]
        val normalized = normalizeWeights(mutableParticles)
        // Summed in particle order, exactly as summary() sums its groups, so the argmax floor matches bit for bit.
        val vote = normalized.filter { it.floorId == floorId }.sumOf { it.weight }
        return (if (vote <= 0.0) null else summarize(normalized, floorId, vote)).also { cachedFloorSummaries[floorId] = it }
    }

    /** Normalised weight per floor, keyed in floor-id order. Empty when the filter is lost. */
    fun floorVotes(): Map<String, Double> {
        if (mutableParticles.isEmpty()) return emptyMap()
        return normalizeWeights(mutableParticles).groupBy { it.floorId }
            .mapValues { (_, rows) -> rows.sumOf { it.weight } }
            .toSortedMap()
    }

    /**
     * Multi-floor only: the share of the filter's weight inside each portal's area, now or in portal memory, keyed by
     * portal index. A particle in several portals counts for each. Empty without a topology or particles.
     */
    fun portalWeights(): Map<Int, Double> {
        if (!multiFloor || mutableParticles.isEmpty()) return emptyMap()
        val total = mutableParticles.sumOf { it.weight }
        if (!total.isFinite() || total <= 0.0) return emptyMap()
        val weights = sortedMapOf<Int, Double>()
        mutableParticles.forEach { particle ->
            eligiblePortals(particle).forEach { weights[it.index] = (weights[it.index] ?: 0.0) + particle.weight / total }
        }
        return weights
    }

    /**
     * Multi-floor only: moves [VerticalEvidence.share] of the weight of particles in (or recently in) a compatible
     * portal to that portal's exit on the other floor, chaining one-hop links when no direct link covers the floor
     * count. Rejected, with the particles untouched, when no particle is eligible.
     */
    fun applyVerticalEvidence(evidence: VerticalEvidence): FloorTransitionResult {
        if (!multiFloor) return FloorTransitionResult(false, 0, summary())
        return transitionThroughPortals(
            direction = evidence.direction, candidates = evidence.floorCandidates, kind = evidence.kind,
            share = evidence.share, outOfPortalLikelihood = evidence.outOfPortalLikelihood,
            unverifiedFactor = evidence.unverifiedFactor, onlyPortals = null, allowChains = true, source = "vertical evidence",
        )
    }

    /**
     * Multi-floor only: seeds [share] of the weight of particles in the given portals (e.g. after a stairs or lift
     * dwell with no barometer) on the portals' far floors. Particles elsewhere keep their weight.
     */
    fun seedFloorHypotheses(
        kind: VerticalKindHint,
        portalIndices: Set<Int>,
        share: Double,
        unverifiedFactor: Double,
    ): FloorTransitionResult {
        if (!multiFloor) return FloorTransitionResult(false, 0, summary())
        require(share in 0.0..1.0)
        require(unverifiedFactor in 0.0..1.0)
        val candidates = portalIndices.mapNotNull { floorTopology.portals.getOrNull(it) }.map { abs(it.levelDelta) }.toSet()
        if (candidates.isEmpty()) {
            addEvent(FilterEventType.FLOOR_TRANSITION_REJECTED, detail = "Floor seeding named no known portal")
            return FloorTransitionResult(false, mutableParticles.size, summary())
        }
        return transitionThroughPortals(
            direction = VerticalDirection.UNKNOWN, candidates = candidates, kind = kind, share = share,
            outOfPortalLikelihood = 1.0, unverifiedFactor = unverifiedFactor, onlyPortals = portalIndices,
            allowChains = false, source = "floor seeding",
        )
    }

    private fun summarize(normalized: List<Particle>, floorKey: String, vote: Double): ParticleSummary {
        val floorParticles = normalized.filter { it.floorId == floorKey && it.weight > 0.0 }
        val seed = floorParticles.map { candidate ->
            candidate to floorParticles.filter {
                it.position.distanceTo(candidate.position) <= config.clusterRadiusMetres
            }.sumOf { it.weight }
        }.sortedWith(
            compareByDescending<Pair<Particle, Double>> { it.second }
                .thenBy { it.first.x }
                .thenBy { it.first.y },
        ).first().first
        val floor = metricMap.floors.getValue(floorKey)
        fun reachable(point: MetricPoint): Boolean = seed.position.distanceTo(point) <= MetricGeometry.DEFAULT_EPSILON ||
            (floor.isPathInsideWalkableSpace(seed.position, point) && !floor.crossesWall(seed.position, point))
        val cluster = floorParticles.filter {
            it.position.distanceTo(seed.position) <= config.clusterRadiusMetres && reachable(it.position)
        }
        val clusterWeight = cluster.sumOf { it.weight }
        val mean = MetricPoint(
            cluster.sumOf { it.x * it.weight } / clusterWeight,
            cluster.sumOf { it.y * it.weight } / clusterWeight,
        )
        // A mean of valid points need not be valid in a concave corridor. Constrain the aggregate
        // along the seed-to-mean line; retain the distribution's spread rather than claiming certainty.
        val position = if (reachable(mean)) mean else {
            var low = 0.0; var high = 1.0
            repeat(32) {
                val fraction = (low + high) / 2.0
                val candidate = MetricPoint(seed.x + (mean.x - seed.x) * fraction,
                    seed.y + (mean.y - seed.y) * fraction)
                if (reachable(candidate)) low = fraction else high = fraction
            }
            MetricPoint(seed.x + (mean.x - seed.x) * low, seed.y + (mean.y - seed.y) * low)
        }
        val spread = sqrt(
            cluster.sumOf { it.weight * it.position.distanceTo(position).squared() } / clusterWeight,
        )
        val floorConfidence = vote.coerceIn(0.0, 1.0)
        val clusterAgreement = (clusterWeight / vote).coerceIn(0.0, 1.0)
        val compactness = exp(-spread / config.clusterRadiusMetres)
        val confidence = (floorConfidence * clusterAgreement * compactness).coerceIn(0.0, 1.0)
        return ParticleSummary(
            position = position,
            floorId = floorKey,
            floorConfidence = floorConfidence,
            confidence = confidence,
            uncertaintyRadiusMetres = max(0.25, 2.146 * spread),
            spatialSpreadMetres = spread,
            clusterParticleCount = cluster.size,
        )
    }

    // ----- Multi-floor helpers (never called without a topology) -----------------------------------------

    /** Portal memory after an allowed move: 0 steps if a portal now holds the particle, else one more step. */
    private fun updatePortalMemory(particle: Particle): Particle {
        val floor = metricMap.floors.getValue(particle.floorId)
        val captured = floorTopology.capture(floor, particle.position, config.portalSlackMetres).firstOrNull()
        return if (captured != null) {
            particle.copy(portalIndex = captured.index, stepsSincePortal = 0)
        } else {
            val steps = if (particle.stepsSincePortal == Int.MAX_VALUE) Int.MAX_VALUE else particle.stepsSincePortal + 1
            particle.copy(stepsSincePortal = steps)
        }
    }

    /** Portals whose area holds the particle now, then the remembered one if still within portal memory. */
    private fun eligiblePortals(particle: Particle): List<FloorPortal> {
        val floor = metricMap.floors[particle.floorId] ?: return emptyList()
        val captured = floorTopology.capture(floor, particle.position, config.portalSlackMetres)
        val remembered = floorTopology.portals.getOrNull(particle.portalIndex)
            ?.takeIf { particle.stepsSincePortal <= config.portalMemorySteps && it.fromFloorId == particle.floorId }
        return if (remembered == null || captured.any { it.index == remembered.index }) captured else captured + remembered
    }

    private fun kindMatches(hint: VerticalKindHint, kind: ConnectorKind): Boolean = when (hint) {
        VerticalKindHint.STAIRS -> kind == ConnectorKind.STAIRS || kind == ConnectorKind.ESCALATOR || kind == ConnectorKind.RAMP
        VerticalKindHint.LIFT -> kind == ConnectorKind.LIFT
        VerticalKindHint.ANY -> true
    }

    private fun directionMatches(direction: VerticalDirection, levelDelta: Int): Boolean = when (direction) {
        VerticalDirection.UP -> levelDelta > 0
        VerticalDirection.DOWN -> levelDelta < 0
        VerticalDirection.UNKNOWN -> levelDelta != 0
    }

    private fun reversePortalIndex(portal: FloorPortal): Int = floorTopology.portals.firstOrNull {
        it.linkId == portal.linkId && it.fromFloorId == portal.toFloorId && it.toFloorId == portal.fromFloorId
    }?.index ?: -1

    /**
     * A walkable exit near the portal's far end (Gaussian, truncated at 3 sigma), reachable from it; the far end
     * itself after too many tries.
     */
    private fun sampleExit(portal: FloorPortal): MetricPoint {
        val floor = metricMap.floors.getValue(portal.toFloorId)
        val spread = min(portal.radiusMetres / 2.0, config.exitSpreadMaxMetres)
        repeat(config.exitSampleTries) {
            val candidate = MetricPoint(portal.toCentre.x + gaussian(spread), portal.toCentre.y + gaussian(spread))
            if (candidate.distanceTo(portal.toCentre) <= 3.0 * spread &&
                floor.isPathInsideWalkableSpace(portal.toCentre, candidate) && !floor.crossesWall(portal.toCentre, candidate)) {
                return candidate
            }
        }
        return portal.toCentre
    }

    private fun transitionThroughPortals(
        direction: VerticalDirection,
        candidates: Set<Int>,
        kind: VerticalKindHint,
        share: Double,
        outOfPortalLikelihood: Double,
        unverifiedFactor: Double,
        onlyPortals: Set<Int>?,
        allowChains: Boolean,
        source: String,
    ): FloorTransitionResult {
        if (mutableParticles.isEmpty()) {
            addEvent(FilterEventType.FLOOR_TRANSITION_REJECTED, detail = "Filter has no particles")
            return FloorTransitionResult(false, 0, null)
        }
        val mostFloors = candidates.max()
        fun compatible(portal: FloorPortal) = kindMatches(kind, portal.kind) && directionMatches(direction, portal.levelDelta) &&
            (onlyPortals == null || portal.index in onlyPortals)

        data class Plan(val particle: Particle, val starts: List<FloorPortal>)
        val plans = mutableParticles.map { particle ->
            val eligible = eligiblePortals(particle).filter(::compatible)
            val direct = eligible.filter { abs(it.levelDelta) in candidates }
            val starts = when {
                direct.isNotEmpty() -> direct
                allowChains -> eligible.filter { abs(it.levelDelta) < mostFloors }
                else -> emptyList()
            }
            Plan(particle, starts)
        }
        val rejected = plans.count { it.starts.isEmpty() }
        if (rejected == plans.size) {
            addEvent(
                FilterEventType.FLOOR_TRANSITION_REJECTED,
                rejectedParticleCount = rejected,
                detail = "No particle was in a compatible floor link for $source",
            )
            return FloorTransitionResult(false, rejected, summary())
        }

        val next = mutableListOf<Particle>()
        val usedLinks = sortedSetOf<String>()
        var movedWeight = 0.0
        plans.forEach { (particle, starts) ->
            if (starts.isEmpty()) {
                next += particle.copy(weight = particle.weight * outOfPortalLikelihood)
                return@forEach
            }
            var stayerShare = 1.0
            starts.forEach { start ->
                val portalShare = share * (if (start.verifiedOnSite) 1.0 else unverifiedFactor) / starts.size
                stayerShare -= portalShare
                var last = start
                var floorsMoved = abs(start.levelDelta)
                var hops = 1
                usedLinks += start.linkId
                // Chain one-hop links in the same direction until the floor count is reached or no link continues.
                // Each further hop must be reachable from the previous link's landing; only the final exit is sampled.
                while (floorsMoved !in candidates && hops < min(mostFloors, config.maxHops)) {
                    val floor = metricMap.floors.getValue(last.toFloorId)
                    val hop = floorTopology.capture(floor, last.toCentre, config.portalSlackMetres).firstOrNull {
                        compatible(it) && (it.levelDelta > 0) == (start.levelDelta > 0) &&
                            floorsMoved + abs(it.levelDelta) <= mostFloors
                    } ?: break
                    last = hop
                    floorsMoved += abs(hop.levelDelta)
                    hops++
                    usedLinks += hop.linkId
                }
                val exit = sampleExit(last)
                val weight = particle.weight * portalShare
                movedWeight += weight
                next += particle.copy(
                    x = exit.x, y = exit.y, floorId = last.toFloorId, weight = weight,
                    previousX = exit.x, previousY = exit.y,
                    portalIndex = reversePortalIndex(last), stepsSincePortal = 0,
                )
            }
            next += particle.copy(weight = particle.weight * max(0.0, stayerShare))
        }
        val total = next.sumOf { it.weight }
        val movedShare = if (total > 0.0) movedWeight / total else 0.0
        mutableParticles = resample(normalizeWeights(next), config.particleCount)
        addEvent(
            FilterEventType.FLOOR_TRANSITION,
            rejectedParticleCount = rejected,
            detail = String.format(Locale.ROOT, "Moved %.3f of the weight through %s for %s", movedShare, usedLinks.joinToString(", "), source),
        )
        return FloorTransitionResult(true, rejected, summary())
    }

    private fun multiFloorWifiLikelihood(particle: Particle, match: WifiMatchResult): Double =
        match.neighbours.sumOf { neighbour ->
            if (particle.floorId == neighbour.floorId) {
                val sigma = config.correctionSpatialSigmaMetres * (1.0 + neighbour.distance / 25.0)
                val squaredDistance = particle.position.distanceTo(neighbour.position).squared()
                neighbour.weight * exp(-squaredDistance / (2.0 * sigma.squared()))
            } else {
                // Floors do not share a frame, so a neighbour on another floor carries no spatial term.
                neighbour.weight * config.crossFloorLikelihood
            }
        }

    private fun deferCrossFloor(wifiFloor: String): WifiCorrectionResult {
        addEvent(
            FilterEventType.CROSS_FLOOR_WIFI_DEFERRED,
            detail = "Wi-Fi suggests floor $wifiFloor; cross-floor relocalization deferred",
        )
        return WifiCorrectionResult(WifiCorrectionKind.DEFERRED_CROSS_FLOOR, false, effectiveParticleCount(), summary())
    }

    private fun resample(input: List<Particle>, count: Int): List<Particle> =
        if (!multiFloor) systematicResample(input, count) else floorStratifiedResample(input, count)

    /**
     * Resamples each floor separately so the floor posterior survives: floors below [ParticleFilterConfig.floorPruneWeight]
     * are dropped (never the strongest), each kept floor gets max(minParticlesPerFloor, its largest-remainder share of
     * [count]) particles, the excess taken from the largest floor, and each output weighs W_f / n_f.
     */
    private fun floorStratifiedResample(input: List<Particle>, count: Int): List<Particle> {
        require(input.isNotEmpty())
        val normalized = normalizeWeights(input)
        val byFloor = normalized.groupBy { it.floorId }.toSortedMap()
        val floorWeights = byFloor.mapValues { (_, rows) -> rows.sumOf { it.weight } }
        val strongest = floorWeights.entries
            .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
            .first().key
        val kept = floorWeights.filter { (id, weight) -> id == strongest || (weight > 0.0 && weight >= config.floorPruneWeight) }
        val keptTotal = kept.values.sum()
        val ids = kept.keys.toList()
        val shares = ids.map { kept.getValue(it) / keptTotal }
        val exact = shares.map { it * count }
        val allocation = exact.map { it.toInt() }.toIntArray()
        val remaining = count - allocation.sum()
        ids.indices.sortedWith(compareByDescending<Int> { exact[it] - allocation[it] }.thenBy { ids[it] })
            .take(remaining).forEach { allocation[it]++ }
        val minimum = min(config.minParticlesPerFloor, count / ids.size)
        for (i in ids.indices) {
            while (allocation[i] < minimum) {
                val donor = ids.indices.filter { allocation[it] > minimum }
                    .sortedWith(compareByDescending<Int> { allocation[it] }.thenBy { ids[it] })
                    .firstOrNull() ?: break
                allocation[donor]--
                allocation[i]++
            }
        }
        val output = ids.indices.flatMap { i ->
            if (allocation[i] == 0) emptyList()
            else systematicResample(byFloor.getValue(ids[i]), allocation[i]).map { it.copy(weight = shares[i] / allocation[i]) }
        }
        return normalizeWeights(output)
    }

    private fun initializeParticles(fix: AbsoluteFix, positionStdMetres: Double) {
        require(positionStdMetres.isFinite() && positionStdMetres >= 0.0)
        val floor = metricMap.floors[fix.floorId]
            ?: throw IllegalArgumentException("Unknown floor ${fix.floorId}")
        require(floor.isWalkable(fix.position)) { "Absolute fix must lie in walkable space" }
        val generated = mutableListOf<Particle>()
        val maxAttempts = max(config.particleCount * 100, 100)
        var attempts = 0
        while (generated.size < config.particleCount && attempts < maxAttempts) {
            attempts++
            val position = MetricPoint(
                fix.position.x + gaussian(positionStdMetres),
                fix.position.y + gaussian(positionStdMetres),
            )
            if (!floor.isWalkable(position)) continue
            generated += Particle(
                x = position.x,
                y = position.y,
                floorId = fix.floorId,
                headingOffsetRadians = normalizeRadians(
                    fix.initialHeadingOffsetRadians + gaussian(config.initialHeadingOffsetStdRadians),
                ),
                strideScale = (1.0 + gaussian(config.initialStrideScaleStd))
                    .coerceIn(StrideModel.MIN_SCALE, StrideModel.MAX_SCALE),
                weight = 1.0,
            )
        }
        // Near narrow boundaries Gaussian rejection can exhaust attempts. Exact copies are valid,
        // explicit and preferable to secretly admitting invalid particles.
        while (generated.size < config.particleCount) {
            generated += Particle(
                x = fix.position.x,
                y = fix.position.y,
                floorId = fix.floorId,
                headingOffsetRadians = normalizeRadians(fix.initialHeadingOffsetRadians),
                strideScale = 1.0,
                weight = 1.0,
            )
        }
        mutableParticles = normalizeWeights(generated)
    }

    /**
     * Reweighting alone cannot move a cloud whose every particle lags the truth (e.g. a short PDR stride).
     * Replace the weakest few particles with hypotheses around the fix; likelihood weighting then decides
     * whether they survive, so one noisy fix cannot drag the estimate but consistent fixes can.
     */
    private fun injectAroundWifi(match: WifiMatchResult, position: MetricPoint, floorId: String) {
        val count = (config.particleCount * config.wifiInjectionFraction).toInt()
        if (count == 0 || match.confidence < config.wifiInjectionMinimumConfidence || mutableParticles.size < 2) return
        val floor = metricMap.floors[floorId] ?: return
        val std = (match.uncertaintyRadiusMetres / 2.0).coerceIn(config.minimumRelocalizationStdMetres, config.maximumRelocalizationStdMetres)
        val ranked = mutableParticles.sortedBy { it.weight }
        val donors = ranked.drop(count).ifEmpty { ranked }
        val meanWeight = mutableParticles.sumOf { it.weight } / mutableParticles.size
        val injected = mutableListOf<Particle>()
        var attempts = 0
        while (injected.size < count && attempts < count * 50) {
            attempts++
            val candidate = MetricPoint(position.x + gaussian(std), position.y + gaussian(std))
            if (!floor.isWalkable(candidate)) continue
            // Keep a surviving particle's heading and stride calibration; only the location is new.
            val donor = donors[random.nextInt(donors.size)]
            injected += donor.copy(x = candidate.x, y = candidate.y, floorId = floorId, weight = meanWeight,
                portalIndex = -1, stepsSincePortal = Int.MAX_VALUE,
                previousX = candidate.x + donor.previousX - donor.x, previousY = candidate.y + donor.previousY - donor.y)
        }
        if (injected.isEmpty()) return
        mutableParticles = ranked.drop(injected.size) + injected
    }

    private fun relocateFromWifi(match: WifiMatchResult) {
        val position = requireNotNull(match.estimatedPosition)
        val floor = requireNotNull(match.estimatedFloorId)
        val std = (match.uncertaintyRadiusMetres / 2.0)
            .coerceIn(config.minimumRelocalizationStdMetres, config.maximumRelocalizationStdMetres)
        initializeParticles(AbsoluteFix(position, floor, AbsoluteFixSource.WIFI), std)
        addEvent(FilterEventType.GLOBAL_RELOCALIZED, detail = "Reinitialized around strong Wi-Fi fix")
    }

    private fun normalizeWeights(input: List<Particle>): List<Particle> {
        if (input.isEmpty()) return emptyList()
        val total = input.sumOf { it.weight }
        return if (!total.isFinite() || total <= 0.0) {
            val uniform = 1.0 / input.size
            input.map { it.copy(weight = uniform) }
        } else {
            input.map { it.copy(weight = it.weight / total) }
        }
    }

    private fun systematicResample(input: List<Particle>, count: Int): List<Particle> {
        require(input.isNotEmpty())
        val normalized = normalizeWeights(input)
        val cumulative = DoubleArray(normalized.size)
        var running = 0.0
        normalized.forEachIndexed { index, particle ->
            running += particle.weight
            cumulative[index] = running
        }
        cumulative[cumulative.lastIndex] = 1.0
        val start = random.nextDouble() / count
        var sourceIndex = 0
        val uniformWeight = 1.0 / count
        return List(count) { outputIndex ->
            val threshold = start + outputIndex.toDouble() / count
            while (sourceIndex < cumulative.lastIndex && threshold > cumulative[sourceIndex]) {
                sourceIndex++
            }
            normalized[sourceIndex].copy(weight = uniformWeight)
        }
    }

    private fun gaussian(standardDeviation: Double): Double =
        if (standardDeviation == 0.0) 0.0 else random.nextGaussian() * standardDeviation

    private fun addEvent(
        type: FilterEventType,
        rejectedParticleCount: Int = 0,
        detail: String,
    ) {
        mutableEvents += FilterEvent(nextEventSequence++, type, rejectedParticleCount, detail)
    }
}
