package com.turn.fieldtest.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BluetoothDisabled
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.turn.fieldtest.BuildConfig
import com.turn.fieldtest.ui.TurnAppState
import com.turn.fieldtest.ui.model.DataMode
import com.turn.fieldtest.ui.model.EventSeverity
import com.turn.fieldtest.ui.model.TurnDestination
import com.turn.fieldtest.ui.theme.TurnAmber
import com.turn.fieldtest.ui.theme.TurnMint
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    state: TurnAppState,
    compact: Boolean,
    onModeChanged: (DataMode) -> Unit = { state.mode = it },
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(PagePadding),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        PageHeader(
            eyebrow = "08 · Configuration",
            title = "Settings",
            description = "Tune research parameters explicitly. TURN never substitutes simulated radio or sensor readings in real-device mode.",
            compact = compact,
            action = {
                OutlinedButton(onClick = { resetAlgorithmDefaults(state) }) {
                    Icon(Icons.Outlined.RestartAlt, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Reset defaults")
                }
            }
        )

        SectionCard("Data source", "Choose the environment before starting a session") {
            AdaptiveColumns(
                breakpoint = 540.dp,
                primary = {
                    ModeChoice(
                        title = "DEMO / SIMULATED",
                        detail = "Deterministic Wi-Fi and motion trace; suitable for emulator workflow checks",
                        selected = state.mode == DataMode.DEMO,
                        onClick = { onModeChanged(DataMode.DEMO) }
                    )
                },
                secondary = {
                    ModeChoice(
                        title = "REAL DEVICE",
                        detail = "Physical phone radio and Android sensors; failures are shown, never replaced",
                        selected = state.mode == DataMode.REAL_DEVICE,
                        onClick = { onModeChanged(DataMode.REAL_DEVICE) }
                    )
                }
            )
            Spacer(Modifier.height(10.dp))
            StatusPill(
                if (state.mode == DataMode.DEMO) "SIMULATED DATA active" else "REAL DEVICE sources required",
                if (state.mode == DataMode.DEMO) EventSeverity.WARNING else EventSeverity.GOOD
            )
        }

        AdaptiveColumns(
            primary = {
                SectionCard("Wi-Fi positioning", "Weighted k-nearest-neighbour fingerprint matching") {
                    SettingSlider(
                        label = "Neighbour count (k)",
                        valueLabel = state.knnK.toString(),
                        value = state.knnK.toFloat(),
                        range = 1f..10f,
                        steps = 8,
                        onChange = { state.knnK = it.roundToInt() }
                    )
                    SettingSlider(
                        label = "Missing BSSID RSSI",
                        valueLabel = "${state.missingRssi} dBm",
                        value = state.missingRssi.toFloat(),
                        range = -120f..-80f,
                        steps = 39,
                        onChange = { state.missingRssi = it.roundToInt() }
                    )
                    SettingToggle(
                        "Device-offset normalization",
                        "Optional median offset normalization across shared BSSIDs",
                        state.deviceOffsetNormalization,
                        { state.deviceOffsetNormalization = it }
                    )
                    LabelValue("Exact-match safeguard", "Enabled")
                    LabelValue("Unlike-database detection", "Enabled")
                }
            },
            secondary = {
                SectionCard("PDR & particle filter", "Session values are logged with each estimate") {
                    SettingSlider(
                        label = "Default stride",
                        valueLabel = "%.2f m".format(state.strideMetres),
                        value = state.strideMetres,
                        range = 0.4f..1.2f,
                        steps = 79,
                        onChange = { state.strideMetres = it }
                    )
                    SettingSlider(
                        label = "Particle count",
                        valueLabel = state.particleCount.toString(),
                        value = state.particleCount.toFloat(),
                        range = 100f..1_500f,
                        steps = 13,
                        onChange = { state.particleCount = (it / 100f).roundToInt() * 100 }
                    )
                    SettingToggle(
                        "Accelerometer step fallback",
                        "Used only when the step detector is unavailable; never double-counted",
                        state.accelerometerFallback,
                        { state.accelerometerFallback = it }
                    )
                    LabelValue("Heading preference", "Game rotation vector")
                    LabelValue("Global relocalization", "Explicit strong-match recovery")
                }
            }
        )

        AdaptiveColumns(
            primaryWeight = 1.05f,
            secondaryWeight = 0.95f,
            primary = {
                SectionCard("Permissions & hardware", "Requested only when the matching workflow starts") {
                    PermissionRow("Wi-Fi state", "manifest ready", true)
                    PermissionRow("Coarse + fine location", "Android 12+ pair; fine is required for scan-derived location", true)
                    PermissionRow("Nearby Wi-Fi", "version-aware", true)
                    PermissionRow("Camera", "requested for QR scan", true)
                    PermissionRow("Bluetooth scan", "not requested while BLE is off", false)
                    Text(
                        "Android Wi-Fi throttling is reported honestly; a cached broadcast is never counted as a fresh survey snapshot.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    FilledTonalButton(
                        onClick = { state.selectDestination(TurnDestination.RADIO_DIAGNOSTICS) },
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
                    ) { Text("Open hardware diagnostics") }
                }
            },
            secondary = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SectionCard("Appearance", "Stored as a local preference") {
                        SettingToggle("Dark field theme", "Higher contrast for on-site testing", state.darkTheme) { state.darkTheme = it }
                    }
                    SectionCard("Build", "Reproducibility metadata") {
                        LabelValue("Application", "TURN Field Test")
                        LabelValue("Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                        LabelValue("Package", BuildConfig.APPLICATION_ID)
                        LabelValue("Storage", "Offline Room database")
                    }
                }
            }
        )

        BleDisabledBanner()
        SectionCard("Future BLE correction", "Code paths exist, but no beacon hardware is required") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.BluetoothDisabled, contentDescription = null, tint = TurnAmber)
                Column(Modifier.weight(1f)) {
                    Text("Feature flag off", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "Register and physically place beacons before enabling scans. Fake BLE observations are allowed only in labelled demo mode.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = state.bleConfigured, onCheckedChange = null, enabled = false)
            }
            Spacer(Modifier.height(10.dp))
            LabelValue("Parsers", "iBeacon · Eddystone UID")
            LabelValue("Default build flag", if (BuildConfig.BLE_DEFAULT_ENABLED) "enabled" else "disabled")
            Button(onClick = { state.lastDataAction = "Beacon registration requires a stable identifier and measured placement" }) {
                Icon(Icons.Outlined.Info, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Review registration requirements")
            }
        }
    }
}

@Composable
private fun ModeChoice(title: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
                Text(title, fontWeight = FontWeight.Bold)
                Text(detail, style = MaterialTheme.typography.labelSmall)
            }
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
                Text(title, fontWeight = FontWeight.Bold)
                Text(detail, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun SettingSlider(
    label: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Text(valueLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }
        Slider(value = value.coerceIn(range), onValueChange = onChange, valueRange = range, steps = steps)
    }
}

@Composable
private fun SettingToggle(title: String, detail: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun PermissionRow(label: String, detail: String, active: Boolean) {
    LabelValue(label, detail, valueColor = if (active) TurnMint else MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun resetAlgorithmDefaults(state: TurnAppState) {
    state.knnK = 4
    state.missingRssi = -100
    state.strideMetres = 0.73f
    state.particleCount = 600
    state.deviceOffsetNormalization = false
    state.accelerometerFallback = false
}
