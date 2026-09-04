package com.turn.fieldtest.core

import java.util.Random
import kotlin.math.PI
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
) {
    init {
        require(x.isFinite() && y.isFinite())
        require(floorId.isNotBlank())
        require(headingOffsetRadians.isFinite())
        require(strideScale.isFinite() && strideScale > 0.0)
        require(weight.isFinite() && weight >= 0.0)
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
    QR_CORRECTION,
    FLOOR_TRANSITION,
    FLOOR_TRANSITION_REJECTED,
    FILTER_LOST,
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
}

data class WifiCorrectionResult(
    val kind: WifiCorrectionKind,
    val resampled: Boolean,
    val effectiveParticleCountBeforeResampling: Double,
    val summary: ParticleSummary?,
)

data class FloorTransitionResult(
    val accepted: Boolean,
    val rejectedParticleCount: Int,
    val summary: ParticleSummary?,
)

/**
 * Seeded particle-filter fusion. It deliberately has no clock, persistence or Android dependency;
 * callers persist returned [FilterEvent] values with their own timestamps.
 */
class ParticleFilter(
    private val metricMap: MetricMap,
    private val config: ParticleFilterConfig = ParticleFilterConfig(),
    seed: Long = 0L,
) {
    private val random = Random(seed)
    private var mutableParticles: List<Particle> = emptyList()
    private val mutableEvents = mutableListOf<FilterEvent>()
    private var nextEventSequence = 1L

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

    fun predictStep(strideMetres: Double, measuredHeadingRadians: Double): PredictionResult {
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
            val distance = max(0.0, strideMetres * strideScale + gaussian(config.movementStdMetres))
            val candidate = MetricPoint(
                particle.x + distance * cos(heading),
                particle.y + distance * sin(heading),
            )
            val constraint = metricMap.movementConstraint(particle.floorId, particle.position, candidate)
            if (constraint == MovementConstraint.ALLOWED) {
                survivors += particle.copy(
                    x = candidate.x,
                    y = candidate.y,
                    headingOffsetRadians = headingOffset,
                    strideScale = strideScale,
                )
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
            systematicResample(normalizeWeights(survivors), config.particleCount)
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

    fun correctWithWifi(
        match: WifiMatchResult,
        allowGlobalRelocalization: Boolean = true,
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
        if (wifiPosition == null || wifiFloor == null || match.neighbours.isEmpty()) {
            return WifiCorrectionResult(WifiCorrectionKind.IGNORED_NO_ESTIMATE, false, 0.0, summary())
        }

        val current = summary()
        val isStrong = !match.unlikeDatabase && match.confidence >= config.strongWifiConfidence
        val cloudCannotExplainMatch = current == null || current.floorId != wifiFloor ||
            current.position.distanceTo(wifiPosition) > config.globalRelocalizationDistanceMetres
        if (allowGlobalRelocalization && isStrong && cloudCannotExplainMatch) {
            relocateFromWifi(match)
            return WifiCorrectionResult(
                WifiCorrectionKind.GLOBAL_RELOCALIZATION,
                resampled = true,
                effectiveParticleCountBeforeResampling = 0.0,
                summary = summary(),
            )
        }
        if (mutableParticles.isEmpty()) {
            addEvent(FilterEventType.FILTER_LOST, detail = "Wi-Fi evidence was not strong enough to relocalize")
            return WifiCorrectionResult(WifiCorrectionKind.FILTER_LOST, false, 0.0, null)
        }

        val evidenceStrength = match.confidence.coerceIn(0.05, 1.0)
        val corrected = mutableParticles.map { particle ->
            val likelihood = match.neighbours.sumOf { neighbour ->
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
        if (shouldResample) mutableParticles = systematicResample(mutableParticles, config.particleCount)
        addEvent(FilterEventType.WIFI_CORRECTION, detail = "Applied ${match.freshness} Wi-Fi likelihood")
        return WifiCorrectionResult(
            kind = WifiCorrectionKind.APPLIED,
            resampled = shouldResample,
            effectiveParticleCountBeforeResampling = effectiveCount,
            summary = summary(),
        )
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

    /** A floor change is accepted only for particles currently inside a configured transition. */
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
            mutableParticles = systematicResample(normalizeWeights(mutableParticles), config.particleCount)
        }
        return summary()
    }

    /** Uses the highest-density spatial cluster on the strongest weighted floor. */
    fun summary(): ParticleSummary? {
        if (mutableParticles.isEmpty()) return null
        val normalized = normalizeWeights(mutableParticles)
        val floorVotes = normalized.groupBy { it.floorId }
            .mapValues { (_, rows) -> rows.sumOf { it.weight } }
        val selectedFloor = floorVotes.entries
            .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
            .first()
        val floorParticles = normalized.filter { it.floorId == selectedFloor.key }
        val seed = floorParticles.map { candidate ->
            candidate to floorParticles.filter {
                it.position.distanceTo(candidate.position) <= config.clusterRadiusMetres
            }.sumOf { it.weight }
        }.sortedWith(
            compareByDescending<Pair<Particle, Double>> { it.second }
                .thenBy { it.first.x }
                .thenBy { it.first.y },
        ).first().first
        val cluster = floorParticles.filter {
            it.position.distanceTo(seed.position) <= config.clusterRadiusMetres
        }
        val clusterWeight = cluster.sumOf { it.weight }
        val position = MetricPoint(
            cluster.sumOf { it.x * it.weight } / clusterWeight,
            cluster.sumOf { it.y * it.weight } / clusterWeight,
        )
        val spread = sqrt(
            cluster.sumOf { it.weight * it.position.distanceTo(position).squared() } / clusterWeight,
        )
        val floorConfidence = selectedFloor.value.coerceIn(0.0, 1.0)
        val clusterAgreement = (clusterWeight / selectedFloor.value).coerceIn(0.0, 1.0)
        val compactness = exp(-spread / config.clusterRadiusMetres)
        val confidence = (floorConfidence * clusterAgreement * compactness).coerceIn(0.0, 1.0)
        return ParticleSummary(
            position = position,
            floorId = selectedFloor.key,
            floorConfidence = floorConfidence,
            confidence = confidence,
            uncertaintyRadiusMetres = max(0.25, 2.146 * spread),
            spatialSpreadMetres = spread,
            clusterParticleCount = cluster.size,
        )
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
