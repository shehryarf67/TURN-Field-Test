package com.turn.fieldtest.mapper.core

import kotlin.math.abs
import kotlin.math.sqrt

/** Saved in the scan's source fields, independently of whether a corner was projected or inferred. */
internal enum class HitSource(val savedName: String, val approximate: Boolean = false) {
    PLANE("AR_PLANE"),
    PLANE_MARGIN("AR_PLANE_MARGIN", true),
    DEPTH("DEPTH_HIT"),
    FEATURE("AR_FEATURE_POINT", true),
    EXTENDED_PLANE("AR_EXTENDED_PLANE", true);

    companion object {
        fun isApproximate(source: String) = entries.any { it.approximate && it.savedName == source }
    }
}

internal object SurfaceHits {
    private val distanceRange = 0.2..8.0
    const val PLANE_MARGIN = 0.3

    enum class Surface { VERTICAL, HORIZONTAL, UNKNOWN }
    data class Candidate<out T>(
        val point: V3, val distance: Double, val source: HitSource, val nativeHit: T,
        val surface: Surface = Surface.UNKNOWN,
    )
    enum class Failure { NO_SURFACE, NO_WALL_FACE, NO_ELEVATED_SURFACE, TOO_FAR, TOO_CLOSE, TOO_HIGH, BELOW_FLOOR }
    data class Selection<T>(val hit: Candidate<T>? = null, val failure: Failure = Failure.NO_SURFACE)

    fun <T> select(
        candidates: List<Candidate<T>>, floorY: Double, tool: String,
        fallback: () -> List<Candidate<T>> = { emptyList() },
    ): Selection<T> {
        fun valid(hit: Candidate<T>) = hit.point.finite && hit.distance in distanceRange &&
            HiddenCorners.acceptsHitHeight(hit.point.y - floorY, tool)

        fun matchesTool(hit: Candidate<T>) = when (tool) {
            "WALL_CORNER" -> hit.surface == Surface.VERTICAL
            "ABOVE_CORNER" -> hit.point.y - floorY > HiddenCorners.ELEVATED_HIT_METRES
            else -> true
        }
        val order = compareBy<Candidate<T>> { it.source }.thenBy { it.distance }
        val direct = candidates.filter(::valid)
        val nearest = direct.minOfOrNull { it.distance }
        // Rank sources only within the foreground surface. A farther plane can be behind the wall under the tap.
        val foreground = direct.filter { nearest != null && it.distance - nearest <= 0.25 }
        foreground.filter(::matchesTool).minWithOrNull(order)?.let { return Selection(hit = it) }
        val extended = fallback()
        extended.filter { valid(it) && matchesTool(it) && (nearest == null || it.distance - nearest <= 0.25) }
            .minByOrNull { it.distance }?.let { return Selection(hit = it) }

        val rejected = (candidates + extended).filter { matchesTool(it) && it.point.finite && it.distance.isFinite() }
            .minByOrNull { it.distance }
        if (rejected == null || valid(rejected)) {
            return Selection(failure = when (tool) {
                "WALL_CORNER" -> Failure.NO_WALL_FACE
                "ABOVE_CORNER" -> Failure.NO_ELEVATED_SURFACE
                else -> Failure.NO_SURFACE
            })
        }
        return Selection(failure = when {
            rejected.distance > distanceRange.endInclusive -> Failure.TOO_FAR
            rejected.distance < distanceRange.start -> Failure.TOO_CLOSE
            rejected.point.y - floorY < HiddenCorners.MIN_HIT_HEIGHT_METRES -> Failure.BELOW_FLOOR
            else -> Failure.TOO_HIGH
        })
    }

    /** Distance to the nearest polygon edge, using coordinates local to the plane. */
    fun withinBoundaryMargin(point: P2, polygon: List<P2>, margin: Double = PLANE_MARGIN): Boolean {
        if (polygon.size < 3) return false
        return polygon.indices.any { i ->
            val a = polygon[i]
            val b = polygon[(i + 1) % polygon.size]
            val dx = b.x - a.x
            val dy = b.y - a.y
            val squaredLength = dx * dx + dy * dy
            if (squaredLength == 0.0) return@any false
            val t = (((point.x - a.x) * dx + (point.y - a.y) * dy) / squaredLength).coerceIn(0.0, 1.0)
            point.distance(P2(a.x + dx * t, a.y + dy * t)) <= margin
        }
    }

    /** Intersects an infinite plane in front of the ray, rejecting parallel and grazing rays. */
    fun intersect(origin: V3, direction: V3, planePoint: V3, planeNormal: V3): Candidate<Nothing?>? {
        val directionLength = sqrt(direction.dot(direction))
        val normalLength = sqrt(planeNormal.dot(planeNormal))
        if (!directionLength.isFinite() || !normalLength.isFinite() || directionLength == 0.0 || normalLength == 0.0) return null
        val denominator = direction.dot(planeNormal)
        if (abs(denominator) / (directionLength * normalLength) < 0.02) return null
        val t = V3(planePoint.x - origin.x, planePoint.y - origin.y, planePoint.z - origin.z).dot(planeNormal) / denominator
        if (!t.isFinite() || t <= 0.0) return null
        val point = V3(origin.x + t * direction.x, origin.y + t * direction.y, origin.z + t * direction.z)
        return Candidate(point, t * directionLength, HitSource.EXTENDED_PLANE, null)
    }

    private fun V3.dot(other: V3) = x * other.x + y * other.y + z * other.z
}
