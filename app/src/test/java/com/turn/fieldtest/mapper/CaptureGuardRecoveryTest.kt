package com.turn.fieldtest.mapper

import com.turn.fieldtest.mapper.core.CaptureGuard
import com.turn.fieldtest.mapper.core.CaptureGuard.State
import com.turn.fieldtest.mapper.core.V3
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class CaptureGuardRecoveryTest {
    private fun pose(x: Double = 0.0, yaw: Double = 0.0): CaptureGuard.CameraPose {
        val half = Math.toRadians(yaw) / 2
        return CaptureGuard.CameraPose(V3(x, 0.0, 0.0), floatArrayOf(0f, sin(half).toFloat(), 0f, cos(half).toFloat()))
    }

    @Test fun shortDropoutResumesInSameSection() {
        val guard = CaptureGuard()
        assertEquals(State.TRACKING, guard.observe(1, true, true, 0, pose()))
        assertEquals(State.RECOVERING, guard.observe(10, false, true, 0))
        assertEquals(State.STALE, guard.observe(10, true, true, 0, pose(9.0)))
        val recovered = pose(0.3, 20.0)
        val equivalentRotation = recovered.copy(quaternion = recovered.quaternion.map { -it }.toFloatArray())
        assertEquals(State.TRACKING, guard.observe(300_000_000, true, true, 0, equivalentRotation))
    }

    @Test fun firstRecoveryFrameAfterDeadlineCannotReopenSection() {
        val guard = CaptureGuard(graceNanos = 100)
        guard.observe(1, true, true, 0, pose())
        assertEquals(State.RECOVERING, guard.observe(2, false, true, 0))
        assertEquals(State.LOST, guard.observe(102, true, true, 0, pose()))
        assertEquals(State.LOST, guard.observe(103, true, true, 0, pose()))
    }

    @Test fun translationOrRotationJumpOnRecoveryClosesSection() {
        for (jump in listOf(pose(1.01), pose(yaw = 31.0))) {
            val guard = CaptureGuard()
            guard.observe(1, true, true, 0, pose())
            guard.observe(2, false, true, 0)
            assertEquals(State.LOST, guard.observe(3, true, true, 0, jump))
        }
    }

    @Test fun thermalGateStillStopsMarkingBeforeAndAfterRecovery() {
        val guard = CaptureGuard()
        assertEquals(State.HOT, guard.observe(1, true, true, 4, pose()))
        assertEquals(State.RECOVERING, guard.observe(2, false, true, 4))
        assertEquals(State.HOT, guard.observe(3, true, true, 4, pose()))
        assertEquals(State.TRACKING, guard.observe(4, true, true, 2, pose()))
    }
}
