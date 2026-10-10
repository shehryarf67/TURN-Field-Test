package com.turn.fieldtest.ui

import android.content.res.Configuration
import android.view.WindowManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.turn.fieldtest.MainActivity
import com.turn.fieldtest.ui.model.DataMode
import com.turn.fieldtest.ui.model.TurnDestination
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Tests lifecycle/display behavior with UI fixtures; no synthetic evidence enters the physical estimator. */
@RunWith(AndroidJUnit4::class)
class NavigationRotationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun physicalCollectionKeepsScreenOnAndReleasesItWhenStopped() {
        val runtime = ViewModelProvider(compose.activity)[TurnRuntimeViewModel::class.java]
        val state = runtime.appState
        compose.waitUntil(15_000) { !state.startupLoading }
        val originalMode = state.mode
        try {
            compose.runOnIdle { state.mode = DataMode.REAL_DEVICE; state.surveyRunning = true }
            compose.runOnIdle { assertTrue(compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0) }
            compose.runOnIdle { state.surveyRunning = false }
            compose.runOnIdle { assertEquals(0, compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        } finally {
            compose.runOnIdle { state.surveyRunning = false; state.mode = originalMode }
        }
    }

    @Test fun fullscreenRotatesAndBackRestoresUiWithoutStoppingOrRecreatingActivity() {
        val activity = compose.activity
        val runtime = ViewModelProvider(activity)[TurnRuntimeViewModel::class.java]
        val state = runtime.appState
        compose.waitUntil(15_000) { !state.startupLoading }
        val originalMode = state.mode
        val originalDestination = state.destination
        val originalMapReady = state.realMapReady
        val originalPosition = state.realLivePosition
        val originalRoute = state.routePoints
        val originalInitialized = state.realLiveInitialized
        var stops = 0
        val originalOrientation = activity.requestedOrientation
        val point = Offset(8f, 4f)
        val route = listOf(point, Offset(15f, 4f))
        val observer = object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) { stops++ }
        }
        try {
            compose.runOnIdle {
                activity.lifecycle.addObserver(observer)
                state.mode = DataMode.REAL_DEVICE
                state.realMapReady = true
                state.destination = TurnDestination.LIVE_LOCATE
                state.liveRunning = true; state.sessionLocked = true
                state.realLiveInitialized = true; state.realLivePosition = point
                state.routePoints = route
            }
            compose.onNodeWithContentDescription("Full screen map").performClick()
            compose.onNodeWithText("Exit full screen").assertIsDisplayed()
            compose.onNodeWithText("Projects").assertDoesNotExist()
            compose.waitUntil(5_000) {
                ViewCompat.getRootWindowInsets(activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.statusBars()) == false
            }
            compose.onNodeWithText("Landscape").performClick()
            compose.waitUntil(15_000) { activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
            compose.runOnIdle {
                assertSame(activity, compose.activity)
                assertSame(runtime, ViewModelProvider(compose.activity)[TurnRuntimeViewModel::class.java])
                assertEquals(0, stops)
                assertTrue(state.liveRunning); assertTrue(state.sessionLocked)
                assertEquals(point, state.realLivePosition); assertEquals(route, state.routePoints)
            }
            compose.onNodeWithText("Portrait").performClick()
            compose.waitUntil(15_000) { activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
            compose.runOnIdle { activity.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithContentDescription("Full screen map").assertIsDisplayed()
            compose.runOnIdle {
                assertFalse(state.navigationFullscreen)
                assertEquals(originalOrientation, activity.requestedOrientation)
                assertEquals(0, stops); assertTrue(state.liveRunning)
                assertEquals(point, state.realLivePosition); assertEquals(route, state.routePoints)
            }
        } finally {
            compose.runOnIdle {
                state.navigationFullscreen = false; state.liveRunning = false; state.sessionLocked = false
                state.mode = originalMode; state.destination = originalDestination
                state.realMapReady = originalMapReady; state.realLiveInitialized = originalInitialized
                state.realLivePosition = originalPosition; state.routePoints = originalRoute
                activity.lifecycle.removeObserver(observer)
            }
        }
    }
}
