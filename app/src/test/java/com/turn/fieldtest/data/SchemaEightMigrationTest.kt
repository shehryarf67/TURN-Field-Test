package com.turn.fieldtest.data

import androidx.sqlite.db.SupportSQLiteDatabase
import com.turn.fieldtest.data.local.TurnDatabase
import com.turn.fieldtest.data.local.TurnDatabaseMigrations
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * JVM check of the additive schema 7 -> 8 change (the device test FloorGeometryMigrationTest runs the migration on
 * SQLite): the exported schemas differ only by two nullable floor columns, and the migration adds exactly those.
 */
class SchemaEightMigrationTest {
    private fun schema(version: Int): JsonObject {
        val file = listOf("schemas", "app/schemas").map { File(it, "com.turn.fieldtest.data.local.TurnDatabase/$version.json") }.first { it.exists() }
        return Json.parseToJsonElement(file.readText()).jsonObject.getValue("database").jsonObject
    }

    private fun entities(version: Int): Map<String, JsonObject> =
        schema(version).getValue("entities").jsonArray.associate { it.jsonObject.getValue("tableName").jsonPrimitive.content to it.jsonObject }

    private fun fields(entity: JsonObject): Map<String, Pair<String, Boolean>> = (entity.getValue("fields") as JsonArray).associate {
        val f = it.jsonObject
        f.getValue("columnName").jsonPrimitive.content to (f.getValue("affinity").jsonPrimitive.content to f.getValue("notNull").jsonPrimitive.boolean)
    }

    @Test
    fun schemaEightOnlyAddsNullableFloorColumns() {
        assertEquals(8, TurnDatabase.SCHEMA_VERSION)
        val seven = entities(7); val eight = entities(8)
        assertEquals(seven.keys, eight.keys, "no table added or removed")
        seven.keys.filter { it != "floors" }.forEach { table ->
            assertEquals(seven.getValue(table), eight.getValue(table), "$table is unchanged")
        }
        val before = fields(seven.getValue("floors")); val after = fields(eight.getValue("floors"))
        before.forEach { (column, type) -> assertEquals(type, after[column], "floors.$column keeps its type") }
        assertEquals(mapOf("elevationMetres" to ("REAL" to false), "frameAlignmentJson" to ("TEXT" to false)), after - before.keys)
        assertEquals(seven.getValue("floors").getValue("indices"), eight.getValue("floors").getValue("indices"))
        assertEquals(seven.getValue("floors").getValue("foreignKeys"), eight.getValue("floors").getValue("foreignKeys"))
    }

    @Test
    fun migrationSevenToEightAddsExactlyTheExportedColumns() {
        val statements = mutableListOf<String>()
        val db = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SupportSQLiteDatabase::class.java)) { _, method, args ->
            if (method.name == "execSQL") statements += args!![0] as String else error("Unexpected call ${method.name}")
            null
        } as SupportSQLiteDatabase
        TurnDatabaseMigrations.FROM_7_TO_8.migrate(db)
        assertEquals(listOf("ALTER TABLE `floors` ADD COLUMN `elevationMetres` REAL", "ALTER TABLE `floors` ADD COLUMN `frameAlignmentJson` TEXT"), statements)
        assertTrue(statements.none { it.contains("NOT NULL") || it.contains("DROP") || it.contains("UPDATE") })
        assertEquals(7, TurnDatabaseMigrations.FROM_7_TO_8.startVersion)
        assertEquals(8, TurnDatabaseMigrations.FROM_7_TO_8.endVersion)
        assertTrue(TurnDatabaseMigrations.ALL.last() === TurnDatabaseMigrations.FROM_7_TO_8)
        assertEquals((1..7).toList(), TurnDatabaseMigrations.ALL.map { it.startVersion }, "a migration from every older version")
    }
}
