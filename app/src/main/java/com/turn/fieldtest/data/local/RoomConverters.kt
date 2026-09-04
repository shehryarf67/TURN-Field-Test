package com.turn.fieldtest.data.local

import androidx.room.TypeConverter
import java.time.Instant

/** Converters kept deterministic so exported database values are stable across locales. */
class RoomConverters {
    @TypeConverter
    fun instantToEpochMillis(value: Instant?): Long? = value?.toEpochMilli()

    @TypeConverter
    fun epochMillisToInstant(value: Long?): Instant? = value?.let(Instant::ofEpochMilli)

    @TypeConverter
    fun stringSetToStorage(value: Set<String>?): String? = value
        ?.map(::escape)
        ?.sorted()
        ?.joinToString(SEPARATOR)

    @TypeConverter
    fun storageToStringSet(value: String?): Set<String>? = value
        ?.takeIf(String::isNotEmpty)
        ?.split(SEPARATOR)
        ?.map(::unescape)
        ?.toSet()

    private fun escape(value: String): String = value
        .replace("%", "%25")
        .replace(SEPARATOR, "%1F")

    private fun unescape(value: String): String = value
        .replace("%1F", SEPARATOR)
        .replace("%25", "%")

    private companion object {
        const val SEPARATOR = "\u001F"
    }
}
