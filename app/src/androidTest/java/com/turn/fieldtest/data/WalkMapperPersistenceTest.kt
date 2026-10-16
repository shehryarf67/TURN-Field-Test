package com.turn.fieldtest.data

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.turn.fieldtest.data.local.*
import com.turn.fieldtest.data.repository.*
import com.turn.fieldtest.mapper.core.*
import com.turn.fieldtest.mapper.data.ScanRepository
import com.turn.fieldtest.core.NavigationGraph
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WalkMapperPersistenceTest {
    @get:Rule val helper=MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),TurnDatabase::class.java)
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val name="turn-scan-test-${UUID.randomUUID()}.db"
    private var db: TurnDatabase?=null
    private val images=mutableListOf<File>()
    private fun open()=TurnDatabase.build(context,name).also {db=it}
    @After fun clean() {db?.close(); context.deleteDatabase(name); images.forEach {it.delete()} }
    private suspend fun seed(database: TurnDatabase) {
        database.venueDao().upsert(VenueEntity("v","Original venue",createdAtEpochMillis=1,updatedAtEpochMillis=1))
        database.floorPlanDao().upsertFloor(FloorEntity("f","v","Existing calibrated floor",0,30.0,20.0,createdAtEpochMillis=1,updatedAtEpochMillis=1))
        database.floorPlanDao().upsertReferencePoint(ReferencePointEntity("rp","f","RP-01",2.0,3.0,true,updatedAtEpochMillis=1))
        database.surveyDao().upsertSession(SurveySessionEntity("survey","v","f","rp",2.0,3.0,"test","test","35","1",dataMode="REAL_DEVICE",startedAtEpochMillis=1,endedAtEpochMillis=2))
        database.surveyDao().upsertAggregatedFingerprints(listOf(AggregatedWifiFingerprintEntity("fp","rp","survey","aa:bb",-55.0,-55.0,0.0,-55,-55,1,1,1,1.0,calculatedAtEpochMillis=2)))
    }
    private fun document(): ScanDocument {
        fun room(id: String,x: Double)=ScanRoom(id,id,listOf(P2(x,0.0),P2(x+4,0.0),P2(x+4,4.0),P2(x,4.0)).mapIndexed {i,p->ScanPoint("$id:$i",p,"MEASURED","s")},true)
        return ScanDocument(id="scan",venueId="v",floorId="f",name="House test",rooms=listOf(room("bedroom",0.0),room("corridor",4.0)),
            doors=listOf(ScanDoor("door","bedroom:1",P2(4.0,1.5),P2(4.0,2.5),listOf("bedroom","corridor"))),segments=listOf(ScanSegment("s","MANUAL_MEASURED")),
            measurements=listOf(ScanMeasurement(pointA="bedroom:0",pointB="bedroom:1",measured=4.0,estimatedAtCapture=4.0,purpose="VALIDATION")),updatedAt=3)
    }
    @Test fun versionTwoMigratesAndDraftSurvivesReopenWithoutChangingExistingCoordinates()=runBlocking {
        helper.createDatabase(name,2).apply {
            execSQL("INSERT INTO venues (id,name,createdAtEpochMillis,updatedAtEpochMillis) VALUES ('v','Home',1,1)")
            execSQL("INSERT INTO floors (id,venueId,name,levelNumber,widthMetres,heightMetres,originXMetres,originYMetres,imageYAxisPointsDown,createdAtEpochMillis,updatedAtEpochMillis) VALUES ('f','v','Ground',0,30.123456789,20,0,0,1,1,1)")
            execSQL("INSERT INTO reference_points (id,floorId,name,xMetres,yMetres,isInsideWalkableSpace,updatedAtEpochMillis) VALUES ('rp','f','RP-01',2.123456789,3,1,1)")
            close()
        }
        helper.runMigrationsAndValidate(name,3,true,TurnDatabaseMigrations.FROM_2_TO_3).close()
        val database=open(); val repository=ScanRepository(database,context)
        val draft=document().copy(segments=listOf(ScanSegment("s","ARCORE",interrupted=true)))
        repository.save(draft); database.close()
        val reopened=open(); assertEquals(draft,ScanRepository(reopened,context).load(draft.id))
        assertEquals(30.123456789,reopened.floorPlanDao().getFloor("f")!!.widthMetres,0.0)
        assertEquals(2.123456789,reopened.floorPlanDao().getReferencePoint("rp")!!.xMetres,0.0)
        assertTrue(ScanGeometry.issues(draft).any {it.message.contains("Tracking was lost")})
    }
    @Test fun publishedMultiRoomMapReopensInExistingRepositoryWithImageRoutesAndSurveyOwnership()=runBlocking {
        val database=open(); seed(database); val before=database.backupDao().createExport(10)
        val repository=ScanRepository(database,context); repository.save(document())
        val mapped=repository.publish(document()); assertNotEquals("f",mapped)
        val mappedVenue=repository.load("scan")!!.publishedVenueId!!
        assertNotEquals("v",mappedVenue)
        val saved=ProjectRepository(database).load(ProjectContext(mappedVenue,mapped))
        assertEquals(before.floors.single().levelNumber,saved.floor.levelNumber)
        val image=File(Uri.parse(saved.asset!!.contentUri).path!!); images.add(image)
        val size=BitmapFactory.Options().apply {inJustDecodeBounds=true}; BitmapFactory.decodeFile(image.path,size)
        assertEquals(saved.asset!!.pixelWidth,size.outWidth); assertEquals(saved.asset!!.pixelHeight,size.outHeight)
        assertTrue(image.length()>100); assertEquals(2,saved.regions.size); assertEquals(2,saved.destinations.size)
        val floor=saved.metricMap().floors.getValue(mapped); val nodes=saved.nodes.map {it.core()}; val edges=saved.edges.map {it.core()}
        val graph=NavigationGraph(floor,nodes,edges); assertTrue(graph.validationErrors().isEmpty())
        assertNotNull(graph.route(nodes.first().point,nodes.last {it.poiId!=null}.id))
        database.floorPlanDao().upsertReferencePoint(ReferencePointEntity("new-rp",mapped,"RP-new",2.0,2.0,true,updatedAtEpochMillis=11))
        database.close(); val reopened=open(); val after=reopened.backupDao().createExport(12)
        assertEquals(before.aggregatedWifiFingerprints,after.aggregatedWifiFingerprints)
        assertEquals(before.surveySessions,after.surveySessions)
        assertEquals(before.floors.single(),after.floors.first {it.id=="f"})
        assertEquals(mapped,reopened.floorPlanDao().getReferencePoint("new-rp")!!.floorId)
        assertEquals(mapped,ScanRepository(reopened,context).load("scan")!!.publishedFloorId)
        assertEquals(2,ProjectRepository(reopened).load(ProjectContext(mappedVenue,mapped)).regions.size)
        assertTrue(after.validationErrors().isEmpty())
    }
    @Test fun failedPublicationRollsBackDatabaseAndDeletesOnlyItsNewImage()=runBlocking {
        val database=open(); seed(database); val repository=ScanRepository(database,context); repository.save(document())
        val before=database.backupDao().createExport(10)
        val dir=File(context.filesDir,"walk-mapper"); val files=dir.listFiles()?.map {it.name}?.toSet().orEmpty()
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_scan_nodes BEFORE INSERT ON navigation_nodes BEGIN SELECT RAISE(ABORT, 'Injected storage failure'); END")
        try {repository.publish(document()); fail("Expected failure")} catch(e: Exception) {assertTrue(e.message.orEmpty(),e.message.orEmpty().contains("Injected storage failure"))}
        val after=database.backupDao().createExport(10)
        assertEquals(before,after)
        assertEquals(files,dir.listFiles()?.map {it.name}?.toSet().orEmpty())
        assertEquals("DRAFT",repository.load("scan")!!.status)
    }
    @Test fun repeatedPublicationCreatesIndependentFloorAndGraphIdentities()=runBlocking {
        val database=open(); seed(database); val repository=ScanRepository(database,context)
        val a=repository.publish(document()); val va=repository.load("scan")!!.publishedVenueId!!
        val b=repository.publish(document()); val vb=repository.load("scan")!!.publishedVenueId!!; assertNotEquals(a,b); assertNotEquals(va,vb)
        val first=ProjectRepository(database).load(ProjectContext(va,a)); val second=ProjectRepository(database).load(ProjectContext(vb,b))
        listOf(first,second).forEach {images.add(File(Uri.parse(it.asset!!.contentUri).path!!))}
        assertEquals(3,first.nodes.size); assertEquals(3,second.nodes.size)
        assertTrue(first.nodes.map {it.id}.toSet().intersect(second.nodes.map {it.id}.toSet()).isEmpty())
        assertTrue(database.backupDao().createExport(10).validationErrors().isEmpty())
    }
}
