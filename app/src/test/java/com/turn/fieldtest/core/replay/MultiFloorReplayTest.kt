package com.turn.fieldtest.core.replay

import com.turn.fieldtest.core.AbsoluteFix
import com.turn.fieldtest.core.AbsoluteFixSource
import com.turn.fieldtest.core.ConnectorKind
import com.turn.fieldtest.core.CrossFloorWifi
import com.turn.fieldtest.core.FloorDetectionConfig
import com.turn.fieldtest.core.FloorFixSource
import com.turn.fieldtest.core.FloorLinkInput
import com.turn.fieldtest.core.FloorState
import com.turn.fieldtest.core.FloorTopologyBuilder
import com.turn.fieldtest.core.FloorTrackingCoordinator
import com.turn.fieldtest.core.FloorTrackingEventType
import com.turn.fieldtest.core.MapFloor
import com.turn.fieldtest.core.MetricMap
import com.turn.fieldtest.core.MetricPoint
import com.turn.fieldtest.core.MetricPolygon
import com.turn.fieldtest.core.ParticleFilter
import com.turn.fieldtest.core.ParticleFilterConfig
import com.turn.fieldtest.core.WifiCorrectionKind
import com.turn.fieldtest.core.WifiMatchResult
import com.turn.fieldtest.core.WifiNeighbour
import com.turn.fieldtest.core.WifiScanFreshness
import com.turn.fieldtest.platform.sensors.TraceAccessPoint
import com.turn.fieldtest.platform.sensors.TraceRow
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 2 Step 6 replay scenarios (cut to four by the user's decision): synthetic walks written through the real
 * RawSensorRecorder, parsed back with RawSensorTrace, and replayed through a multi-floor ParticleFilter and the
 * FloorTrackingCoordinator. Asymmetric frames: F1 (0,0)-(50,10), F2 (100,0)-(140,10), F3 (50,50)-(90,60); stairs
 * F1 (37,5) <-> F2 (135,5), lift F1 (4,5) <-> F3 (56,55), stairs F2 (103,5) <-> F3 (85,55). Thresholds are loose:
 * the traces are synthetic.
 */
class MultiFloorReplayTest {
    private fun rectangle(left: Double, bottom: Double, right: Double, top: Double) = MetricPolygon(listOf(
        MetricPoint(left, bottom), MetricPoint(right, bottom), MetricPoint(right, top), MetricPoint(left, top)))

    private val f1 = MapFloor("F1", listOf(rectangle(0.0, 0.0, 50.0, 10.0)))
    private val f2 = MapFloor("F2", listOf(rectangle(100.0, 0.0, 140.0, 10.0)))
    private val f3 = MapFloor("F3", listOf(rectangle(50.0, 50.0, 90.0, 60.0)))
    private val map = MetricMap(listOf(f1, f2, f3).associateBy { it.id })
    private val levels = mapOf("F1" to 0, "F2" to 1, "F3" to 2)

    private fun link(id: String, kind: ConnectorKind, from: String, a: MetricPoint, to: String, b: MetricPoint, travel: Double) =
        FloorLinkInput(id, kind, from, a, to, b, 2.0, true, true, true, travel, id)

    private val topology = FloorTopologyBuilder.build(
        "F1", map.floors, levels,
        listOf(
            link("stairs-12", ConnectorKind.STAIRS, "F1", MetricPoint(37.0, 5.0), "F2", MetricPoint(135.0, 5.0), 15.0),
            link("lift-13", ConnectorKind.LIFT, "F1", MetricPoint(4.0, 5.0), "F3", MetricPoint(56.0, 55.0), 10.0),
            link("stairs-23", ConnectorKind.STAIRS, "F2", MetricPoint(103.0, 5.0), "F3", MetricPoint(85.0, 55.0), 15.0),
        ),
        useUnverifiedLinks = false,
    ).topology

    private val harness = FloorReplayHarness(map, topology)
    private val seeds = listOf(11L, 23L, 37L)
    private val east = 0.0
    private val west = PI

    /** Fixture fingerprints: one BSSID per reference point; a strong RSSI gives a confident, floor-unanimous match. */
    private val fingerprints = mapOf(F2_AP to ("F2" to MetricPoint(120.0, 5.0)), F3_AP to ("F3" to MetricPoint(66.0, 55.0)),
        F1_AP to ("F1" to MetricPoint(15.0, 5.0)))

    private fun matcher(scan: TraceRow.WifiScan): WifiMatchResult? {
        val ap = scan.accessPoints.maxByOrNull { it.rssiDbm } ?: return null
        val (floor, position) = fingerprints[ap.bssid] ?: return null
        val confidence = ((ap.rssiDbm + 90) / 50.0).coerceIn(0.0, 1.0)
        return WifiMatchResult(position, floor, listOf(WifiNeighbour("rp-${ap.bssid}", floor, position, 1.0, 1.0, 0.0, 1)),
            confidence, 2.0, floorAgreement = 1.0, weightedMatchDistance = 1.0, freshness = WifiScanFreshness.FRESH,
            unlikeDatabase = false)
    }

    @Test
    fun stairsUpOneFloorIsConfirmedOnceWithBoundedLatency() {
        seeds.forEach { seed ->
            val walk = SyntheticWalk(seed).arrived("F1")
                .walk(19.8, east) // (12,5) -> the stairs landing at (37,5)
                .stairs(1, "F2", headingA = east)
                .walk(20.0, west) // off the F2 landing at (135,5)
                .build()
            val run = harness.replay(walk.trace, ReplayStart("F1", MetricPoint(12.0, 5.0)), seed, walk::headingAt)
            val m = run.metrics
            assertEquals(listOf("F1", "F2"), run.confirmed.map { it.floorId }, "seed $seed")
            assertEquals(1, run.events.count { it.type == FloorTrackingEventType.APPLIED }, "seed $seed")
            assertEquals(0, run.events.count { it.type == FloorTrackingEventType.REJECTED }, "seed $seed")
            assertEquals(1, m.latenciesSeconds.size, "seed $seed: $m")
            assertTrue(m.latenciesSeconds.single() in 0.0..LATENCY_SECONDS, "seed $seed: $m")
            assertEquals(0, m.falseChanges, "seed $seed")
            assertEquals(0, m.reverts, "seed $seed")
            assertEquals(0, m.missedChanges, "seed $seed")
            assertTrue(m.accuracy >= 0.75, "seed $seed: $m")
            assertEquals(FloorState.CONFIRMED, run.decisions.last().second.state, "seed $seed")
        }
    }

    @Test
    fun liftRideOfTwoFloorsIsConfirmedOnTheTopFloor() {
        seeds.forEach { seed ->
            val walk = SyntheticWalk(seed).arrived("F1")
                .walk(6.3, west) // (12,5) -> the lift at (4,5)
                .stand(3.0)
                .lift(2, "F3")
                .stand(3.0)
                .walk(15.0, east) // turned round in the car, out of the F3 lift at (56,55) through the same-facing door
                .stand(10.0)
                .walk(10.0, west)
                .build()
            val run = harness.replay(walk.trace, ReplayStart("F1", MetricPoint(12.0, 5.0)), seed, walk::headingAt)
            val m = run.metrics
            assertEquals(listOf("F1", "F3"), run.confirmed.map { it.floorId }, "seed $seed: ${run.describe()}")
            assertTrue(run.events.single { it.type == FloorTrackingEventType.VERTICAL_EVENT }.detail.contains("LIFT"), "seed $seed")
            assertEquals(1, m.latenciesSeconds.size, "seed $seed: $m")
            assertTrue(m.latenciesSeconds.single() in 0.0..LATENCY_SECONDS, "seed $seed: $m")
            assertEquals(0, m.falseChanges, "seed $seed")
            assertEquals(0, m.reverts, "seed $seed")
            assertTrue(m.accuracy >= 0.75, "seed $seed: $m")
        }
    }

    @Test
    fun walkingPastTheStairsWithDoorAndHvacBlipsNeverChangesFloor() {
        seeds.forEach { seed ->
            val walk = SyntheticWalk(seed).arrived("F1").drift(1.5)
                .walk(9.5, east) // (25,5) -> just short of the stairs at (37,5)
                .pulseWhileWalking(0.3, 1.0, east) // a stairwell door opens and closes as the walker passes it
                .walk(6.0, east) // on past the stairs to about (46,5)
                .walk(4.0, west)
                .pressureStep(0.15) // HVAC kicks in, persistent
                .walk(12.0, west) // back past the stairs
                .walk(12.0, east)
                .pulseWhileWalking(-0.25, 0.8, east)
                .walk(3.0, east)
                .stand(5.0)
                .walk(16.0, west)
                .build()
            val run = harness.replay(walk.trace, ReplayStart("F1", MetricPoint(25.0, 5.0)), seed, walk::headingAt)
            val m = run.metrics
            assertEquals(listOf("F1"), run.confirmed.map { it.floorId }.distinct(), "seed $seed")
            assertEquals(0, run.events.count { it.type == FloorTrackingEventType.APPLIED }, "seed $seed")
            assertEquals(0, run.events.count { it.type == FloorTrackingEventType.VERTICAL_EVENT }, "seed $seed")
            assertEquals(1.0, m.accuracy, 1e-9, "seed $seed")
            assertEquals(0, m.falseChanges, "seed $seed")
            assertEquals(0, m.reverts, "seed $seed")
            assertTrue(run.filter.floorVotes().getValue("F1") > 0.999, "seed $seed: ${run.filter.floorVotes()}")
        }
    }

    @Test
    fun strongWifiForAnotherFloorWhileStayingPutIsVetoedNotFollowed() {
        seeds.forEach { seed ->
            val builder = SyntheticWalk(seed).arrived("F1").walk(4.0, east)
            repeat(5) { i -> builder.wifi(F2_AP to -42).walk(6.0, if (i % 2 == 0) east else west) }
            val walk = builder.walk(20.0, west).build()
            val run = harness.replay(walk.trace, ReplayStart("F1", MetricPoint(15.0, 5.0)), seed, walk::headingAt, ::matcher)
            val m = run.metrics
            assertEquals(listOf("F1"), run.confirmed.map { it.floorId }.distinct(), "seed $seed")
            assertEquals(5, run.wifiResults.size, "seed $seed")
            assertTrue(run.wifiResults.none { it == WifiCorrectionKind.GLOBAL_RELOCALIZATION }, "seed $seed: ${run.wifiResults}")
            assertEquals(5, run.events.count { it.type == FloorTrackingEventType.WIFI_CROSS_FLOOR_VETOED }, "seed $seed")
            assertEquals(0, run.events.count { it.type == FloorTrackingEventType.WIFI_CROSS_FLOOR_RELOCALIZATION }, "seed $seed")
            // After four vetoes on the same floor the display says the floor is uncertain (the Relocalize prompt).
            assertTrue(run.decisions.any { (_, d) -> d.state == FloorState.UNCERTAIN && d.uncertainReason == "Wi-Fi suggests another floor" },
                "seed $seed")
            assertTrue(run.filter.particles.all { it.floorId == "F1" }, "seed $seed")
            assertEquals(1.0, m.accuracy, 1e-9, "seed $seed")
            assertEquals(0, m.falseChanges, "seed $seed")
        }
    }

    /** Phase 2 list (a) without a barometer: the stairs dwell cue and/or 3 strong F2 scans move the floor; no false change. */
    @Test
    fun stairsUpWithoutBarometerIsResolvedByDwellAndWifi() {
        seeds.forEach { seed ->
            val builder = SyntheticWalk(seed, barometer = false).arrived("F1")
                .walk(19.8, east)
                .stairs(1, "F2", headingA = east)
            repeat(6) { builder.walk(3.0, west).wifi(F2_AP to -42) } // off the F2 landing at (135,5), scanning as it goes
            val walk = builder.walk(4.0, west).build()
            val run = harness.replay(walk.trace, ReplayStart("F1", MetricPoint(12.0, 5.0)), seed, walk::headingAt, ::matcher)
            val m = run.metrics
            assertEquals(listOf("F1", "F2"), run.confirmed.map { it.floorId }, "seed $seed: ${run.describe()}")
            assertEquals(0, run.events.count { it.type == FloorTrackingEventType.VERTICAL_EVENT }, "seed $seed")
            assertEquals(1, m.latenciesSeconds.size, "seed $seed: $m")
            assertTrue(m.latenciesSeconds.single() in 0.0..NO_BAROMETER_LATENCY_SECONDS, "seed $seed: $m ${run.describe()}")
            assertEquals(0, m.falseChanges, "seed $seed")
            assertEquals(0, m.reverts, "seed $seed")
            assertEquals(0, m.missedChanges, "seed $seed")
            assertEquals("F2", run.decisions.last().second.floorId, "seed $seed")
        }
    }

    /** Phase 2 list (b) without a barometer: the lift dwell cue seeds F3 through the lift and F3 Wi-Fi resolves it. */
    @Test
    fun twoFloorLiftWithoutBarometerIsResolvedByDwellAndWifi() {
        seeds.forEach { seed ->
            val builder = SyntheticWalk(seed, barometer = false).arrived("F1")
                .walk(6.3, west) // (12,5) -> the lift at (4,5)
                .stand(3.0)
                .lift(2, "F3")
                .stand(3.0)
            repeat(5) { builder.walk(3.0, east).wifi(F3_AP to -42) } // out of the F3 lift at (56,55), scanning
            val walk = builder.stand(5.0).walk(6.0, west).build()
            val run = harness.replay(walk.trace, ReplayStart("F1", MetricPoint(12.0, 5.0)), seed, walk::headingAt, ::matcher)
            val m = run.metrics
            assertEquals(listOf("F1", "F3"), run.confirmed.map { it.floorId }, "seed $seed: ${run.describe()}")
            assertEquals(0, run.events.count { it.type == FloorTrackingEventType.VERTICAL_EVENT }, "seed $seed")
            assertEquals(1, m.latenciesSeconds.size, "seed $seed: $m")
            assertTrue(m.latenciesSeconds.single() in 0.0..NO_BAROMETER_LATENCY_SECONDS, "seed $seed: $m ${run.describe()}")
            assertEquals(0, m.falseChanges, "seed $seed")
            assertEquals(0, m.reverts, "seed $seed")
            assertEquals(0, m.missedChanges, "seed $seed")
            assertEquals("F3", run.decisions.last().second.floorId, "seed $seed")
        }
    }

    /**
     * Phase 4 fix: Wi-Fi agreeing with the confirmed floor while the filter still leads on the old one is neither
     * vetoed nor counted, so it never marks the floor uncertain (only vetoes do). No decide() here: with every particle
     * on F1 the decision would follow the filter back at once; the state is the moment before it does.
     */
    @Test
    fun wifiOnTheConfirmedFloorIsNeverVetoedWhileTheFilterLeadsElsewhere() {
        val filter = ParticleFilter(map, ParticleFilterConfig(particleCount = 300), 5L, topology)
        filter.initialize(AbsoluteFix(MetricPoint(15.0, 5.0), "F1", AbsoluteFixSource.MANUAL), 0.5)
        val coordinator = requireNotNull(FloorTrackingCoordinator.createOrNull(topology, FloorDetectionConfig(), true, "F1"))
        coordinator.onAbsoluteFix("F1", FloorFixSource.MANUAL, 0L)
        coordinator.onAbsoluteFix("F2", FloorFixSource.MANUAL, 1L) // the display is on F2; every particle is still on F1
        coordinator.drainEvents()
        val match = assertNotNullMatch(F2_AP)
        repeat(6) { i ->
            val t = (i + 1) * 2_000_000_000L
            assertEquals(CrossFloorWifi.DEFER, coordinator.crossFloorWifi(match, filter.summary(), t), "scan $i")
        }
        val events = coordinator.drainEvents().map { it.type }
        assertTrue(FloorTrackingEventType.WIFI_CROSS_FLOOR_VETOED !in events, "$events")
        assertTrue(FloorTrackingEventType.WIFI_CROSS_FLOOR_DEFERRED !in events, "$events")
        // A match on the filter's own floor still needs no permission.
        assertEquals(CrossFloorWifi.ALLOW, coordinator.crossFloorWifi(assertNotNullMatch(F1_AP), filter.summary(), 20_000_000_000L))
    }

    private fun assertNotNullMatch(bssid: String): WifiMatchResult =
        requireNotNull(matcher(TraceRow.WifiScan(0L, 0L, true, listOf(TraceAccessPoint(bssid, -42, 2437, 0L)))))

    private companion object {
        const val F2_AP = "02:00:00:00:02:01"
        const val F3_AP = "02:00:00:00:03:01"
        const val F1_AP = "02:00:00:00:01:01"

        /** No barometer: a dwell cue (or nothing) then 3 strong scans (matchesWithoutBarometer) about 3 s apart; loose. */
        const val NO_BAROMETER_LATENCY_SECONDS = 20.0

        /** Settle (3 s) plus rate-window lag after arrival, then 3 decisions over 2 s: about 9 s; synthetic, so loose. */
        const val LATENCY_SECONDS = 15.0
    }
}
