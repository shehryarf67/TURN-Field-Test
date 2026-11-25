package com.turn.fieldtest.mapper

import com.turn.fieldtest.mapper.core.*
import kotlinx.serialization.json.Json
import java.util.Random
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Walk Mapper corners hidden by furniture: corner from two walls, and elevated hits projected to the floor. */
class HiddenCornersTest {
    private fun close(expected: P2, actual: P2, tolerance: Double) =
        assertTrue(expected.distance(actual) <= tolerance, "expected $expected, got $actual (${expected.distance(actual)} m)")

    @Test
    fun twoPerpendicularWallsMeetAtTheHiddenCorner() {
        // Corner at (4,3), hidden by a wardrobe; points tapped 0.8-2.5 m away along each wall.
        val (corner, inference) = HiddenCorners.cornerFromWalls(
            listOf(P2(1.5, 3.0), P2(2.5, 3.0), P2(3.2, 3.0)),
            listOf(P2(4.0, 0.5), P2(4.0, 1.4), P2(4.0, 2.2)))
        close(P2(4.0, 3.0), corner, 1e-9)
        assertEquals(90.0, inference.angleDegrees, 1e-9)
        assertEquals(0.0, inference.rmsA, 1e-9)
        assertEquals(0.8, inference.extrapolationMetres, 1e-9)
    }

    @Test
    fun obliqueWallsAndAnyOrderOfPoints() {
        val corner = P2(-2.0, 5.0)
        val angle = Math.toRadians(35.0)
        val a = listOf(1.0, 2.5, 1.7).map { t -> P2(corner.x + t, corner.y) }
        val b = listOf(2.0, 0.6, 1.3).map { t -> P2(corner.x + t * cos(angle), corner.y + t * sin(angle)) }
        val (found, inference) = HiddenCorners.cornerFromWalls(b, a)
        close(corner, found, 1e-9)
        assertEquals(35.0, inference.angleDegrees, 1e-6)
    }

    @Test
    fun nearParallelWallsAreRefused() {
        val angle = Math.toRadians(25.0)
        val error = assertFailsWith<IllegalArgumentException> {
            HiddenCorners.cornerFromWalls(listOf(P2(0.0, 0.0), P2(2.0, 0.0)), listOf(P2(0.0, 1.0), P2(2 * cos(angle), 1.0 + 2 * sin(angle))))
        }
        assertTrue(error.message!!.contains("only 25° apart"), error.message)
        assertFailsWith<IllegalArgumentException> { HiddenCorners.cornerFromWalls(listOf(P2(0.0, 0.0), P2(2.0, 0.0)), listOf(P2(0.0, 1.0), P2(2.0, 1.0))) }
        // A custom gate still applies the same rule.
        assertTrue(runCatching { HiddenCorners.cornerFromWalls(listOf(P2(0.0, 0.0), P2(2.0, 0.0)),
            listOf(P2(0.0, 1.0), P2(2 * cos(angle), 1.0 + 2 * sin(angle))), minAngleDegrees = 20.0) }.isSuccess)
    }

    @Test
    fun tooFewCloseCrookedOrFarPointsAreRefused() {
        assertFailsWith<IllegalArgumentException> { HiddenCorners.cornerFromWalls(listOf(P2(0.0, 0.0)), listOf(P2(3.0, 0.0), P2(3.0, 2.0))) }
        assertTrue(assertFailsWith<IllegalArgumentException> {
            HiddenCorners.cornerFromWalls(listOf(P2(0.0, 0.0), P2(0.1, 0.0)), listOf(P2(3.0, 0.0), P2(3.0, 2.0)))
        }.message!!.contains("0.3 m apart"))
        assertTrue(assertFailsWith<IllegalArgumentException> {
            HiddenCorners.cornerFromWalls(listOf(P2(0.0, 0.0), P2(1.0, 0.3), P2(2.0, 0.0), P2(3.0, 0.3)), listOf(P2(5.0, -2.0), P2(5.0, -1.0)))
        }.message!!.contains("not on one straight wall"))
        assertTrue(assertFailsWith<IllegalArgumentException> {
            HiddenCorners.cornerFromWalls(listOf(P2(0.0, 0.0), P2(1.0, 0.0)), listOf(P2(9.0, 3.0), P2(9.0, 4.0)))
        }.message!!.contains("beyond the tapped points"))
    }

    @Test
    fun noisyWallTapsStillGiveTheCornerWithinCentimetres() {
        val random = Random(9)
        val corner = P2(3.0, 2.0)
        repeat(50) {
            fun noisy(p: P2) = P2(p.x + random.nextGaussian() * 0.015, p.y + random.nextGaussian() * 0.015)
            val a = listOf(0.6, 1.2, 1.8, 2.4).map { noisy(P2(corner.x - it, corner.y)) }
            val b = listOf(0.6, 1.2, 1.8).map { noisy(P2(corner.x, corner.y - it)) }
            val (found, inference) = HiddenCorners.cornerFromWalls(a, b)
            close(corner, found, 0.08)
            assertTrue(inference.rmsA < 0.04 && inference.rmsB < 0.04)
            assertTrue(inference.angleDegrees in 85.0..90.0)
        }
    }

    @Test
    fun elevatedHitsProjectStraightDownAndAreFlagged() {
        val frame = FloorFrame.along(V3(1.0, -1.4, 2.0), V3(1.0, -1.4, 0.0)) // forward is AR -Z
        val floorHit = V3(2.0, -1.4, -1.0)
        val wallHit = V3(2.0, 0.1, -1.0) // 1.5 m up the wall, straight above
        val ceilingHit = V3(2.0, 1.0, -1.0) // 2.4 m ceiling
        val (onFloor, floorHeight) = HiddenCorners.projectToFloor(frame, floorHit)
        val (fromWall, wallHeight) = HiddenCorners.projectToFloor(frame, wallHit)
        val (fromCeiling, ceilingHeight) = HiddenCorners.projectToFloor(frame, ceilingHit)
        close(onFloor, fromWall, 1e-12)
        close(onFloor, fromCeiling, 1e-12)
        close(P2(1.0, 3.0), onFloor, 1e-9)
        assertEquals(0.0, floorHeight, 1e-12); assertEquals(1.5, wallHeight, 1e-12); assertEquals(2.4, ceilingHeight, 1e-12)
        assertEquals(CornerProvenance.OBSERVED, HiddenCorners.provenanceForHeight(0.05))
        assertEquals(CornerProvenance.OBSERVED, HiddenCorners.provenanceForHeight(null))
        assertEquals(CornerProvenance.PROJECTED, HiddenCorners.provenanceForHeight(wallHeight))
        assertEquals(CornerProvenance.PROJECTED, HiddenCorners.provenanceForHeight(ceilingHeight))
        assertTrue(HiddenCorners.acceptsHitHeight(2.7) && HiddenCorners.acceptsHitHeight(-0.1))
        assertTrue(!HiddenCorners.acceptsHitHeight(-0.4) && !HiddenCorners.acceptsHitHeight(3.8) && !HiddenCorners.acceptsHitHeight(Double.NaN))
    }

    @Test
    fun provenanceIsSavedAdditivelyAndReportedAsAQualityNote() {
        val inference = HiddenCorners.cornerFromWalls(listOf(P2(1.0, 4.0), P2(3.0, 4.0)), listOf(P2(4.0, 1.0), P2(4.0, 3.0))).second
        val room = ScanRoom(name = "Living room", confirmed = true, corners = listOf(
            ScanPoint(p = P2(0.0, 0.0), source = "AR_PLANE", segmentId = "s", heightAboveFloor = 0.02),
            ScanPoint(p = P2(4.0, 0.0), source = "DEPTH_HIT", segmentId = "s", provenance = CornerProvenance.PROJECTED, heightAboveFloor = 2.4),
            ScanPoint(p = P2(4.0, 4.0), source = "INFERRED_FROM_WALLS", segmentId = "s", provenance = CornerProvenance.INFERRED, inference = inference),
            ScanPoint(p = P2(0.0, 4.0), source = "AR_PLANE", segmentId = "s"),
        ))
        val doc = ScanDocument(venueId = "v", floorId = "f", rooms = listOf(room), segments = listOf(ScanSegment("s", "ARCORE")))
        val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
        val text = json.encodeToString(ScanDocument.serializer(), doc)
        assertEquals(doc, json.decodeFromString(ScanDocument.serializer(), text))
        val notes = ScanGeometry.issues(doc).filter { it.message.startsWith("Living room: 1 corner inferred") }
        assertEquals(1, notes.size)
        assertTrue(!notes.single().blocking, "provenance is reported, never blocking")
        assertTrue(notes.single().message.contains("1 projected down from a high wall or ceiling hit"))
        assertNull(HiddenCorners.qualityNote(room.copy(corners = room.corners.map { it.copy(provenance = CornerProvenance.OBSERVED) })))

        // A draft saved before provenance existed reads every corner as observed.
        val old = """{"id":"p","p":{"x":1.0,"y":2.0},"source":"AR_PLANE","segmentId":"s"}"""
        val point = json.decodeFromString(ScanPoint.serializer(), old)
        assertEquals(CornerProvenance.OBSERVED, point.provenance)
        assertNull(point.inference); assertNull(point.heightAboveFloor)

        // Map corrections move an inferred corner together with the wall points it came from.
        val turned = doc.transformed(Alignment(radians = Math.PI / 2, tx = 1.0))
        val moved = turned.rooms.single().corners[2]
        close(P2(-3.0, 4.0), moved.p, 1e-9)
        close(Alignment(radians = Math.PI / 2, tx = 1.0).apply(inference.wallA.first()), moved.inference!!.wallA.first(), 1e-9)
    }
}
