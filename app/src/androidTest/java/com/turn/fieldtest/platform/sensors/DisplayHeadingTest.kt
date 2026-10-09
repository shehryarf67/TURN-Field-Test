package com.turn.fieldtest.platform.sensors

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.turn.fieldtest.core.normalizeRadians
import com.turn.fieldtest.core.*
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

    @Test fun leftAlignedHallwayMovementAndRightTurnsWorkInAllDisplayRotations() {
        for (rotation in 0..3) {
            val baseline = DisplayHeading.azimuth(clockwiseVector(-rotation * 90.0), rotation)
            val straight = MapHeading.fromRelativeAzimuth(Math.PI, baseline, baseline)
            val pdr = PdrTracker(MetricPoint(18.0, 5.0), straight,
                StepProcessor(setOf(StepSource.STEP_DETECTOR)), StrideModel(0.7))
            val first = pdr.processStep(StepSignal(1_000_000_000, StepSource.STEP_DETECTOR), straight)
            assertEquals(17.3, first.stateAfter.position.x, 1e-6)
            assertEquals(5.0, first.stateAfter.position.y, 1e-6)
            val clockwiseTurn = DisplayHeading.azimuth(clockwiseVector(-rotation * 90.0 + 90), rotation)
            val second = pdr.processStep(StepSignal(2_000_000_000, StepSource.STEP_DETECTOR),
                MapHeading.fromRelativeAzimuth(Math.PI, baseline, clockwiseTurn))
            assertEquals(17.3, second.stateAfter.position.x, 1e-6)
            assertEquals(5.7, second.stateAfter.position.y, 1e-6)
        }
    }
}
