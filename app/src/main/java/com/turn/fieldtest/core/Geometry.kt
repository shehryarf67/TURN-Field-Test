package com.turn.fieldtest.core

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** A point in the venue's metric coordinate system. */
data class MetricPoint(val x: Double, val y: Double) {
    init {
        require(x.isFinite() && y.isFinite()) { "Metric coordinates must be finite" }
    }

    fun distanceTo(other: MetricPoint): Double = hypot(other.x - x, other.y - y)

    operator fun plus(vector: MetricVector): MetricPoint = MetricPoint(x + vector.dx, y + vector.dy)
    operator fun minus(other: MetricPoint): MetricVector = MetricVector(x - other.x, y - other.y)
}

data class MetricVector(val dx: Double, val dy: Double) {
    val length: Double get() = hypot(dx, dy)
}

data class MetricSegment(val start: MetricPoint, val end: MetricPoint) {
    val length: Double get() = start.distanceTo(end)
}

data class WallSegment(
    val id: String,
    val start: MetricPoint,
    val end: MetricPoint,
) {
    init {
        require(id.isNotBlank()) { "Wall id must not be blank" }
        require(start != end) { "A wall must have non-zero length" }
    }

    val segment: MetricSegment get() = MetricSegment(start, end)
}

enum class SegmentIntersection {
    NONE,
    TOUCH,
    CROSS,
    OVERLAP,
}

data class SegmentProjection(
    val point: MetricPoint,
    /** Fraction along the segment, clamped to [0, 1]. */
    val fraction: Double,
    val distanceMetres: Double,
)

object MetricGeometry {
    const val DEFAULT_EPSILON: Double = 1e-9

    fun project(point: MetricPoint, segment: MetricSegment): SegmentProjection {
        val dx = segment.end.x - segment.start.x
        val dy = segment.end.y - segment.start.y
        val squaredLength = dx * dx + dy * dy
        if (squaredLength <= DEFAULT_EPSILON) {
            return SegmentProjection(segment.start, 0.0, point.distanceTo(segment.start))
        }
        val rawFraction = ((point.x - segment.start.x) * dx + (point.y - segment.start.y) * dy) /
            squaredLength
        val fraction = rawFraction.coerceIn(0.0, 1.0)
        val projected = MetricPoint(segment.start.x + fraction * dx, segment.start.y + fraction * dy)
        return SegmentProjection(projected, fraction, point.distanceTo(projected))
    }

    fun intersection(
        first: MetricSegment,
        second: MetricSegment,
        epsilon: Double = DEFAULT_EPSILON,
    ): SegmentIntersection {
        val a = first.start
        val b = first.end
        val c = second.start
        val d = second.end
        val o1 = cross(a, b, c)
        val o2 = cross(a, b, d)
        val o3 = cross(c, d, a)
        val o4 = cross(c, d, b)

        if (abs(o1) <= epsilon && abs(o2) <= epsilon && abs(o3) <= epsilon && abs(o4) <= epsilon) {
            val useX = abs(b.x - a.x) >= abs(b.y - a.y)
            val firstLow = min(if (useX) a.x else a.y, if (useX) b.x else b.y)
            val firstHigh = max(if (useX) a.x else a.y, if (useX) b.x else b.y)
            val secondLow = min(if (useX) c.x else c.y, if (useX) d.x else d.y)
            val secondHigh = max(if (useX) c.x else c.y, if (useX) d.x else d.y)
            val overlap = min(firstHigh, secondHigh) - max(firstLow, secondLow)
            return when {
                overlap > epsilon -> SegmentIntersection.OVERLAP
                overlap >= -epsilon -> SegmentIntersection.TOUCH
                else -> SegmentIntersection.NONE
            }
        }

        val strictlyCrosses = ((o1 > epsilon && o2 < -epsilon) || (o1 < -epsilon && o2 > epsilon)) &&
            ((o3 > epsilon && o4 < -epsilon) || (o3 < -epsilon && o4 > epsilon))
        if (strictlyCrosses) return SegmentIntersection.CROSS

        if (abs(o1) <= epsilon && pointOnSegment(c, first, epsilon)) return SegmentIntersection.TOUCH
        if (abs(o2) <= epsilon && pointOnSegment(d, first, epsilon)) return SegmentIntersection.TOUCH
        if (abs(o3) <= epsilon && pointOnSegment(a, second, epsilon)) return SegmentIntersection.TOUCH
        if (abs(o4) <= epsilon && pointOnSegment(b, second, epsilon)) return SegmentIntersection.TOUCH
        return SegmentIntersection.NONE
    }

    fun pointOnSegment(
        point: MetricPoint,
        segment: MetricSegment,
        epsilon: Double = DEFAULT_EPSILON,
    ): Boolean {
        if (abs(cross(segment.start, segment.end, point)) > epsilon) return false
        return point.x >= min(segment.start.x, segment.end.x) - epsilon &&
            point.x <= max(segment.start.x, segment.end.x) + epsilon &&
            point.y >= min(segment.start.y, segment.end.y) - epsilon &&
            point.y <= max(segment.start.y, segment.end.y) + epsilon
    }

    internal fun cross(a: MetricPoint, b: MetricPoint, c: MetricPoint): Double =
        (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)

    internal fun segmentBoundaryParameters(
        segment: MetricSegment,
        polygon: MetricPolygon,
        epsilon: Double = DEFAULT_EPSILON,
    ): List<Double> {
        val result = mutableListOf(0.0, 1.0)
        val rX = segment.end.x - segment.start.x
        val rY = segment.end.y - segment.start.y
        val rSquared = rX * rX + rY * rY
        if (rSquared <= epsilon) return result

        polygon.edges.forEach { edge ->
            val sX = edge.end.x - edge.start.x
            val sY = edge.end.y - edge.start.y
            val denominator = rX * sY - rY * sX
            val qX = edge.start.x - segment.start.x
            val qY = edge.start.y - segment.start.y
            if (abs(denominator) > epsilon) {
                val t = (qX * sY - qY * sX) / denominator
                val u = (qX * rY - qY * rX) / denominator
                if (t in -epsilon..(1.0 + epsilon) && u in -epsilon..(1.0 + epsilon)) {
                    result += t.coerceIn(0.0, 1.0)
                }
            } else if (abs(qX * rY - qY * rX) <= epsilon) {
                result += ((edge.start.x - segment.start.x) * rX +
                    (edge.start.y - segment.start.y) * rY) / rSquared
                result += ((edge.end.x - segment.start.x) * rX +
                    (edge.end.y - segment.start.y) * rY) / rSquared
            }
        }
        return result.filter { it in -epsilon..(1.0 + epsilon) }
            .map { it.coerceIn(0.0, 1.0) }
            .sorted()
            .fold(mutableListOf()) { unique, value ->
                if (unique.isEmpty() || abs(unique.last() - value) > epsilon) unique += value
                unique
            }
    }
}

/** A simple polygon. Holes are represented by separate non-walkable boundaries/walls. */
class MetricPolygon(vertices: List<MetricPoint>) {
    val vertices: List<MetricPoint> = vertices.toList()

    init {
        require(this.vertices.size >= 3) { "A polygon needs at least three vertices" }
        require(abs(signedArea()) > MetricGeometry.DEFAULT_EPSILON) { "Polygon area must be non-zero" }
    }

    val edges: List<MetricSegment> by lazy {
        vertices.indices.map { index ->
            MetricSegment(vertices[index], vertices[(index + 1) % vertices.size])
        }
    }

    fun contains(point: MetricPoint, includeBoundary: Boolean = true): Boolean {
        if (edges.any { MetricGeometry.pointOnSegment(point, it) }) return includeBoundary
        var inside = false
        var previous = vertices.last()
        vertices.forEach { current ->
            val crossesRay = (current.y > point.y) != (previous.y > point.y)
            if (crossesRay) {
                val crossingX = (previous.x - current.x) * (point.y - current.y) /
                    (previous.y - current.y) + current.x
                if (point.x < crossingX) inside = !inside
            }
            previous = current
        }
        return inside
    }

    fun centroid(): MetricPoint {
        var factorSum = 0.0
        var xSum = 0.0
        var ySum = 0.0
        edges.forEach { edge ->
            val factor = edge.start.x * edge.end.y - edge.end.x * edge.start.y
            factorSum += factor
            xSum += (edge.start.x + edge.end.x) * factor
            ySum += (edge.start.y + edge.end.y) * factor
        }
        return MetricPoint(xSum / (3.0 * factorSum), ySum / (3.0 * factorSum))
    }

    fun closestBoundaryPoint(point: MetricPoint): SegmentProjection =
        edges.map { MetricGeometry.project(point, it) }.minBy { it.distanceMetres }

    private fun signedArea(): Double = edgesArea(vertices)

    override fun equals(other: Any?): Boolean = other is MetricPolygon && vertices == other.vertices
    override fun hashCode(): Int = vertices.hashCode()
    override fun toString(): String = "MetricPolygon(vertices=$vertices)"

    private companion object {
        fun edgesArea(points: List<MetricPoint>): Double {
            var sum = 0.0
            points.indices.forEach { index ->
                val current = points[index]
                val next = points[(index + 1) % points.size]
                sum += current.x * next.y - next.x * current.y
            }
            return sum / 2.0
        }
    }
}

data class MapFloor(
    val id: String,
    val walkableRegions: List<MetricPolygon>,
    val walls: List<WallSegment> = emptyList(),
) {
    init {
        require(id.isNotBlank()) { "Floor id must not be blank" }
        require(walkableRegions.isNotEmpty()) { "A floor needs at least one walkable region" }
    }

    fun isWalkable(point: MetricPoint): Boolean = walkableRegions.any { it.contains(point) }

    fun crossesWall(from: MetricPoint, to: MetricPoint): Boolean {
        val movement = MetricSegment(from, to)
        return walls.any { MetricGeometry.intersection(movement, it.segment) != SegmentIntersection.NONE }
    }

    /**
     * Checks the complete line, not only its endpoints. Boundary-intersection parameters split
     * the line into intervals whose midpoints can be tested exactly against the polygon union.
     */
    fun isPathInsideWalkableSpace(from: MetricPoint, to: MetricPoint): Boolean {
        if (!isWalkable(from) || !isWalkable(to)) return false
        val segment = MetricSegment(from, to)
        if (segment.length <= MetricGeometry.DEFAULT_EPSILON) return true
        val parameters = walkableRegions.flatMap {
            MetricGeometry.segmentBoundaryParameters(segment, it)
        }.plus(listOf(0.0, 1.0)).sorted().fold(mutableListOf<Double>()) { unique, value ->
            if (unique.isEmpty() || abs(unique.last() - value) > MetricGeometry.DEFAULT_EPSILON) {
                unique += value
            }
            unique
        }
        return parameters.zipWithNext().all { (start, end) ->
            val middle = (start + end) / 2.0
            isWalkable(
                MetricPoint(
                    from.x + (to.x - from.x) * middle,
                    from.y + (to.y - from.y) * middle,
                ),
            )
        }
    }
}

enum class MovementConstraint {
    ALLOWED,
    UNKNOWN_FLOOR,
    START_OUTSIDE_WALKABLE_SPACE,
    END_OUTSIDE_WALKABLE_SPACE,
    LEAVES_WALKABLE_SPACE,
    CROSSES_WALL,
}

data class VerticalTransition(
    val id: String,
    val firstFloorId: String,
    val secondFloorId: String,
    val firstFloorArea: MetricPolygon,
    val secondFloorArea: MetricPolygon,
) {
    init {
        require(id.isNotBlank()) { "Transition id must not be blank" }
        require(firstFloorId != secondFloorId) { "A transition must join different floors" }
    }

    fun supports(fromFloorId: String, toFloorId: String): Boolean =
        (fromFloorId == firstFloorId && toFloorId == secondFloorId) ||
            (fromFloorId == secondFloorId && toFloorId == firstFloorId)

    fun containsOnFloor(floorId: String, point: MetricPoint): Boolean = when (floorId) {
        firstFloorId -> firstFloorArea.contains(point)
        secondFloorId -> secondFloorArea.contains(point)
        else -> false
    }

    fun destinationCentre(toFloorId: String): MetricPoint = when (toFloorId) {
        firstFloorId -> firstFloorArea.centroid()
        secondFloorId -> secondFloorArea.centroid()
        else -> error("Transition $id does not connect floor $toFloorId")
    }
}

data class MetricMap(
    val floors: Map<String, MapFloor>,
    val verticalTransitions: List<VerticalTransition> = emptyList(),
) {
    init {
        require(floors.isNotEmpty()) { "A metric map needs at least one floor" }
        require(floors.keys.all { key -> floors.getValue(key).id == key }) {
            "Floor map keys must equal floor ids"
        }
    }

    fun movementConstraint(floorId: String, from: MetricPoint, to: MetricPoint): MovementConstraint {
        val floor = floors[floorId] ?: return MovementConstraint.UNKNOWN_FLOOR
        if (!floor.isWalkable(from)) return MovementConstraint.START_OUTSIDE_WALKABLE_SPACE
        if (!floor.isWalkable(to)) return MovementConstraint.END_OUTSIDE_WALKABLE_SPACE
        if (floor.crossesWall(from, to)) return MovementConstraint.CROSSES_WALL
        if (!floor.isPathInsideWalkableSpace(from, to)) return MovementConstraint.LEAVES_WALKABLE_SPACE
        return MovementConstraint.ALLOWED
    }

    fun transitionFor(fromFloorId: String, toFloorId: String, point: MetricPoint): VerticalTransition? =
        verticalTransitions.firstOrNull {
            it.supports(fromFloorId, toFloorId) && it.containsOnFloor(fromFloorId, point)
        }
}

data class PixelPoint(val x: Double, val y: Double) {
    init {
        require(x.isFinite() && y.isFinite()) { "Pixel coordinates must be finite" }
    }
}

/** Uniform image-to-metric projection derived from a measured two-point calibration. */
data class ImageMetricProjection(
    val metresPerPixel: Double,
    val imageOrigin: PixelPoint,
    val metricOrigin: MetricPoint = MetricPoint(0.0, 0.0),
    val invertImageYAxis: Boolean = true,
) {
    init {
        require(metresPerPixel.isFinite() && metresPerPixel > 0.0) {
            "Scale must be a positive finite value"
        }
    }

    fun toMetric(point: PixelPoint): MetricPoint {
        val deltaY = (point.y - imageOrigin.y) * if (invertImageYAxis) -1.0 else 1.0
        return MetricPoint(
            metricOrigin.x + (point.x - imageOrigin.x) * metresPerPixel,
            metricOrigin.y + deltaY * metresPerPixel,
        )
    }

    fun toPixel(point: MetricPoint): PixelPoint {
        val ySign = if (invertImageYAxis) -1.0 else 1.0
        return PixelPoint(
            imageOrigin.x + (point.x - metricOrigin.x) / metresPerPixel,
            imageOrigin.y + ySign * (point.y - metricOrigin.y) / metresPerPixel,
        )
    }

    companion object {
        fun calibrate(
            firstImagePoint: PixelPoint,
            secondImagePoint: PixelPoint,
            measuredDistanceMetres: Double,
            metricOrigin: MetricPoint = MetricPoint(0.0, 0.0),
            invertImageYAxis: Boolean = true,
        ): ImageMetricProjection {
            require(measuredDistanceMetres.isFinite() && measuredDistanceMetres > 0.0) {
                "Measured distance must be positive"
            }
            val pixelDistance = hypot(
                secondImagePoint.x - firstImagePoint.x,
                secondImagePoint.y - firstImagePoint.y,
            )
            require(pixelDistance > MetricGeometry.DEFAULT_EPSILON) {
                "Calibration image points must be distinct"
            }
            return ImageMetricProjection(
                metresPerPixel = measuredDistanceMetres / pixelDistance,
                imageOrigin = firstImagePoint,
                metricOrigin = metricOrigin,
                invertImageYAxis = invertImageYAxis,
            )
        }
    }
}

internal fun Double.squared(): Double = this * this
