package com.turn.fieldtest

import android.os.Bundle
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.turn.fieldtest.ui.TurnApp
import com.turn.fieldtest.ui.TurnAppActions
import com.turn.fieldtest.ui.TurnRuntimeViewModel
import com.turn.fieldtest.ui.model.DataMode

class MainActivity : ComponentActivity() {
    private val runtimeViewModel by viewModels<TurnRuntimeViewModel>()
    private var afterWifiPermission: (() -> Unit)? = null
    private var afterMotionPermission: (() -> Unit)? = null
    private val floorPlanLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) {
            runtimeViewModel.onFloorPlanImportCancelled()
            return@registerForActivityResult
        }
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runtimeViewModel.importFloorPlan(uri)
    }
    private val motionPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        val pending = afterMotionPermission
        afterMotionPermission = null
        runtimeViewModel.refreshMotionSources()
        if (runtimeViewModel.missingMotionPermissions().isEmpty()) pending?.invoke()
        else runtimeViewModel.onMotionPermissionDenied()
    }

    private val wifiPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        runtimeViewModel.refreshWifiPermissionStatus()
        val pending = afterWifiPermission
        afterWifiPermission = null
        if (runtimeViewModel.missingWifiPermissions().isEmpty()) pending?.invoke()
        else runtimeViewModel.onWifiPermissionDenied()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TurnApp(
                state = runtimeViewModel.appState,
                actions = TurnAppActions(
                    onModeChanged = { mode ->
                        runtimeViewModel.setMode(mode)
                        if (mode == DataMode.REAL_DEVICE) {
                            withWifiPermissions { runtimeViewModel.refreshWifiPermissionStatus() }
                        }
                    },
                    onDiagnosticScanRequested = {
                        withWifiPermissions(runtimeViewModel::requestDiagnosticScan)
                    },
                    onDiagnosticWalkToggled = {
                        withMotionPermissions(runtimeViewModel::toggleDiagnosticWalk)
                    },
                    onRealSurveyStarted = { metadata ->
                        withWifiPermissions { runtimeViewModel.beginRealSurvey(metadata) }
                    },
                    onRealSurveyFinished = runtimeViewModel::finishRealSurvey,
                    onRealLiveToggled = {
                        withWifiPermissions {
                            withMotionPermissions(runtimeViewModel::toggleRealLive)
                        }
                    },
                    onRealLiveScanRequested = {
                        withWifiPermissions(runtimeViewModel::requestLiveScan)
                    },
                    onRealLiveRelocalizationRequested = runtimeViewModel::relocalizeWithNextWifi,
                    onRealExport = runtimeViewModel::exportRealData,
                    onMapSaved = runtimeViewModel::savePilotMap,
                    onMapDeleted = runtimeViewModel::deleteSavedMap,
                    onFloorPlanImportRequested = {
                        floorPlanLauncher.launch(arrayOf("image/png", "image/jpeg"))
                    },
                    onCheckpointCaptured = runtimeViewModel::captureCheckpoint,
                ),
            )
        }
    }

    override fun onStart() {
        super.onStart()
        runtimeViewModel.onForeground()
    }

    override fun onStop() {
        runtimeViewModel.onBackground()
        super.onStop()
    }

    private fun withWifiPermissions(action: () -> Unit) {
        val missing = runtimeViewModel.missingWifiPermissions()
        if (missing.isEmpty()) {
            runtimeViewModel.refreshWifiPermissionStatus()
            action()
            return
        }
        afterWifiPermission = action
        wifiPermissionLauncher.launch(missing)
    }

    private fun withMotionPermissions(action: () -> Unit) {
        val missing = runtimeViewModel.missingMotionPermissions()
        if (missing.isEmpty()) {
            runtimeViewModel.refreshMotionSources()
            action()
        } else {
            afterMotionPermission = action
            motionPermissionLauncher.launch(missing)
        }
    }
}
