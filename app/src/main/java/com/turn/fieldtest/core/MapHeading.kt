package com.turn.fieldtest.core

import kotlin.math.atan2

/** TURN coordinates have +x right and +y up; compass north is unrelated to an imported drawing. */
object MapHeading {
    fun toward(from: MetricPoint, ahead: MetricPoint): Double {
        require(from.distanceTo(ahead) >= 1.0) { "Choose a point at least 1 metre ahead" }
        return normalizeRadians(atan2(ahead.y - from.y, ahead.x - from.x))
    }

    /** Android azimuth grows clockwise; map heading grows counter-clockwise. */
    fun fromRelativeAzimuth(mapAnchor: Double, sensorAnchor: Double, sensorNow: Double): Double =
        normalizeRadians(mapAnchor - normalizeRadians(sensorNow - sensorAnchor))

    /** Different sensor queues may deliver nearby timestamps in either order. */
    fun hasFreshHeading(stepNanos: Long, headingNanos: Long?, alignmentNanos: Long): Boolean =
        headingNanos != null && stepNanos >= alignmentNanos && headingNanos >= alignmentNanos &&
            stepNanos - headingNanos in -200_000_000L..2_000_000_000L
}
