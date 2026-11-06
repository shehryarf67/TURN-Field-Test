package com.turn.fieldtest.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.turn.fieldtest.ui.model.DataMode
import com.turn.fieldtest.ui.model.TurnDestination
import com.turn.fieldtest.ui.screens.FullscreenNavigationScreen
import com.turn.fieldtest.ui.screens.ToolsScreen
import kotlinx.coroutines.delay

/** Only presentation navigation changes here; hardware and repository actions retain their owners. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CanvasAppShell(state: TurnAppState, actions: TurnAppActions) {
    LaunchedEffect(state.mode, state.liveRunning) {
        if (state.mode == DataMode.DEMO) while (state.liveRunning) { delay(900); state.stepReplay() }
    }
    // Older research screens use this flag to open the section menu.
    LaunchedEffect(state.compactMenuOpen) { if (state.compactMenuOpen) state.openTools() }
    if (state.isNavigationFullscreen) { FullscreenNavigationScreen(state); return }
    val exploring = !state.toolsHome && state.destination == TurnDestination.LIVE_LOCATE
    val buildings = !state.toolsHome && state.destination == TurnDestination.VENUES
    BackHandler(enabled = state.toolsHome || (!exploring && !buildings)) {
        if (state.toolsHome) state.selectDestination(if (state.realMapReady) TurnDestination.LIVE_LOCATE else TurnDestination.VENUES)
        else state.openTools()
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (state.startupLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator(); Text("Opening your saved floors…")
                }
            }
        } else Scaffold(
            topBar = {
                if (!exploring) TopAppBar(
                    title = { Text(if (state.toolsHome) "Tools" else if (buildings) "Buildings" else state.destination.title,
                        fontWeight = FontWeight.Bold) },
                    navigationIcon = { if (!state.toolsHome && !buildings) IconButton(onClick = state::openTools) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to tools")
                    } },
                    actions = { IconButton(onClick = { state.darkTheme = !state.darkTheme }) {
                        Icon(if (state.darkTheme) Icons.Outlined.LightMode else Icons.Outlined.DarkMode,
                            if (state.darkTheme) "Use light mode" else "Use dark mode")
                    } },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
            },
            bottomBar = {
                if (!(exploring && state.liveRunning && state.mode == DataMode.REAL_DEVICE)) NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                    NavigationBarItem(selected = exploring, onClick = { state.selectDestination(TurnDestination.LIVE_LOCATE) },
                        icon = { Icon(Icons.Outlined.Map, null) }, label = { Text("Explore") })
                    NavigationBarItem(selected = buildings, onClick = { state.selectDestination(TurnDestination.VENUES) },
                        icon = { Icon(Icons.Outlined.Apartment, null) }, label = { Text("Buildings") })
                    NavigationBarItem(selected = !exploring && !buildings, onClick = state::openTools,
                        icon = { Icon(Icons.Outlined.Tune, null) }, label = { Text("Tools") })
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (state.mode == DataMode.DEMO) Surface(color = MaterialTheme.colorScheme.tertiaryContainer) {
                    Text("DEMO · simulated data", Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium)
                }
                if (state.liveRunning && !exploring) TextButton(
                    onClick = { state.selectDestination(TurnDestination.LIVE_LOCATE) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.MyLocation, null); Spacer(Modifier.width(8.dp)); Text("Tracking is running · Return to map")
                }
                if (state.toolsHome) ToolsScreen(state, actions)
                else TurnScreen(state, actions, compact = true)
            }
        }
    }
}
