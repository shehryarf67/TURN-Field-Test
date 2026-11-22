package com.turn.fieldtest.ui

import androidx.compose.ui.geometry.Offset
import com.turn.fieldtest.core.BarometerHealth
import com.turn.fieldtest.core.FloorState
import kotlin.math.roundToInt

/** One floor of a multi-floor session, for the checkpoint floor chips and their coordinate bounds. */
data class SessionFloorUi(val id: String, val name: String, val level: Int, val widthMetres: Double, val heightMetres: Double)

/**
 * The confirmed floor of a multi-floor session as the screens show it (plan Step 9). Null in single-floor sessions,
 * so every screen then renders exactly as before.
 */
data class LiveFloorUi(
    val floorId: String,
    val floorName: String,
    val level: Int,
    val confidence: Double,
    val state: FloorState,
    val barometer: BarometerHealth,
    val announcedAtEpochMillis: Long?,
    val sessionFloors: List<SessionFloorUi>,
    val startFloorId: String,
)

/** The destination chosen during a live session, kept by id and floor so a floor swap does not lose its name. */
data class LiveDestination(val id: String, val floorId: String, val name: String)

/** The fused trail and Wi-Fi fixes of one floor, in that floor's frame. */
data class FloorTrails(val fused: List<Offset> = emptyList(), val wifi: List<Offset> = emptyList())

const val FLOOR_BANNER_MILLIS = 6_000L
private const val ANNOUNCE_VOTE = 0.8

private fun percent(value: Double) = (value.coerceIn(0.0, 1.0) * 100).roundToInt()

/** Chip text: "Floor 4 · 92%", "Changing floor…" or "Floor uncertain". */
fun floorChipText(floor: LiveFloorUi): String = when (floor.state) {
    FloorState.CHANGING -> "Changing floor…"
    FloorState.UNCERTAIN -> "Floor uncertain"
    FloorState.CONFIRMED -> "${floor.floorName} · ${percent(floor.confidence)}%"
}

/** Banner text after a change or while uncertain (the Relocalize action is a separate button). */
fun floorBannerText(floor: LiveFloorUi): String = when {
    floor.state == FloorState.UNCERTAIN -> "Not sure which floor"
    floor.confidence >= ANNOUNCE_VOTE -> "You're on ${floor.floorName}"
    else -> "Probably on ${floor.floorName} · Not right?"
}

/** Whether the banner offers Relocalize: when the floor is uncertain or only probable. */
fun floorBannerOffersRelocalize(floor: LiveFloorUi): Boolean =
    floor.state == FloorState.UNCERTAIN || floor.confidence < ANNOUNCE_VOTE

/** The banner shows for [FLOOR_BANNER_MILLIS] after a change, and stays up while the floor is uncertain. */
fun floorBannerVisible(floor: LiveFloorUi?, nowEpochMillis: Long): Boolean {
    if (floor == null) return false
    if (floor.state == FloorState.UNCERTAIN) return true
    val at = floor.announcedAtEpochMillis ?: return false
    return nowEpochMillis - at in 0 until FLOOR_BANNER_MILLIS
}

/** Full-screen header suffix: " · floor 92%". */
fun fullscreenFloorSuffix(floor: LiveFloorUi?): String = floor?.let { " · floor ${percent(it.confidence)}%" } ?: ""

/** "Barometer: ready / not on this phone / no recent readings". */
fun barometerStatusText(health: BarometerHealth): String = "Barometer: " + when (health) {
    BarometerHealth.OK -> "ready"
    BarometerHealth.UNAVAILABLE -> "not on this phone"
    BarometerHealth.STALE -> "no recent readings"
}

/** Route status when the live destination lies on another floor than the tracked one (phase 3 adds directions). */
fun destinationOnOtherFloorText(floorName: String) =
    "Destination is on $floorName · directions between floors come in a later update"

/** Wi-Fi status while a match for another floor waits for confirmation. */
fun wifiSuggestsFloorText(floorName: String) = "Wi-Fi suggests $floorName · waiting for more evidence"

/**
 * Which floor the display should show after a decision: always the confirmed floor (invariant I3). Returns null
 * when the display already shows it, so nothing is reloaded.
 */
fun floorToFollow(displayedFloorId: String?, confirmedFloorId: String): String? =
    confirmedFloorId.takeIf { it != displayedFloorId }

/** Whether the live position dot belongs on the displayed floor (single-floor sessions always draw it). */
fun positionOnDisplayedFloor(tracking: LiveFloorUi?, liveFloorId: String?, displayedFloorId: String?): Boolean =
    tracking == null || liveFloorId == null || liveFloorId == displayedFloorId

/**
 * Default floor for the next checkpoint: the previous checkpoint's floor if it is in the session, else the
 * session's start floor; never the displayed floor (so floor accuracy cannot confirm itself).
 */
fun defaultCheckpointFloor(previousCheckpointFloorId: String?, sessionFloorIds: Collection<String>, startFloorId: String): String =
    previousCheckpointFloorId?.takeIf { it in sessionFloorIds } ?: startFloorId

/** Appends [point] unless it repeats the last one, keeping at most [max] points. */
fun appendTrailPoint(trail: List<Offset>, point: Offset, max: Int): List<Offset> =
    if (trail.lastOrNull() == point) trail else (trail + point).takeLast(max)
