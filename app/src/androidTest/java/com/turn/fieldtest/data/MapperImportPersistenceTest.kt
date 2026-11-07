package com.turn.fieldtest.data
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.turn.fieldtest.data.local.*
import com.turn.fieldtest.data.repository.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID
import java.io.File
@RunWith(AndroidJUnit4::class)
class MapperImportPersistenceTest {
 @Test fun existingFloorGetsAllRoomLabelsWithoutDeletingFingerprintsOrChangingGraph()=runBlocking {
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  val name="mapper-room-upgrade-${UUID.randomUUID()}.db";var db=TurnDatabase.build(context,name);var image:File?=null
  try {
   val text=context.assets.open("mapper/C-F5.json").bufferedReader().use {it.readText()}
   val parsed=MapperPackage.parse(text)
   val imported=MapperImporter(db,context).install(parsed,text,false)
   val originalPlaces=db.navigationDao().destinations(imported.floorId)
   val originalNodes=db.navigationDao().nodes(imported.floorId);val originalEdges=db.navigationDao().edges(imported.floorId)
   val rp=db.floorPlanDao().observeReferencePoints(imported.floorId).first().first()
   db.surveyDao().upsertSession(SurveySessionEntity("kept-session",imported.venueId,imported.floorId,rp.id,rp.xMetres,rp.yMetres,"test","test","35","1",dataMode="REAL_DEVICE",startedAtEpochMillis=1))
   db.surveyDao().upsertAggregatedFingerprints(listOf(AggregatedWifiFingerprintEntity("kept-fp",rp.id,"kept-session","aa:bb",-55.0,-55.0,0.0,-55,-55,1,1,1,1.0,calculatedAtEpochMillis=2)))
   val upgraded=ProjectRepository(db).load(imported)
   image=File(android.net.Uri.parse(upgraded.asset!!.contentUri).path!!)
   assertTrue(upgraded.destinations.size>originalPlaces.size)
   assertEquals(parsed.rooms.size,upgraded.destinationAreas().size)
   assertTrue(upgraded.destinations.containsAll(originalPlaces))
   assertEquals(originalNodes,upgraded.nodes);assertEquals(originalEdges,upgraded.edges)
   assertTrue(upgraded.points.none {it.name.startsWith("Anchor-")})
   assertTrue(upgraded.points.single {it.id==rp.id}.name.contains("landing"))
   db.floorPlanDao().upsertReferencePoint(upgraded.points.single {it.id==rp.id}.copy(name="My surveyed stair landing"))
   val room=upgraded.destinations.first {it.name.contains("C505")}
   db.navigationDao().saveDestinations(listOf(room.copy(name="Renamed office")))
   val expectedCount=upgraded.destinations.size
   db.close();db=TurnDatabase.build(context,name)
   val restored=ProjectRepository(db).load(imported)
   assertEquals(expectedCount,restored.destinations.size)
   assertEquals("Renamed office",restored.destinations.single {it.id==room.id}.name)
   assertEquals("My surveyed stair landing",restored.points.single {it.id==rp.id}.name)
   assertEquals(parsed.rooms.size,restored.destinationAreas().size)
   assertEquals(1,ProjectRepository(db).observeProjects().first().single().fingerprintPointCount)
   assertEquals("kept-session",db.surveyDao().observeSessions(imported.venueId).first().single().id)
  } finally {db.close();context.deleteDatabase(name);image?.delete()}
 }
 @Test fun replacesOldSurveyAndReopensMetricMapWithoutFingerprints()=runBlocking {
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  val name="mapper-import-${UUID.randomUUID()}.db";var db=TurnDatabase.build(context,name);var image:File?=null
  try {
   db.venueDao().upsert(VenueEntity("old","Old",createdAtEpochMillis=1,updatedAtEpochMillis=1))
   db.floorPlanDao().upsertFloor(FloorEntity("oldfloor","old","Old",0,85.0,50.8,createdAtEpochMillis=1,updatedAtEpochMillis=1))
   db.floorPlanDao().upsertReferencePoint(ReferencePointEntity("rp","oldfloor","RP-01",2.0,3.0,true,updatedAtEpochMillis=1))
   db.surveyDao().upsertSession(SurveySessionEntity("s","old","oldfloor","rp",2.0,3.0,"test","test","35","1",dataMode="REAL_DEVICE",startedAtEpochMillis=1))
   db.surveyDao().upsertAggregatedFingerprints(listOf(AggregatedWifiFingerprintEntity("fp","rp","s","aa:bb",-55.0,-55.0,0.0,-55,-55,1,1,1,1.0,calculatedAtEpochMillis=2)))
   // Evaluation samples RESTRICT deleting their checkpoint and floor; replacing must still succeed.
   db.evaluationDao().upsertRun(TestRunEntity("run","old",null,"anon","m","d","35","1",startedAtEpochMillis=1))
   db.evaluationDao().upsertCheckpoint(TestCheckpointEntity("cp","old","oldfloor","CP-01",4.0,5.0))
   db.evaluationDao().upsertSample(TestSampleEntity("sample","run","cp","oldfloor",4.0,5.0,stepCount=0,wifiAccessPointCount=0,timestampEpochMillis=3))
   val text=context.assets.open("mapper/C-F4.json").bufferedReader().use {it.readText()}
   val parsed=MapperPackage.parse(text)
   val invalid=parsed.copy(places=parsed.places+parsed.places.first().copy(id="duplicate-node-destination"))
   assertTrue(runCatching {MapperImporter(db,context).install(invalid,text,true)}.isFailure)
   assertNotNull(db.venueDao().get("old"))
   val imported=MapperImporter(db,context).install(parsed,text,true)
   val loaded=ProjectRepository(db).load(imported);image=File(android.net.Uri.parse(loaded.asset!!.contentUri).path!!)
   assertEquals(56.73,loaded.floor.widthMetres,0.0);assertEquals(91,loaded.nodes.size);assertEquals(15,loaded.destinations.size)
   assertNull(db.venueDao().get("old"));assertTrue(db.surveyDao().observeSessions("old").first().isEmpty())
   assertFalse(loaded.hasMeasurements);assertTrue(image!!.isFile)
   db.close();db=TurnDatabase.build(context,name)
   assertEquals(15,ProjectRepository(db).load(imported).destinations.size)
   val restored=ProjectRepository(db).load(imported)
   val areas=restored.destinationAreas()
   val graph=com.turn.fieldtest.core.NavigationGraph(restored.metricMap().floors.getValue(imported.floorId),restored.nodes.map {it.core()},restored.edges.map {it.core()})
   assertTrue(graph.validationErrors().isEmpty())
   restored.points.forEach { rp -> restored.nodes.filter {it.poiId!=null}.forEach { destination ->
    assertNotNull("Saved anchor ${rp.name} must reach ${destination.id}",graph.route(com.turn.fieldtest.core.MetricPoint(rp.xMetres.toFloat().toDouble(),rp.yMetres.toFloat().toDouble()),destination.id))
   } }
   assertTrue(areas.isNotEmpty())
   assertTrue(areas.all { area -> restored.destinations.any {it.id==area.destinationId} })
   assertEquals(areas.map {it.destinationId to it.polygon.vertices},restored.copy(destinations=restored.destinations.map {it.copy(name="Renamed ${it.id}")}).destinationAreas().map {it.destinationId to it.polygon.vertices})
   assertTrue(restored.copy(floor=restored.floor.copy(widthMetres=80.0)).destinationAreas().isEmpty())
   assertEquals(0,ProjectRepository(db).observeProjects().first().single().fingerprintPointCount)
  } finally {db.close();context.deleteDatabase(name);image?.delete()}
 }
}
