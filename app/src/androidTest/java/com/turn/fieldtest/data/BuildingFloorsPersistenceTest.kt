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
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BuildingFloorsPersistenceTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), TurnDatabase::class.java)
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "turn-building-test-${UUID.randomUUID()}.db"
    private var db: TurnDatabase? = null
    private val images = mutableListOf<File>()
    @After fun clean() { db?.close(); context.deleteDatabase(name); images.forEach { it.delete() } }

    private fun pkg(floor: String) = context.assets.open("mapper/$floor.json").bufferedReader().use { it.readText() }

    @Test fun versionFourMigratesAdditivelyAndKeepsFloorsAndMagneticData() = runBlocking {
        helper.createDatabase(name, 4).apply {
            execSQL("INSERT INTO venues (id,name,createdAtEpochMillis,updatedAtEpochMillis) VALUES ('v','Home',1,1)")
            execSQL("INSERT INTO floors (id,venueId,name,levelNumber,widthMetres,heightMetres,originXMetres,originYMetres,imageYAxisPointsDown,createdAtEpochMillis,updatedAtEpochMillis) VALUES ('f','v','Ground',0,30.5,20,0,0,1,1,1)")
            execSQL("INSERT INTO magnetic_cells (floorId,cellX,cellY,cellSizeMetres,count,meanMagnitude,m2Magnitude,meanVertical,m2Vertical,updatedAtEpochMillis) VALUES ('f',1,2,0.6,3,44.0,1.0,30.0,1.0,5)")
            close()
        }
        helper.runMigrationsAndValidate(name, 5, true, TurnDatabaseMigrations.FROM_4_TO_5).close()
        val database = TurnDatabase.build(context, name).also { db = it }
        val floor = database.floorPlanDao().getFloor("f")!!
        assertEquals(30.5, floor.widthMetres, 0.0)
        assertNull(floor.mapperSourceJson)
        assertEquals(1, database.magneticDao().cells("f").size)
    }

    @Test fun migrationMovesLegacyMapperSourceOntoItsOnlyFloorAndNowhereElse() = runBlocking {
        val legacy = "TURN-Mapper 0.3.0\nprovenance line\n" + pkg("C-F4")
        helper.createDatabase(name, 4).apply {
            execSQL("INSERT INTO venues (id,name,notes,createdAtEpochMillis,updatedAtEpochMillis) VALUES ('m','Mapper building',?,1,1)", arrayOf(legacy))
            execSQL("INSERT INTO floors (id,venueId,name,levelNumber,widthMetres,heightMetres,originXMetres,originYMetres,imageYAxisPointsDown,createdAtEpochMillis,updatedAtEpochMillis) VALUES ('mf','m','C-F4',4,56.73,24.819,0,0,1,1,1)")
            // A plain venue with notes that are not a mapper source is left alone.
            execSQL("INSERT INTO venues (id,name,notes,createdAtEpochMillis,updatedAtEpochMillis) VALUES ('p','Plain','Keep these notes',1,1)")
            execSQL("INSERT INTO floors (id,venueId,name,levelNumber,widthMetres,heightMetres,originXMetres,originYMetres,imageYAxisPointsDown,createdAtEpochMillis,updatedAtEpochMillis) VALUES ('pf','p','Ground',0,20,20,0,0,1,1,1)")
            close()
        }
        helper.runMigrationsAndValidate(name, 5, true, TurnDatabaseMigrations.FROM_4_TO_5).close()
        val database = TurnDatabase.build(context, name).also { db = it }
        assertEquals(pkg("C-F4"), database.floorPlanDao().getFloor("mf")!!.mapperSourceJson)
        assertNull(database.floorPlanDao().getFloor("pf")!!.mapperSourceJson)
        val repository = ProjectRepository(database)
        assertEquals(MapperPackage.parse(pkg("C-F4")).rooms.size, repository.load(ProjectContext("m", "mf")).destinationAreas().size)
        // A floor added later with the same footprint must not inherit C-F4's rooms from the building notes.
        val twin = repository.addFloor("m", "C-F4 copy", 5, now = 9)
        database.floorPlanDao().upsertFloor(database.floorPlanDao().getFloor(twin.floorId)!!.copy(widthMetres = 56.73, heightMetres = 24.819))
        val loadedTwin = repository.load(twin)
        assertTrue(loadedTwin.destinationAreas().isEmpty())
        assertTrue(loadedTwin.destinations.isEmpty())
    }

    @Test fun levelsAreUniquePerBuildingAndScaleLocksArePerFloor() = runBlocking {
        val database = TurnDatabase.build(context, name).also { db = it }
        val repository = ProjectRepository(database)
        val ground = repository.create(ProjectInput("Library", "Ground"), now = 1)
        val clash = runCatching { repository.addFloor(ground.venueId, "Mezzanine", 0, now = 2) }
        assertTrue(clash.exceptionOrNull()?.message.orEmpty().contains("level 0"))
        assertEquals(1, repository.observeProjects().first().count { it.venueId == ground.venueId })
        // A positioning session on the ground floor must not lock the scale of a newly added floor.
        database.positioningDao().upsertPositioningSession(PositioningSessionEntity("ps", ground.venueId, initialFloorId = ground.floorId,
            dataMode = "REAL_DEVICE", particleCount = 100, startedAtEpochMillis = 3))
        val first = repository.addFloor(ground.venueId, "First", 1, now = 4)
        assertTrue(database.floorPlanDao().hasMeasurements(ground.floorId, ground.venueId))
        assertFalse(database.floorPlanDao().hasMeasurements(first.floorId, first.venueId))
    }

    @Test fun mapperFloorJoiningABuildingRespectsLevelsAndRenamesAFloorNamedBuilding() = runBlocking {
        val database = TurnDatabase.build(context, name).also { db = it }
        val importer = MapperImporter(database, context)
        val f4Text = pkg("C-F4")
        val f4 = importer.install(MapperPackage.parse(f4Text), f4Text, replaceExisting = false)
        assertTrue(database.venueDao().get(f4.venueId)!!.name.endsWith(" - C-F4"))
        val f3Text = pkg("C-F3")
        importer.install(MapperPackage.parse(f3Text), f3Text, false, intoVenueId = f4.venueId)
        assertEquals("FAST-NUCES Islamabad", database.venueDao().get(f4.venueId)!!.name)
        // Another package at an occupied level is refused with a clear message, leaving no partial floor.
        val clash = MapperPackage.parse(f3Text).copy(floorId = "C-F3b")
        val failure = runCatching { importer.install(clash, f3Text, false, intoVenueId = f4.venueId) }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("level 3"))
        assertEquals(2, ProjectRepository(database).observeProjects().first().count { it.venueId == f4.venueId })
        ProjectRepository(database).observeProjects().first().filter { it.venueId == f4.venueId }.forEach {
            ProjectRepository(database).load(it.context).asset?.contentUri?.let { u -> images += File(android.net.Uri.parse(u).path!!) }
        }
    }

    @Test fun oneBuildingHoldsSeveralMapperFloorsEachWithItsOwnRooms() = runBlocking {
        val database = TurnDatabase.build(context, name).also { db = it }
        val importer = MapperImporter(database, context)
        val f4Text = pkg("C-F4"); val f3Text = pkg("C-F3")
        val f4 = importer.install(MapperPackage.parse(f4Text), f4Text, replaceExisting = false)
        val f3 = importer.install(MapperPackage.parse(f3Text), f3Text, replaceExisting = false, intoVenueId = f4.venueId)
        assertEquals(f4.venueId, f3.venueId)
        assertNotEquals(f4.floorId, f3.floorId)

        val repository = ProjectRepository(database)
        val rows = repository.observeProjects().first().filter { it.venueId == f4.venueId }
        assertEquals(listOf("C-F3" to 3, "C-F4" to 4), rows.map { it.floorName to it.floorLevel })

        // Each floor reads its own mapper source: room outlines must not leak between floors.
        val loadedF4 = repository.load(f4).also { images += File(android.net.Uri.parse(it.asset!!.contentUri).path!!) }
        val loadedF3 = repository.load(f3).also { images += File(android.net.Uri.parse(it.asset!!.contentUri).path!!) }
        assertEquals(MapperPackage.parse(f4Text).rooms.size, loadedF4.destinationAreas().size)
        assertEquals(MapperPackage.parse(f3Text).rooms.size, loadedF3.destinationAreas().size)
        assertTrue(loadedF4.destinationAreas().map { it.destinationId }.none { id -> loadedF3.destinations.any { it.id == id } })

        // The same floor cannot be added twice, and adding never wipes the building.
        assertTrue(runCatching { importer.install(MapperPackage.parse(f4Text), f4Text, false, intoVenueId = f4.venueId) }.isFailure)
        assertTrue(runCatching { importer.install(MapperPackage.parse(f3Text), f3Text, true, intoVenueId = f4.venueId) }.isFailure)
        assertEquals(2, repository.observeProjects().first().count { it.venueId == f4.venueId })
    }

    @Test fun emptyFloorsAreAddedToExistingBuildingsWithUniqueNames() = runBlocking {
        val database = TurnDatabase.build(context, name).also { db = it }
        val repository = ProjectRepository(database)
        val ground = repository.create(ProjectInput("Library", "Ground"), now = 1)
        val first = repository.addFloor(ground.venueId, " First ", 1, now = 2)
        assertEquals(ground.venueId, first.venueId)
        assertEquals("First", database.floorPlanDao().getFloor(first.floorId)!!.name)
        assertTrue(runCatching { repository.addFloor(ground.venueId, "first", 2, now = 3) }.isFailure)
        assertTrue(runCatching { repository.addFloor("missing-venue", "Roof", 5, now = 4) }.isFailure)
        val rows = repository.observeProjects().first()
        assertEquals(listOf(0, 1), rows.filter { it.venueId == ground.venueId }.map { it.floorLevel })
        // An empty added floor never borrows another floor's mapper rooms.
        assertTrue(repository.load(first).destinationAreas().isEmpty())
    }
}
