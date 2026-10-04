package com.turn.fieldtest.platform.sensors

import com.turn.fieldtest.core.normalizeRadians
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.math.PI

class DisplayHeadingDeltaTest {
    @Test fun displayCompensationCancelsGripRotationForGyroscopeHeading() {
        // ROTATION_90 follows a CCW grip turn: natural-axis PDR gains 90 degrees, which remapping cancels.
        val initial = 0.4
        val landscape = normalizeRadians(initial + PI / 2 + DisplayHeading.rotationDeltaRadians(0, 1))
        assertEquals(initial, landscape, 1e-9)
        val portrait = normalizeRadians(landscape - PI / 2 + DisplayHeading.rotationDeltaRadians(1, 0))
        assertEquals(initial, portrait, 1e-9)
        assertEquals(-PI / 2, DisplayHeading.rotationDeltaRadians(3, 0), 1e-9)
        assertEquals(0.0, DisplayHeading.rotationDeltaRadians(2, 2), 1e-9)
    }
}
