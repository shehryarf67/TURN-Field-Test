package com.turn.fieldtest.core

import kotlin.math.PI
import kotlin.test.*

class MapHeadingTest {
    @Test fun gyroCannotExtrapolateThroughMissingOrOutOfOrderSamples() {
        assertEquals(0.02, MapHeading.gyroIntervalSeconds(1_000_000_000, 1_020_000_000)!!, 1e-10)
        assertNull(MapHeading.gyroIntervalSeconds(1_000_000_000, 4_000_000_000))
        assertNull(MapHeading.gyroIntervalSeconds(1_000_000_000, 1_000_000_000))
        assertNull(MapHeading.gyroIntervalSeconds(1_000_000_000, 900_000_000))
    }
    @Test fun mapPointsGiveRightUpLeftDownAndRejectTinyBaselines() {
        val start = MetricPoint(10.0, 10.0)
        assertEquals(0.0, MapHeading.toward(start, MetricPoint(15.0, 10.0)), 1e-10)
        assertEquals(PI / 2, MapHeading.toward(start, MetricPoint(10.0, 15.0)), 1e-10)
        assertEquals(PI, MapHeading.toward(start, MetricPoint(5.0, 10.0)), 1e-10)
        assertEquals(-PI / 2, MapHeading.toward(start, MetricPoint(10.0, 5.0)), 1e-10)
        assertFailsWith<IllegalArgumentException> { MapHeading.toward(start, start) }
    }

    @Test fun leftAlignedHallwayStepDecreasesXAndRealignmentDoesNotMovePosition() {
        val pdr = PdrTracker(MetricPoint(18.0, 5.0), 0.0,
            StepProcessor(setOf(StepSource.STEP_DETECTOR)), StrideModel(0.7))
        pdr.setHeading(PI)
        assertEquals(MetricPoint(18.0, 5.0), pdr.state.position)
        val sensorAnchor = 0.8
        val step = pdr.processStep(StepSignal(1_000_000_000, StepSource.STEP_DETECTOR),
            MapHeading.fromRelativeAzimuth(PI, sensorAnchor, sensorAnchor))
        assertEquals(17.3, step.stateAfter.position.x, 1e-10)
        assertEquals(5.0, step.stateAfter.position.y, 1e-10)
        assertEquals(1, step.stateAfter.acceptedStepCount)
        pdr.setHeading(0.0)
        assertEquals(step.stateAfter.position, pdr.state.position)
        assertEquals(1, pdr.state.acceptedStepCount)
    }

    @Test fun clockwiseAndCounterclockwiseSensorTurnsUseMapSigns() {
        assertEquals(PI / 2, MapHeading.fromRelativeAzimuth(PI, 0.5, 0.5 + PI / 2), 1e-10)
        assertEquals(-PI / 2, MapHeading.fromRelativeAzimuth(PI, 0.5, 0.5 - PI / 2), 1e-10)
    }

    @Test fun azimuthWrapDoesNotReverseWalkingDirection() {
        assertEquals(PI - 0.02, MapHeading.fromRelativeAzimuth(PI, PI - 0.01, -PI + 0.01), 1e-10)
    }

    @Test fun stepsWaitForPostAlignmentHeadingAndCannotUseStaleSamples() {
        assertFalse(MapHeading.hasFreshHeading(5_000_000_000, null, 4_000_000_000))
        assertFalse(MapHeading.hasFreshHeading(5_000_000_000, 3_999_000_000, 4_000_000_000))
        assertFalse(MapHeading.hasFreshHeading(3_999_000_000, 4_100_000_000, 4_000_000_000))
        assertFalse(MapHeading.hasFreshHeading(8_000_000_000, 5_000_000_000, 4_000_000_000))
        assertTrue(MapHeading.hasFreshHeading(5_000_000_000, 4_950_000_000, 4_000_000_000))
        assertTrue(MapHeading.hasFreshHeading(5_000_000_000, 5_050_000_000, 4_000_000_000))
    }
}
