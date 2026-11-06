package com.turn.fieldtest.ui

import androidx.compose.ui.geometry.Offset
import com.turn.fieldtest.ui.screens.nextInstruction
import kotlin.test.*

class RouteInstructionTest {
    @Test fun straightRouteEndsAtDestination() {
        val step = assertNotNull(nextInstruction(listOf(Offset(0f, 0f), Offset(10f, 0f)), "Lab 1"))
        assertEquals(0.0, step.mapHeadingDegrees, 1e-6)
        assertEquals(10.0, step.distanceMetres, 1e-6)
        assertEquals("then arrive at Lab 1", step.then)
    }

    @Test fun nearlyStraightLegsMergeBeforeTheTurn() {
        // +x then a slight bend, then +y: with y up, turning from +x to +y is a left turn.
        val route = listOf(Offset(0f, 0f), Offset(4f, 0f), Offset(9f, 0.5f), Offset(9f, 6f))
        val step = assertNotNull(nextInstruction(route, "Lab 1"))
        assertEquals(9.0, step.distanceMetres, 0.05)
        assertEquals("then turn left", step.then)
    }

    @Test fun turnTowardsNegativeYIsRight() {
        val step = assertNotNull(nextInstruction(listOf(Offset(0f, 0f), Offset(5f, 0f), Offset(5f, -5f)), "Lab 1"))
        assertEquals("then turn right", step.then)
    }

    @Test fun routeWithoutMovementHasNoInstruction() {
        assertNull(nextInstruction(listOf(Offset(2f, 2f), Offset(2.1f, 2f)), "Lab 1"))
    }
}
