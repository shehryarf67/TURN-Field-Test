package com.turn.fieldtest.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** A rotation (counter-clockwise, about the origin) followed by a translation, in metres. Scale is always 1. */
@Serializable
data class Rigid2D(val rotationRadians: Double = 0.0, val tx: Double = 0.0, val ty: Double = 0.0) {
    init {
        require(rotationRadians.isFinite() && tx.isFinite() && ty.isFinite()) { "Transform values must be finite" }
    }

    fun apply(p: MetricPoint): MetricPoint {
        val c = cos(rotationRadians); val s = sin(rotationRadians)
        return MetricPoint(c * p.x - s * p.y + tx, s * p.x + c * p.y + ty)
    }

    fun inverse(): Rigid2D {
        val c = cos(-rotationRadians); val s = sin(-rotationRadians)
        return Rigid2D(-rotationRadians, -(c * tx - s * ty), -(s * tx + c * ty))
    }

    /** This transform, then [next]. */
    fun then(next: Rigid2D): Rigid2D {
        val moved = next.apply(MetricPoint(tx, ty))
        return Rigid2D(rotationRadians + next.rotationRadians, moved.x, moved.y)
    }

    /** Rotation in degrees, wrapped to (-180, 180]. */
    val rotationDegrees: Double get() {
        var d = Math.toDegrees(rotationRadians) % 360.0
        if (d <= -180.0) d += 360.0
        if (d > 180.0) d -= 360.0
        return d
    }

    companion object {
        val IDENTITY = Rigid2D()
    }
}

@Serializable
data class AlignmentPoint(val x: Double, val y: Double) {
    fun metric() = MetricPoint(x, y)

    companion object {
        fun of(p: MetricPoint) = AlignmentPoint(p.x, p.y)
    }
}

/**
 * How this floor's own frame maps onto [toFloorId]'s frame (saved on this floor as `frameAlignmentJson`, schema 8):
 * every Walk Mapper floor is captured in its own AR session, so its origin and "up" are arbitrary. Computed by
 * [FloorAlignmentSolver] from matching points picked on both floors (e.g. two stairwell corners). Only the floor-link
 * tools read it; tracking and routing keep each floor's own coordinates and use explicit links.
 */
@Serializable
data class FloorAlignment(
    val toFloorId: String,
    val transform: Rigid2D,
    /** Root-mean-square misfit of the picked pairs after the fit. */
    val rmsResidualMetres: Double,
    val maxResidualMetres: Double,
    /** Spread of the points on [toFloorId] divided by their spread here; 1 when both floors are to the same scale. */
    val scaleRatio: Double,
    /** The picked points on this floor and, in the same order, on [toFloorId]. */
    val pointsHere: List<AlignmentPoint>,
    val pointsThere: List<AlignmentPoint>,
    val createdAtEpochMillis: Long = 0L,
) {
    /** Distances on the two floors disagree by more than [FloorAlignmentSolver.SCALE_WARNING_FRACTION]. */
    val scaleWarning: Boolean get() = abs(scaleRatio - 1.0) > FloorAlignmentSolver.SCALE_WARNING_FRACTION

    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

        /** Null for null, blank or unreadable JSON (an unreadable alignment is treated as no alignment). */
        fun fromJsonOrNull(text: String?): FloorAlignment? =
            text?.takeIf { it.isNotBlank() }?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() }
    }
}

/** Least-squares rigid fit (rotation + translation, no scale) of matching points picked on two floors. */
object FloorAlignmentSolver {
    /** Points on one floor closer than this cannot fix a rotation. */
    const val MIN_BASELINE_METRES = 0.5
    /** Distances that differ by more than this share are reported (the floors' scales or the picks disagree). */
    const val SCALE_WARNING_FRACTION = 0.05
    /** A pair misfit above this after the fit is reported. */
    const val RESIDUAL_WARNING_METRES = 0.5

    data class Result(val alignment: FloorAlignment, val warnings: List<String>)

    /**
     * Fits [here] (this floor) onto [there] ([toFloorId]), pair by pair in order. Needs at least two pairs, spread at
     * least [MIN_BASELINE_METRES] on each floor. Both floors are taken as metric, so scale stays 1 and a distance
     * disagreement above 5 % becomes a warning rather than being absorbed.
     */
    fun solve(toFloorId: String, here: List<MetricPoint>, there: List<MetricPoint>, now: Long = 0L): Result {
        require(here.size == there.size) { "Pick the same number of points on both floors" }
        require(here.size >= 2) { "Pick at least two matching points on each floor" }
        require(spread(here) >= MIN_BASELINE_METRES && spread(there) >= MIN_BASELINE_METRES) {
            "Pick points at least ${MIN_BASELINE_METRES} m apart on each floor"
        }
        val ca = centroid(here); val cb = centroid(there)
        var dot = 0.0; var cross = 0.0; var sa = 0.0; var sb = 0.0
        here.indices.forEach { i ->
            val ax = here[i].x - ca.x; val ay = here[i].y - ca.y
            val bx = there[i].x - cb.x; val by = there[i].y - cb.y
            dot += ax * bx + ay * by
            cross += ax * by - ay * bx
            sa += ax * ax + ay * ay
            sb += bx * bx + by * by
        }
        val theta = atan2(cross, dot)
        val rotated = Rigid2D(theta).apply(ca)
        val transform = Rigid2D(theta, cb.x - rotated.x, cb.y - rotated.y)
        val residuals = here.indices.map { transform.apply(here[it]).distanceTo(there[it]) }
        val rms = sqrt(residuals.sumOf { it * it } / residuals.size)
        val scale = sqrt(sb / sa)
        val alignment = FloorAlignment(toFloorId, transform, rms, residuals.max(), scale,
            here.map(AlignmentPoint::of), there.map(AlignmentPoint::of), now)
        val warnings = buildList {
            if (alignment.scaleWarning) add(String.format(Locale.ROOT,
                "Distances differ by %.0f %% between the floors: check the picked points, or the scale of one floor", abs(scale - 1.0) * 100.0))
            if (alignment.maxResidualMetres > RESIDUAL_WARNING_METRES) add(String.format(Locale.ROOT,
                "Points disagree by up to %.2f m after alignment: check they are the same physical spots", alignment.maxResidualMetres))
        }
        return Result(alignment, warnings)
    }

    /**
     * The transform from [fromFloorId]'s frame to [toFloorId]'s, chaining saved alignments in either direction
     * ([alignments] maps a floor to its own alignment). Null when the floors are not connected by alignments.
     */
    fun transformBetween(fromFloorId: String, toFloorId: String, alignments: Map<String, FloorAlignment>): Rigid2D? {
        if (fromFloorId == toFloorId) return Rigid2D.IDENTITY
        val edges = mutableMapOf<String, MutableList<Pair<String, Rigid2D>>>()
        alignments.forEach { (floor, a) ->
            if (floor == a.toFloorId) return@forEach
            edges.getOrPut(floor) { mutableListOf() } += a.toFloorId to a.transform
            edges.getOrPut(a.toFloorId) { mutableListOf() } += floor to a.transform.inverse()
        }
        val reached = mutableMapOf(fromFloorId to Rigid2D.IDENTITY)
        val queue = ArrayDeque(listOf(fromFloorId))
        while (queue.isNotEmpty()) {
            val floor = queue.removeFirst()
            val sofar = reached.getValue(floor)
            edges[floor].orEmpty().sortedBy { it.first }.forEach { (next, step) ->
                if (next !in reached) {
                    reached[next] = sofar.then(step)
                    if (next == toFloorId) return reached.getValue(next)
                    queue.addLast(next)
                }
            }
        }
        return null
    }

    private fun centroid(points: List<MetricPoint>) = MetricPoint(points.sumOf { it.x } / points.size, points.sumOf { it.y } / points.size)

    private fun spread(points: List<MetricPoint>) = points.maxOf { a -> points.maxOf { b -> hypot(a.x - b.x, a.y - b.y) } }
}

/** What an alignment says about a saved or edited floor link. */
data class LinkAlignmentCheck(val planOffsetMetres: Double, val toleranceMetres: Double, val message: String) {
    val consistent: Boolean get() = planOffsetMetres <= toleranceMetres
}

/** Places and checks floor-link endpoints with a floor-to-floor alignment. */
object FloorLinkAlignment {
    /** A lift car is in the same place on every floor. */
    const val LIFT_TOLERANCE_METRES = 1.0
    /** Each end of a stair, escalator or ramp sits on its own landing; a domestic flight runs about 3-4 m in plan. */
    const val STAIRS_TOLERANCE_METRES = 5.0

    fun tolerance(kind: ConnectorKind): Double = if (kind == ConnectorKind.LIFT) LIFT_TOLERANCE_METRES else STAIRS_TOLERANCE_METRES

    /** The point on the other floor directly above or below [from] (its plan position through [fromToTo]). */
    fun placeOtherEnd(from: MetricPoint, fromToTo: Rigid2D): MetricPoint = fromToTo.apply(from)

    /** How far apart in plan the two ends of a link are once both are in [to]'s frame. */
    fun check(kind: ConnectorKind, from: MetricPoint, to: MetricPoint, fromToTo: Rigid2D): LinkAlignmentCheck {
        val offset = fromToTo.apply(from).distanceTo(to)
        val tolerance = tolerance(kind)
        val message = if (offset <= tolerance) {
            String.format(Locale.ROOT, "Aligned floors: ends %.1f m apart in plan", offset)
        } else {
            String.format(Locale.ROOT, "Aligned floors: ends %.1f m apart in plan (more than %.0f m for a %s): check the link points or the alignment",
                offset, tolerance, kind.label.lowercase(Locale.ROOT))
        }
        return LinkAlignmentCheck(offset, tolerance, message)
    }
}
