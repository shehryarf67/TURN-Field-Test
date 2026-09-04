package com.turn.fieldtest.platform.storage

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream

interface TransferCodec<T> {
    fun encode(value: T, output: OutputStream)
    fun decode(input: InputStream): T
}

@OptIn(ExperimentalSerializationApi::class)
class KotlinxJsonTransferCodec<T>(
    private val serializer: KSerializer<T>,
    private val json: Json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
    },
) : TransferCodec<T> {
    override fun encode(value: T, output: OutputStream) {
        output.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write(json.encodeToString(serializer, value))
        }
    }

    override fun decode(input: InputStream): T = input.bufferedReader(Charsets.UTF_8).use { reader ->
        json.decodeFromString(serializer, reader.readText())
    }
}

sealed interface TransferResult<out T> {
    data class Success<T>(val value: T) : TransferResult<T>
    data class Failure(val message: String, val cause: Throwable? = null) : TransferResult<Nothing>
}

/**
 * Storage Access Framework bridge. Callers obtain a content Uri via ACTION_CREATE_DOCUMENT or
 * ACTION_OPEN_DOCUMENT; this class never assumes a filesystem path and is Windows/emulator safe.
 */
class SafTransferService(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val resolver: ContentResolver = context.applicationContext.contentResolver

    suspend fun <T> export(uri: Uri, value: T, codec: TransferCodec<T>): TransferResult<Unit> =
        withContext(ioDispatcher) {
            runCatching {
                val output = resolver.openOutputStream(uri, "wt")
                    ?: error("The selected document cannot be opened for writing")
                output.use { codec.encode(value, it) }
            }.fold(
                onSuccess = { TransferResult.Success(Unit) },
                onFailure = { TransferResult.Failure(it.message ?: "Export failed", it) },
            )
        }

    suspend fun <T> import(uri: Uri, codec: TransferCodec<T>): TransferResult<T> =
        withContext(ioDispatcher) {
            runCatching {
                val input = resolver.openInputStream(uri)
                    ?: error("The selected document cannot be opened for reading")
                input.use(codec::decode)
            }.fold(
                onSuccess = { TransferResult.Success(it) },
                onFailure = { TransferResult.Failure(it.message ?: "Import failed", it) },
            )
        }

    fun retainDocumentPermission(uri: Uri, intentFlags: Int): TransferResult<Unit> = runCatching {
        val allowed = intentFlags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        resolver.takePersistableUriPermission(uri, allowed)
    }.fold(
        onSuccess = { TransferResult.Success(Unit) },
        onFailure = { TransferResult.Failure(it.message ?: "Could not retain document permission", it) },
    )
}

data class CsvTable(
    val headers: List<String>,
    val rows: List<List<String?>>,
) {
    init {
        require(headers.isNotEmpty()) { "CSV headers cannot be empty" }
        require(rows.all { it.size == headers.size }) { "Every CSV row must match the header width" }
    }
}

object CsvTableCodec : TransferCodec<CsvTable> {
    override fun encode(value: CsvTable, output: OutputStream) {
        output.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.appendLine(value.headers.joinToString(",", transform = ::escape))
            value.rows.forEach { row ->
                writer.appendLine(row.joinToString(",") { escape(it.orEmpty()) })
            }
        }
    }

    override fun decode(input: InputStream): CsvTable {
        val records = parse(input.bufferedReader(Charsets.UTF_8).use { it.readText() })
        require(records.isNotEmpty()) { "CSV is empty" }
        val width = records.first().size
        require(width > 0) { "CSV header is empty" }
        require(records.drop(1).all { it.size == width }) { "CSV contains rows with different column counts" }
        return CsvTable(records.first(), records.drop(1))
    }

    private fun escape(value: String): String {
        val escaped = value.replace("\"", "\"\"")
        return if (value.any { it == ',' || it == '\"' || it == '\r' || it == '\n' }) "\"$escaped\"" else escaped
    }

    internal fun parse(text: String): List<List<String>> {
        if (text.isEmpty()) return emptyList()
        val records = mutableListOf<MutableList<String>>()
        var record = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0
        while (index < text.length) {
            val char = text[index]
            when {
                quoted && char == '\"' && index + 1 < text.length && text[index + 1] == '\"' -> {
                    field.append('\"')
                    index++
                }
                char == '\"' -> quoted = !quoted
                !quoted && char == ',' -> {
                    record += field.toString()
                    field.clear()
                }
                !quoted && (char == '\r' || char == '\n') -> {
                    if (char == '\r' && index + 1 < text.length && text[index + 1] == '\n') index++
                    record += field.toString()
                    field.clear()
                    records += record
                    record = mutableListOf()
                }
                else -> field.append(char)
            }
            index++
        }
        require(!quoted) { "CSV has an unterminated quoted field" }
        if (field.isNotEmpty() || record.isNotEmpty()) {
            record += field.toString()
            records += record
        }
        return records
    }
}
