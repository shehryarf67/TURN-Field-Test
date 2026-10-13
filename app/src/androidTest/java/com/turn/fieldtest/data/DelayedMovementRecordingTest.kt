package com.turn.fieldtest.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.turn.fieldtest.core.*
import com.turn.fieldtest.data.local.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Offline synthetic trace in a dedicated DB; never installed as a real hardware source. */
@RunWith(AndroidJUnit4::class)
class DelayedMovementRecordingTest {
    @Test fun delayedStepMovesFusedEstimateAndRecordingSurvivesDatabaseReopen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "delayed-step-${UUID.randomUUID()}.db"
        var db = TurnDatabase.build(context, name)
        try {
            db.venueDao().upsert(VenueEntity("v", "Trace fixture", createdAtEpochMillis = 1, updatedAtEpochMillis = 1))
            db.floorPlanDao().upsertFloor(FloorEntity("f", "v", "Floor", 0, 20.0, 20.0, createdAtEpochMillis = 1, updatedAtEpochMillis = 1))
            db.positioningDao().upsertSensorSession(SensorSessionEntity("s", "DEMO", "STEP_DETECTOR", "GAME_ROTATION_VECTOR", 0.7, startedAtEpochMillis = 1))
            db.positioningDao().upsertPositioningSession(PositioningSessionEntity("p", "v", "f", "s", "MANUAL", "DEMO", particleCount = 100, startedAtEpochMillis = 1))
            val floor = MapFloor("f", listOf(MetricPolygon(listOf(MetricPoint(0.0, 0.0),
                MetricPoint(20.0, 0.0), MetricPoint(20.0, 20.0), MetricPoint(0.0, 20.0)))))
            val filter = ParticleFilter(MetricMap(mapOf("f" to floor)), ParticleFilterConfig(particleCount = 100,
                initialHeadingOffsetStdRadians = 0.0, initialStrideScaleStd = 0.0, movementStdMetres = 0.0,
                headingNoiseStdRadians = 0.0, headingOffsetRandomWalkStdRadians = 0.0, strideScaleRandomWalkStd = 0.0))
            filter.initialize(AbsoluteFix(MetricPoint(5.0, 5.0), "f", AbsoluteFixSource.MANUAL), 0.0)
            val tracker = PdrTracker(MetricPoint(5.0, 5.0), 0.0,
                StepProcessor(setOf(StepSource.STEP_DETECTOR)), StrideModel(0.7))
            val headings = StepHeadingHistory().apply { record(1_500_000_000, 0.0); record(2_300_000_000, 1.0) }
            val heading = requireNotNull(headings.headingForStep(1_600_000_000))
            val step = tracker.processStep(StepSignal(1_600_000_000, StepSource.STEP_DETECTOR), heading)
            val summary = withContext(Dispatchers.Default) { requireNotNull(filter.predictStep(step.strideMetres, heading).summary) }
            db.positioningDao().upsertPdrEvent(PdrEventEntity("event", "s", 0, "STEP", "STEP_DETECTOR",
                2400, 1_600_000_000, stepDelta = 1, strideMetres = step.strideMetres, headingRadians = heading, accepted = true))
            db.positioningDao().upsertEstimate(PositionEstimateEntity("estimate", "p", 0, "FUSED", "f", summary.position.x,
                summary.position.y, headingRadians = heading, confidenceScore = summary.confidence,
                floorConfidence = summary.floorConfidence, stepCount = 1, timestampEpochMillis = 2400, status = "PDR prediction"))
            db.close(); db = TurnDatabase.build(context, name)
            assertTrue(db.positioningDao().observePdrEvents("s").first().single().accepted)
            val saved = requireNotNull(db.positioningDao().latestEstimate("p", "FUSED"))
            assertEquals(5.7, saved.xMetres, 1e-8)
            assertEquals(5.0, saved.yMetres, 1e-8)
            assertEquals(1L, saved.stepCount)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
