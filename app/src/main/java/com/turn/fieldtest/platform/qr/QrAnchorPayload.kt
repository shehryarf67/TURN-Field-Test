package com.turn.fieldtest.platform.qr

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

@Serializable
data class QrAnchorPayload(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("venue_id") val venueId: String,
    @SerialName("floor_id") val floorId: String,
    @SerialName("anchor_id") val anchorId: String,
    @SerialName("x_metres") val xMetres: Double,
    @SerialName("y_metres") val yMetres: Double,
    @SerialName("initial_route_direction_degrees") val initialRouteDirectionDegrees: Double? = null,
)

enum class QrPayloadError {
    MALFORMED_JSON,
    UNSUPPORTED_SCHEMA,
    MISSING_IDENTIFIER,
    INVALID_COORDINATE,
    INVALID_DIRECTION,
    UNKNOWN_VENUE,
    UNKNOWN_FLOOR,
}

sealed interface QrPayloadResult {
    data class Valid(val payload: QrAnchorPayload) : QrPayloadResult
    data class Invalid(val error: QrPayloadError, val detail: String) : QrPayloadResult
}

/** Pure Kotlin codec: scanning/generation UIs can pass strings without depending on camera APIs. */
class QrAnchorPayloadCodec(
    private val json: Json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
        isLenient = false
    },
    private val supportedSchemaVersions: Set<Int> = setOf(CURRENT_SCHEMA_VERSION),
) {
    fun encode(payload: QrAnchorPayload): String {
        val validation = validate(payload)
        require(validation is QrPayloadResult.Valid) {
            (validation as QrPayloadResult.Invalid).detail
        }
        return json.encodeToString(QrAnchorPayload.serializer(), payload)
    }

    fun decodeAndValidate(
        value: String,
        knownVenueIds: Set<String>? = null,
        knownFloorIds: Set<String>? = null,
    ): QrPayloadResult {
        val payload = try {
            json.decodeFromString(QrAnchorPayload.serializer(), value)
        } catch (error: SerializationException) {
            return QrPayloadResult.Invalid(QrPayloadError.MALFORMED_JSON, error.message ?: "Malformed QR payload")
        } catch (error: IllegalArgumentException) {
            return QrPayloadResult.Invalid(QrPayloadError.MALFORMED_JSON, error.message ?: "Malformed QR payload")
        }
        val structural = validate(payload)
        if (structural is QrPayloadResult.Invalid) return structural
        if (knownVenueIds != null && payload.venueId !in knownVenueIds) {
            return QrPayloadResult.Invalid(QrPayloadError.UNKNOWN_VENUE, "Unknown venue '${payload.venueId}'")
        }
        if (knownFloorIds != null && payload.floorId !in knownFloorIds) {
            return QrPayloadResult.Invalid(QrPayloadError.UNKNOWN_FLOOR, "Unknown floor '${payload.floorId}'")
        }
        return QrPayloadResult.Valid(payload)
    }

    fun validate(payload: QrAnchorPayload): QrPayloadResult {
        if (payload.schemaVersion !in supportedSchemaVersions) {
            return QrPayloadResult.Invalid(
                QrPayloadError.UNSUPPORTED_SCHEMA,
                "Unsupported QR schema ${payload.schemaVersion}",
            )
        }
        if (listOf(payload.venueId, payload.floorId, payload.anchorId).any { it.isBlank() }) {
            return QrPayloadResult.Invalid(QrPayloadError.MISSING_IDENTIFIER, "Venue, floor and anchor IDs are required")
        }
        if (!payload.xMetres.isFinite() || !payload.yMetres.isFinite()) {
            return QrPayloadResult.Invalid(QrPayloadError.INVALID_COORDINATE, "Coordinates must be finite metres")
        }
        val direction = payload.initialRouteDirectionDegrees
        if (direction != null && (!direction.isFinite() || direction !in 0.0..<360.0)) {
            return QrPayloadResult.Invalid(QrPayloadError.INVALID_DIRECTION, "Direction must be in [0, 360) degrees")
        }
        return QrPayloadResult.Valid(payload)
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}
