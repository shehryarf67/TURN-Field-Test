package com.turn.fieldtest.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.turn.fieldtest.ui.model.DataMode
import com.turn.fieldtest.ui.model.TurnDestination
import com.turn.fieldtest.ui.screens.DataExportScreen
import com.turn.fieldtest.ui.screens.DiagnosticsScreen
import com.turn.fieldtest.ui.screens.EvaluateScreen
import com.turn.fieldtest.ui.screens.FloorEditorScreen
import com.turn.fieldtest.ui.screens.LiveLocateScreen
import com.turn.fieldtest.ui.screens.SettingsScreen
import com.turn.fieldtest.ui.screens.SurveyScreen
import com.turn.fieldtest.ui.screens.VenuesScreen
import com.turn.fieldtest.ui.theme.TurnAmber
import com.turn.fieldtest.ui.theme.TurnMint
import com.turn.fieldtest.ui.theme.TurnTheme
import kotlinx.coroutines.delay

data class TurnAppActions(
    val onModeChanged: (DataMode) -> Unit = {},
    val onDiagnosticScanRequested: () -> Unit = {},
    val onDiagnosticWalkToggled: () -> Unit = {},
    val onRealSurveyStarted: (SurveyCaptureMetadata) -> Unit = {},
    val onRealSurveyFinished: () -> Unit = {},
    val onRealLiveToggled: () -> Unit = {},
    val onRealLiveScanRequested: () -> Unit = {},
    val onRealLiveRelocalizationRequested: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TurnApp(
    state: TurnAppState = rememberTurnAppState(),
    actions: TurnAppActions = TurnAppActions(),
) {
    TurnTheme(darkTheme = state.darkTheme) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val expanded = maxWidth >= 760.dp
            if (expanded) {
                Row(Modifier.fillMaxSize()) {
                    ResearchNavigationRail(
                        current = state.destination,
                        onSelected = state::selectDestination
                    )
                    HorizontalDivider(
                        modifier = Modifier.fillMaxHeight().width(1.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                    AppScaffold(state = state, actions = actions, compact = false, modifier = Modifier.weight(1f))
                }
            } else {
                AppScaffold(state = state, actions = actions, compact = true)
            }
        }
    }

    if (state.compactMenuOpen) {
        ModalBottomSheet(onDismissRequest = { state.compactMenuOpen = false }) {
            Text(
                text = "All research tools",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 24.dp)
            ) {
                TurnDestination.entries.forEach { destination ->
                    DestinationRow(
                        destination = destination,
                        selected = state.destination == destination,
                        onClick = { state.selectDestination(destination) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppScaffold(
    state: TurnAppState,
    actions: TurnAppActions,
    compact: Boolean,
    modifier: Modifier = Modifier
) {
    val primaryDestinations = listOf(
        TurnDestination.VENUES,
        TurnDestination.SURVEY,
        TurnDestination.LIVE_LOCATE,
        TurnDestination.EVALUATE
    )

    if (state.mode == DataMode.DEMO && state.liveRunning) {
        LaunchedEffect(state.liveRunning) {
            while (state.liveRunning) {
                delay(900)
                state.stepReplay()
            }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                navigationIcon = {
                    if (compact) {
                        IconButton(
                            onClick = { state.compactMenuOpen = true },
                            modifier = Modifier.semantics { contentDescription = "Open all TURN sections" }
                        ) {
                            Icon(Icons.Outlined.Menu, contentDescription = null)
                        }
                    }
                },
                title = {
                    Column {
                        Text(
                            "TURN RESEARCH PROTOTYPE",
                            fontWeight = FontWeight.Black,
                            letterSpacing = 0.6.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            state.destination.title,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    if (!compact) {
                        FilledTonalButton(
                            onClick = {
                                actions.onModeChanged(if (state.mode == DataMode.DEMO) {
                                    DataMode.REAL_DEVICE
                                } else {
                                    DataMode.DEMO
                                })
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                            modifier = Modifier.padding(end = 12.dp)
                        ) {
                            Icon(Icons.Outlined.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(state.mode.label, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (compact) {
                NavigationBar {
                    primaryDestinations.forEach { destination ->
                        NavigationBarItem(
                            selected = state.destination == destination,
                            onClick = { state.selectDestination(destination) },
                            icon = { DestinationBadge(destination.shortLabel, selected = state.destination == destination) },
                            label = { Text(destination.title.substringBefore(' '), maxLines = 1) }
                        )
                    }
                    NavigationBarItem(
                        selected = state.destination !in primaryDestinations,
                        onClick = { state.compactMenuOpen = true },
                        icon = { Icon(Icons.Outlined.MoreHoriz, contentDescription = "More sections") },
                        label = { Text("More") }
                    )
                }
            }
        }
    ) { scaffoldPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPadding)
        ) {
            ModeStatusStrip(state.mode, compact)
            TurnScreen(state = state, actions = actions, compact = compact)
        }
    }
}

@Composable
private fun TurnScreen(state: TurnAppState, actions: TurnAppActions, compact: Boolean) {
    when (state.destination) {
        TurnDestination.VENUES -> VenuesScreen(state, compact)
        TurnDestination.FLOOR_EDITOR -> FloorEditorScreen(state, compact)
        TurnDestination.RADIO_DIAGNOSTICS -> DiagnosticsScreen(
            state,
            compact,
            onRequestScan = actions.onDiagnosticScanRequested,
            onToggleDiagnosticWalk = actions.onDiagnosticWalkToggled,
        )
        TurnDestination.SURVEY -> SurveyScreen(
            state,
            compact,
            onStartRealSurvey = actions.onRealSurveyStarted,
            onFinishRealSurvey = actions.onRealSurveyFinished,
        )
        TurnDestination.LIVE_LOCATE -> LiveLocateScreen(
            state,
            compact,
            onRealLiveToggle = actions.onRealLiveToggled,
            onRealScanRequested = actions.onRealLiveScanRequested,
            onRealRelocalizationRequested = actions.onRealLiveRelocalizationRequested,
        )
        TurnDestination.EVALUATE -> EvaluateScreen(state, compact)
        TurnDestination.DATA_EXPORT -> DataExportScreen(state, compact)
        TurnDestination.SETTINGS -> SettingsScreen(state, compact, actions.onModeChanged)
    }
}

@Composable
private fun ModeStatusStrip(mode: DataMode, compact: Boolean) {
    val demo = mode == DataMode.DEMO
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (demo) TurnAmber.copy(alpha = 0.16f) else TurnMint.copy(alpha = 0.14f))
            .padding(horizontal = if (compact) 12.dp else 24.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(50))
                .background(if (demo) TurnAmber else TurnMint)
        )
        Text(
            text = if (demo) "SIMULATED DATA · deterministic replay" else "REAL DEVICE · no simulated fallback",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = if (demo) TurnAmber else TurnMint,
            modifier = Modifier.weight(1f)
        )
        if (!compact) {
            Text(
                text = "BLE not configured — Wi-Fi + PDR active",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ResearchNavigationRail(
    current: TurnDestination,
    onSelected: (TurnDestination) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxHeight().width(224.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(Modifier.padding(vertical = 18.dp)) {
            Row(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Text("T", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Black, fontSize = 22.sp)
                }
                Column {
                    Text("TURN", fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleLarge)
                    Text("FIELD TEST", style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp)
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(
                "WORKSPACE",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 1.2.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
            )
            TurnDestination.entries.forEach { destination ->
                DestinationRow(destination, current == destination) { onSelected(destination) }
            }
            Spacer(Modifier.weight(1f))
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            Text(
                "Offline research workspace\nAnonymous session data only",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(20.dp)
            )
        }
    }
}

@Composable
private fun DestinationRow(
    destination: TurnDestination,
    selected: Boolean,
    onClick: () -> Unit
) {
    val background = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Open ${destination.title}: ${destination.description}" }
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp)
    ) {
        DestinationBadge(destination.shortLabel, selected)
        Column(Modifier.weight(1f)) {
            Text(
                destination.title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
            )
            if (selected) {
                Text(
                    destination.description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun DestinationBadge(label: String, selected: Boolean) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                else MaterialTheme.colorScheme.surfaceVariant
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Black,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Preview(widthDp = 1200, heightDp = 800)
@Composable
private fun TurnTabletPreview() {
    TurnApp()
}

@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun TurnPhonePreview() {
    TurnApp()
}
