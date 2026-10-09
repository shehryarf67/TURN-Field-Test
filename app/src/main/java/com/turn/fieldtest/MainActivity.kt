package com.turn.fieldtest

import android.os.Bundle
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.DisposableEffect
import android.content.pm.ActivityInfo
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.turn.fieldtest.ui.NavigationViewOrientation
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
            val dark = runtimeViewModel.appState.darkTheme
            val fullscreen = runtimeViewModel.appState.isNavigationFullscreen
            val orientation = runtimeViewModel.appState.navigationViewOrientation
            DisposableEffect(fullscreen) {
                val previousOrientation = requestedOrientation
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                val previousBehavior = controller.systemBarsBehavior
                if (fullscreen) {
                    controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    controller.hide(WindowInsetsCompat.Type.systemBars())
                }
                onDispose {
                    if (fullscreen) {
                        requestedOrientation = previousOrientation
                        controller.systemBarsBehavior = previousBehavior
                        controller.show(WindowInsetsCompat.Type.systemBars())
                    }
                }
            }
            SideEffect {
                val barStyle = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = barStyle, navigationBarStyle = barStyle)
                if (fullscreen) {
                    requestedOrientation = when (orientation) {
                        NavigationViewOrientation.AUTO -> ActivityInfo.SCREEN_ORIENTATION_USER
                        NavigationViewOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
                        NavigationViewOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
                    }
                    WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
                }
            }
            TurnApp(
                state = runtimeViewModel.appState,
                actions = TurnAppActions(
                    onModeChanged = { mode ->
                        runtimeViewModel.setMode(mode)
                        if (mode == DataMode.REAL_DEVICE && runtimeViewModel.appState.mode == mode) {
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
                    onRealHeadingAligned = runtimeViewModel::alignLiveHeading,
                    onRealLiveScanRequested = {
                        withWifiPermissions(runtimeViewModel::requestLiveScan)
                    },
                    onRealLiveRelocalizationRequested = runtimeViewModel::relocalizeWithNextWifi,
                    onRealExport = runtimeViewModel::exportRealData,
                    onMapSaved = runtimeViewModel::savePilotMap,
                    onDestinationSelected = runtimeViewModel::selectNavigationDestination,
                    onProjectCreated = runtimeViewModel::createProject,
                    onProjectOpened = runtimeViewModel::openProject,
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
