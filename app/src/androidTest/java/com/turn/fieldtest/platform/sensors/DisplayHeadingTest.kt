package com.turn.fieldtest.platform.sensors

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.turn.fieldtest.core.normalizeRadians
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.cos
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class DisplayHeadingTest {
    private fun clockwiseVector(degrees: Double): FloatArray {
        val half = Math.toRadians(degrees) / 2
        return floatArrayOf(0f, 0f, -sin(half).toFloat(), cos(half).toFloat())
    }
    @Test fun portraitLandscapeAndReverseRotationsKeepSameForwardHeading() {
        for (rotation in 0..3) {
            // Android ROTATION_90 is caused by a counter-clockwise physical device turn.
            val yaw = DisplayHeading.azimuth(clockwiseVector(-rotation * 90.0), rotation)
            assertEquals("display rotation $rotation", 0.0, normalizeRadians(yaw), 1e-6)
        }
    }
    @Test fun actualWalkingTurnsRemainVisibleInEveryDisplayOrientation() {
        for (rotation in 0..3) {
            val yaw = DisplayHeading.azimuth(clockwiseVector(-rotation * 90.0 + 35), rotation)
            assertEquals("display rotation $rotation", Math.toRadians(35.0), normalizeRadians(yaw), 1e-6)
        }
    }
}
