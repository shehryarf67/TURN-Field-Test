package com.turn.fieldtest.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.turn.fieldtest.data.local.*
import com.turn.fieldtest.data.repository.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID

/** Uses a dedicated test database; never opens or deletes the user's TURN database. */
@RunWith(AndroidJUnit4::class)
class ProjectPersistenceTest {
    @get:Rule val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), TurnDatabase::class.java)
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val databaseName = "turn-project-test-${UUID.randomUUID()}.db"
    private var database: TurnDatabase? = null

    @After fun closeTestDatabase() {
        database?.close()
        context.deleteDatabase(databaseName)
    }

    @Test fun versionOneSchemaReopensWithSurveyLineageAndPositioningIntact() = runBlocking {
        // Create the file from the committed v1 schema, just as it existed before the revamp.
        migrationHelper.createDatabase(databaseName, 1).close()
        val db = TurnDatabase.build(context, databaseName).also { database = it }
        val repo = RoomRepositories.from(db)
        val legacy = ProjectContext("VEN-CS-01", "FL-G")
        val venue = VenueEntity(legacy.venueId, "University", notes = "Keep these notes", createdAtEpochMillis = 1, updatedAtEpochMillis = 2)
        val floor = FloorEntity(legacy.floorId, legacy.venueId, "Ground", 0, 42.123456789, 28.0, createdAtEpochMillis = 1, updatedAtEpochMillis = 2)
        val region = WalkableRegionEntity("REGION-PILOT", floor.id, "Corridor",
            """[{"x":0,"y":0},{"x":40,"y":0},{"x":40,"y":20},{"x":0,"y":20}]""", updatedAtEpochMillis = 2)
        val point = ReferencePointEntity("RP-G-07", floor.id, "RP-01", 14.123456789, 18.0, true, updatedAtEpochMillis = 2)
        val survey = SurveySessionEntity("survey", venue.id, floor.id, point.id, point.xMetres, point.yMetres,
            "fixture", "fixture", "35", "0.1.0", dataMode = "REAL_DEVICE", startedAtEpochMillis = 3, endedAtEpochMillis = 5)
        val snapshot = WifiScanSnapshotEntity("scan", survey.id, 0, 3, 4, 10, true, true, true, false, 1, false)
        val observation = WifiObservationEntity("raw", snapshot.id, survey.id, point.id, "00:11:22:33:44:55", "test", -60, 2412,
            scanTimestampMicros = 1000, observedAtEpochMillis = 4, isFresh = true)
        val fingerprint = AggregatedWifiFingerprintEntity("aggregate", point.id, survey.id, observation.bssid, -60.0, -60.0,
            0.0, -60, -60, 1, 1, 1, 1.0, calculatedAtEpochMillis = 5)
        val position = PositioningSessionEntity("position", venue.id, floor.id, dataMode = "REAL_DEVICE", particleCount = 600, startedAtEpochMillis = 6)
        val estimate = PositionEstimateEntity("estimate", position.id, 0, "FUSED", floor.id, 14.0, 18.0,
            confidenceRadiusMetres = 2.0, confidenceScore = 0.8, floorConfidence = 1.0, stepCount = 2, timestampEpochMillis = 7, status = "TRACKING")
        val wall = WallSegmentEntity("wall", floor.id, 2.0, 2.0, 8.0, 2.0)
        val anchor = QrAnchorEntity("anchor", floor.id, "QR-G-ENTRANCE", 3.0, 4.0, initialDirectionDegrees = 90.0)
        val sensor = SensorSessionEntity("sensor", "REAL_DEVICE", "STEP_DETECTOR", "GAME_ROTATION_VECTOR", 0.73, startedAtEpochMillis = 6)
        val step = PdrEventEntity("step", sensor.id, 0, "STEP", "STEP_DETECTOR", 7, 10000, stepDelta = 1, strideMetres = 0.73)
        repo.venues.save(venue)
        repo.floorPlans.save(floor)
        repo.floorPlans.save(region)
        repo.floorPlans.save(point)
        repo.floorPlans.save(wall)
        repo.floorPlans.save(anchor)
        repo.floorPlans.saveAsset(FloorPlanAssetEntity("asset", floor.id, "content://fixture/floor", "image/png", 420, 280,
            metresPerPixel = 0.1, importedAtEpochMillis = 2))
        repo.surveys.save(survey)
        db.surveyDao().storeSnapshot(snapshot, listOf(observation))
        db.surveyDao().upsertAggregatedFingerprints(listOf(fingerprint))
        repo.positioning.saveSession(position)
        repo.positioning.saveEstimate(estimate)
        db.positioningDao().upsertSensorSession(sensor)
        db.positioningDao().upsertPdrEvent(step)
        db.close()

        val reopened = TurnDatabase.build(context, databaseName).also { database = it }
        val projects = ProjectRepository(reopened)
        val loaded = projects.load(legacy)
        assertEquals(venue, loaded.venue)
        assertEquals(floor, loaded.floor)
        assertEquals(listOf(region), loaded.regions)
        assertEquals(listOf(point), loaded.points)
        assertEquals(listOf(wall), loaded.walls)
        assertEquals(listOf(anchor), loaded.anchors)
        assertEquals("content://fixture/floor", loaded.asset?.contentUri)
        assertTrue(loaded.hasMeasurements)
        assertEquals(listOf(observation), reopened.surveyDao().observationsForSnapshot(snapshot.id))
        assertEquals(listOf(fingerprint), reopened.surveyDao().matchingFingerprintsForVenue(venue.id))
        assertEquals(survey, reopened.surveyDao().getSession(survey.id))
        assertEquals(position, reopened.positioningDao().getPositioningSession(position.id))
        assertEquals(listOf(estimate), reopened.positioningDao().observeEstimates(position.id).first())
        assertEquals(listOf(step), reopened.positioningDao().observePdrEvents(sensor.id).first())

        val newProject = projects.create(ProjectInput("Other university", "Ground"), 10)
        assertNotEquals(legacy, newProject)
        assertTrue(projects.load(newProject).points.isEmpty())
        assertFalse(projects.load(newProject).hasMeasurements)
        assertEquals(listOf(point), projects.load(legacy).points)
        val summaries = projects.observeProjects().first()
        assertEquals(2, summaries.size)
        assertEquals(1, summaries.single { it.context == legacy }.fingerprintPointCount)
        assertEquals(1, summaries.single { it.context == legacy }.positioningSessionCount)
    }

    @Test fun invalidProjectCreationDoesNotLeavePartialRows() = runBlocking {
        val db = TurnDatabase.build(context, databaseName).also { database = it }
        val projects = ProjectRepository(db)
        try {
            projects.create(ProjectInput("University", " "), 1)
            fail("Blank floor name should be rejected")
        } catch (_: IllegalArgumentException) { }
        assertTrue(projects.observeProjects().first().isEmpty())
        assertTrue(db.venueDao().observeAll().first().isEmpty())
    }
}
