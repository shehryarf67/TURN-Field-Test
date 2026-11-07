package com.turn.fieldtest.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.turn.fieldtest.core.MagneticFeature
import com.turn.fieldtest.core.MetricPoint
import com.turn.fieldtest.data.local.*
import com.turn.fieldtest.data.repository.MagneticRepository
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MagneticPersistenceTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), TurnDatabase::class.java)
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "turn-magnetic-test-${UUID.randomUUID()}.db"
    private var db: TurnDatabase? = null
    @After fun clean() { db?.close(); context.deleteDatabase(name) }

    private fun walk(id: String, status: String = "ACCEPTED") = MagneticWalkEntity(id, "f", "rp-a", "rp-b", 1, 2, 10, 7.0, 30, status,
        deviceModel = "test")

    @Test fun versionThreeMigratesAdditivelyAndWalksMergeIntoTheFloorMap() = runBlocking {
        helper.createDatabase(name, 3).apply {
            execSQL("INSERT INTO venues (id,name,createdAtEpochMillis,updatedAtEpochMillis) VALUES ('v','Home',1,1)")
            execSQL("INSERT INTO floors (id,venueId,name,levelNumber,widthMetres,heightMetres,originXMetres,originYMetres,imageYAxisPointsDown,createdAtEpochMillis,updatedAtEpochMillis) VALUES ('f','v','Ground',0,30,20,0,0,1,1,1)")
            execSQL("INSERT INTO reference_points (id,floorId,name,xMetres,yMetres,isInsideWalkableSpace,updatedAtEpochMillis) VALUES ('rp','f','RP-01',2.5,3,1,1)")
            close()
        }
        helper.runMigrationsAndValidate(name, 4, true, TurnDatabaseMigrations.FROM_3_TO_4).close()
        val database = TurnDatabase.build(context, name).also { db = it }
        assertEquals(2.5, database.floorPlanDao().getReferencePoint("rp")!!.xMetres, 0.0)
        val repository = MagneticRepository(database)
        assertTrue(repository.map("f").isEmpty)

        val point = MetricPoint(5.1, 1.1)
        repository.recordWalk(walk("w1"), listOf(point to MagneticFeature(42.0, 30.0)), now = 3)
        repository.recordWalk(walk("w2"), listOf(point to MagneticFeature(44.0, 32.0)), now = 4)
        repository.recordWalk(walk("w3", "REJECTED"), null, now = 5)
        val map = repository.map("f")
        val cell = map.cells.getValue(map.keyFor(point))
        assertEquals(2, cell.count)
        assertEquals(43.0, cell.meanMagnitude, 1e-9)
        assertEquals(31.0, cell.meanVertical, 1e-9)
        assertEquals(3, repository.walks("f").size)

        // Deleting the floor's venue removes its magnetic data through the floor foreign key.
        database.venueDao().delete(database.venueDao().get("v")!!)
        assertTrue(repository.map("f").isEmpty)
        assertTrue(repository.walks("f").isEmpty())
    }
}
