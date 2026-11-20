package com.turn.fieldtest.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.turn.fieldtest.core.*
import com.turn.fieldtest.data.local.*
import com.turn.fieldtest.data.repository.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class FloorConnectorPersistenceTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), TurnDatabase::class.java)
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "turn-connector-test-${UUID.randomUUID()}.db"
    private var db: TurnDatabase? = null
    private val images = mutableListOf<File>()
    @After fun clean() { db?.close(); context.deleteDatabase(name); images.forEach { it.delete() } }

    private fun pkg(floor: String) = context.assets.open("mapper/$floor.json").bufferedReader().use { it.readText() }

    /** Installs C-F3 and C-F4 as one building and returns their contexts (F3, F4). */
    private suspend fun building(database: TurnDatabase): Pair<ProjectContext, ProjectContext> {
        val importer = MapperImporter(database, context)
        val f4Text = pkg("C-F4"); val f3Text = pkg("C-F3")
        val f4 = importer.install(MapperPackage.parse(f4Text), f4Text, replaceExisting = false)
        val f3 = importer.install(MapperPackage.parse(f3Text), f3Text, replaceExisting = false, intoVenueId = f4.venueId)
        listOf(f3, f4).forEach { c -> ProjectRepository(database).load(c).asset?.contentUri?.let { images += File(android.net.Uri.parse(it).path!!) } }
        return f3 to f4
    }

    private suspend fun walkablePoint(database: TurnDatabase, c: ProjectContext): MetricPoint {
        val saved = ProjectRepository(database).load(c)
        return saved.destinations.map { MetricPoint(it.xMetres, it.yMetres) }.first { saved.metricMap().floors.getValue(c.floorId).isWalkable(it) }
    }

    @Test fun versionFiveMigratesAdditivelyAndMarksLiftsStepFree() = runBlocking {
        helper.createDatabase(name, 5).apply {
            execSQL("INSERT INTO venues (id,name,createdAtEpochMillis,updatedAtEpochMillis) VALUES ('v','Home',1,1)")
            listOf("a" to 0, "b" to 1).forEach { (id, level) ->
                execSQL("INSERT INTO floors (id,venueId,name,levelNumber,widthMetres,heightMetres,originXMetres,originYMetres,imageYAxisPointsDown,createdAtEpochMillis,updatedAtEpochMillis) VALUES ('$id','v','$id',$level,20,20,0,0,1,1,1)")
            }
            execSQL("INSERT INTO vertical_transitions (id,fromFloorId,toFloorId,kind,fromXMetres,fromYMetres,toXMetres,toYMetres,activationRadiusMetres,bidirectional,enabled) VALUES ('s','a','b','STAIRS',1,1,1,1,2,1,1)")
            execSQL("INSERT INTO vertical_transitions (id,fromFloorId,toFloorId,kind,fromXMetres,fromYMetres,toXMetres,toYMetres,activationRadiusMetres,bidirectional,enabled) VALUES ('l','a','b','ELEVATOR',5,5,5,5,2,1,1)")
            close()
        }
        helper.runMigrationsAndValidate(name, 6, true, TurnDatabaseMigrations.FROM_5_TO_6).close()
        val database = TurnDatabase.build(context, name).also { db = it }
        val stairs = database.floorPlanDao().transition("s")!!
        val lift = database.floorPlanDao().transition("l")!!
        assertFalse(stairs.accessible); assertTrue(lift.accessible)
        assertEquals("", stairs.label); assertEquals(15.0, stairs.travelTimeSeconds, 0.0); assertFalse(lift.verifiedOnSite)
        // Legacy kinds still show as a known connector.
        assertEquals(ConnectorKind.LIFT, ConnectorRepository(database).connectors("v").first { it.entity.id == "l" }.kind)
    }

    @Test fun linksAreValidatedSavedEditedVerifiedAndRemoved() = runBlocking {
        val database = TurnDatabase.build(context, name).also { db = it }
        val (f3, f4) = building(database)
        val repository = ConnectorRepository(database)
        val a = walkablePoint(database, f3); val b = walkablePoint(database, f4)
        val draft = ConnectorDraft(ConnectorKind.LIFT, f3.floorId, a, f4.floorId, b, " Main lift ", 25.0)

        val id = repository.save(draft, bidirectional = true, accessible = true, verifiedOnSite = false)
        val saved = repository.connectors(f3.venueId).single()
        assertEquals(id, saved.entity.id)
        assertEquals("Main lift", saved.entity.label)
        assertEquals(3 to 4, saved.fromLevel to saved.toLevel)
        assertTrue(saved.entity.accessible); assertFalse(saved.entity.verifiedOnSite)

        repository.setVerified(id, true)
        assertTrue(repository.connectors(f3.venueId).single().entity.verifiedOnSite)
        repository.save(draft.copy(label = "Lift A", travelTimeSeconds = 30.0), true, true, true, replacingId = id)
        assertEquals(listOf("Lift A" to 30.0), repository.connectors(f3.venueId).map { it.entity.label to it.entity.travelTimeSeconds })

        // A point outside walkable space, a same-floor link and a link to another building are refused.
        assertTrue(runCatching { repository.save(draft.copy(from = MetricPoint(-5.0, -5.0)), true, true, false) }
            .exceptionOrNull()?.message.orEmpty().contains("outside walkable space"))
        assertTrue(runCatching { repository.save(draft.copy(toFloorId = f3.floorId, to = a), true, true, false) }.isFailure)
        val other = ProjectRepository(database).create(ProjectInput("Library", "Ground"), now = 1)
        assertTrue(runCatching { repository.save(draft.copy(toFloorId = other.floorId), true, true, false) }
            .exceptionOrNull()?.message.orEmpty().contains("one building"))
        // The same two spots cannot be linked twice, even drawn the other way round; editing the link itself is fine.
        assertTrue(runCatching { repository.save(draft.copy(label = "Second lift"), true, true, false) }
            .exceptionOrNull()?.message.orEmpty().contains("already joins"))
        assertTrue(runCatching { repository.save(ConnectorDraft(ConnectorKind.LIFT, f4.floorId, b, f3.floorId, a, "Reverse", 25.0), true, true, false) }.isFailure)
        repository.save(draft.copy(label = "Lift A"), true, true, true, replacingId = id)
        assertEquals(1, repository.connectors(f3.venueId).size)

        repository.delete(id)
        assertTrue(repository.connectors(f3.venueId).isEmpty())
        repository.delete(id) // removing twice is harmless

        // An up and a down escalator side by side are two one-way links, not a duplicate.
        val up = ConnectorDraft(ConnectorKind.ESCALATOR, f3.floorId, a, f4.floorId, b, "Escalator up", 12.0)
        repository.save(up, bidirectional = false, accessible = false, verifiedOnSite = false)
        repository.save(ConnectorDraft(ConnectorKind.ESCALATOR, f4.floorId, b, f3.floorId, a, "Escalator down", 12.0), false, false, false)
        assertEquals(2, repository.connectors(f3.venueId).size)
        assertTrue(runCatching { repository.save(up.copy(label = "Escalator up again"), false, false, false) }.isFailure)
    }

    @Test fun stairLandingsOnAdjacentCBlockFloorsAreSuggestedUntilLinked() = runBlocking {
        val database = TurnDatabase.build(context, name).also { db = it }
        val (f3, _) = building(database)
        val repository = ConnectorRepository(database)
        val suggestions = repository.suggestions(f3.venueId)
        assertTrue("C-F3 and C-F4 share numbered stair landings", suggestions.isNotEmpty())
        suggestions.forEach {
            assertEquals(ConnectorKind.STAIRS, it.kind)
            assertTrue(it.label.startsWith("Stairs "))
            assertEquals(f3.floorId, it.fromFloorId) // lower floor first
        }
        // Each suggestion must be saveable as-is (both landings in walkable space), and is then no longer suggested.
        // Renamed during review, it is still the same staircase: no longer suggested, and not saveable twice.
        val first = suggestions.first()
        repository.save(first.copy(label = "North stairs"), bidirectional = true, accessible = false, verifiedOnSite = false)
        val after = repository.suggestions(f3.venueId)
        assertEquals(suggestions.size - 1, after.size)
        assertTrue(after.none { it.label == first.label })
        assertTrue(runCatching { repository.save(first, true, false, false) }.isFailure)
    }
}
