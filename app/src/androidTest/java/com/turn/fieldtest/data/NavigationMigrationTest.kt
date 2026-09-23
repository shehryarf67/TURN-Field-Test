package com.turn.fieldtest.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.turn.fieldtest.data.local.*
import com.turn.fieldtest.data.repository.ProjectRepository
import com.turn.fieldtest.data.repository.ProjectContext
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class NavigationMigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), TurnDatabase::class.java)
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "turn-migration-${UUID.randomUUID()}.db"
    private var room: TurnDatabase? = null
    @After fun clean() { room?.close(); context.deleteDatabase(name) }

    @Test fun populatedV1MigratesWithoutLosingRadioOrPositionRecordsAndGraphReopens() = runBlocking {
        helper.createDatabase(name, 1).apply {
            execSQL("INSERT INTO venues (id,name,createdAtEpochMillis,updatedAtEpochMillis) VALUES ('v','University',1,1)")
            execSQL("INSERT INTO floors (id,venueId,name,levelNumber,widthMetres,heightMetres,originXMetres,originYMetres,imageYAxisPointsDown,createdAtEpochMillis,updatedAtEpochMillis) VALUES ('f','v','Ground',0,40,20,0,0,1,1,1)")
            execSQL("INSERT INTO reference_points (id,floorId,name,xMetres,yMetres,isInsideWalkableSpace,updatedAtEpochMillis) VALUES ('rp','f','RP-01',2.123456789,3,1,1)")
            execSQL("INSERT INTO survey_sessions (id,venueId,floorId,referencePointId,knownXMetres,knownYMetres,deviceManufacturer,deviceModel,androidVersion,appVersion,dataMode,startedAtEpochMillis,endedAtEpochMillis) VALUES ('survey','v','f','rp',2.123456789,3,'test','test','35','1','REAL_DEVICE',10,20)")
            execSQL("INSERT INTO wifi_scan_snapshots (id,surveySessionId,scanSequence,requestedAtEpochMillis,resultsReceivedAtEpochMillis,newestResultAgeMillis,requestAccepted,resultsUpdated,isFresh,isCached,visibleAccessPointCount,throttled) VALUES ('scan','survey',0,10,11,1,1,1,1,0,1,0)")
            execSQL("INSERT INTO wifi_observations (id,snapshotId,surveySessionId,referencePointId,bssid,ssid,rssiDbm,frequencyMhz,scanTimestampMicros,observedAtEpochMillis,isFresh) VALUES ('raw','scan','survey','rp','aa:bb','test',-55,2412,1000,11,1)")
            execSQL("INSERT INTO aggregated_wifi_fingerprints (id,referencePointId,surveySessionId,bssid,medianRssiDbm,meanRssiDbm,standardDeviationDb,minimumRssiDbm,maximumRssiDbm,observationCount,eligibleFreshSnapshotCount,totalFreshSnapshotCount,detectionRate,unstable,excludedFromMatching,calculatedAtEpochMillis) VALUES ('agg','rp','survey','aa:bb',-55,-55,0,-55,-55,1,1,1,1,0,0,20)")
            execSQL("INSERT INTO positioning_sessions (id,venueId,initialFloorId,dataMode,wifiOnlyEnabled,particleCount,startedAtEpochMillis) VALUES ('pos','v','f','REAL_DEVICE',1,600,21)")
            execSQL("INSERT INTO position_estimates (id,positioningSessionId,sequence,method,floorId,xMetres,yMetres,confidenceScore,floorConfidence,stepCount,timestampEpochMillis,status) VALUES ('est','pos',0,'FUSED','f',2,3,0.8,1,1,22,'TRACKING')")
            close()
        }
        helper.runMigrationsAndValidate(name, 2, true, TurnDatabaseMigrations.FROM_1_TO_2).close()
        val db = TurnDatabase.build(context, name).also { room = it }
        val before = db.backupDao().createExport(100)
        assertEquals(2.123456789, before.referencePoints.single().xMetres, 0.0)
        assertEquals(-55, before.wifiObservations.single().rssiDbm)
        assertEquals("LEGACY", before.surveySessions.single().completionStatus)
        assertNull(before.surveySessions.single().targetSnapshotCount)
        assertEquals("est", before.positionEstimates.single().id)
        assertEquals(1, db.surveyDao().matchingFingerprintsForVenue("v").size)
        db.navigationDao().saveDestinations(listOf(PointOfInterestEntity("lab", "f", "Lab 1", "ROOM", 12.0, 3.0)))
        db.navigationDao().saveNodes(listOf(NavigationNodeEntity("a", "f", 2.0, 3.0), NavigationNodeEntity("b", "f", 12.0, 3.0, "lab")))
        db.navigationDao().saveEdges(listOf(NavigationEdgeEntity("ab", "f", "a", "b", 10.0)))
        db.close()
        val reopened = TurnDatabase.build(context, name).also { room = it }
        val loaded = ProjectRepository(reopened).load(ProjectContext("v", "f"))
        assertEquals(2, loaded.nodes.size)
        assertEquals("Lab 1", loaded.destinations.single().name)
        assertEquals(10.0, loaded.edges.single().distanceMetres, 0.0)
        val after = reopened.backupDao().createExport(100)
        assertEquals(before.wifiObservations, after.wifiObservations)
        assertEquals(before.aggregatedWifiFingerprints, after.aggregatedWifiFingerprints)
        assertEquals(before.positionEstimates, after.positionEstimates)
        assertTrue(after.validationErrors().isEmpty())
        assertEquals(1, after.navigationEdges.size)
        reopened.backupDao().upsertSurveySessions(listOf(before.surveySessions.single().copy(id = "partial", targetSnapshotCount = 8, completionStatus = "PARTIAL")))
        reopened.backupDao().upsertAggregatedWifiFingerprints(listOf(before.aggregatedWifiFingerprints.single().copy(id = "partial-agg", surveySessionId = "partial")))
        assertEquals(1, reopened.surveyDao().matchingFingerprintsForVenue("v").size)
        assertEquals(2, reopened.backupDao().createExport(100).aggregatedWifiFingerprints.size)
        reopened.backupDao().upsertSurveySessions(listOf(before.surveySessions.single().copy(id = "demo", dataMode = "DEMO", completionStatus = "COMPLETED")))
        reopened.backupDao().upsertAggregatedWifiFingerprints(listOf(before.aggregatedWifiFingerprints.single().copy(id = "demo-agg", surveySessionId = "demo")))
        assertEquals(1, reopened.surveyDao().matchingFingerprintsForVenue("v").size)
        assertEquals(3, reopened.backupDao().createExport(100).aggregatedWifiFingerprints.size)
    }
}
