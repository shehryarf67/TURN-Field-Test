package com.turn.fieldtest.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GeometryTest {
    @Test
    fun polygonContainsInteriorAndBoundaryButNotExterior() {
        val polygon = rectangle(0.0, 0.0, 10.0, 5.0)

        assertTrue(polygon.contains(MetricPoint(4.0, 2.0)))
        assertTrue(polygon.contains(MetricPoint(0.0, 2.0)))
        assertFalse(polygon.contains(MetricPoint(0.0, 2.0), includeBoundary = false))
        assertFalse(polygon.contains(MetricPoint(10.1, 2.0)))
    }

    @Test
    fun classifiesWallIntersections() {
        val horizontal = MetricSegment(MetricPoint(0.0, 0.0), MetricPoint(4.0, 0.0))

        assertEquals(
            SegmentIntersection.CROSS,
            MetricGeometry.intersection(
                horizontal,
                MetricSegment(MetricPoint(2.0, -2.0), MetricPoint(2.0, 2.0)),
            ),
        )
        assertEquals(
            SegmentIntersection.TOUCH,
            MetricGeometry.intersection(
                horizontal,
                MetricSegment(MetricPoint(4.0, 0.0), MetricPoint(5.0, 1.0)),
            ),
        )
        assertEquals(
            SegmentIntersection.OVERLAP,
            MetricGeometry.intersection(
                horizontal,
                MetricSegment(MetricPoint(2.0, 0.0), MetricPoint(6.0, 0.0)),
            ),
        )
        assertEquals(
            SegmentIntersection.NONE,
            MetricGeometry.intersection(
                horizontal,
                MetricSegment(MetricPoint(0.0, 1.0), MetricPoint(4.0, 1.0)),
            ),
        )
    }

    @Test
    fun projectsPointOntoFiniteSegment() {
        val projection = MetricGeometry.project(
            MetricPoint(4.0, 3.0),
            MetricSegment(MetricPoint(0.0, 0.0), MetricPoint(2.0, 0.0)),
        )

        assertEquals(MetricPoint(2.0, 0.0), projection.point)
        assertEquals(1.0, projection.fraction, 1e-12)
        assertEquals(kotlin.math.sqrt(13.0), projection.distanceMetres, 1e-12)
    }

    @Test
    fun metricMapRejectsWallCrossing() {
        val floor = MapFloor(
            id = "F1",
            walkableRegions = listOf(rectangle(0.0, 0.0, 10.0, 10.0)),
            walls = listOf(WallSegment("wall", MetricPoint(5.0, 0.0), MetricPoint(5.0, 10.0))),
        )
        val map = MetricMap(mapOf("F1" to floor))

        assertEquals(
            MovementConstraint.CROSSES_WALL,
            map.movementConstraint("F1", MetricPoint(4.0, 5.0), MetricPoint(6.0, 5.0)),
        )
        assertEquals(
            MovementConstraint.ALLOWED,
            map.movementConstraint("F1", MetricPoint(1.0, 1.0), MetricPoint(4.0, 5.0)),
        )
    }

    @Test
    fun pathCheckDetectsLineLeavingConcaveWalkablePolygon() {
        val concave = MetricPolygon(
            listOf(
                MetricPoint(0.0, 0.0),
                MetricPoint(4.0, 0.0),
                MetricPoint(4.0, 1.0),
                MetricPoint(1.0, 1.0),
                MetricPoint(1.0, 4.0),
                MetricPoint(0.0, 4.0),
            ),
        )
        val floor = MapFloor("F1", listOf(concave))

        assertTrue(floor.isWalkable(MetricPoint(0.5, 3.5)))
        assertTrue(floor.isWalkable(MetricPoint(3.5, 0.5)))
        assertFalse(
            floor.isPathInsideWalkableSpace(MetricPoint(0.5, 3.5), MetricPoint(3.5, 0.5)),
        )
    }

    @Test
    fun calibrationConvertsPixelsToMetresAndRoundTrips() {
        val projection = ImageMetricProjection.calibrate(
            firstImagePoint = PixelPoint(100.0, 200.0),
            secondImagePoint = PixelPoint(300.0, 200.0),
            measuredDistanceMetres = 10.0,
            metricOrigin = MetricPoint(2.0, 3.0),
        )

        assertEquals(0.05, projection.metresPerPixel, 1e-12)
        val metric = projection.toMetric(PixelPoint(140.0, 160.0))
        assertEquals(MetricPoint(4.0, 5.0), metric)
        assertEquals(PixelPoint(140.0, 160.0), projection.toPixel(metric))
    }

    private fun rectangle(left: Double, bottom: Double, right: Double, top: Double): MetricPolygon =
        MetricPolygon(
            listOf(
                MetricPoint(left, bottom),
                MetricPoint(right, bottom),
                MetricPoint(right, top),
                MetricPoint(left, top),
            ),
        )
}
