package com.turn.fieldtest.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.turn.fieldtest.core.FloorAlignment
import com.turn.fieldtest.core.FloorAlignmentSolver
import com.turn.fieldtest.core.MetricPoint
import com.turn.fieldtest.data.local.*
import com.turn.fieldtest.data.repository.ProjectRepository
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID

/** Schema 8 adds floors.elevationMetres and floors.frameAlignmentJson additively; old rows keep NULL. */
@RunWith(AndroidJUnit4::class)
class FloorGeometryMigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), TurnDatabase::class.java)
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "turn-floor-geometry-test-${UUID.randomUUID()}.db"
    private var db: TurnDatabase? = null
    @After fun clean() { db?.close(); context.deleteDatabase(name) }

    @Test fun versionSevenMigratesAdditivelyAndKeepsEveryRow() {
        helper.createDatabase(name, 7).apply {
            execSQL("INSERT INTO venues (id,name,createdAtEpochMillis,updatedAtEpochMillis) VALUES ('v','Home',1,1)")
            listOf("g" to 0, "f1" to 1).forEach { (id, level) ->
                execSQL("INSERT INTO floors (id,venueId,name,levelNumber,widthMetres,heightMetres,originXMetres,originYMetres,imageYAxisPointsDown,createdAtEpochMillis,updatedAtEpochMillis,mapperSourceJson) VALUES ('$id','v','$id',$level,10,8,0,0,1,1,1,'{}')")
            }
            execSQL("INSERT INTO vertical_transitions (id,fromFloorId,toFloorId,kind,fromXMetres,fromYMetres,toXMetres,toYMetres,activationRadiusMetres,bidirectional,enabled,label,travelTimeSeconds,accessible,verifiedOnSite) VALUES ('s','g','f1','STAIRS',8,4,2,4,2,1,1,'Stairs',12,0,1)")
            close()
        }
        val migrated = helper.runMigrationsAndValidate(name, 8, true, TurnDatabaseMigrations.FROM_7_TO_8)
        migrated.query("SELECT id, elevationMetres, frameAlignmentJson, mapperSourceJson, levelNumber FROM floors ORDER BY levelNumber").use {
            assertEquals(2, it.count)
            while (it.moveToNext()) {
                assertTrue(it.isNull(1)); assertTrue(it.isNull(2)); assertEquals("{}", it.getString(3))
            }
        }
        migrated.query("SELECT label, verifiedOnSite FROM vertical_transitions WHERE id = 's'").use {
            assertTrue(it.moveToFirst()); assertEquals("Stairs", it.getString(0)); assertEquals(1, it.getInt(1))
        }
        migrated.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
        migrated.close()
    }

    @Test fun elevationsAndAlignmentsRoundTripThroughTheRepository() = runBlocking {
        val database = TurnDatabase.build(context, name).also { db = it }
        database.venueDao().upsert(VenueEntity("v", "Home", createdAtEpochMillis = 1, updatedAtEpochMillis = 1))
        listOf("g" to 0, "f1" to 1).forEach { (id, level) ->
            database.floorPlanDao().upsertFloor(FloorEntity(id, "v", id, level, 10.0, 8.0, createdAtEpochMillis = 1, updatedAtEpochMillis = 1))
        }
        val projects = ProjectRepository(database)
        projects.setFloorElevations("v", mapOf("g" to 0.0, "f1" to 2.7), 2)
        assertEquals(2.7, database.floorPlanDao().getFloor("f1")!!.elevationMetres!!, 1e-12)
        assertTrue(runCatching { projects.setFloorElevations("v", mapOf("g" to 0.0, "f1" to 0.5), 3) }.isFailure)
        assertEquals(2.7, database.floorPlanDao().getFloor("f1")!!.elevationMetres!!, 1e-12)
        projects.setFloorElevations("v", mapOf("g" to null, "f1" to null), 4)
        assertNull(database.floorPlanDao().getFloor("f1")!!.elevationMetres)

        val alignment = FloorAlignmentSolver.solve("g", listOf(MetricPoint(1.0, 1.0), MetricPoint(1.0, 3.0)),
            listOf(MetricPoint(5.0, 1.0), MetricPoint(7.0, 1.0))).alignment
        projects.setFloorAlignment("f1", alignment, 5)
        assertEquals(alignment, FloorAlignment.fromJsonOrNull(database.floorPlanDao().getFloor("f1")!!.frameAlignmentJson))
        // Aligning the other way round replaces the pair's alignment instead of keeping two.
        projects.setFloorAlignment("g", FloorAlignmentSolver.solve("f1", listOf(MetricPoint(5.0, 1.0), MetricPoint(7.0, 1.0)),
            listOf(MetricPoint(1.0, 1.0), MetricPoint(1.0, 3.0))).alignment, 6)
        assertNull(database.floorPlanDao().getFloor("f1")!!.frameAlignmentJson)
        assertNotNull(database.floorPlanDao().getFloor("g")!!.frameAlignmentJson)
        projects.setFloorAlignment("g", null, 7)
        assertNull(database.floorPlanDao().getFloor("g")!!.frameAlignmentJson)
    }
}
