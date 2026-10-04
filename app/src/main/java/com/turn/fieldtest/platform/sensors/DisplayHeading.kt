package com.turn.fieldtest.platform.sensors

import android.hardware.SensorManager
import android.view.Surface
import kotlin.math.PI

/** Heading of the visible screen's top edge projected onto the horizontal plane. */
object DisplayHeading {
    fun azimuth(rotationVector: FloatArray, displayRotation: Int): Double {
        val matrix = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(matrix, rotationVector)
        val (x, y) = when (displayRotation) {
            Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
            else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
        }
        val screenMatrix = FloatArray(9)
        check(SensorManager.remapCoordinateSystem(matrix, x, y, screenMatrix))
        return SensorManager.getOrientation(screenMatrix, FloatArray(3))[0].toDouble()
    }

    /** Display turns offset the natural-axis gyroscope heading, including reverse landscape. */
    fun rotationDeltaRadians(previous: Int, current: Int): Double {
        require(previous in 0..3 && current in 0..3)
        val quarters = ((current - previous + 6) % 4) - 2
        // Display rotation describes rendering compensation: ROTATION_90 means the device turned CCW.
        // Natural-axis gyro yaw already includes that positive turn, so subtract the display angle.
        return -quarters * PI / 2.0
    }
}
