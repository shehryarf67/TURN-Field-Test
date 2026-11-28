package com.turn.fieldtest.mapper

import com.turn.fieldtest.mapper.core.*
import org.junit.Assert.*
import org.junit.Test

class SurfaceHitsTest {
    private fun hit(source: HitSource, distance: Double = 2.0, y: Double = 1.0,
        surface: SurfaceHits.Surface = SurfaceHits.Surface.UNKNOWN) =
        SurfaceHits.Candidate(V3(0.0, y, 0.0), distance, source, Unit, surface)

    @Test fun eligiblePlaneBeatsDepthWhichBeatsFeatureOnSameSurface() {
        val feature = hit(HitSource.FEATURE, 1.0)
        val depth = hit(HitSource.DEPTH, 1.1)
        val plane = hit(HitSource.PLANE_MARGIN, 1.2)
        assertEquals(plane, SurfaceHits.select(listOf(feature, depth, plane), 0.0, "CORNER").hit)
        assertEquals(depth, SurfaceHits.select(listOf(feature, depth), 0.0, "CORNER").hit)
        assertEquals(feature, SurfaceHits.select(listOf(feature), 0.0, "CORNER") { error("Fallback is lazy") }.hit)
    }

    @Test fun elevatedWallDepthWinsOverBackgroundFloorPlane() {
        val floor = hit(HitSource.PLANE, 4.0, 0.0, SurfaceHits.Surface.HORIZONTAL)
        val wall = hit(HitSource.DEPTH, 2.0, 1.4)
        assertEquals(wall, SurfaceHits.select(listOf(floor, wall), 0.0, "CORNER").hit)
        assertEquals(wall, SurfaceHits.select(listOf(floor, wall), 0.0, "ABOVE_CORNER").hit)
        assertEquals(SurfaceHits.Failure.NO_ELEVATED_SURFACE,
            SurfaceHits.select(listOf(floor), 0.0, "ABOVE_CORNER").failure)
    }

    @Test fun hiddenCornerRequiresWallEvidenceAndRefusesBackgroundWall() {
        val floor = hit(HitSource.PLANE, 2.0, 0.0, SurfaceHits.Surface.HORIZONTAL)
        val wall = hit(HitSource.FEATURE, 1.7, 1.4, SurfaceHits.Surface.VERTICAL)
        assertEquals(wall, SurfaceHits.select(listOf(floor, wall), 0.0, "WALL_CORNER").hit)
        assertEquals(SurfaceHits.Failure.NO_WALL_FACE, SurfaceHits.select(listOf(floor), 0.0, "WALL_CORNER") {
            listOf(wall.copy(distance = 2.8, source = HitSource.EXTENDED_PLANE))
        }.failure)
        assertEquals(wall, SurfaceHits.select(listOf(hit(HitSource.DEPTH, 1.65), wall), 0.0, "WALL_CORNER").hit)
    }

    @Test fun limitsApplyToDirectAndFallbackHits() {
        val ceiling = hit(HitSource.PLANE, y = 3.2)
        assertEquals(ceiling, SurfaceHits.select(listOf(ceiling), 0.0, "CORNER").hit)
        val high = SurfaceHits.select(listOf(ceiling), 0.0, "DOOR") {
            listOf(hit(HitSource.EXTENDED_PLANE, y = 3.0))
        }
        assertNull(high.hit)
        assertEquals(SurfaceHits.Failure.TOO_HIGH, high.failure)
        val far = SurfaceHits.select<Unit>(emptyList(), 0.0, "CORNER") {
            listOf(hit(HitSource.EXTENDED_PLANE, distance = 8.1))
        }
        assertEquals(SurfaceHits.Failure.TOO_FAR, far.failure)
        val fallback = hit(HitSource.EXTENDED_PLANE)
        assertEquals(fallback, SurfaceHits.select(listOf(ceiling), 0.0, "WALL") { listOf(fallback) }.hit)
    }

    @Test fun marginMeasuresNearestEdgeIncludingPolygonClosingEdgeAndCorners() {
        val polygon = listOf(P2(0.0, 0.0), P2(2.0, 0.0), P2(2.0, 2.0), P2(0.0, 2.0))
        assertTrue(SurfaceHits.withinBoundaryMargin(P2(-0.29, 1.0), polygon))
        assertFalse(SurfaceHits.withinBoundaryMargin(P2(-0.31, 1.0), polygon))
        assertFalse(SurfaceHits.withinBoundaryMargin(P2(-0.25, -0.25), polygon))
        assertFalse(SurfaceHits.withinBoundaryMargin(P2(0.0, 0.0), emptyList()))
    }

    @Test fun extendedPlaneRayReportsMetricDistanceAndRejectsParallelOrBehindHits() {
        val eye = V3(0.0, 1.0, 0.0)
        val wall = V3(2.0, 0.0, 0.0)
        val normal = V3(-1.0, 0.0, 0.0)
        val hit = requireNotNull(SurfaceHits.intersect(eye, V3(0.25, 0.0, 0.0), wall, normal))
        assertEquals(V3(2.0, 1.0, 0.0), hit.point)
        assertEquals(2.0, hit.distance, 1e-9)
        assertEquals(HitSource.EXTENDED_PLANE, hit.source)
        assertNull(SurfaceHits.intersect(eye, V3(0.0, 0.0, 1.0), wall, normal))
        assertNull(SurfaceHits.intersect(eye, V3(-1.0, 0.0, 0.0), wall, normal))
        val floor = requireNotNull(SurfaceHits.intersect(eye, V3(1.0, -1.0, 0.0), V3(0.0, 0.0, 0.0), V3(0.0, 1.0, 0.0)))
        assertEquals(V3(1.0, 0.0, 0.0), floor.point)
    }
}
