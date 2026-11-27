package com.turn.fieldtest.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiagnosticsScreen(
    state: TurnAppState,
    compact: Boolean,
    onRequestScan: () -> Unit = {},
    onToggleDiagnosticWalk: () -> Unit = {},
    onToggleRawRecording: () -> Unit = {},
    onSaveRawRecording: () -> Unit = {},
    onRawMarker: (String) -> Unit = {},
) {
    val simulated = state.mode == DataMode.DEMO
    // The walk's only Stop button lives here; leaving the screen must not leave it recording and locking Settings.
    val stopWalk by androidx.compose.runtime.rememberUpdatedState(onToggleDiagnosticWalk)
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            if (state.diagnosticWalkRunning) {
                if (state.mode == DataMode.DEMO) state.diagnosticWalkRunning = false else stopWalk()
            }
        }
    }
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
            eyebrow = "Diagnostics",
            title = "Diagnostics",
            description = "Technical checks of your phone's Wi-Fi and motion sensors, and raw recordings for testing stairs and lifts. You don't need this page to map or navigate.",
            compact = compact,
            action = {
                Button(onClick = {
                    if (simulated) state.diagnosticScanSequence += 1 else onRequestScan()
                }) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null)
                    Spacer(Modifier.width(7.dp))
                    Text(if (simulated) "Replay scan" else "Scan Wi-Fi now")
                }
            }
        )

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatusPill(
                if (simulated) "Trace scan #${state.diagnosticScanSequence}"
                else state.realWifiIssue ?: when {
                    state.realWifiRequestInFlight -> "Waiting for the Wi-Fi scan…"
                    state.realWifiLastBatchEpochMillis != null -> "Wi-Fi scan received"
                    else -> "No Wi-Fi scan yet"
                },
                if (simulated || state.realWifiFresh == true) EventSeverity.GOOD
                else if (state.realWifiIssue != null) EventSeverity.WARNING else EventSeverity.INFO,
            )
            StatusPill(
                if (simulated) "throttling replayed"
                else when (state.realWifiThrottlingEnabled) { true -> "Android limits how often apps scan"; false -> "Android scan limit off"; null -> "Android scan limit: unknown" },
                EventSeverity.INFO,
            )
        }

        DiagnosticsGroup("Wi-Fi")
        BoxWithMetrics(state = state, compact = compact, simulated = simulated)

        if (!simulated) DiagnosticsGroup("Stairs and lifts")
        if (!simulated) SectionCard(
            title = "Record a stair or lift walk",
            subtitle = "Saves barometer, motion, magnetic and Wi-Fi readings to one CSV file, with markers for when you change floor",
            trailing = { if (state.rawRecording) StatusPill("${state.rawRecordingRows} rows", EventSeverity.GOOD) },
        ) {
            Text(state.rawRecordingStatus, style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onToggleRawRecording, enabled = !state.rawRecordingSaving, modifier = Modifier.weight(1f)) {
                    Text(if (state.rawRecording) "Stop recording" else "Start recording")
                }
                OutlinedButton(onClick = onSaveRawRecording, enabled = !state.rawRecording && state.rawRecordingSaveable && !state.rawRecordingSaving,
                    modifier = Modifier.weight(1f)) { Text("Save recording…") }
            }
            val venueId = state.activeProject?.venueId
            RawRecordingMarkers(
                floors = state.projects.filter { it.venueId == venueId }.sortedBy { it.floorLevel }.map { it.floorId to it.floorName },
                enabled = state.rawRecording,
                onMarker = onRawMarker,
            )
        }

        if (!simulated) FloorThresholdsCard(state)

        DiagnosticsGroup("Wi-Fi details")
        SectionCard(
            title = "Wi-Fi networks in range",
            subtitle = "Each access point is identified by its BSSID (hardware address), never its name (SSID)",
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
                            (if (ap.fresh) "New" else "Cached") to 94.dp
                        ),
                        tint = if (ap.fresh) TurnMint else TurnAmber
                    )
                }
                if (wifiRows.isEmpty()) {
                    Text(
                        if (simulated) "The demo trace contains no access points."
                        else "No Wi-Fi networks seen yet. Allow TURN to use location, turn on Wi-Fi and Location, then tap Scan Wi-Fi now.",
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
                    "Asking Android to scan doesn't guarantee new results. Results count as new only when Android confirms they were updated (EXTRA_RESULTS_UPDATED)."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        DiagnosticsGroup("Motion")
        SectionCard(
            title = "Motion sensors",
            subtitle = "Which sensors this phone has and whether they're working",
            trailing = {
                StatusPill(
                    "${sensorRows.count { it.available }} / ${sensorRows.size} available",
                    if (sensorRows.any { it.available }) EventSeverity.GOOD else EventSeverity.WARNING,
                )
            }
        ) {
            if (sensorRows.isEmpty()) {
                Text("No sensor information yet.", style = MaterialTheme.typography.bodySmall)
            } else {
                SensorGrid(sensorRows)
            }
        }

        DiagnosticWalkCard(state, compact, simulated, onToggleDiagnosticWalk)

        DiagnosticsGroup("Checklist")
        SectionCard("What's working", "Green is working; amber needs attention or is optional. TURN never makes up data when hardware is missing.") {
            AdaptiveColumns(
                breakpoint = 600.dp,
                primary = {
                    Column {
                        FailureRow("Wi-Fi listening", simulated || state.realWifiMonitoring, if (simulated) "replay ready" else if (state.realWifiMonitoring) "listening" else "not listening")
                        FailureRow("Wi-Fi permissions", simulated || state.wifiPermissionStatus.startsWith("Granted"), state.wifiPermissionStatus)
                        FailureRow("Scan request accepted", simulated || state.realWifiRequestAccepted == true, if (simulated) "trace sequence ${state.diagnosticScanSequence}" else yesNo(state.realWifiRequestAccepted, "not asked yet"))
                        FailureRow("Results updated", simulated || state.realWifiResultsUpdated == true, if (simulated) "trace metadata" else yesNo(state.realWifiResultsUpdated, "no results yet"))
                    }
                },
                secondary = {
                    Column {
                        FailureRow("New Wi-Fi results", simulated || state.realWifiFresh == true, if (simulated) "trace newest 1 s" else if (state.realWifiFresh == true) "yes" else "none")
                        FailureRow("Next scan allowed", true, if (simulated) "replayed" else nextRequestLabel(state.realWifiNextPermittedRequestEpochMillis))
                        FailureRow("Bluetooth beacons", false, "off by design")
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
    val request = if (simulated) "OK" else when (state.realWifiRequestAccepted) {
        true -> "Accepted"
        false -> "Refused"
        null -> "Not yet"
    }
    val visible = if (simulated) 5 else state.realWifiAccessPoints.size
    val newestAge = if (simulated) "1 s" else state.realWifiNewestAgeMillis?.let { "${it / 1_000.0} s" } ?: "—"
    val freshness = if (simulated) "fresh" else when (state.realWifiFresh) {
        true -> "new results"
        false -> "old (cached) results"
        null -> "no result"
    }
    val nextRequest = if (simulated) "00:23" else nextRequestLabel(state.realWifiNextPermittedRequestEpochMillis)
    if (compact) {
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                MetricCard("Scan request", request, Modifier.weight(1f), freshness, if (request == "Refused") TurnAmber else TurnMint)
                MetricCard("Networks seen", visible.toString(), Modifier.weight(1f), if (simulated) "3 new · 2 cached" else "in the last scan")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                MetricCard("Newest result", newestAge, Modifier.weight(1f), freshness)
                MetricCard("Next scan", nextRequest, Modifier.weight(1f), "TURN waits to respect Android's limit", TurnAmber)
            }
        }
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricCard("Scan request", request, Modifier.weight(1f), freshness, if (request == "Refused") TurnAmber else TurnMint)
            MetricCard("Networks seen", visible.toString(), Modifier.weight(1f), if (simulated) "3 new · 2 cached" else "in the last scan")
            MetricCard("Newest result", newestAge, Modifier.weight(1f), freshness)
            MetricCard("Next scan", nextRequest, Modifier.weight(1f), "TURN waits to respect Android's limit", TurnAmber)
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
        title = "Short test walk",
        subtitle = "Counts steps and distance on a short walk. Nothing is learned or saved.",
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
            Text(if (simulated) "Missing: magnetometer (optional)" else "Only real sensor readings from this phone are counted.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun nextRequestLabel(epochMillis: Long?): String {
    if (epochMillis == null) return "any time"
    val remaining = (epochMillis - System.currentTimeMillis()).coerceAtLeast(0L)
    return if (remaining == 0L) "ready" else "in ${remaining / 1_000L} s"
}

private fun yesNo(value: Boolean?, unknown: String) = when (value) { true -> "yes"; false -> "no"; null -> unknown }

/** Plain heading that groups the technical cards on this page. */
@Composable
private fun DiagnosticsGroup(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun FailureRow(label: String, healthy: Boolean, detail: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(if (healthy) TurnMint else TurnAmber))
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Ground-truth markers for stair and lift walks (plan Step 11), written into the recording with the
 * [com.turn.fieldtest.platform.sensors.TraceMarkers] grammar the replay harness reads. A lift's direction is part of
 * the grammar, so "Enter lift" is one button per direction. Usable only while a recording runs.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RawRecordingMarkers(floors: List<Pair<String, String>>, enabled: Boolean, onMarker: (String) -> Unit) {
    val markers = com.turn.fieldtest.platform.sensors.TraceMarkers
    val stairs = com.turn.fieldtest.core.ConnectorKind.STAIRS
    val lift = com.turn.fieldtest.core.ConnectorKind.LIFT
    Text("Markers", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
    Text(if (enabled) "Tap as it happens; they mark the true floor in the recording" else "Start recording to add markers",
        style = MaterialTheme.typography.bodySmall)
    FlowRow(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            "Start stairs up" to markers.verticalStart(stairs, up = true),
            "Start stairs down" to markers.verticalStart(stairs, up = false),
            "Enter lift up" to markers.verticalStart(lift, up = true),
            "Enter lift down" to markers.verticalStart(lift, up = false),
            "Standing still" to markers.STILL,
        ).forEach { (label, text) -> OutlinedButton(onClick = { onMarker(text) }, enabled = enabled) { Text(label) } }
    }
    // Floor ids the grammar cannot carry (commas, semicolons, outer spaces) are left out rather than written wrongly.
    val arrivals = floors.mapNotNull { (id, name) -> runCatching { markers.arrived(id) }.getOrNull()?.let { name to it } }
    Text("Arrived on…", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
    if (arrivals.isEmpty()) Text("Open a floor of a building to mark arrivals", style = MaterialTheme.typography.bodySmall)
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        arrivals.forEach { (name, text) ->
            androidx.compose.material3.AssistChip(onClick = { onMarker(text) }, enabled = enabled, label = { Text(name) })
        }
    }
}
