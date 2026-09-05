package com.turn.fieldtest.core

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

enum class WifiScanFreshness {
    FRESH,
    CACHED,
    STALE,
}

data class WifiObservation(
    val bssid: String,
    val rssiDbm: Int,
    val timestampMillis: Long,
    val ssid: String? = null,
    val frequencyMhz: Int? = null,
) {
    init {
        require(bssid.isNotBlank()) { "BSSID is the required Wi-Fi identifier" }
        require(rssiDbm in -127..0) { "RSSI must be a plausible dBm value" }
    }
}

data class WifiScanSnapshot(
    val id: String,
    val capturedAtMillis: Long,
    val freshness: WifiScanFreshness,
    val observations: List<WifiObservation>,
) {
    init {
        require(id.isNotBlank()) { "Snapshot id must not be blank" }
    }

    val isIndependent: Boolean get() = freshness == WifiScanFreshness.FRESH

    /** Android can occasionally expose duplicate rows; retain the strongest row for one BSSID. */
    fun distinctObservations(): Map<String, WifiObservation> = observations
        .groupBy { canonicalBssid(it.bssid) }
        .mapValues { (_, rows) -> rows.maxBy { it.rssiDbm } }
}

data class AggregatedWifiSignal(
    val bssid: String,
    val medianRssiDbm: Double,
    val meanRssiDbm: Double,
    val standardDeviationDb: Double,
    val minimumRssiDbm: Int,
    val maximumRssiDbm: Int,
    val observationCount: Int,
    /** Fraction of independent fresh snapshots in which this BSSID was detected. */
    val detectionRate: Double,
)

object WifiFingerprintAggregator {
    /**
     * Cached and stale snapshots remain available to storage, but do not contribute independent
     * observations to a fingerprint.
     */
    fun aggregate(snapshots: List<WifiScanSnapshot>): List<AggregatedWifiSignal> {
        val freshSnapshots = snapshots.filter { it.isIndependent }
        if (freshSnapshots.isEmpty()) return emptyList()
        val readingsByBssid = linkedMapOf<String, MutableList<Int>>()
        freshSnapshots.forEach { snapshot ->
            snapshot.distinctObservations().forEach { (bssid, observation) ->
                readingsByBssid.getOrPut(bssid) { mutableListOf() } += observation.rssiDbm
            }
        }
        return readingsByBssid.map { (bssid, values) ->
            val sorted = values.sorted()
            val mean = values.average()
            val variance = values.sumOf { (it - mean).squared() } / values.size
            AggregatedWifiSignal(
                bssid = bssid,
                medianRssiDbm = median(sorted),
                meanRssiDbm = mean,
                standardDeviationDb = sqrt(variance),
                minimumRssiDbm = values.min(),
                maximumRssiDbm = values.max(),
                observationCount = values.size,
                detectionRate = values.size.toDouble() / freshSnapshots.size,
            )
        }.sortedBy { it.bssid }
    }

    private fun median(sorted: List<Int>): Double {
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle].toDouble()
        } else {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        }
    }
}

data class WifiFingerprint(
    val referencePointId: String,
    val floorId: String,
    val position: MetricPoint,
    /** Default matching vector; normally populated from per-BSSID median RSSI. */
    val rssiByBssid: Map<String, Double>,
    val excludedBssids: Set<String> = emptySet(),
) {
    init {
        require(referencePointId.isNotBlank()) { "Reference point id must not be blank" }
        require(floorId.isNotBlank()) { "Floor id must not be blank" }
        require(rssiByBssid.values.all { it.isFinite() && it in -127.0..0.0 }) {
            "Fingerprint RSSI values must be plausible dBm values"
        }
    }

    fun canonicalVector(): Map<String, Double> = rssiByBssid.entries.associate { (bssid, rssi) ->
        canonicalBssid(bssid) to rssi
    }

    fun canonicalExclusions(): Set<String> = excludedBssids.mapTo(mutableSetOf(), ::canonicalBssid)
}

enum class WifiNormalization {
    RAW,
    DEVICE_OFFSET,
}

data class WifiDistanceResult(
    val distance: Double,
    /** Offset added to detected live RSSI values before comparison. */
    val appliedDeviceOffsetDb: Double,
    val comparedBssidCount: Int,
)

object WifiVectorDistance {
    fun calculate(
        liveRssiByBssid: Map<String, Double>,
        storedRssiByBssid: Map<String, Double>,
        missingRssiDbm: Double = -100.0,
        normalization: WifiNormalization = WifiNormalization.RAW,
        excludedBssids: Set<String> = emptySet(),
    ): WifiDistanceResult {
        require(missingRssiDbm.isFinite() && missingRssiDbm < 0.0) {
            "Missing RSSI must be a finite negative dBm value"
        }
        val excluded = excludedBssids.mapTo(mutableSetOf(), ::canonicalBssid)
        val live = liveRssiByBssid.entries
            .associate { (bssid, rssi) -> canonicalBssid(bssid) to rssi }
            .filterKeys { it !in excluded }
        val stored = storedRssiByBssid.entries
            .associate { (bssid, rssi) -> canonicalBssid(bssid) to rssi }
            .filterKeys { it !in excluded }
        val union = (live.keys + stored.keys).toSortedSet()
        if (union.isEmpty()) return WifiDistanceResult(Double.POSITIVE_INFINITY, 0.0, 0)

        val offset = when (normalization) {
            WifiNormalization.RAW -> 0.0
            WifiNormalization.DEVICE_OFFSET -> {
                val differences = live.keys.intersect(stored.keys)
                    .map { bssid -> stored.getValue(bssid) - live.getValue(bssid) }
                    .sorted()
                medianOrZero(differences)
            }
        }
        val squaredError = union.sumOf { bssid ->
            // Missing values represent absence and must not receive a device offset.
            val liveValue = live[bssid]?.plus(offset) ?: missingRssiDbm
            val storedValue = stored[bssid] ?: missingRssiDbm
            (liveValue - storedValue).squared()
        }
        return WifiDistanceResult(
            distance = sqrt(squaredError / union.size),
            appliedDeviceOffsetDb = offset,
            comparedBssidCount = union.size,
        )
    }

    private fun medianOrZero(sorted: List<Double>): Double {
        if (sorted.isEmpty()) return 0.0
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle]
        else (sorted[middle - 1] + sorted[middle]) / 2.0
    }
}

data class WifiMatcherConfig(
    val k: Int = 4,
    val missingRssiDbm: Double = -100.0,
    val normalization: WifiNormalization = WifiNormalization.RAW,
    val inverseDistancePower: Double = 1.0,
    val exactMatchEpsilon: Double = 1e-9,
    val unlikeDatabaseDistanceThreshold: Double = 35.0,
) {
    init {
        require(k > 0) { "k must be positive" }
        require(missingRssiDbm < 0.0 && missingRssiDbm.isFinite())
        require(inverseDistancePower > 0.0 && inverseDistancePower.isFinite())
        require(exactMatchEpsilon > 0.0)
        require(unlikeDatabaseDistanceThreshold > 0.0)
    }
}

data class WifiNeighbour(
    val referencePointId: String,
    val floorId: String,
    val position: MetricPoint,
    val distance: Double,
    /** Globally normalized inverse-distance weight across the selected neighbours. */
    val weight: Double,
    val appliedDeviceOffsetDb: Double,
)

data class WifiMatchResult(
    val estimatedPosition: MetricPoint?,
    val estimatedFloorId: String?,
    val neighbours: List<WifiNeighbour>,
    val confidence: Double,
    val uncertaintyRadiusMetres: Double,
    val floorAgreement: Double,
    val weightedMatchDistance: Double,
    val freshness: WifiScanFreshness,
    val unlikeDatabase: Boolean,
) {
    val isExactMatch: Boolean get() = neighbours.isNotEmpty() && neighbours.all { it.distance <= 1e-9 }

    companion object {
        fun noEstimate(freshness: WifiScanFreshness): WifiMatchResult = WifiMatchResult(
            estimatedPosition = null,
            estimatedFloorId = null,
            neighbours = emptyList(),
            confidence = 0.0,
            uncertaintyRadiusMetres = Double.POSITIVE_INFINITY,
            floorAgreement = 0.0,
            weightedMatchDistance = Double.POSITIVE_INFINITY,
            freshness = freshness,
            unlikeDatabase = true,
        )
    }
}

class WeightedKnnWifiMatcher(private val config: WifiMatcherConfig = WifiMatcherConfig()) {
    fun match(
        liveRssiByBssid: Map<String, Double>,
        fingerprints: List<WifiFingerprint>,
        freshness: WifiScanFreshness = WifiScanFreshness.FRESH,
        globallyExcludedBssids: Set<String> = emptySet(),
    ): WifiMatchResult {
        if (liveRssiByBssid.isEmpty() || fingerprints.isEmpty()) {
            return WifiMatchResult.noEstimate(freshness)
        }

        val distances = fingerprints.mapNotNull { fingerprint ->
            if (fingerprint.rssiByBssid.isEmpty()) return@mapNotNull null
            val excluded = (globallyExcludedBssids + fingerprint.canonicalExclusions())
                .mapTo(hashSetOf(), ::canonicalBssid)
            val observedIds = liveRssiByBssid.filterValues { it.isFinite() }.keys
                .mapTo(hashSetOf(), ::canonicalBssid) - excluded
            if (fingerprint.canonicalVector().keys.none { it in observedIds }) return@mapNotNull null
            val distance = WifiVectorDistance.calculate(
                liveRssiByBssid = liveRssiByBssid,
                storedRssiByBssid = fingerprint.canonicalVector(),
                missingRssiDbm = config.missingRssiDbm,
                normalization = config.normalization,
                excludedBssids = globallyExcludedBssids + fingerprint.canonicalExclusions(),
            )
            if (!distance.distance.isFinite()) null else fingerprint to distance
        }.sortedWith(compareBy<Pair<WifiFingerprint, WifiDistanceResult>> { it.second.distance }
            .thenBy { it.first.referencePointId })
            .take(config.k)
        if (distances.isEmpty()) return WifiMatchResult.noEstimate(freshness)

        val exact = distances.filter { it.second.distance <= config.exactMatchEpsilon }
        val weightedRows = if (exact.isNotEmpty()) {
            exact.map { it to 1.0 / exact.size }
        } else {
            val raw = distances.map { row ->
                row to 1.0 / max(row.second.distance, config.exactMatchEpsilon)
                    .pow(config.inverseDistancePower)
            }
            val total = raw.sumOf { it.second }
            raw.map { (row, weight) -> row to weight / total }
        }

        val neighbours = weightedRows.map { (row, weight) ->
            val (fingerprint, distance) = row
            WifiNeighbour(
                referencePointId = fingerprint.referencePointId,
                floorId = fingerprint.floorId,
                position = fingerprint.position,
                distance = distance.distance,
                weight = weight,
                appliedDeviceOffsetDb = distance.appliedDeviceOffsetDb,
            )
        }
        val floorVotes = neighbours.groupBy { it.floorId }
            .mapValues { (_, rows) -> rows.sumOf { it.weight } }
        val selectedFloor = floorVotes.entries
            .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
            .first()
        val floorNeighbours = neighbours.filter { it.floorId == selectedFloor.key }
        val floorWeight = floorNeighbours.sumOf { it.weight }
        val estimatedPosition = MetricPoint(
            x = floorNeighbours.sumOf { it.position.x * it.weight } / floorWeight,
            y = floorNeighbours.sumOf { it.position.y * it.weight } / floorWeight,
        )
        val spread = sqrt(
            floorNeighbours.sumOf { it.weight * it.position.distanceTo(estimatedPosition).squared() } /
                floorWeight,
        )
        val weightedDistance = neighbours.sumOf { it.weight * it.distance }
        val floorAgreement = selectedFloor.value
        val freshnessFactor = when (freshness) {
            WifiScanFreshness.FRESH -> 1.0
            WifiScanFreshness.CACHED -> 0.55
            WifiScanFreshness.STALE -> 0.25
        }
        val matchQuality = exp(-weightedDistance / 18.0)
        val spreadQuality = exp(-spread / 8.0)
        val confidence = ((0.55 * matchQuality + 0.20 * spreadQuality + 0.25 * floorAgreement) *
            freshnessFactor).coerceIn(0.0, 1.0)
        val uncertainty = (
            1.0 + spread * 1.5 + weightedDistance / 5.0 + (1.0 - floorAgreement) * 8.0
            ) / max(freshnessFactor, 0.2)
        return WifiMatchResult(
            estimatedPosition = estimatedPosition,
            estimatedFloorId = selectedFloor.key,
            neighbours = neighbours,
            confidence = confidence,
            uncertaintyRadiusMetres = uncertainty,
            floorAgreement = floorAgreement,
            weightedMatchDistance = weightedDistance,
            freshness = freshness,
            unlikeDatabase = neighbours.minOf { it.distance } > config.unlikeDatabaseDistanceThreshold,
        )
    }
}

internal fun canonicalBssid(value: String): String = value.trim().lowercase()
