package com.turn.fieldtest.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.turn.fieldtest.ui.model.DataMode
import com.turn.fieldtest.ui.model.TurnDestination
import com.turn.fieldtest.ui.screens.*
import com.turn.fieldtest.ui.theme.TurnTheme

data class TurnAppActions(
    val onMapperImportRequested: () -> Unit = {},
    val onBundledMapperImport: (String, Boolean) -> Unit = { _, _ -> },
    val onDestinationSelected: (String?) -> Unit = {},
    val onProjectCreated: (com.turn.fieldtest.data.repository.ProjectInput) -> Unit = {},
    val onProjectOpened: (com.turn.fieldtest.data.repository.ProjectContext, Boolean) -> Unit = { _, _ -> },
    val onRealExport: (android.net.Uri, com.turn.fieldtest.data.export.ResearchDataset) -> Unit = { _, _ -> },
    val onMapSaved: () -> Unit = {},
    val onMapDeleted: () -> Unit = {},
    val onFloorPlanImportRequested: () -> Unit = {},
    val onWalkMapperRequested: () -> Unit = {},
    val onCheckpointCaptured: (com.turn.fieldtest.ui.screens.CheckpointInput) -> Unit = {},
    val onModeChanged: (DataMode) -> Unit = {},
    val onDiagnosticScanRequested: () -> Unit = {},
    val onDiagnosticWalkToggled: () -> Unit = {},
    val onRealSurveyStarted: (SurveyCaptureMetadata) -> Unit = {},
    val onRealSurveyFinished: () -> Unit = {},
    val onRealHeadingAligned: (Double) -> Unit = {},
    val onRealLiveToggled: () -> Unit = {},
    val onRealLiveScanRequested: () -> Unit = {},
    val onRealLiveRelocalizationRequested: () -> Unit = {},
)

@Composable
fun TurnApp(state: TurnAppState = rememberTurnAppState(), actions: TurnAppActions = TurnAppActions()) {
    TurnTheme(darkTheme = state.darkTheme) { CanvasAppShell(state, actions) }
}

@Composable
internal fun TurnScreen(state: TurnAppState, actions: TurnAppActions, compact: Boolean) {
    if (state.mode == DataMode.REAL_DEVICE && !state.realMapReady && state.destination in listOf(
        TurnDestination.FLOOR_EDITOR, TurnDestination.SURVEY, TurnDestination.LIVE_LOCATE, TurnDestination.EVALUATE)) {
        com.turn.fieldtest.ui.screens.ProjectsScreen(state, actions)
        return
    }
    when (state.destination) {
        TurnDestination.VENUES -> if (state.mode == DataMode.REAL_DEVICE) com.turn.fieldtest.ui.screens.ProjectsScreen(state, actions) else VenuesScreen(state, compact)
        TurnDestination.FLOOR_EDITOR -> if (state.mode == DataMode.REAL_DEVICE) com.turn.fieldtest.ui.screens.MapSetupScreen(state, actions) else FloorEditorScreen(
            state,
            compact,
            onSave = actions.onMapSaved,
            onImportImage = actions.onFloorPlanImportRequested,
            onDeleteSavedMap = actions.onMapDeleted,
        )
        TurnDestination.RADIO_DIAGNOSTICS -> DiagnosticsScreen(
            state,
            compact,
            onRequestScan = actions.onDiagnosticScanRequested,
            onToggleDiagnosticWalk = actions.onDiagnosticWalkToggled,
        )
        TurnDestination.SURVEY -> if (state.mode == DataMode.REAL_DEVICE) com.turn.fieldtest.ui.screens.FieldSurveyScreen(state, actions) else SurveyScreen(
            state,
            compact,
            onStartRealSurvey = actions.onRealSurveyStarted,
            onFinishRealSurvey = actions.onRealSurveyFinished,
        )
        TurnDestination.LIVE_LOCATE -> if (state.mode == DataMode.REAL_DEVICE) com.turn.fieldtest.ui.screens.CanvasNavigationScreen(state, actions) else LiveLocateScreen(
            state,
            compact,
            onRealLiveToggle = actions.onRealLiveToggled,
            onRealScanRequested = actions.onRealLiveScanRequested,
            onRealRelocalizationRequested = actions.onRealLiveRelocalizationRequested,
        )
        TurnDestination.EVALUATE -> EvaluateScreen(state, compact, actions.onCheckpointCaptured)
        TurnDestination.DATA_EXPORT -> DataExportScreen(state, compact, actions.onRealExport)
        TurnDestination.SETTINGS -> SettingsScreen(state, compact, actions.onModeChanged)
    }
}


@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun TurnPhonePreview() { TurnApp() }
