package com.turn.fieldtest.core

import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Aligning two floors' independent mapper frames at the stairs: rigid fit, residuals, scale warning, link checks. */
class FloorAlignmentTest {
    private fun close(expected: MetricPoint, actual: MetricPoint, tolerance: Double = 1e-9) =
        assertTrue(expected.distanceTo(actual) <= tolerance, "expected $expected, got $actual")

    private val truth = Rigid2D(rotationRadians = Math.toRadians(73.0), tx = 4.2, ty = -11.5)

    @Test
    fun rigidTransformAppliesInvertsAndComposes() {
        val p = MetricPoint(3.0, -2.0)
        close(p, truth.inverse().apply(truth.apply(p)))
        close(p, truth.then(truth.inverse()).apply(p))
        val other = Rigid2D(-0.4, 1.0, 2.0)
        close(other.apply(truth.apply(p)), truth.then(other).apply(p))
        close(MetricPoint(2.0, 3.0), Rigid2D(PI / 2).apply(p))
        assertEquals(73.0, truth.rotationDegrees, 1e-9)
        assertEquals(-90.0, Rigid2D(3 * PI / 2).rotationDegrees, 1e-9)
    }

    @Test
    fun twoExactPairsRecoverTheTransformWithZeroResidual() {
        val here = listOf(MetricPoint(1.0, 1.0), MetricPoint(1.0, 3.6))
        val there = here.map(truth::apply)
        val r = FloorAlignmentSolver.solve("G", here, there)
        assertEquals(truth.rotationRadians, r.alignment.transform.rotationRadians, 1e-9)
        assertEquals(truth.tx, r.alignment.transform.tx, 1e-9)
        assertEquals(truth.ty, r.alignment.transform.ty, 1e-9)
        assertEquals(0.0, r.alignment.maxResidualMetres, 1e-9)
        assertEquals(1.0, r.alignment.scaleRatio, 1e-12)
        assertTrue(r.warnings.isEmpty())
        close(truth.apply(MetricPoint(7.0, 5.0)), r.alignment.transform.apply(MetricPoint(7.0, 5.0)), 1e-9)
    }

    @Test
    fun noisyPicksGiveAResidualAndAStillUsefulFit() {
        val random = Random(42)
        val here = listOf(MetricPoint(0.0, 0.0), MetricPoint(3.0, 0.0), MetricPoint(3.0, 1.0), MetricPoint(0.0, 1.0))
        repeat(20) {
            val there = here.map { truth.apply(it).let { p -> MetricPoint(p.x + random.nextGaussian() * 0.05, p.y + random.nextGaussian() * 0.05) } }
            val a = FloorAlignmentSolver.solve("G", here, there).alignment
            assertTrue(a.rmsResidualMetres in 0.0..0.15, "rms ${a.rmsResidualMetres}")
            assertTrue(abs(a.transform.rotationRadians - truth.rotationRadians) < Math.toRadians(4.0), "rotation ${a.transform.rotationDegrees}")
            close(truth.apply(MetricPoint(5.0, 5.0)), a.transform.apply(MetricPoint(5.0, 5.0)), 0.5)
        }
        // Two pairs with a 0.1 m pick error on a 2.6 m baseline: residual about half of it on each pair.
        val two = listOf(MetricPoint(0.0, 0.0), MetricPoint(2.6, 0.0))
        val r = FloorAlignmentSolver.solve("G", two, listOf(truth.apply(two[0]), truth.apply(MetricPoint(2.7, 0.0))))
        assertEquals(0.05, r.alignment.maxResidualMetres, 1e-9)
        assertTrue(r.warnings.isEmpty(), "3.8 % is within the 5 % scale tolerance")
    }

    @Test
    fun distanceDisagreementAbove5PercentIsAWarningNotAScale() {
        val here = listOf(MetricPoint(0.0, 0.0), MetricPoint(2.0, 0.0))
        val there = listOf(truth.apply(MetricPoint(0.0, 0.0)), truth.apply(MetricPoint(2.2, 0.0)))
        val r = FloorAlignmentSolver.solve("G", here, there)
        assertEquals(1.1, r.alignment.scaleRatio, 1e-9)
        assertTrue(r.alignment.scaleWarning)
        assertTrue(r.warnings.single().startsWith("Distances differ by 10 %"), r.warnings.toString())
        // Scale stays 1: a point at the far pick is off by the full disagreement split across both ends.
        assertEquals(0.1, r.alignment.maxResidualMetres, 1e-9)
        val within = FloorAlignmentSolver.solve("G", here, listOf(truth.apply(MetricPoint(0.0, 0.0)), truth.apply(MetricPoint(2.08, 0.0))))
        assertTrue(!within.alignment.scaleWarning)
    }

    @Test
    fun degenerateOrMismatchedPicksAreRefused() {
        val a = MetricPoint(1.0, 1.0)
        assertFailsWith<IllegalArgumentException> { FloorAlignmentSolver.solve("G", listOf(a), listOf(a)) }
        assertFailsWith<IllegalArgumentException> { FloorAlignmentSolver.solve("G", listOf(a, MetricPoint(1.2, 1.0)), listOf(a, MetricPoint(3.0, 1.0))) }
        assertFailsWith<IllegalArgumentException> { FloorAlignmentSolver.solve("G", listOf(a, MetricPoint(3.0, 1.0)), listOf(a)) }
    }

    @Test
    fun alignmentsRoundTripAsJsonAndBadJsonIsNoAlignment() {
        val a = FloorAlignmentSolver.solve("G", listOf(MetricPoint(0.0, 0.0), MetricPoint(2.0, 0.0)),
            listOf(MetricPoint(5.0, 5.0), MetricPoint(5.0, 7.0)), now = 9L).alignment
        assertEquals(a, FloorAlignment.fromJsonOrNull(a.toJson()))
        assertNull(FloorAlignment.fromJsonOrNull(null))
        assertNull(FloorAlignment.fromJsonOrNull(""))
        assertNull(FloorAlignment.fromJsonOrNull("{not json"))
    }

    @Test
    fun transformsChainThroughSavedAlignmentsInEitherDirection() {
        // F1 is aligned to G, F2 to F1: G <-> F2 goes through F1 both ways; an unaligned floor has no transform.
        val f1ToG = Rigid2D(0.3, 1.0, 2.0)
        val f2ToF1 = Rigid2D(-1.1, -4.0, 0.5)
        fun saved(to: String, t: Rigid2D) = FloorAlignment(to, t, 0.0, 0.0, 1.0, emptyList(), emptyList())
        val alignments = mapOf("F1" to saved("G", f1ToG), "F2" to saved("F1", f2ToF1))
        val p = MetricPoint(2.0, 3.0)
        close(f2ToF1.then(f1ToG).apply(p), FloorAlignmentSolver.transformBetween("F2", "G", alignments)!!.apply(p), 1e-9)
        close(p, FloorAlignmentSolver.transformBetween("G", "F2", alignments)!!.then(FloorAlignmentSolver.transformBetween("F2", "G", alignments)!!).apply(p), 1e-9)
        assertEquals(Rigid2D.IDENTITY, FloorAlignmentSolver.transformBetween("G", "G", alignments))
        assertNull(FloorAlignmentSolver.transformBetween("G", "B1", alignments))
        assertNull(FloorAlignmentSolver.transformBetween("G", "F1", emptyMap()))
    }

    @Test
    fun linkEndsArePlacedAboveEachOtherAndChecked() {
        // Upstairs (F1) was mapped turned 90° and shifted; the stair top is 3 m from its foot in plan.
        val f1ToG = Rigid2D(PI / 2, 10.0, 0.0)
        val gToF1 = f1ToG.inverse()
        val liftG = MetricPoint(2.0, 2.0)
        val liftF1 = FloorLinkAlignment.placeOtherEnd(liftG, gToF1)
        close(liftG, f1ToG.apply(liftF1), 1e-9)
        val lift = FloorLinkAlignment.check(ConnectorKind.LIFT, liftG, liftF1, gToF1)
        assertTrue(lift.consistent && lift.planOffsetMetres < 1e-9, lift.message)

        val stairFootG = MetricPoint(4.0, 1.0)
        val stairTopF1 = gToF1.apply(MetricPoint(4.0, 4.0))
        val stairs = FloorLinkAlignment.check(ConnectorKind.STAIRS, stairFootG, stairTopF1, gToF1)
        assertEquals(3.0, stairs.planOffsetMetres, 1e-9)
        assertTrue(stairs.consistent, stairs.message)
        assertTrue(stairs.message.startsWith("Aligned floors: ends 3.0 m apart"), stairs.message)

        val wrongLift = FloorLinkAlignment.check(ConnectorKind.LIFT, liftG, gToF1.apply(MetricPoint(5.0, 2.0)), gToF1)
        assertTrue(!wrongLift.consistent, wrongLift.message)
        assertTrue(wrongLift.message.contains("more than 1 m for a lift"), wrongLift.message)
        // An unaligned (identity) pair of frames is checked as-is.
        assertNotNull(FloorLinkAlignment.check(ConnectorKind.STAIRS, stairFootG, stairFootG, Rigid2D.IDENTITY).takeIf { it.consistent })
    }
}
