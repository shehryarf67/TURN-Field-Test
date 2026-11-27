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
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.RadioButton
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
import com.turn.fieldtest.ui.components.AdvancedSection
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
    val editable = state.canChangeAlgorithmSettings
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(PagePadding),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        PageHeader(
            eyebrow = "Settings",
            title = "Settings",
            description = "Appearance and how TURN works. The defaults suit most homes — positioning and hardware details are under the collapsed sections.",
            compact = compact,
        )
        SectionCard("Appearance") {
            SettingToggle("Dark mode", "Blue and white by day; navy and blue by night", state.darkTheme) { state.darkTheme = it }
        }

        SectionCard("Data source", "Use your phone's real sensors to map and navigate your home. Demo plays back recorded data, for trying the app.") {
            AdaptiveColumns(
                breakpoint = 540.dp,
                primary = {
                    ModeChoice(
                        title = "Real phone sensors",
                        detail = "Your phone's Wi-Fi and motion sensors. Problems are shown, never hidden or faked.",
                        selected = state.mode == DataMode.REAL_DEVICE,
                        onClick = { onModeChanged(DataMode.REAL_DEVICE) }
                    )
                },
                secondary = {
                    ModeChoice(
                        title = "Demo (simulated data)",
                        detail = "A recorded walk with simulated Wi-Fi, for trying the app or the emulator",
                        selected = state.mode == DataMode.DEMO,
                        onClick = { onModeChanged(DataMode.DEMO) }
                    )
                }
            )
            Spacer(Modifier.height(10.dp))
            StatusPill(
                if (state.mode == DataMode.DEMO) "Using simulated data" else "Using real sensors",
                if (state.mode == DataMode.DEMO) EventSeverity.WARNING else EventSeverity.GOOD
            )
        }

        AdvancedSection(title = POSITIONING_SETTINGS, subtitle = "Wi-Fi matching, step length and tracking. Only change these if you know why.") {
            if (!editable) Text("Stop tracking or recording before changing these settings.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { resetAlgorithmDefaults(state) }, enabled = editable) {
                Icon(Icons.Outlined.RestartAlt, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Reset defaults")
            }
            AdaptiveColumns(
                primary = {
                    SectionCard("Wi-Fi positioning", "How a Wi-Fi reading is matched to the points you recorded (weighted k-nearest neighbours)") {
                        SettingSlider(
                            label = "Recorded points compared (k)",
                            valueLabel = state.knnK.toString(),
                            value = state.knnK.toFloat(),
                            range = 1f..10f,
                            steps = 8,
                            enabled = editable,
                            onChange = { state.knnK = it.roundToInt() }
                        )
                        SettingSlider(
                            label = "Signal assumed when an access point is missing",
                            valueLabel = "${state.missingRssi} dBm",
                            value = state.missingRssi.toFloat(),
                            range = -120f..-80f,
                            steps = 39,
                            enabled = editable,
                            onChange = { state.missingRssi = it.roundToInt() }
                        )
                        SettingToggle(
                            "Device-offset normalization",
                            "Corrects for phones that read all Wi-Fi a little stronger or weaker",
                            state.deviceOffsetNormalization,
                            enabled = editable,
                            onCheckedChange = { state.deviceOffsetNormalization = it }
                        )
                        LabelValue("Exact-match safeguard", "On")
                        LabelValue("Unknown-place detection", "On")
                    }
                },
                secondary = {
                    SectionCard("Step tracking", "Step counting and the particle filter that follows you between Wi-Fi fixes") {
                        SettingSlider(
                            label = "Step length",
                            valueLabel = "%.2f m".format(state.strideMetres),
                            value = state.strideMetres,
                            range = 0.4f..1.2f,
                            steps = 79,
                            enabled = editable,
                            onChange = { state.strideMetres = it }
                        )
                        SettingSlider(
                            label = "Tracking samples (particles)",
                            valueLabel = state.particleCount.toString(),
                            value = state.particleCount.toFloat(),
                            range = 100f..1_500f,
                            steps = 13,
                            enabled = editable,
                            onChange = { state.particleCount = (it / 100f).roundToInt() * 100 }
                        )
                        LabelValue("Step counting", "Uses the phone's step detector (required)")
                        LabelValue("Direction source", "Game rotation vector")
                        LabelValue("Recovering when lost", "Only on a strong Wi-Fi match")
                    }
                }
            )
        }

        AdvancedSection(title = "Permissions & hardware", subtitle = "What TURN asks for, and when") {
            PermissionRow("Wi-Fi state", "Ready", true)
            PermissionRow("Location", "Needed by Android to read Wi-Fi scans", true)
            PermissionRow("Nearby Wi-Fi", "Asked on newer Android versions", true)
            PermissionRow("Physical activity", "Asked when step tracking starts", true)
            PermissionRow("Camera", "Asked when you map with the camera or scan a QR code", true)
            PermissionRow("Bluetooth scan", "Not asked — Bluetooth beacons are off", false)
            Text(
                "Android limits how often apps can scan Wi-Fi. TURN shows when a scan is old instead of counting it as new.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilledTonalButton(
                onClick = { state.selectDestination(TurnDestination.RADIO_DIAGNOSTICS) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Open diagnostics") }
        }

        AdvancedSection(title = "About this build") {
            LabelValue("Application", "TURN")
            LabelValue("Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            LabelValue("Package", BuildConfig.APPLICATION_ID)
            LabelValue("Storage", "On this phone only (offline database)")
        }

        AdvancedSection(title = "Bluetooth beacons", subtitle = "Not used — TURN works with Wi-Fi and step tracking") {
            BleDisabledBanner()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.BluetoothDisabled, contentDescription = null, tint = TurnAmber)
                Column(Modifier.weight(1f)) {
                    Text("Beacons are off", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "Beacons would need to be registered and physically placed before scanning. Simulated beacon readings appear only in demo mode.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = state.bleConfigured, onCheckedChange = null, enabled = false)
            }
            LabelValue("Supported beacon types", "iBeacon · Eddystone UID")
            LabelValue("Default build flag", if (BuildConfig.BLE_DEFAULT_ENABLED) "on" else "off")
            Button(onClick = { state.lastDataAction = "Beacon registration requires a stable identifier and measured placement" }) {
                Icon(Icons.Outlined.Info, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("What beacon setup needs")
            }
        }
    }
}

/** Title of the collapsed positioning section (tests expand it by this name). */
internal const val POSITIONING_SETTINGS = "Positioning (advanced)"

@Composable
private fun ModeChoice(title: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    OutlinedCard(onClick=onClick,modifier=Modifier.fillMaxWidth(),shape=MaterialTheme.shapes.small,
        border=BorderStroke(1.dp,if(selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        colors=CardDefaults.outlinedCardColors(containerColor=if(selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha=.3f) else MaterialTheme.colorScheme.surface)) {
        Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
            RadioButton(selected=selected,onClick=null)
            Column(Modifier.weight(1f).padding(start=8.dp)) {
                Text(title, fontWeight = FontWeight.Bold)
                Text(detail, style = MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
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
    enabled: Boolean = true,
    onChange: (Float) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Text(valueLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }
        Slider(value = value.coerceIn(range), onValueChange = onChange, valueRange = range, steps = steps, enabled = enabled)
    }
}

@Composable
private fun SettingToggle(title: String, detail: String, checked: Boolean, enabled: Boolean = true, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun PermissionRow(label: String, detail: String, active: Boolean) {
    LabelValue(label, detail, valueColor = if (active) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun resetAlgorithmDefaults(state: TurnAppState) {
    if (!state.canChangeAlgorithmSettings) return
    state.knnK = 4
    state.missingRssi = -100
    state.strideMetres = 0.73f
    state.particleCount = 600
    state.deviceOffsetNormalization = false
    state.accelerometerFallback = false
}
