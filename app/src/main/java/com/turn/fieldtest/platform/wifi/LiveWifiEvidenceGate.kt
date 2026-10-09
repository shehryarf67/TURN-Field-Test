package com.turn.fieldtest.platform.wifi

/** Monotonic timestamps keep pre-refresh, duplicate and cached AP rows out of live matching. */
class LiveWifiEvidenceGate {
    private var notBeforeMicros = 0L
    private val lastTimestamps = mutableMapOf<String, Long>()

    fun reset(startedAtMicros: Long) {
        lastTimestamps.clear()
        notBeforeMicros = startedAtMicros
    }

    fun requireMeasurementsAfter(requestedAtMicros: Long) {
        notBeforeMicros = maxOf(notBeforeMicros, requestedAtMicros)
    }

    fun consume(batch: WifiScanBatch): List<WifiAccessPoint> {
        if (batch.simulated || !batch.isIndependentFreshScan) return emptyList()
        val receiptMicros = batch.receivedAtElapsedMillis * 1_000L
        val eligible = batch.accessPoints.groupBy { it.bssid.trim().lowercase() }.values
            .map { rows -> rows.maxWith(compareBy<WifiAccessPoint> { it.scanTimestampMicros }.thenBy { it.rssiDbm }) }
            .filter { ap ->
                ap.bssid.isNotBlank() && ap.ageMillisAtReceipt in 0L..15_000L &&
                    ap.scanTimestampMicros >= notBeforeMicros &&
                    ap.scanTimestampMicros <= receiptMicros + 999L &&
                    ap.scanTimestampMicros > (lastTimestamps[ap.bssid.trim().lowercase()] ?: 0L)
            }
        eligible.forEach { lastTimestamps[it.bssid.trim().lowercase()] = it.scanTimestampMicros }
        return eligible
    }
}
