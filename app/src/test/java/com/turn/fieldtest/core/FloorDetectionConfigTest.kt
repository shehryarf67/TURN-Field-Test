package com.turn.fieldtest.core

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FloorDetectionConfigTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun defaultsMatchThePlan() {
        val config = FloorDetectionConfig()
        assertEquals(5, config.barometer.medianWindow)
        assertEquals(8434.0, config.barometer.scaleHeightMetres)
        assertEquals(3.0, config.barometer.staleAfterSeconds)
        assertEquals(3.5, config.vertical.floorHeightMetres)
        assertEquals(0.12, config.vertical.enterRateMps)
        assertEquals(6, config.cues.stairsMinimumSteps)
        assertEquals(0.85, config.transition.barometricShare)
        assertEquals(2, config.wifi.matchesWithConsistentHeight)
        assertEquals(0.75, config.decision.switchVote)
    }

    @Test
    fun roundTripsThroughJsonAndOldSnapshotsFillInDefaults() {
        val config = FloorDetectionConfig(vertical = VerticalMotionConfig(floorHeightMetres = 4.2))
        assertEquals(config, json.decodeFromString<FloorDetectionConfig>(json.encodeToString(FloorDetectionConfig.serializer(), config)))
        assertEquals(FloorDetectionConfig(), json.decodeFromString<FloorDetectionConfig>("{}"))
    }

    @Test
    fun rejectsOutOfRangeValuesIncludingWhenDecoded() {
        assertFailsWith<IllegalArgumentException> { BarometerConfig(maximumHpa = 200.0) }
        assertFailsWith<IllegalArgumentException> { VerticalMotionConfig(exitRateMps = 0.2) }
        assertFailsWith<IllegalArgumentException> { CueConfig(stairsMinimumSteps = 0) }
        assertFailsWith<IllegalArgumentException> { TransitionConfig(barometricShare = 1.5) }
        assertFailsWith<IllegalArgumentException> { WifiFloorConfig(vetoesBeforeUncertain = 0) }
        assertFailsWith<IllegalArgumentException> { DecisionConfig(switchVote = 0.4) }
        assertFailsWith<IllegalArgumentException> { FloorDetectionConfig(vertical = VerticalMotionConfig(rateWindowSeconds = 5.0)) }
        assertFailsWith<IllegalArgumentException> { FloorDetectionConfig(vertical = VerticalMotionConfig(scaleHeightMetres = 8000.0)) }
        assertFailsWith<IllegalArgumentException> { BarometerConfig(stepLevelShiftFraction = 1.5) }
        assertFailsWith<IllegalArgumentException> { json.decodeFromString<FloorDetectionConfig>("""{"decision":{"hardSwitchVote":0.9}}""") }
    }
}
