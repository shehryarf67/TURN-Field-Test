package com.turn.fieldtest.mapper.ar

import android.opengl.Matrix
import com.google.ar.core.Config
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.turn.fieldtest.mapper.core.FloorFrame
import com.turn.fieldtest.mapper.core.HiddenCorners
import com.turn.fieldtest.mapper.core.HitSource
import com.turn.fieldtest.mapper.core.P2
import com.turn.fieldtest.mapper.core.SurfaceHits
import com.turn.fieldtest.mapper.core.V3
import kotlin.math.abs

internal fun Frame.surfaceHit(
    session: Session, x: Float, y: Float, width: Int, height: Int, floor: FloorFrame,
    tool: String, depthMode: Config.DepthMode,
): SurfaceHits.Selection<HitResult?> {
    val candidates = hitTest(x, y).mapNotNull { hit ->
        val trackable = hit.trackable
        if (trackable.trackingState != TrackingState.TRACKING) return@mapNotNull null
        val source = when (trackable) {
            is Plane -> trackable.sourceFor(hit.hitPose)
            is DepthPoint -> HitSource.DEPTH.takeIf { depthMode == Config.DepthMode.AUTOMATIC }
            is Point -> HitSource.FEATURE.takeIf { trackable.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL }
            else -> null
        } ?: return@mapNotNull null
        val surface = when (trackable) {
            is Plane -> if (trackable.type == Plane.Type.VERTICAL) SurfaceHits.Surface.VERTICAL else SurfaceHits.Surface.HORIZONTAL
            is Point -> if (abs(hit.hitPose.getTransformedAxis(1, 1f)[1]) <= 0.35f) SurfaceHits.Surface.VERTICAL
                else SurfaceHits.Surface.UNKNOWN
            else -> SurfaceHits.Surface.UNKNOWN
        }
        SurfaceHits.Candidate(hit.hitPose.v(), hit.distance.toDouble(), source, hit, surface)
    }
    return SurfaceHits.select<HitResult?>(candidates, floor.origin.y, tool) {
        extendedHits(session, x, y, width, height, floor, tool)
    }
}

private fun Plane.sourceFor(hit: Pose): HitSource? {
    if (subsumedBy != null) return null
    if (isPoseInPolygon(hit)) return HitSource.PLANE
    if (type == Plane.Type.HORIZONTAL_UPWARD_FACING) return null
    return HitSource.PLANE_MARGIN.takeIf { SurfaceHits.withinBoundaryMargin(localPoint(hit.v()), boundary()) }
}

private fun Plane.boundary(): List<P2> {
    val vertices = polygon.duplicate()
    return List(vertices.remaining() / 2) { P2(vertices.get().toDouble(), vertices.get().toDouble()) }
}

private fun Plane.localPoint(world: V3): P2 {
    val local = centerPose.inverse().transformPoint(floatArrayOf(world.x.toFloat(), world.y.toFloat(), world.z.toFloat()))
    return P2(local[0].toDouble(), local[2].toDouble())
}

private fun Frame.extendedHits(session: Session, x: Float, y: Float, width: Int, height: Int, floor: FloorFrame, tool: String):
    List<SurfaceHits.Candidate<Nothing?>> {
    val camera = camera
    val projection = FloatArray(16)
    camera.getProjectionMatrix(projection, 0, 0.1f, 100f)
    val inverse = FloatArray(16)
    if (!Matrix.invertM(inverse, 0, projection, 0)) return emptyList()
    val ndc = floatArrayOf(2f * x / width - 1f, 1f - 2f * y / height, -1f, 1f)
    val near = FloatArray(4)
    Matrix.multiplyMV(near, 0, inverse, 0, ndc, 0)
    val displayPose = camera.displayOrientedPose
    val origin = displayPose.v()
    val direction = displayPose.rotateVector(near.copyOf(3)).toV3()
    val up = V3(0.0, 1.0, 0.0)

    return buildList {
        if (tool !in setOf("WALL_CORNER", "ABOVE_CORNER") && direction.y < 0) {
            SurfaceHits.intersect(origin, direction, floor.origin, up)?.let { add(it.copy(surface = SurfaceHits.Surface.HORIZONTAL)) }
        }
        session.getAllTrackables(Plane::class.java).forEach { plane ->
            if (plane.trackingState != TrackingState.TRACKING || plane.subsumedBy != null) return@forEach
            val centre = plane.centerPose
            val point = centre.v()
            when (plane.type) {
                Plane.Type.VERTICAL -> {
                    val normal = centre.getTransformedAxis(1, 1f).toV3()
                    val facing = (origin.x - point.x) * normal.x + (origin.y - point.y) * normal.y +
                        (origin.z - point.z) * normal.z
                    if (facing <= 0) return@forEach
                    val hit = SurfaceHits.intersect(origin, direction, point, normal) ?: return@forEach
                    if (plane.isPoseInPolygon(Pose.makeTranslation(hit.point.x.toFloat(), hit.point.y.toFloat(), hit.point.z.toFloat())) ||
                        SurfaceHits.withinBoundaryMargin(plane.localPoint(hit.point), plane.boundary(), margin = 0.65)) {
                        add(hit.copy(surface = SurfaceHits.Surface.VERTICAL))
                    }
                }
                Plane.Type.HORIZONTAL_DOWNWARD_FACING -> {
                    if (tool == "WALL_CORNER" || direction.y <= 0 || point.y <= origin.y || point.y - floor.origin.y <= HiddenCorners.ELEVATED_HIT_METRES) return@forEach
                    // Only extend a measured ceiling near its observed patch, never assume a room height.
                    val hit = SurfaceHits.intersect(origin, direction, point, up) ?: return@forEach
                    val local = plane.localPoint(hit.point)
                    if (abs(local.x) <= plane.extentX / 2 + 1.0 && abs(local.y) <= plane.extentZ / 2 + 1.0) add(hit)
                }
                else -> Unit
            }
        }
    }
}

internal fun Pose.v() = V3(tx().toDouble(), ty().toDouble(), tz().toDouble())
private fun FloatArray.toV3() = V3(this[0].toDouble(), this[1].toDouble(), this[2].toDouble())
