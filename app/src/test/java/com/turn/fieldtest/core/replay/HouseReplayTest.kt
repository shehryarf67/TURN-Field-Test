package com.turn.fieldtest.core.replay

import com.turn.fieldtest.core.ConnectorKind
import com.turn.fieldtest.core.FloorDetectionConfig
import com.turn.fieldtest.core.FloorElevations
import com.turn.fieldtest.core.FloorLinkInput
import com.turn.fieldtest.core.FloorTopologyBuilder
import com.turn.fieldtest.core.FloorTrackingEventType
import com.turn.fieldtest.core.MapFloor
import com.turn.fieldtest.core.MetricMap
import com.turn.fieldtest.core.MetricPoint
import com.turn.fieldtest.core.MetricPolygon
import kotlinx.serialization.json.Json
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Home testing (schema 8 floor elevations): a UK house with 2.6-2.8 m storeys, each floor in its own mapper frame.
 * G (0,0)-(12,8) with the stairs at (6,4); F1 (100,0)-(112,8) landing at (106,4); F2 (50,0)-(62,8) landing at (56,4),
 * reached from a second flight starting at F1 (106.5,4). Walkers stand at the landing until the event settles.
 */
class HouseReplayTest {
    private fun rectangle(left: Double, bottom: Double, right: Double, top: Double) = MetricPolygon(listOf(
        MetricPoint(left, bottom), MetricPoint(right, bottom), MetricPoint(right, top), MetricPoint(left, top)))

    private val g = MapFloor("G", listOf(rectangle(0.0, 0.0, 12.0, 8.0)))
    private val f1 = MapFloor("F1", listOf(rectangle(100.0, 0.0, 112.0, 8.0)))
    private val f2 = MapFloor("F2", listOf(rectangle(50.0, 0.0, 62.0, 8.0)))
    private val east = 0.0
    private val seeds = listOf(5L, 17L, 29L)

    private fun link(id: String, from: String, a: MetricPoint, to: String, b: MetricPoint) =
        FloorLinkInput(id, ConnectorKind.STAIRS, from, a, to, b, 2.0, true, true, true, 12.0, id)

    private val twoStorey = run {
        val map = MetricMap(listOf(g, f1).associateBy { it.id })
        val levels = mapOf("G" to 0, "F1" to 1)
        map to FloorTopologyBuilder.build("G", map.floors, levels,
            listOf(link("stairs", "G", MetricPoint(6.0, 4.0), "F1", MetricPoint(106.0, 4.0))), useUnverifiedLinks = false).topology
    }

    private val threeStorey = run {
        val map = MetricMap(listOf(g, f1, f2).associateBy { it.id })
        val levels = mapOf("G" to 0, "F1" to 1, "F2" to 2)
        map to FloorTopologyBuilder.build("G", map.floors, levels, listOf(
            link("stairs-g1", "G", MetricPoint(6.0, 4.0), "F1", MetricPoint(106.0, 4.0)),
            link("stairs-12", "F1", MetricPoint(106.5, 4.0), "F2", MetricPoint(56.0, 4.0)),
        ), useUnverifiedLinks = false).topology
    }

    private fun houseElevations(storey: Double) = FloorElevations.of(mapOf("G" to 0, "F1" to 1), mapOf("G" to 0.0, "F1" to storey))

    @Test
    fun stairsUpAndDownAHouseStoreyAreConfirmedWithElevations() {
        val (map, topology) = twoStorey
        listOf(2.6, 2.7, 2.8).forEach { storey ->
            val harness = FloorReplayHarness(map, topology, elevations = houseElevations(storey))
            seeds.forEach { seed ->
                val walk = SyntheticWalk(seed).arrived("G")
                    .walk(3.0, east) // (2,4) -> the foot of the stairs at about (6,4)
                    .stairs(1, "F1", headingA = east, floorHeightMetres = storey)
                    .stand(8.0).walk(3.0, east) // settle on the F1 landing at (106,4), then walk off it
                    .stand(4.0)
                    .walk(3.0, east + PI) // back to the landing
                    .stairs(-1, "G", headingA = east + PI, floorHeightMetres = storey)
                    .stand(8.0).walk(3.0, east + PI)
                    .build()
                val run = harness.replay(walk.trace, ReplayStart("G", MetricPoint(2.0, 4.0)), seed, walk::headingAt)
                assertEquals(listOf("G", "F1", "G"), run.confirmed.map { it.floorId }, "storey $storey, seed $seed: ${run.describe()}")
                assertEquals(2, run.events.count { it.type == FloorTrackingEventType.APPLIED }, "storey $storey, seed $seed")
                assertEquals(0, run.events.count { it.type == FloorTrackingEventType.REJECTED }, "storey $storey, seed $seed")
                assertTrue(run.events.filter { it.type == FloorTrackingEventType.VERTICAL_EVENT }.all { it.detail.contains(" 1 floor(s)") },
                    "storey $storey, seed $seed: ${run.describe()}")
                assertEquals(0, run.metrics.falseChanges, "storey $storey, seed $seed")
                val config = run.events.first { it.type == FloorTrackingEventType.CONFIG }.detail
                assertTrue(config.endsWith("; elevations=G=0.00, F1=${"%.2f".format(java.util.Locale.ROOT, storey)}"), config)
            }
        }
    }

    @Test
    fun anUnderReadClimbIsAFloorOnlyWhenTheStoreyIsKnown() {
        // A 2.6 m storey the barometer reads as 1.7 m: 0.49 of the nominal 3.5 m storey (no event), 0.65 of the real one.
        val (map, topology) = twoStorey
        seeds.forEach { seed ->
            val walk = SyntheticWalk(seed).arrived("G").walk(3.0, east)
                .stairs(1, "F1", headingA = east, secondsPerFloor = 8.0, floorHeightMetres = 1.7)
                .stand(8.0).walk(3.0, east).stand(4.0).build()
            val start = ReplayStart("G", MetricPoint(2.0, 4.0))
            val withHeights = FloorReplayHarness(map, topology, elevations = houseElevations(2.6)).replay(walk.trace, start, seed, walk::headingAt)
            assertEquals(listOf("G", "F1"), withHeights.confirmed.map { it.floorId }, "seed $seed: ${withHeights.describe()}")
            val nominal = FloorReplayHarness(map, topology).replay(walk.trace, start, seed, walk::headingAt)
            assertEquals(0, nominal.events.count { it.type == FloorTrackingEventType.VERTICAL_EVENT }, "seed $seed")
        }
    }

    @Test
    fun twoHouseStoreysInOneClimbAreTwoFloorsNotOne() {
        val (map, topology) = threeStorey
        val elevations = FloorElevations.of(mapOf("G" to 0, "F1" to 1, "F2" to 2), mapOf("G" to 0.0, "F1" to 2.7, "F2" to 5.4))
        seeds.forEach { seed ->
            val walk = SyntheticWalk(seed).arrived("G").walk(3.0, east)
                .stairs(2, "F2", headingA = east, floorHeightMetres = 2.7)
                .stand(8.0).walk(3.0, east).stand(4.0).build()
            val start = ReplayStart("G", MetricPoint(2.0, 4.0))
            val run = FloorReplayHarness(map, topology, elevations = elevations).replay(walk.trace, start, seed, walk::headingAt)
            assertEquals("F2", run.finalFloor, "seed $seed: ${run.describe()}")
            assertTrue(run.events.single { it.type == FloorTrackingEventType.VERTICAL_EVENT }.detail.contains("UP 2 floor(s)"), run.describe())
            // The nominal 3.5 m storey reads 5.4 m as one or two floors.
            val nominal = FloorReplayHarness(map, topology).replay(walk.trace, start, seed, walk::headingAt)
            assertTrue(nominal.events.single { it.type == FloorTrackingEventType.VERTICAL_EVENT }.detail.contains("UP 1/2 floor(s)"), nominal.describe())
        }
    }

    @Test
    fun aLiftRideInAHouseStoreyIsOneFloor() {
        val map = MetricMap(listOf(g, f1).associateBy { it.id })
        val topology = FloorTopologyBuilder.build("G", map.floors, mapOf("G" to 0, "F1" to 1), listOf(
            FloorLinkInput("lift", ConnectorKind.LIFT, "G", MetricPoint(6.0, 4.0), "F1", MetricPoint(106.0, 4.0), 1.5, true, true, true, 20.0, "Lift")),
            useUnverifiedLinks = false).topology
        seeds.forEach { seed ->
            val walk = SyntheticWalk(seed).arrived("G").walk(3.0, east).stand(3.0)
                .lift(1, "F1", speedMps = 0.5, floorHeightMetres = 2.7).stand(3.0).walk(3.0, east).stand(10.0).build()
            val run = FloorReplayHarness(map, topology, elevations = houseElevations(2.7))
                .replay(walk.trace, ReplayStart("G", MetricPoint(2.0, 4.0)), seed, walk::headingAt)
            assertEquals(listOf("G", "F1"), run.confirmed.map { it.floorId }, "seed $seed: ${run.describe()}")
        }
    }

    @Test
    fun withoutElevationsTheConfigEventAndResultsAreExactlyAsBefore() {
        val (map, topology) = twoStorey
        val seed = 5L
        val walk = SyntheticWalk(seed).arrived("G").walk(3.0, east).stairs(1, "F1", headingA = east, floorHeightMetres = 2.7)
            .stand(8.0).walk(3.0, east).stand(4.0).build()
        val start = ReplayStart("G", MetricPoint(2.0, 4.0))
        val unset = FloorReplayHarness(map, topology).replay(walk.trace, start, seed, walk::headingAt)
        val explicitNull = FloorReplayHarness(map, topology, elevations = null).replay(walk.trace, start, seed, walk::headingAt)
        assertEquals(unset.events, explicitNull.events)
        assertEquals(unset.confirmed, explicitNull.confirmed)
        // Incomplete elevations fall back to the nominal storey, so the session is unchanged too.
        val partial = FloorElevations.of(mapOf("G" to 0, "F1" to 1), mapOf("G" to 0.0, "F1" to null))
        assertEquals(null, partial)
        val config = unset.events.first { it.type == FloorTrackingEventType.CONFIG }.detail
        assertEquals("barometerPresent=true; config=" + Json.encodeToString(FloorDetectionConfig.serializer(), FloorDetectionConfig()), config)
    }
}
