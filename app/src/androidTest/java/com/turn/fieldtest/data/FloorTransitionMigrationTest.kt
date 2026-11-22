package com.turn.fieldtest.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.turn.fieldtest.data.local.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID

/** Plan Step 10: schema 7 adds floor_transition_events additively; rows round-trip through the DAO. */
@RunWith(AndroidJUnit4::class)
class FloorTransitionMigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), TurnDatabase::class.java)
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "turn-floor-events-test-${UUID.randomUUID()}.db"
    private var db: TurnDatabase? = null
    @After fun clean() { db?.close(); context.deleteDatabase(name) }

    private fun SupportSQLiteDatabase.count(sql: String): Int = query(sql).use { it.moveToFirst(); it.getInt(0) }

    @Test fun versionSixMigratesAdditivelyWithCleanForeignKeysAndCascade() {
        helper.createDatabase(name, 6).apply {
            execSQL("INSERT INTO venues (id,name,createdAtEpochMillis,updatedAtEpochMillis) VALUES ('v','Home',1,1)")
            listOf("a" to 0, "b" to 1).forEach { (id, level) ->
                execSQL("INSERT INTO floors (id,venueId,name,levelNumber,widthMetres,heightMetres,originXMetres,originYMetres,imageYAxisPointsDown,createdAtEpochMillis,updatedAtEpochMillis) VALUES ('$id','v','$id',$level,20,20,0,0,1,1,1)")
            }
            execSQL("INSERT INTO vertical_transitions (id,fromFloorId,toFloorId,kind,fromXMetres,fromYMetres,toXMetres,toYMetres,activationRadiusMetres,bidirectional,enabled,label,travelTimeSeconds,accessible,verifiedOnSite) VALUES ('s','a','b','STAIRS',1,1,1,1,2,1,1,'North stairs',20,0,1)")
            execSQL("INSERT INTO positioning_sessions (id,venueId,initialFloorId,dataMode,wifiOnlyEnabled,particleCount,startedAtEpochMillis) VALUES ('p','v','a','REAL_DEVICE',1,500,1)")
            close()
        }
        val migrated = helper.runMigrationsAndValidate(name, 7, true, TurnDatabaseMigrations.FROM_6_TO_7)
        migrated.query("SELECT label, verifiedOnSite, travelTimeSeconds FROM vertical_transitions WHERE id = 's'").use {
            assertTrue(it.moveToFirst())
            assertEquals("North stairs", it.getString(0)); assertEquals(1, it.getInt(1)); assertEquals(20.0, it.getDouble(2), 0.0)
        }
        assertEquals(1, migrated.count("SELECT COUNT(*) FROM positioning_sessions"))
        assertEquals(2, migrated.count("SELECT COUNT(*) FROM floors"))
        assertEquals(0, migrated.count("SELECT COUNT(*) FROM floor_transition_events"))
        assertEquals(4, migrated.count("SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND tbl_name = 'floor_transition_events' AND name LIKE 'index_%'"))

        migrated.execSQL("PRAGMA foreign_keys = ON")
        migrated.execSQL("INSERT INTO floor_transition_events (id,positioningSessionId,sequence,type,fromFloorId,toFloorId,linkId,barometerHealth,timestampEpochMillis,detail) VALUES ('e','p',0,'APPLIED','a','b','s','OK',2,'')")
        migrated.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
        migrated.execSQL("DELETE FROM positioning_sessions WHERE id = 'p'")
        assertEquals(0, migrated.count("SELECT COUNT(*) FROM floor_transition_events"))
        migrated.close()
    }

    @Test fun eventsRoundTripInSequenceAndOutliveDeletedFloorsAndLinks() = runBlocking {
        val database = TurnDatabase.build(context, name).also { db = it }
        database.venueDao().upsert(VenueEntity("v", "Home", createdAtEpochMillis = 1, updatedAtEpochMillis = 1))
        listOf("a" to 0, "b" to 1).forEach { (id, level) ->
            database.floorPlanDao().upsertFloor(FloorEntity(id, "v", id, level, 20.0, 20.0, createdAtEpochMillis = 1, updatedAtEpochMillis = 1))
        }
        database.floorPlanDao().upsertTransition(VerticalTransitionEntity("s", "a", "b", "STAIRS", 1.0, 1.0, 1.0, 1.0))
        database.positioningDao().upsertPositioningSession(PositioningSessionEntity("p", "v", initialFloorId = "a",
            dataMode = "REAL_DEVICE", particleCount = 500, startedAtEpochMillis = 1))
        val config = FloorTransitionEventEntity("FTE-p-0", "p", 0, "CONFIG", barometerHealth = "OK", timestampEpochMillis = 1,
            detail = "barometerPresent=true; config={}")
        val applied = FloorTransitionEventEntity("FTE-p-1", "p", 1, "APPLIED", "a", "b", "s", 0.8, 0.9, 3.5, 1001.2, "OK",
            5_000_000_000, 6, "")
        val dao = database.floorTransitionEventDao()
        dao.insert(listOf(applied, config))
        dao.insert(listOf(applied)) // a retried write is harmless
        assertEquals(listOf(config, applied), dao.forSession("p"))
        assertEquals(listOf(config, applied), database.backupDao().createExport(0).floorTransitionEvents)

        database.openHelper.writableDatabase.execSQL("DELETE FROM floors WHERE id = 'b'")
        val kept = dao.forSession("p")[1]
        assertNull(kept.toFloorId); assertNull(kept.linkId); assertEquals("a", kept.fromFloorId)
    }
}
