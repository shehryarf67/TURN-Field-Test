package com.turn.fieldtest.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.turn.fieldtest.ui.TurnAppState
import com.turn.fieldtest.ui.model.DataMode
import com.turn.fieldtest.ui.model.EventSeverity
import com.turn.fieldtest.ui.model.SensorReadingUi
import com.turn.fieldtest.ui.model.TurnDemoData
import com.turn.fieldtest.ui.theme.TurnAmber
import com.turn.fieldtest.ui.theme.TurnMint
import com.turn.fieldtest.ui.theme.TurnRed

@Composable
fun DiagnosticsScreen(
    state: TurnAppState,
    compact: Boolean,
    onRequestScan: () -> Unit = {},
    onToggleDiagnosticWalk: () -> Unit = {},
) {
    val simulated = state.mode == DataMode.DEMO
    val wifiRows = if (simulated) TurnDemoData.wifi else state.realWifiAccessPoints
    val sensorRows = if (simulated) TurnDemoData.sensors else state.realSensorReadings
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(PagePadding),
        verticalArrangement = Arrangement.spacedBy(15.dp)
    ) {
        PageHeader(
            eyebrow = "03 · Hardware readiness",
            title = "Radio & sensor diagnostics",
            description = "Verify freshness, scan restrictions and motion sources before collecting research data.",
            compact = compact,
            action = {
                Button(onClick = {
                    if (simulated) state.diagnosticScanSequence += 1 else onRequestScan()
                }) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null)
                    Spacer(Modifier.width(7.dp))
                    Text(if (simulated) "Replay scan" else "Request scan")
                }
            }
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            StatusPill(
                if (simulated) "Trace scan #${state.diagnosticScanSequence}"
                else state.realWifiIssue ?: when {
                    state.realWifiRequestInFlight -> "Waiting for Android scan results"
                    state.realWifiLastBatchEpochMillis != null -> "Physical SCAN_RESULTS received"
                    else -> "No physical scan received yet"
                },
                if (simulated || state.realWifiFresh == true) EventSeverity.GOOD
                else if (state.realWifiIssue != null) EventSeverity.WARNING else EventSeverity.INFO,
            )
            StatusPill(
                if (simulated) "throttling replayed"
                else if (state.realWifiThrottlingEnabled == true) "Android throttling enabled" else "throttling status ${state.realWifiThrottlingEnabled ?: "unknown"}",
                EventSeverity.INFO,
            )
        }

        BoxWithMetrics(state = state, compact = compact, simulated = simulated)

        SectionCard(
            title = "Visible Wi-Fi access points",
            subtitle = "Fingerprint identity is BSSID, never SSID",
            trailing = {
                StatusPill(
                    "${wifiRows.size} visible",
                    if (wifiRows.isNotEmpty()) EventSeverity.GOOD else EventSeverity.WARNING,
                )
            }
        ) {
            TableShell(
                headers = listOf(
                    "BSSID / SSID" to 190.dp,
                    "RSSI" to 70.dp,
                    "Frequency" to 90.dp,
                    "Channel" to 72.dp,
                    "Age" to 66.dp,
                    "Freshness" to 94.dp
                )
            ) {
                wifiRows.forEach { ap ->
                    DataRow(
                        cells = listOf(
                            "${ap.bssid}\n${ap.ssid}" to 190.dp,
                            "${ap.rssiDbm} dBm" to 70.dp,
                            "${ap.frequencyMhz} MHz" to 90.dp,
                            (ap.channel?.toString() ?: "—") to 72.dp,
                            "${ap.ageSeconds} s" to 66.dp,
                            (if (ap.fresh) "FRESH" else "CACHED") to 94.dp
                        ),
                        tint = if (ap.fresh) TurnMint else TurnAmber
                    )
                }
                if (wifiRows.isEmpty()) {
                    Text(
                        if (simulated) "The demo trace contains no access points."
                        else "No physical Wi-Fi observations are available. Grant permission, enable Wi-Fi and location services, then request a scan.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                if (simulated) {
                    "Replay metadata preserves original scan boundaries. Cached snapshots are shown but never counted as independent scans."
                } else {
                    "A successful startScan() call is only a request. Results count as fresh only when EXTRA_RESULTS_UPDATED confirms an update."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        SectionCard(
            title = "Motion sensors",
            subtitle = "Live source availability and stability",
            trailing = {
                StatusPill(
                    "${sensorRows.count { it.available }} / ${sensorRows.size} available",
                    if (sensorRows.any { it.available }) EventSeverity.GOOD else EventSeverity.WARNING,
                )
            }
        ) {
            if (sensorRows.isEmpty()) {
                Text("No physical sensor inventory is available yet.", style = MaterialTheme.typography.bodySmall)
            } else {
                SensorGrid(sensorRows)
            }
        }

        DiagnosticWalkCard(state, compact, simulated, onToggleDiagnosticWalk)

        SectionCard("Failure-state checks", "TURN reports hardware limits; it never fabricates fresh data") {
            AdaptiveColumns(
                breakpoint = 600.dp,
                primary = {
                    Column {
                        FailureRow("Wi-Fi source", simulated || state.realWifiMonitoring, if (simulated) "replay ready" else if (state.realWifiMonitoring) "foreground receiver active" else "not active")
                        FailureRow("Radio permissions", simulated || state.wifiPermissionStatus.startsWith("Granted"), state.wifiPermissionStatus)
                        FailureRow("Scan request accepted", simulated || state.realWifiRequestAccepted == true, if (simulated) "trace sequence ${state.diagnosticScanSequence}" else state.realWifiRequestAccepted?.toString() ?: "not requested")
                        FailureRow("Results updated", simulated || state.realWifiResultsUpdated == true, if (simulated) "trace metadata" else state.realWifiResultsUpdated?.toString() ?: "no broadcast")
                    }
                },
                secondary = {
                    Column {
                        FailureRow("Fresh system results", simulated || state.realWifiFresh == true, if (simulated) "trace newest 1 s" else if (state.realWifiFresh == true) "fresh" else "none")
                        FailureRow("Throttling monitored", true, if (simulated) "replayed" else nextRequestLabel(state.realWifiNextPermittedRequestEpochMillis))
                        FailureRow("BLE configured", false, "disabled by design")
                        FailureRow("Magnetometer", false, "optional")
                    }
                }
            )
        }

        BleDisabledBanner()
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun BoxWithMetrics(state: TurnAppState, compact: Boolean, simulated: Boolean) {
    val request = if (simulated) "SUCCESS" else when (state.realWifiRequestAccepted) {
        true -> "ACCEPTED"
        false -> "REJECTED"
        null -> "NOT REQUESTED"
    }
    val visible = if (simulated) 5 else state.realWifiAccessPoints.size
    val newestAge = if (simulated) "1 s" else state.realWifiNewestAgeMillis?.let { "${it / 1_000.0} s" } ?: "—"
    val freshness = if (simulated) "fresh" else when (state.realWifiFresh) {
        true -> "fresh physical results"
        false -> "cached/stale"
        null -> "no result"
    }
    val nextRequest = if (simulated) "00:23" else nextRequestLabel(state.realWifiNextPermittedRequestEpochMillis)
    if (compact) {
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                MetricCard("Scan request", request, Modifier.weight(1f), freshness, if (request == "REJECTED") TurnAmber else TurnMint)
                MetricCard("Visible APs", visible.toString(), Modifier.weight(1f), if (simulated) "3 fresh · 2 cached" else "physical observations")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                MetricCard("Newest age", newestAge, Modifier.weight(1f), freshness)
                MetricCard("Next request", nextRequest, Modifier.weight(1f), "conservative client limit", TurnAmber)
            }
        }
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricCard("Scan request", request, Modifier.weight(1f), freshness, if (request == "REJECTED") TurnAmber else TurnMint)
            MetricCard("Visible APs", visible.toString(), Modifier.weight(1f), if (simulated) "3 fresh · 2 cached" else "physical observations")
            MetricCard("Newest age", newestAge, Modifier.weight(1f), freshness)
            MetricCard("Next request", nextRequest, Modifier.weight(1f), "conservative client limit", TurnAmber)
        }
    }
}

@Composable
private fun SensorGrid(sensors: List<SensorReadingUi>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        sensors.chunked(2).forEach { rowSensors ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowSensors.forEach { sensor -> SensorCard(sensor, Modifier.weight(1f)) }
                if (rowSensors.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SensorCard(sensor: SensorReadingUi, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.33f)),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(if (sensor.available) TurnMint else TurnRed)
            )
            Column(Modifier.weight(1f)) {
                Text(sensor.name, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                Text(sensor.value, style = MaterialTheme.typography.bodySmall)
                Text(sensor.quality, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun DiagnosticWalkCard(
    state: TurnAppState,
    compact: Boolean,
    simulated: Boolean,
    onToggleDiagnosticWalk: () -> Unit,
) {
    SectionCard(
        title = "Short diagnostic walk",
        subtitle = "Relative motion only · no ground-truth learning",
        trailing = {
            FilledTonalButton(onClick = {
                if (simulated) state.diagnosticWalkRunning = !state.diagnosticWalkRunning
                else onToggleDiagnosticWalk()
            }) {
                Icon(
                    if (state.diagnosticWalkRunning) Icons.Outlined.Stop else Icons.AutoMirrored.Outlined.DirectionsWalk,
                    contentDescription = null
                )
                Spacer(Modifier.width(7.dp))
                Text(if (state.diagnosticWalkRunning) "Stop" else "Start walk")
            }
        }
    ) {
        if (compact) {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    MetricCard("Detected steps", if (simulated && !state.diagnosticWalkRunning) "18" else state.diagnosticWalkSteps.toString(), Modifier.weight(1f), if (simulated) "step detector replay" else "physical step detector")
                    MetricCard("Distance", if (simulated) "13.1 m" else "%.1f m".format(state.realDiagnosticDistanceMetres), Modifier.weight(1f), "stride ${state.strideMetres} m")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    MetricCard("Heading source", if (simulated) "game rotation" else state.realSensorReadings.firstOrNull { it.quality == "selected heading source" }?.name ?: "unavailable", Modifier.weight(1f), "relative orientation")
                    MetricCard("Status", if (simulated) "1 turn" else state.realDiagnosticStatus, Modifier.weight(1f), "foreground only")
                }
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                MetricCard("Detected steps", if (simulated && !state.diagnosticWalkRunning) "18" else state.diagnosticWalkSteps.toString(), Modifier.weight(1f), if (simulated) "step detector replay" else "physical step detector")
                MetricCard("Distance", if (simulated) "13.1 m" else "%.1f m".format(state.realDiagnosticDistanceMetres), Modifier.weight(1f), "stride ${state.strideMetres} m")
                MetricCard("Heading source", if (simulated) "game rotation" else state.realSensorReadings.firstOrNull { it.quality == "selected heading source" }?.name ?: "unavailable", Modifier.weight(1f), "relative orientation")
                MetricCard("Status", if (simulated) "1 turn" else state.realDiagnosticStatus, Modifier.weight(1f), "foreground only")
                MetricCard("Pressure", if (simulated) "−0.04 hPa" else state.realSensorReadings.firstOrNull { it.name.contains("Barometer") }?.value ?: "unavailable", Modifier.weight(1f), "optional floor evidence")
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill(if (state.diagnosticWalkRunning) "recording" else if (simulated) "complete" else "not recording", if (state.diagnosticWalkRunning) EventSeverity.INFO else EventSeverity.GOOD)
            Text(if (simulated) "Missing: magnetometer (optional)" else "Only physical Android sensor events are counted.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun nextRequestLabel(epochMillis: Long?): String {
    if (epochMillis == null) return "not scheduled"
    val remaining = (epochMillis - System.currentTimeMillis()).coerceAtLeast(0L)
    return if (remaining == 0L) "ready" else "in ${remaining / 1_000L} s"
}

@Composable
private fun FailureRow(label: String, healthy: Boolean, detail: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(if (healthy) TurnMint else TurnAmber))
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
