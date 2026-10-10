package com.turn.fieldtest.ui

import androidx.compose.ui.geometry.Offset
import com.turn.fieldtest.ui.model.DataMode
import com.turn.fieldtest.ui.screens.navigationConfidence
import kotlin.test.*

class LiveReadinessTest {
    @Test fun changingStartInvalidatesDirectionAndCancelsBothTapTools() {
        val state = TurnAppState().apply {
            manualStartPoint = Offset(10f, 10f); headingChoiceConfirmed = true
            pickingHeading = true; pickingStart = true
        }
        state.chooseKnownStart(Offset(2f, 3f))
        assertFalse(state.headingChoiceConfirmed)
        assertFalse(state.pickingHeading); assertFalse(state.pickingStart)
        assertEquals(Offset(2f, 3f), state.manualStartPoint)
        state.headingChoiceConfirmed = true
        state.chooseKnownStart(null)
        assertFalse(state.headingChoiceConfirmed)
    }

    @Test fun startCannotChangeDuringPhysicalSession() {
        val state = TurnAppState().apply {
            manualStartPoint = Offset(1f, 1f); headingChoiceConfirmed = true; sessionLocked = true
        }
        state.chooseKnownStart(Offset(9f, 9f))
        assertEquals(Offset(1f, 1f), state.manualStartPoint)
        assertTrue(state.headingChoiceConfirmed)
    }

    @Test fun recalibratingFloorClearsAnOldStartingCoordinate() {
        val state = TurnAppState().apply {
            mode = DataMode.REAL_DEVICE; realMapReady = true
            manualStartPoint = Offset(10f, 10f); headingChoiceConfirmed = true
        }
        assertTrue(state.resizeFloor(20f, 20f))
        assertNull(state.manualStartPoint); assertFalse(state.headingChoiceConfirmed)
    }

    @Test fun activeOperationsKeepPhysicalScreenAwakeAndLockAlgorithmSettings() {
        val state = TurnAppState().apply { mode = DataMode.REAL_DEVICE }
        assertFalse(state.keepScreenAwake); assertTrue(state.canChangeAlgorithmSettings)
        state.surveyStarting = true
        assertTrue(state.keepScreenAwake); assertFalse(state.canChangeAlgorithmSettings)
        state.surveyStarting = false; state.liveRunning = true
        assertTrue(state.keepScreenAwake); assertFalse(state.canChangeAlgorithmSettings)
        state.liveRunning = false; state.sessionLocked = true
        assertFalse(state.keepScreenAwake); assertFalse(state.canChangeAlgorithmSettings)
    }

    @Test fun freshWifiPositionCannotHideMissingHeadingInTrackingStatus() {
        val state = TurnAppState().apply {
            liveRunning = true; realLiveInitialized = true; realLiveConfidence = 0.9
        }
        assertEquals("Heading unavailable · movement paused", navigationConfidence(state, 0, 10_000))
        state.realHeadingAligned = true; state.realHeadingSampleEpochMillis = 6_000
        assertEquals("Heading stale · movement paused", navigationConfidence(state, 0, 10_000))
        state.realHeadingSampleEpochMillis = 9_900
        assertEquals("Tracking", navigationConfidence(state, 0, 10_000))
    }
}
