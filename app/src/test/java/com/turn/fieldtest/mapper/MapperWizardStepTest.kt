package com.turn.fieldtest.mapper

import com.turn.fieldtest.mapper.ui.MapperStage
import com.turn.fieldtest.mapper.ui.wizardStep
import kotlin.test.Test
import kotlin.test.assertEquals

class MapperWizardStepTest {
    private fun capture(cameraOn: Boolean = true, started: Boolean = false, originSet: Boolean = false,
        floorPending: Boolean = false, hasPoints: Boolean = false, measured: Boolean = false) =
        wizardStep(MapperStage.CAPTURE, cameraOn, started, originSet, floorPending, hasPoints, measured)

    @Test fun cameraOffStartsAtStepOneUntilThereIsSomethingToContinue() {
        assertEquals(1, capture(cameraOn = false))
        assertEquals(3, capture(cameraOn = false, hasPoints = true))
        assertEquals(3, capture(cameraOn = false, measured = true))
    }

    @Test fun startingPointThenDirectionThenCorners() {
        assertEquals(1, capture())
        assertEquals(1, capture(floorPending = true)) // "Is this the floor?" is still step 1
        assertEquals(1, capture(originSet = true, floorPending = true))
        assertEquals(2, capture(originSet = true))
        assertEquals(3, capture(originSet = true, started = true))
    }

    @Test fun aLinedUpSessionStaysOnStepThreeWhenTrackingIsLost() {
        // The wizard decides step 3 from the session existing, not from it still tracking (marking is disabled instead),
        // so losing tracking mid-room never drops back to step 2 and keeps Pause, Undo, Next room and Finish floor visible.
        assertEquals(3, capture(started = true))
        assertEquals(3, capture(started = true, originSet = false, hasPoints = true))
    }

    @Test fun laterStagesAreFixedSteps() {
        assertEquals(4, wizardStep(MapperStage.REVIEW, true, true, true, false, true, false))
        assertEquals(5, wizardStep(MapperStage.MEASURE, false, false, false, false, true, false))
        assertEquals(6, wizardStep(MapperStage.SAVE, false, false, false, false, true, false))
    }
}
