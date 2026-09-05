package com.turn.fieldtest.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FieldEvaluationTest {
    @Test
    fun horizontalErrorAndFloorAreIndependent() {
        val result = evaluateAtCheckpoint(MetricPoint(0.0, 0.0), "G", EvaluationEstimate(3.0, 4.0, "L1", 6.0))
        assertEquals(5.0, result.horizontalMetres)
        assertFalse(result.floorCorrect!!)
        assertTrue(result.insideConfidence!!)
    }

    @Test
    fun missingEstimateIsFailureAndNeverZeroError() {
        val missing = evaluateAtCheckpoint(MetricPoint(0.0, 0.0), "G", null)
        assertNull(missing.horizontalMetres)
        assertNull(missing.floorCorrect)
        val stats = summarizeErrors(listOf(missing, MethodError(2.0, true, true)))
        assertEquals(50.0, stats.failurePercent)
        assertEquals(50.0, stats.within3Percent)
        assertEquals(50.0, stats.floorAccuracyPercent)
        assertEquals(2.0, stats.mean)
    }

    @Test
    fun percentilesAndEmptyRunsAreDefined() {
        val stats = summarizeErrors(listOf(1.0, 2.0, 3.0, 20.0).map { MethodError(it, true, null) })
        assertEquals(2.5, stats.median)
        assertEquals(20.0, stats.p90)
        assertNull(summarizeErrors(emptyList()).mean)
        assertEquals(0, summarizeErrors(emptyList()).sampleCount)
    }
}
