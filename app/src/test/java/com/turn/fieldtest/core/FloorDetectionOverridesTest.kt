package com.turn.fieldtest.core

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.turn.fieldtest.data.settings.DataStoreTurnSettingsRepository
import com.turn.fieldtest.data.settings.TurnSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Diagnostics threshold editor: validation, defaults unchanged when nothing is overridden, persistence round trip. */
class FloorDetectionOverridesTest {
    @Test
    fun nothingOverriddenIsExactlyTheDefaultConfig() {
        val applied = FloorDetectionOverrides.apply(emptyMap())
        assertEquals(FloorDetectionConfig(), applied.config)
        assertTrue(applied.used.isEmpty() && applied.rejected.isEmpty())
        assertEquals(emptyMap(), FloorDetectionOverrides.sanitize(emptyMap()))
        assertEquals(emptyMap(), TurnSettings().floorDetectionOverrides)
        assertEquals(emptyMap(), TurnSettings().normalized().floorDetectionOverrides)
    }

    @Test
    fun everyEditableValueReadsItsDefaultAndWritesBackTheSameConfig() {
        val defaults = FloorDetectionConfig()
        FloorDetectionOverrides.specs.forEach { spec ->
            val value = spec.read(defaults)
            assertNull(spec.problem(value), "${spec.key} default ${spec.format(value)} must be inside ${spec.min}..${spec.max}")
            assertEquals(defaults, spec.write(defaults, value), spec.key)
        }
        assertEquals(FloorDetectionOverrides.specs.size, FloorDetectionOverrides.specs.map { it.key }.toSet().size, "keys are unique")
        assertEquals(3.5, FloorDetectionOverrides.defaults().getValue("vertical.floorHeightMetres"))
    }

    @Test
    fun overridesChangeOnlyTheirValue() {
        val applied = FloorDetectionOverrides.apply(mapOf("vertical.floorHeightMetres" to 2.7, "cues.stairsMinimumSteps" to 4.0,
            "decision.switchVote" to 0.8))
        assertEquals(FloorDetectionConfig(vertical = VerticalMotionConfig(floorHeightMetres = 2.7), cues = CueConfig(stairsMinimumSteps = 4),
            decision = DecisionConfig(switchVote = 0.8)), applied.config)
        assertEquals(3, applied.used.size)
        assertTrue(applied.rejected.isEmpty())
    }

    @Test
    fun outOfRangeUnknownFractionalAndConflictingValuesAreRejected() {
        val applied = FloorDetectionOverrides.apply(mapOf(
            "vertical.floorHeightMetres" to 1.0, // below 2 m
            "cues.stairsMinimumSteps" to 4.5, // not whole
            "decision.switchVote" to Double.NaN,
            "nonsense.key" to 1.0,
            "vertical.exitRateMps" to 0.2, // above the default 0.12 m/s start speed
            "transition.barometricShare" to 0.9, // fine
        ))
        assertEquals(FloorDetectionConfig(transition = TransitionConfig(barometricShare = 0.9)), applied.config)
        assertEquals(setOf("vertical.floorHeightMetres", "cues.stairsMinimumSteps", "decision.switchVote", "nonsense.key", "vertical.exitRateMps"),
            applied.rejected.keys)
        assertEquals("Allowed 2–6 m", applied.rejected["vertical.floorHeightMetres"])
        assertEquals("Enter a whole number", applied.rejected["cues.stairsMinimumSteps"])
        assertEquals("Conflicts with another threshold", applied.rejected["vertical.exitRateMps"])
        // The same exit speed is fine once the start speed is raised first (editor order applies enter before exit).
        assertTrue(FloorDetectionOverrides.apply(mapOf("vertical.enterRateMps" to 0.3, "vertical.exitRateMps" to 0.2)).rejected.isEmpty())
    }

    @Test
    fun sanitizeKeepsOnlyUsableNonDefaultValues() {
        assertEquals(mapOf("vertical.floorHeightMetres" to 2.8),
            FloorDetectionOverrides.sanitize(mapOf("vertical.floorHeightMetres" to 2.8, "vertical.settleSeconds" to 3.0, "x" to 1.0,
                "barometer.staleAfterSeconds" to 99.0)))
    }

    @Test
    fun encodingRoundTripsAndToleratesBadText() {
        val overrides = mapOf("vertical.floorHeightMetres" to 2.7, "decision.switchUpdates" to 4.0)
        val text = FloorDetectionOverrides.encode(overrides)
        assertEquals("""{"decision.switchUpdates":4.0,"vertical.floorHeightMetres":2.7}""", text)
        assertEquals(overrides, FloorDetectionOverrides.decode(text))
        assertEquals("", FloorDetectionOverrides.encode(emptyMap()))
        assertEquals(emptyMap(), FloorDetectionOverrides.decode(""))
        assertEquals(emptyMap(), FloorDetectionOverrides.decode(null))
        assertEquals(emptyMap(), FloorDetectionOverrides.decode("{broken"))
        assertEquals(mapOf("a" to 1.0), FloorDetectionOverrides.decode("""{"a":1,"b":"x","c":[1]}"""))
        assertEquals("decision.switchUpdates=4, vertical.floorHeightMetres=2.7", FloorDetectionOverrides.describe(overrides))
    }

    @Test
    fun overridesPersistThroughTheSettingsPreferencesAndResetClearsTheKey() {
        val preferences = mutablePreferencesOf()
        val saved = TurnSettings(floorDetectionOverrides = mapOf("vertical.floorHeightMetres" to 2.7, "cues.liftMinimumSeconds" to 6.0)).normalized()
        DataStoreTurnSettingsRepository.write(preferences, saved)
        val read = DataStoreTurnSettingsRepository.fromPreferences(preferences)
        assertEquals(saved, read)
        assertEquals(mapOf("cues.liftMinimumSeconds" to 6.0, "vertical.floorHeightMetres" to 2.7), read.floorDetectionOverrides)

        DataStoreTurnSettingsRepository.write(preferences, read.copy(floorDetectionOverrides = emptyMap()))
        assertNull(preferences[stringPreferencesKey("floor_detection_overrides")], "reset to defaults removes the stored overrides")
        assertEquals(TurnSettings(), DataStoreTurnSettingsRepository.fromPreferences(preferences).copy(
            activeVenueId = null, activeFloorId = null))
        // A stored value that is no longer valid (e.g. after a range change) is dropped on read, never crashes.
        preferences[stringPreferencesKey("floor_detection_overrides")] = """{"vertical.floorHeightMetres":99,"decision.switchVote":0.8}"""
        assertEquals(mapOf("decision.switchVote" to 0.8), DataStoreTurnSettingsRepository.fromPreferences(preferences).floorDetectionOverrides)
    }

    @Test
    fun theConfigEventRecordsTheOverridesOnlyWhenThereAreSome() {
        val topology = FloorTopologyBuilder.build("A", mapOf(
            "A" to MapFloor("A", listOf(MetricPolygon(listOf(MetricPoint(0.0, 0.0), MetricPoint(10.0, 0.0), MetricPoint(10.0, 10.0), MetricPoint(0.0, 10.0))))),
            "B" to MapFloor("B", listOf(MetricPolygon(listOf(MetricPoint(0.0, 0.0), MetricPoint(10.0, 0.0), MetricPoint(10.0, 10.0), MetricPoint(0.0, 10.0))))),
        ), mapOf("A" to 0, "B" to 1), listOf(FloorLinkInput("s", ConnectorKind.STAIRS, "A", MetricPoint(5.0, 5.0), "B", MetricPoint(5.0, 5.0),
            2.0, true, true, true, 15.0, "Stairs")), useUnverifiedLinks = false).topology
        val overrides = mapOf("vertical.floorHeightMetres" to 2.7)
        val applied = FloorDetectionOverrides.apply(overrides)
        val custom = FloorTrackingCoordinator.createOrNull(topology, applied.config, true, "A", configOverrides = applied.used)!!
        val detail = custom.drainEvents().single { it.type == FloorTrackingEventType.CONFIG }.detail
        assertTrue(detail.contains("\"floorHeightMetres\":2.7"), detail)
        assertTrue(detail.endsWith("; overrides=vertical.floorHeightMetres=2.7"), detail)
        val plain = FloorTrackingCoordinator.createOrNull(topology, FloorDetectionConfig(), true, "A")!!
        val plainDetail = plain.drainEvents().single { it.type == FloorTrackingEventType.CONFIG }.detail
        assertTrue(!plainDetail.contains("overrides") && !plainDetail.contains("elevations"), plainDetail)
    }

    @Test
    fun aValidFinalPairIsAcceptedWhateverItsEditorOrder() {
        // Lowering both speeds: exit 0.03 then start 0.05. Start 0.05 alone would equal the default exit (0.05) and fail.
        val lowered = FloorDetectionOverrides.apply(mapOf("vertical.exitRateMps" to 0.03, "vertical.enterRateMps" to 0.05))
        assertTrue(lowered.rejected.isEmpty(), lowered.rejected.toString())
        assertEquals(VerticalMotionConfig(enterRateMps = 0.05, exitRateMps = 0.03), lowered.config.vertical)
        // Raising both above the old start speed works too.
        val raised = FloorDetectionOverrides.apply(mapOf("vertical.exitRateMps" to 0.2, "vertical.enterRateMps" to 0.3))
        assertEquals(VerticalMotionConfig(enterRateMps = 0.3, exitRateMps = 0.2), raised.config.vertical)
        // A pair that is invalid in any order is still refused, keeping the other value.
        val bad = FloorDetectionOverrides.apply(mapOf("vertical.exitRateMps" to 0.2, "vertical.enterRateMps" to 0.1))
        assertEquals(VerticalMotionConfig(enterRateMps = 0.1), bad.config.vertical)
        assertEquals(setOf("vertical.exitRateMps"), bad.rejected.keys)
        assertEquals(mapOf("vertical.enterRateMps" to 0.05, "vertical.exitRateMps" to 0.03),
            FloorDetectionOverrides.sanitize(mapOf("vertical.exitRateMps" to 0.03, "vertical.enterRateMps" to 0.05)))
    }
}
