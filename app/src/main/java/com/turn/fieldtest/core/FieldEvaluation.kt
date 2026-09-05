package com.turn.fieldtest.core

import kotlinx.serialization.Serializable
import kotlin.math.ceil

@Serializable
data class EvaluationEstimate(val x: Double, val y: Double, val floor: String, val confidenceRadius: Double? = null)

data class MethodError(val horizontalMetres: Double?, val floorCorrect: Boolean?, val insideConfidence: Boolean?)

/** Ground truth is an input only to evaluation; this code has no positioning dependencies. */
fun evaluateAtCheckpoint(truth: MetricPoint, trueFloor: String, estimate: EvaluationEstimate?): MethodError {
    if (estimate == null) return MethodError(null, null, null)
    val error = truth.distanceTo(MetricPoint(estimate.x, estimate.y))
    return MethodError(error, trueFloor == estimate.floor,
        estimate.confidenceRadius?.takeIf { it.isFinite() && it >= 0 }?.let { error <= it })
}

data class ErrorStatistics(
    val sampleCount: Int, val estimateCount: Int, val mean: Double?, val median: Double?,
    val p90: Double?, val maximum: Double?, val within3Percent: Double,
    val within5Percent: Double, val failurePercent: Double, val floorAccuracyPercent: Double,
)

fun summarizeErrors(values: List<MethodError>): ErrorStatistics {
    val errors = values.mapNotNull { it.horizontalMetres }.sorted()
    fun percent(count: Int) = if (values.isEmpty()) 0.0 else 100.0 * count / values.size
    val median = errors.takeIf { it.isNotEmpty() }?.let {
        if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2]) / 2.0
    }
    return ErrorStatistics(values.size, errors.size, errors.takeIf { it.isNotEmpty() }?.average(), median,
        errors.takeIf { it.isNotEmpty() }?.let { it[(ceil(it.size * 0.9).toInt() - 1).coerceAtLeast(0)] },
        errors.lastOrNull(), percent(errors.count { it <= 3 }), percent(errors.count { it <= 5 }),
        percent(values.size - errors.size), percent(values.count { it.floorCorrect == true }))
}
