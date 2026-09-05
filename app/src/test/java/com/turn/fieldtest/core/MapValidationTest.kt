package com.turn.fieldtest.core

import kotlin.test.Test
import kotlin.test.assertFailsWith

class MapValidationTest {
    @Test
    fun acceptsConcaveCorridor() {
        validateSimpleWalkablePolygon(MetricPolygon(listOf(MetricPoint(0.0,0.0), MetricPoint(6.0,0.0),
            MetricPoint(6.0,2.0), MetricPoint(2.0,2.0), MetricPoint(2.0,6.0), MetricPoint(0.0,6.0))))
    }

    @Test
    fun rejectsCrossingWithNonZeroArea() {
        assertFailsWith<IllegalArgumentException> {
            validateSimpleWalkablePolygon(MetricPolygon(listOf(MetricPoint(0.0,0.0), MetricPoint(6.0,5.0),
                MetricPoint(0.0,4.0), MetricPoint(4.0,0.0))))
        }
    }
}
