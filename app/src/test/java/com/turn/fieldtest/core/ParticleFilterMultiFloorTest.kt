package com.turn.fieldtest.core

import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Multi-floor particle filter (phase 2 step 4). Asymmetric frames as in FloorTopologyTest: F1 covers (0,0)-(10,10)
 * with a wall at x = 3 for y in 0..4, F2 covers (8,4)-(28,14), F3 covers (0,0)-(20,20). The F1-F2 stairs run from
 * (2,2) on F1 to (15,8) on F2, so mixing up the frames would put exits outside walkable space.
 */
class ParticleFilterMultiFloorTest {
    private fun rectangle(left: Double, bottom: Double, right: Double, top: Double) = MetricPolygon(listOf(
        MetricPoint(left, bottom), MetricPoint(right, bottom), MetricPoint(right, top), MetricPoint(left, top)))

    private val f1 = MapFloor("F1", listOf(rectangle(0.0, 0.0, 10.0, 10.0)),
        walls = listOf(WallSegment("w1", MetricPoint(3.0, 0.0), MetricPoint(3.0, 4.0))))
    private val f2 = MapFloor("F2", listOf(rectangle(8.0, 4.0, 28.0, 14.0)))
    private val f3 = MapFloor("F3", listOf(rectangle(0.0, 0.0, 20.0, 20.0)))
    private val levels = mapOf("F1" to 0, "F2" to 1, "F3" to 2)
    private val twoFloorMap = MetricMap(listOf(f1, f2).associateBy { it.id })
    private val threeFloorMap = MetricMap(listOf(f1, f2, f3).associateBy { it.id })

    private fun link(id: String, kind: ConnectorKind, from: String, fromPoint: MetricPoint, to: String, toPoint: MetricPoint,
        verified: Boolean = true) =
        FloorLinkInput(id, kind, from, fromPoint, to, toPoint, 2.0, true, true, verified, 15.0, id)

    private val stairs12 = link("stairs-12", ConnectorKind.STAIRS, "F1", MetricPoint(2.0, 2.0), "F2", MetricPoint(15.0, 8.0))
    private val stairs23 = link("stairs-23", ConnectorKind.STAIRS, "F2", MetricPoint(15.0, 8.0), "F3", MetricPoint(5.0, 5.0))
    private val lift13 = link("lift-13", ConnectorKind.LIFT, "F1", MetricPoint(2.5, 2.0), "F3", MetricPoint(10.0, 10.0))

    private fun topology(map: MetricMap, vararg links: FloorLinkInput, unverified: Boolean = false) =
        FloorTopologyBuilder.build("F1", map.floors, levels.filterKeys { it in map.floors }, links.toList(), unverified).topology

    private val twoFloors = topology(twoFloorMap, stairs12)
    private val threeFloors = topology(threeFloorMap, stairs12, stairs23, lift13)

    private val config = ParticleFilterConfig(particleCount = 200)

    /** No noise at all, so a walk is exact and portal memory can be counted step by step. */
    private val exact = ParticleFilterConfig(particleCount = 20, initialPositionStdMetres = 0.0,
        initialHeadingOffsetStdRadians = 0.0, initialStrideScaleStd = 0.0, movementStdMetres = 0.0,
        headingNoiseStdRadians = 0.0, headingOffsetRandomWalkStdRadians = 0.0, strideScaleRandomWalkStd = 0.0)

    private fun filter(map: MetricMap = twoFloorMap, topology: FloorTopology = twoFloors, cfg: ParticleFilterConfig = config,
        seed: Long = 7L) = ParticleFilter(map, cfg, seed, topology)

    private fun ParticleFilter.start(x: Double, y: Double, floorId: String = "F1", std: Double = 0.2) = apply {
        initialize(AbsoluteFix(MetricPoint(x, y), floorId, AbsoluteFixSource.MANUAL), std)
    }

    private fun up(floors: Set<Int> = setOf(1), kind: VerticalKindHint = VerticalKindHint.STAIRS, share: Double = 0.85) =
        VerticalEvidence(VerticalDirection.UP, floors, kind, share, outOfPortalLikelihood = 0.1, unverifiedFactor = 0.5)

    private fun wifiOn(floorId: String, position: MetricPoint, confidence: Double) = WifiMatchResult(
        estimatedPosition = position,
        estimatedFloorId = floorId,
        neighbours = listOf(WifiNeighbour("rp", floorId, position, 2.0, 1.0, 0.0, 4)),
        confidence = confidence,
        uncertaintyRadiusMetres = 2.0,
        floorAgreement = 1.0,
        weightedMatchDistance = 2.0,
        freshness = WifiScanFreshness.FRESH,
        unlikeDatabase = false,
    )

    private fun ParticleFilter.vote(floorId: String) = floorVotes()[floorId] ?: 0.0

    @Test
    fun evidenceMovesOnlyParticlesInAPortalAndKeepsTheRestOnTheOldFloor() {
        val pf = filter().start(2.0, 2.0)
        assertTrue(pf.portalWeights().getValue(0) > 0.99) // the whole cloud is at the F1 stairs
        val result = pf.applyVerticalEvidence(up())
        assertTrue(result.accepted)
        assertEquals(0.85, pf.vote("F2"), 1e-9)
        assertEquals(0.15, pf.vote("F1"), 1e-9)
        val movers = pf.particles.filter { it.floorId == "F2" }
        assertTrue(movers.all { f2.isWalkable(it.position) && it.position.distanceTo(MetricPoint(15.0, 8.0)) <= 3.0 })
        assertTrue(movers.all { it.previousX == it.x && it.previousY == it.y })
        assertTrue(movers.all { it.portalIndex == 1 && it.stepsSincePortal == 0 }) // portal 1 is the way back down
        assertTrue(movers.map { it.position }.distinct().size > 10, "exits must not be stacked on one point")
        assertEquals(FilterEventType.FLOOR_TRANSITION, pf.events.last().type)
        assertTrue(pf.events.last().detail.contains("stairs-12"))
        assertTrue(pf.predictStep(0.6, 0.0).accepted)
        assertFalse(pf.isLost)
    }

    @Test
    fun evidenceAwayFromAnyPortalIsRejectedAndLeavesParticlesUntouched() {
        val pf = filter().start(8.0, 8.0)
        val before = pf.particles
        val result = pf.applyVerticalEvidence(up())
        assertFalse(result.accepted)
        assertEquals(before, pf.particles)
        assertEquals(FilterEventType.FLOOR_TRANSITION_REJECTED, pf.events.last().type)
        // At the portal but in the wrong direction (the stairs only go up from F1) or the wrong kind: rejected too.
        val atStairs = filter().start(2.0, 2.0)
        val atStairsBefore = atStairs.particles
        assertFalse(atStairs.applyVerticalEvidence(up().copy(direction = VerticalDirection.DOWN)).accepted)
        assertFalse(atStairs.applyVerticalEvidence(up(kind = VerticalKindHint.LIFT)).accepted)
        assertEquals(atStairsBefore, atStairs.particles)
        // Two floors up with only one link: movers stop on the intermediate floor instead of being dropped.
        assertTrue(atStairs.applyVerticalEvidence(up(floors = setOf(2))).accepted)
        assertEquals(0.85, atStairs.vote("F2"), 1e-9)
    }

    @Test
    fun portalMemoryLetsRecentlyCapturedParticlesMoveButNotStaleOnes() {
        val pf = filter(cfg = exact).start(2.0, 2.0, std = 0.0)
        // North from (2,2): captured (radius 2 + slack 1) for four steps, then six steps outside the area.
        repeat(10) { assertTrue(pf.predictStep(0.7, PI / 2).accepted) }
        assertTrue(pf.particles.all { it.portalIndex == 0 && it.stepsSincePortal == 6 })
        assertEquals(1.0, pf.portalWeights().getValue(0), 1e-12)
        assertTrue(pf.applyVerticalEvidence(up()).accepted)

        val stale = filter(cfg = exact).start(2.0, 2.0, std = 0.0)
        repeat(10) { stale.predictStep(0.7, PI / 2) }
        repeat(9) { stale.predictStep(0.7, if (it % 2 == 0) 0.0 else PI) } // pace along y = 9, far from the stairs
        assertTrue(stale.particles.all { it.stepsSincePortal == 15 })
        assertEquals(emptyMap(), stale.portalWeights())
        val before = stale.particles
        assertFalse(stale.applyVerticalEvidence(up()).accepted)
        assertEquals(before, stale.particles)
    }

    @Test
    fun stairsActiveHalvesTheStrideOnlyForParticlesWithPortalMemory() {
        val pf = filter(cfg = exact).start(2.0, 2.0, std = 0.0)
        pf.predictStep(0.7, PI / 2, stairsActive = true) // no memory yet: full stride, then captured
        assertEquals(2.7, pf.particles.first().y, 1e-9)
        pf.predictStep(0.7, PI / 2, stairsActive = true)
        assertEquals(3.05, pf.particles.first().y, 1e-9)
        pf.predictStep(0.7, PI / 2, stairsActive = false)
        assertEquals(3.75, pf.particles.first().y, 1e-9)

        val far = filter(cfg = exact).start(8.0, 5.0, std = 0.0)
        far.predictStep(0.7, PI / 2, stairsActive = true)
        assertEquals(5.7, far.particles.first().y, 1e-9)
        // Without a topology stairsActive is ignored.
        val legacy = ParticleFilter(twoFloorMap, exact, 7L).start(2.0, 2.0, std = 0.0)
        repeat(2) { legacy.predictStep(0.7, PI / 2, stairsActive = true) }
        assertEquals(3.4, legacy.particles.first().y, 1e-9)
        assertTrue(legacy.particles.all { it.portalIndex == -1 && it.stepsSincePortal == Int.MAX_VALUE })
    }

    @Test
    fun noFloorChangeWithoutVerticalEvidenceEvenWithStrongOtherFloorWifi() {
        val pf = filter(cfg = ParticleFilterConfig(particleCount = 80), seed = 11L).start(2.0, 2.0)
        val headings = Random(5L)
        val otherFloor = wifiOn("F2", MetricPoint(20.0, 10.0), 0.95)
        val sameFloor = wifiOn("F1", MetricPoint(5.0, 6.0), 0.8)
        repeat(300) { step ->
            pf.predictStep(0.6, headings.nextDouble() * 2 * PI, stairsActive = step % 7 == 0)
            if (pf.isLost) pf.start(5.0, 6.0)
            if (step % 10 == 0) {
                val otherFloorMatch = step % 20 == 0
                val kind = pf.correctWithWifi(if (otherFloorMatch) otherFloor else sameFloor,
                    crossFloorRelocalizationAllowed = false).kind
                if (otherFloorMatch) assertNotEquals(WifiCorrectionKind.GLOBAL_RELOCALIZATION, kind)
            }
            pf.correctWithMagnetic(emptyMap(), MagneticObservation(MagneticFeature(48.0, 20.0), 25, true), null)
            assertTrue(pf.particles.all { it.floorId == "F1" }, "a particle changed floor at step $step")
        }
    }

    @Test
    fun crossFloorWifiIsDeferredUnlessAllowed() {
        val pf = filter().start(5.0, 8.0)
        val strongF2 = wifiOn("F2", MetricPoint(20.0, 10.0), 0.9)
        val applied = pf.correctWithWifi(strongF2, crossFloorRelocalizationAllowed = false)
        assertEquals(WifiCorrectionKind.APPLIED, applied.kind) // reweighted only
        assertTrue(pf.particles.all { it.floorId == "F1" }, "nothing may be injected on the other floor")

        // Weights collapse when other-floor neighbours count for nothing: the particles are kept and the jump deferred.
        val collapseConfig = ParticleFilterConfig(particleCount = 64, crossFloorLikelihood = 0.0)
        val certain = wifiOn("F2", MetricPoint(20.0, 10.0), 1.0)
        val deferred = filter(cfg = collapseConfig).start(5.0, 8.0)
        val before = deferred.particles
        val result = deferred.correctWithWifi(certain, crossFloorRelocalizationAllowed = false)
        assertEquals(WifiCorrectionKind.DEFERRED_CROSS_FLOOR, result.kind)
        assertEquals(before, deferred.particles)
        assertEquals(FilterEventType.CROSS_FLOOR_WIFI_DEFERRED, deferred.events.last().type)
        assertEquals("F1", result.summary?.floorId)

        val allowed = filter(cfg = collapseConfig).start(5.0, 8.0)
        assertEquals(WifiCorrectionKind.GLOBAL_RELOCALIZATION, allowed.correctWithWifi(certain).kind)
        assertTrue(allowed.particles.all { it.floorId == "F2" })
    }

    @Test
    fun stratifiedResamplingKeepsAMinorityFloorAliveAndPrunesNegligibleOnes() {
        val pf = filter().start(2.0, 2.0)
        assertTrue(pf.applyVerticalEvidence(up(share = 0.03)).accepted)
        val votes = pf.floorVotes()
        assertEquals(0.03, votes.getValue("F2"), 1e-9)
        repeat(50) { pf.forceResample() }
        assertEquals(votes.getValue("F2"), pf.vote("F2"), 1e-12)
        assertEquals(votes.getValue("F1"), pf.vote("F1"), 1e-12)
        assertTrue(pf.particles.count { it.floorId == "F2" } >= 10)
        assertEquals(200, pf.particles.size)

        val negligible = filter().start(2.0, 2.0)
        assertTrue(negligible.applyVerticalEvidence(up(share = 5e-4)).accepted)
        assertEquals(setOf("F1"), negligible.floorVotes().keys)
    }

    @Test
    fun floorSummariesAndVotesAgree() {
        val pf = filter().start(2.0, 2.0)
        pf.applyVerticalEvidence(up(share = 0.3))
        val votes = pf.floorVotes()
        assertEquals(listOf("F1", "F2"), votes.keys.toList())
        assertEquals(1.0, votes.values.sum(), 1e-12)
        val overall = assertNotNull(pf.summary())
        assertEquals("F1", overall.floorId)
        assertEquals(overall, pf.summary("F1"))
        assertEquals(votes.getValue("F1"), overall.floorConfidence, 1e-12)
        val upstairs = assertNotNull(pf.summary("F2"))
        assertEquals("F2", upstairs.floorId)
        assertEquals(votes.getValue("F2"), upstairs.floorConfidence, 1e-12)
        assertTrue(f2.isWalkable(upstairs.position) && upstairs.position.distanceTo(MetricPoint(15.0, 8.0)) < 2.0)
        assertNull(pf.summary("F3"))
        assertNull(filter().summary("F1")) // lost filter
    }

    @Test
    fun floorSummariesAreCachedUntilTheParticlesChange() {
        val pf = filter().start(2.0, 2.0).apply { applyVerticalEvidence(up(share = 0.3)) }
        val twin = filter().start(2.0, 2.0).apply { applyVerticalEvidence(up(share = 0.3)) }
        val first = assertNotNull(pf.summary("F2"))
        assertSame(first, pf.summary("F2"))
        assertNull(pf.summary("F3"))
        assertNull(pf.summary("F3"))
        listOf(pf, twin).forEach { it.predictStep(0.7, 0.0, false) }
        val moved = assertNotNull(pf.summary("F2"))
        assertNotSame(first, moved)
        // The cache never changes the answer: the twin computes it fresh after the same step.
        assertEquals(twin.summary("F2"), moved)
        assertEquals(twin.summary("F1"), pf.summary("F1"))
    }

    @Test
    fun seedingSpreadsWeightAcrossTheGivenPortalsFloors() {
        val pf = filter(threeFloorMap, threeFloors).start(2.0, 2.0, std = 0.2)
        val stairsIndex = threeFloors.portals.first { it.linkId == "stairs-12" && it.fromFloorId == "F1" }.index
        val liftIndex = threeFloors.portals.first { it.linkId == "lift-13" && it.fromFloorId == "F1" }.index
        assertTrue(pf.seedFloorHypotheses(VerticalKindHint.ANY, setOf(stairsIndex, liftIndex), 0.3, 0.5).accepted)
        assertEquals(0.7, pf.vote("F1"), 1e-9)
        assertEquals(0.15, pf.vote("F2"), 1e-9)
        assertEquals(0.15, pf.vote("F3"), 1e-9)

        val liftOnly = filter(threeFloorMap, threeFloors).start(2.0, 2.0, std = 0.2)
        assertTrue(liftOnly.seedFloorHypotheses(VerticalKindHint.LIFT, setOf(stairsIndex, liftIndex), 0.3, 0.5).accepted)
        assertEquals(0.3, liftOnly.vote("F3"), 1e-9)
        assertEquals(0.0, liftOnly.vote("F2"))
        // Unknown portals are rejected; without a topology seeding is inert.
        assertFalse(liftOnly.seedFloorHypotheses(VerticalKindHint.ANY, setOf(99), 0.3, 0.5).accepted)
        val legacy = ParticleFilter(threeFloorMap, config, 7L).start(2.0, 2.0)
        val before = legacy.particles
        assertFalse(legacy.seedFloorHypotheses(VerticalKindHint.ANY, setOf(0), 0.3, 0.5).accepted)
        assertFalse(legacy.applyVerticalEvidence(up()).accepted)
        assertEquals(before, legacy.particles)
        assertEquals(emptyMap(), legacy.portalWeights())
    }

    @Test
    fun twoFloorChangesUseADirectLiftOrChainedStairs() {
        val lift = filter(threeFloorMap, threeFloors).start(2.0, 2.0, std = 0.2)
        assertTrue(lift.applyVerticalEvidence(up(setOf(2), VerticalKindHint.LIFT)).accepted)
        assertEquals(0.85, lift.vote("F3"), 1e-9)
        assertTrue(lift.particles.filter { it.floorId == "F3" }.all { it.position.distanceTo(MetricPoint(10.0, 10.0)) <= 3.0 })

        val chained = filter(threeFloorMap, threeFloors).start(2.0, 2.0, std = 0.2)
        assertTrue(chained.applyVerticalEvidence(up(setOf(2), VerticalKindHint.STAIRS)).accepted)
        assertEquals(0.85, chained.vote("F3"), 1e-9)
        assertTrue(chained.particles.filter { it.floorId == "F3" }.all { it.position.distanceTo(MetricPoint(5.0, 5.0)) <= 3.0 })
        assertTrue(chained.events.last().detail.contains("stairs-12, stairs-23"))

        // With only one hop allowed the movers stop on the intermediate floor.
        val oneHop = filter(threeFloorMap, threeFloors, config.copy(maxHops = 1)).start(2.0, 2.0, std = 0.2)
        assertTrue(oneHop.applyVerticalEvidence(up(setOf(2), VerticalKindHint.STAIRS)).accepted)
        assertEquals(0.85, oneHop.vote("F2"), 1e-9)
        assertEquals(0.0, oneHop.vote("F3"))
    }

    @Test
    fun unverifiedLinksMoveHalfTheShare() {
        val unchecked = topology(twoFloorMap, stairs12.copy(verifiedOnSite = false), unverified = true)
        val pf = filter(topology = unchecked).start(2.0, 2.0)
        assertTrue(pf.applyVerticalEvidence(up()).accepted)
        assertEquals(0.425, pf.vote("F2"), 1e-9)
    }

    @Test
    fun constructorRejectsATopologyWithFloorsMissingFromTheMap() {
        assertFailsWith<IllegalArgumentException> {
            ParticleFilter(MetricMap(mapOf("F1" to f1)), config, 0L, twoFloors)
        }
        assertTrue(abs(ParticleFilter(MetricMap(mapOf("F1" to f1)), config, 0L, FloorTopology.NONE).start(5.0, 5.0)
            .vote("F1") - 1.0) < 1e-12)
    }
}
