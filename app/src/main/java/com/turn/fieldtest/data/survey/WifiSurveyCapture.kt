package com.turn.fieldtest.data.survey

import com.turn.fieldtest.data.local.AggregatedWifiFingerprintEntity
import com.turn.fieldtest.data.local.SurveySessionEntity
import com.turn.fieldtest.data.local.WifiObservationEntity
import com.turn.fieldtest.data.local.WifiScanSnapshotEntity
import com.turn.fieldtest.data.repository.SurveyRepository
import com.turn.fieldtest.platform.wifi.WifiScanBatch
import com.turn.fieldtest.platform.wifi.WifiScanIssue
import java.util.UUID
import kotlin.math.sqrt

fun interface EntityIdGenerator {
    fun nextId(): String
}

data class WifiCaptureEntities(
    val snapshot: WifiScanSnapshotEntity,
    val observations: List<WifiObservationEntity>,
)

class WifiSurveyCaptureFactory(
    private val idGenerator: EntityIdGenerator = EntityIdGenerator { UUID.randomUUID().toString() },
) {
    fun create(
        session: SurveySessionEntity,
        scanSequence: Long,
        batch: WifiScanBatch,
        requestedAtEpochMillis: Long = batch.receivedAtEpochMillis,
    ): WifiCaptureEntities {
        require(scanSequence >= 0L) { "Scan sequence cannot be negative" }
        val snapshotId = idGenerator.nextId()
        val independentlyFresh = batch.isIndependentFreshScan
        val snapshot = WifiScanSnapshotEntity(
            id = snapshotId,
            surveySessionId = session.id,
            scanSequence = scanSequence,
            requestedAtEpochMillis = requestedAtEpochMillis,
            resultsReceivedAtEpochMillis = batch.receivedAtEpochMillis,
            newestResultAgeMillis = batch.newestResultAgeMillis,
            requestAccepted = batch.requestAccepted == true,
            resultsUpdated = batch.resultsUpdated,
            isFresh = independentlyFresh,
            isCached = !independentlyFresh,
            visibleAccessPointCount = batch.accessPoints.size,
            throttled = batch.issue in setOf(
                WifiScanIssue.REQUEST_REJECTED_OR_THROTTLED,
                WifiScanIssue.CLIENT_RATE_LIMITED,
            ),
            nextPermittedRequestAtEpochMillis = batch.nextPermittedRequestAtEpochMillis,
            failureCode = batch.issue?.name,
        )
        val observations = batch.accessPoints
            .groupBy { it.bssid.lowercase() }
            .values
            .map { duplicateRows ->
                duplicateRows.maxWith(
                    compareBy<com.turn.fieldtest.platform.wifi.WifiAccessPoint> { it.rssiDbm }
                        .thenBy { it.scanTimestampMicros },
                )
            }
            .map { accessPoint ->
                WifiObservationEntity(
                    id = idGenerator.nextId(),
                    snapshotId = snapshotId,
                    surveySessionId = session.id,
                    referencePointId = session.referencePointId,
                    bssid = accessPoint.bssid.lowercase(),
                    ssid = accessPoint.ssid,
                    rssiDbm = accessPoint.rssiDbm,
                    frequencyMhz = accessPoint.frequencyMhz,
                    channel = accessPoint.channel,
                    scanTimestampMicros = accessPoint.scanTimestampMicros,
                    observedAtEpochMillis = batch.receivedAtEpochMillis,
                    isFresh = independentlyFresh,
                )
            }
        return WifiCaptureEntities(snapshot, observations)
    }
}

data class WifiAggregationConfig(
    val unstableStandardDeviationDb: Double = 8.0,
    val unstableDetectionRate: Double = 0.5,
)

object WifiEntityFingerprintAggregator {
    fun aggregate(
        session: SurveySessionEntity,
        snapshots: List<WifiScanSnapshotEntity>,
        observations: List<WifiObservationEntity>,
        calculatedAtEpochMillis: Long,
        existingByBssid: Map<String, AggregatedWifiFingerprintEntity> = emptyMap(),
        config: WifiAggregationConfig = WifiAggregationConfig(),
    ): List<AggregatedWifiFingerprintEntity> {
        val freshSnapshotIds = snapshots.asSequence()
            .filter { it.surveySessionId == session.id && it.isFresh && it.resultsUpdated && !it.isCached }
            .map(WifiScanSnapshotEntity::id)
            .toSet()
        if (freshSnapshotIds.isEmpty()) return emptyList()

        return observations.asSequence()
            .filter {
                it.surveySessionId == session.id &&
                    it.referencePointId == session.referencePointId &&
                    it.isFresh &&
                    it.snapshotId in freshSnapshotIds
            }
            .groupBy { it.bssid.lowercase() }
            .map { (bssid, rows) ->
                val independentRows = rows.groupBy(WifiObservationEntity::snapshotId)
                    .values
                    .map { sameSnapshot -> sameSnapshot.maxBy(WifiObservationEntity::rssiDbm) }
                val values = independentRows.map(WifiObservationEntity::rssiDbm).sorted()
                val mean = values.average()
                val variance = values.sumOf { value ->
                    val delta = value - mean
                    delta * delta
                } / values.size
                val deviation = sqrt(variance)
                val detectionRate = independentRows.size.toDouble() / freshSnapshotIds.size
                val previous = existingByBssid[bssid]
                AggregatedWifiFingerprintEntity(
                    id = previous?.id ?: stableAggregateId(session.id, session.referencePointId, bssid),
                    referencePointId = session.referencePointId,
                    surveySessionId = session.id,
                    bssid = bssid,
                    medianRssiDbm = median(values),
                    meanRssiDbm = mean,
                    standardDeviationDb = deviation,
                    minimumRssiDbm = values.first(),
                    maximumRssiDbm = values.last(),
                    observationCount = independentRows.size,
                    eligibleFreshSnapshotCount = independentRows.size,
                    totalFreshSnapshotCount = freshSnapshotIds.size,
                    detectionRate = detectionRate,
                    unstable = deviation > config.unstableStandardDeviationDb ||
                        detectionRate < config.unstableDetectionRate,
                    excludedFromMatching = previous?.excludedFromMatching ?: false,
                    exclusionReason = previous?.exclusionReason,
                    calculatedAtEpochMillis = calculatedAtEpochMillis,
                )
            }
            .sortedBy(AggregatedWifiFingerprintEntity::bssid)
    }

    private fun median(sortedValues: List<Int>): Double {
        val middle = sortedValues.size / 2
        return if (sortedValues.size % 2 == 1) sortedValues[middle].toDouble()
        else (sortedValues[middle - 1] + sortedValues[middle]) / 2.0
    }

    private fun stableAggregateId(sessionId: String, referencePointId: String, bssid: String): String =
        "wifi-aggregate:$sessionId:$referencePointId:$bssid"
}

class WifiSurveyCaptureService(
    private val repository: SurveyRepository,
    private val factory: WifiSurveyCaptureFactory = WifiSurveyCaptureFactory(),
) {
    suspend fun record(
        session: SurveySessionEntity,
        scanSequence: Long,
        batch: WifiScanBatch,
        requestedAtEpochMillis: Long = batch.receivedAtEpochMillis,
    ): WifiCaptureEntities = factory.create(session, scanSequence, batch, requestedAtEpochMillis).also {
        repository.storeSnapshot(it.snapshot, it.observations)
    }

    suspend fun recomputeAggregates(
        sessionId: String,
        calculatedAtEpochMillis: Long,
    ): List<AggregatedWifiFingerprintEntity> {
        val session = requireNotNull(repository.session(sessionId)) { "Unknown survey session '$sessionId'" }
        val snapshots = repository.snapshotsForAggregation(sessionId)
        val observations = repository.freshObservationsForAggregation(sessionId)
        val existing = repository.aggregatesForSession(sessionId).associateBy { it.bssid.lowercase() }
        val aggregates = WifiEntityFingerprintAggregator.aggregate(
            session = session,
            snapshots = snapshots,
            observations = observations,
            calculatedAtEpochMillis = calculatedAtEpochMillis,
            existingByBssid = existing,
        )
        repository.saveAggregates(aggregates)
        return aggregates
    }
}
