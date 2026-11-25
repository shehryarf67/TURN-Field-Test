package com.turn.fieldtest.mapper.core

import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Where a room corner's position came from (saved on [ScanPoint.provenance]; older drafts read as [OBSERVED]). */
object CornerProvenance {
    /** A reliable hit at the corner itself, at floor level, or a measured/edited coordinate. */
    const val OBSERVED = "OBSERVED"
    /** A hit higher up the wall or on the ceiling above the corner, projected vertically to the floor. */
    const val PROJECTED = "PROJECTED"
    /** The intersection of two fitted wall lines (the corner itself was hidden, e.g. behind furniture). */
    const val INFERRED = "INFERRED"
}

/** How an inferred corner was found: the tapped wall points, the angle between the walls and the line-fit quality. */
@kotlinx.serialization.Serializable
data class CornerInference(
    val wallA: List<P2>,
    val wallB: List<P2>,
    /** Acute angle between the two fitted wall lines, 0-90°. */
    val angleDegrees: Double,
    /** RMS distance of each wall's points from its fitted line. */
    val rmsA: Double,
    val rmsB: Double,
    /** How far the corner lies beyond the nearest tapped point of the farther wall. */
    val extrapolationMetres: Double,
)

/** A wall fitted to tapped points: through [point] along unit [direction]. */
data class WallLine(val point: P2, val direction: P2, val rms: Double, val span: Double)

/**
 * Corners hidden by furniture. (a) "Corner from two walls": the operator taps visible points along each of the two
 * walls that meet at the corner; each wall is fitted with a total-least-squares line and the corner is their
 * intersection (walls closer than [MIN_ANGLE_DEGREES] to parallel are refused). (b) A wall or ceiling hit above the
 * corner is accepted as the corner's floor position by vertical projection (AR +Y is gravity-up, so dropping the
 * height is exact). Both are flagged in the draft and the quality report.
 */
object HiddenCorners {
    const val MIN_ANGLE_DEGREES = 30.0
    /** The tapped points of each wall must span at least this to fix its direction. */
    const val MIN_WALL_SPAN_METRES = 0.3
    /** Points further than this from their fitted line are not one straight wall. */
    const val MAX_WALL_RMS_METRES = 0.08
    /** A corner further than this beyond the tapped points is too much of an extrapolation. */
    const val MAX_EXTRAPOLATION_METRES = 5.0
    /** AR hits more than this above the floor are projected (flagged [CornerProvenance.PROJECTED]). */
    const val ELEVATED_HIT_METRES = 0.3
    /** Lowest and highest accepted AR hit relative to the confirmed floor (floor slack, and a ceiling up to 3.5 m). */
    const val MIN_HIT_HEIGHT_METRES = -0.15
    const val MAX_HIT_HEIGHT_METRES = 3.5

    /** Highest accepted hit for tools that are not room corners (wall and door endpoints), as before hidden corners. */
    const val MAX_ENDPOINT_HIT_HEIGHT_METRES = 2.5
    /** Tools whose hits may be projected down from a wall or the ceiling. */
    val CORNER_TOOLS = setOf("CORNER", "ENTRANCE", "BOUNDARY", "WALL_CORNER")

    fun acceptsHitHeight(height: Double): Boolean = height.isFinite() && height in MIN_HIT_HEIGHT_METRES..MAX_HIT_HEIGHT_METRES

    /** Corner tools accept wall and ceiling hits up to 3.5 m; wall and door endpoints keep the 2.5 m limit. */
    fun acceptsHitHeight(height: Double, tool: String): Boolean =
        if (tool in CORNER_TOOLS) acceptsHitHeight(height)
        else height.isFinite() && height in MIN_HIT_HEIGHT_METRES..MAX_ENDPOINT_HIT_HEIGHT_METRES

    /** Wall points of the two-wall tool are only positions: they get no ARCore anchor (anchors are capped per section). */
    fun anchorsTap(tool: String): Boolean = tool != "WALL_CORNER"

    /** Provenance of a direct AR corner hit at [height] above the floor (null for measured points). */
    fun provenanceForHeight(height: Double?): String =
        if (height != null && height > ELEVATED_HIT_METRES) CornerProvenance.PROJECTED else CornerProvenance.OBSERVED

    /** The floor position straight below (or above) an AR hit, and the hit's height above the floor. */
    fun projectToFloor(frame: FloorFrame, hit: V3): Pair<P2, Double> = frame.project(hit) to (hit.y - frame.origin.y)

    /** Total-least-squares line through [points] (at least two, spread [MIN_WALL_SPAN_METRES]). */
    fun fitLine(points: List<P2>): WallLine {
        require(points.size >= 2 && points.all { it.finite }) { "Tap at least two points along each wall" }
        val cx = points.sumOf { it.x } / points.size; val cy = points.sumOf { it.y } / points.size
        var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        points.forEach { val dx = it.x - cx; val dy = it.y - cy; sxx += dx * dx; syy += dy * dy; sxy += dx * dy }
        val theta = 0.5 * atan2(2 * sxy, sxx - syy) // principal axis
        val direction = P2(cos(theta), sin(theta))
        val along = points.map { (it.x - cx) * direction.x + (it.y - cy) * direction.y }
        val span = along.max() - along.min()
        require(span >= MIN_WALL_SPAN_METRES) {
            String.format(Locale.ROOT, "Tap wall points at least %.1f m apart along the wall", MIN_WALL_SPAN_METRES)
        }
        val rms = sqrt(points.sumOf { val d = -(it.x - cx) * direction.y + (it.y - cy) * direction.x; d * d } / points.size)
        return WallLine(P2(cx, cy), direction, rms, span)
    }

    /** Acute angle between two lines, 0-90°. */
    fun angleDegrees(a: WallLine, b: WallLine): Double {
        val cos = abs(a.direction.x * b.direction.x + a.direction.y * b.direction.y).coerceIn(0.0, 1.0)
        return Math.toDegrees(kotlin.math.acos(cos))
    }

    /**
     * The corner where the walls through [wallA] and [wallB] meet, with how it was inferred. Fails with a message the
     * operator can act on: too few or too close points, a wall that is not straight, near-parallel walls, or a corner
     * far beyond the tapped points.
     */
    fun cornerFromWalls(wallA: List<P2>, wallB: List<P2>, minAngleDegrees: Double = MIN_ANGLE_DEGREES): Pair<P2, CornerInference> {
        val a = fitLine(wallA); val b = fitLine(wallB)
        require(a.rms <= MAX_WALL_RMS_METRES && b.rms <= MAX_WALL_RMS_METRES) {
            String.format(Locale.ROOT, "Wall points are not on one straight wall (%.2f m off): retap along the wall base or face", maxOf(a.rms, b.rms))
        }
        val angle = angleDegrees(a, b)
        require(angle >= minAngleDegrees) {
            String.format(Locale.ROOT, "The walls are only %.0f° apart: choose two walls that meet at the corner (at least %.0f°)", angle, minAngleDegrees)
        }
        // a.point + t·a.dir = b.point + u·b.dir
        val det = a.direction.x * (-b.direction.y) - a.direction.y * (-b.direction.x)
        val rx = b.point.x - a.point.x; val ry = b.point.y - a.point.y
        val t = (rx * (-b.direction.y) - ry * (-b.direction.x)) / det
        val corner = P2(a.point.x + t * a.direction.x, a.point.y + t * a.direction.y)
        require(corner.finite) { "The walls do not meet" }
        val reach = maxOf(wallA.minOf { it.distance(corner) }, wallB.minOf { it.distance(corner) })
        require(reach <= MAX_EXTRAPOLATION_METRES) {
            String.format(Locale.ROOT, "The corner would be %.1f m beyond the tapped points: tap closer to the corner", reach)
        }
        return corner to CornerInference(wallA, wallB, angle, a.rms, b.rms, reach)
    }

    /** "Kitchen: 2 corners inferred from walls, 1 projected from a high hit …" or null when every corner was observed. */
    fun qualityNote(room: ScanRoom): String? {
        val inferred = room.corners.count { it.provenance == CornerProvenance.INFERRED }
        val projected = room.corners.count { it.provenance == CornerProvenance.PROJECTED }
        if (inferred == 0 && projected == 0) return null
        val parts = listOfNotNull(
            inferred.takeIf { it > 0 }?.let { "$it ${if (it == 1) "corner" else "corners"} inferred from two walls" },
            projected.takeIf { it > 0 }?.let { "$it projected down from a high wall or ceiling hit" },
        )
        return "${room.name}: ${parts.joinToString(", ")}; check them with a tape measure"
    }
}

/**
 * Wall points of the "Hidden corner (two walls)" tool while they are being tapped: [wallA], then (after [next])
 * [wallB]. Immutable; [undo] removes the last tapped point (or steps back from wall 2 to wall 1 when wall 2 is empty).
 */
data class HiddenCornerDraft(val wallA: List<P2> = emptyList(), val wallB: List<P2>? = null) {
    val isEmpty: Boolean get() = wallA.isEmpty() && wallB == null
    fun add(p: P2) = if (wallB == null) copy(wallA = wallA + p) else copy(wallB = wallB + p)
    fun next(): HiddenCornerDraft {
        require(wallB == null && wallA.size >= 2) { "Tap at least two points along the first wall" }
        return copy(wallB = emptyList())
    }
    fun undo(): HiddenCornerDraft = when {
        wallB == null -> copy(wallA = wallA.dropLast(1))
        wallB.isEmpty() -> copy(wallB = null)
        else -> copy(wallB = wallB.dropLast(1))
    }
}
