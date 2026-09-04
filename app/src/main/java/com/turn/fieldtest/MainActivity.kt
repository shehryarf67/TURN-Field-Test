package com.turn.fieldtest

import android.os.Bundle
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
                    onDiagnosticWalkToggled = runtimeViewModel::toggleDiagnosticWalk,
                    onRealSurveyStarted = { metadata ->
                        withWifiPermissions { runtimeViewModel.beginRealSurvey(metadata) }
                    },
                    onRealSurveyFinished = runtimeViewModel::finishRealSurvey,
                    onRealLiveToggled = {
                        withWifiPermissions(runtimeViewModel::toggleRealLive)
                    },
                    onRealLiveScanRequested = {
                        withWifiPermissions(runtimeViewModel::requestLiveScan)
                    },
                    onRealLiveRelocalizationRequested = runtimeViewModel::relocalizeWithNextWifi,
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
        afterWifiPermission = null
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
}
