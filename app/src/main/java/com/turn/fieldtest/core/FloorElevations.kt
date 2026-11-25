package com.turn.fieldtest.core

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Floor counts for a barometric height change of [floors] storeys (unsigned): null when it is below
 * [VerticalMotionConfig.minimumFloorFraction] of a storey, otherwise the nearest whole count, or both neighbours
 * (never 0) when the residual exceeds [VerticalMotionConfig.integerTolerance]. This is the rule
 * [VerticalMotionDetector] has always applied to `|Δh| / floorHeightMetres`.
 */
fun floorCountCandidates(floors: Double, config: VerticalMotionConfig): Set<Int>? {
    val x = abs(floors)
    if (!x.isFinite() || x < config.minimumFloorFraction) return null
    val nearest = x.roundToInt()
    return if (abs(x - nearest) > config.integerTolerance) {
        (setOf(floor(x).toInt(), ceil(x).toInt()) - 0).ifEmpty { null }
    } else {
        setOf(maxOf(1, nearest))
    }
}

/**
 * Real floor elevations of one building (metres above any common datum, e.g. ground floor = 0), so a vertical event is
 * judged against the actual height between the floors involved instead of the nominal storey
 * ([VerticalMotionConfig.floorHeightMetres]). A 2.7 m house storey is then one floor, not 0.77 of a 3.5 m one.
 *
 * Built only by [of], which returns null unless every floor has a finite elevation and elevations rise with level by
 * a plausible storey ([MIN_STOREY_METRES]..[MAX_STOREY_METRES] per level). Null means "nominal storey height", which is
 * exactly the behaviour before elevations existed (parity).
 */
class FloorElevations private constructor(
    val elevationMetres: Map<String, Double>,
    val levels: Map<String, Int>,
) {
    /** (elevation, level) of every floor, lowest first: the piecewise-linear "level at height" curve. */
    private val curve: List<Pair<Double, Double>> = levels.keys.sortedBy { levels.getValue(it) }
        .map { elevationMetres.getValue(it) to levels.getValue(it).toDouble() }

    /** The lowest per-level storey height of the building (the most sensitive one for "is this a floor at all"). */
    val smallestStoreyMetres: Double = curve.zipWithNext { a, b -> (b.first - a.first) / (b.second - a.second) }.min()

    /** Height from [fromFloorId] up to [toFloorId] (negative going down). */
    fun expectedHeight(fromFloorId: String, toFloorId: String): Double =
        elevationMetres.getValue(toFloorId) - elevationMetres.getValue(fromFloorId)

    /** Average per-level storey between two floors; the smallest storey for the same floor. */
    fun storeyMetres(a: String, b: String): Double {
        val levelGap = abs(levels.getValue(b) - levels.getValue(a))
        return if (levelGap == 0) smallestStoreyMetres else abs(expectedHeight(a, b)) / levelGap
    }

    /**
     * Signed fractional number of levels covered by [heightDeltaMetres] starting on [fromFloorId]: the level at the
     * reached height on the elevation curve (extrapolated with the end storeys beyond the top and bottom floors),
     * minus the start level. With every storey 3.5 m this is `heightDelta / 3.5`.
     */
    fun floorsFor(fromFloorId: String, heightDeltaMetres: Double): Double {
        val start = elevationMetres.getValue(fromFloorId)
        return levelAt(start + heightDeltaMetres) - levels.getValue(fromFloorId)
    }

    /**
     * Floor-count candidates for a height change that started on any of [fromFloorIds] (floors not in this building
     * are ignored): the union of [floorCountCandidates] per start floor, or null when no start floor sees a floor-sized
     * change.
     */
    fun candidates(fromFloorIds: Collection<String>, heightDeltaMetres: Double, config: VerticalMotionConfig): Set<Int>? =
        fromFloorIds.filter { it in elevationMetres }.distinct()
            .mapNotNull { floorCountCandidates(floorsFor(it, heightDeltaMetres), config) }
            .flatten().toSortedSet().ifEmpty { null }

    /** "F0=0.00, F1=2.70" (lowest first), for the CONFIG event. */
    fun describe(): String = levels.keys.sortedBy { levels.getValue(it) }
        .joinToString(", ") { String.format(Locale.ROOT, "%s=%.2f", it, elevationMetres.getValue(it)) }

    private fun levelAt(elevation: Double): Double {
        val i = when {
            elevation <= curve.first().first -> 0
            elevation >= curve.last().first -> curve.size - 2
            else -> curve.indexOfLast { it.first <= elevation }.coerceAtMost(curve.size - 2)
        }
        val (e0, l0) = curve[i]
        val (e1, l1) = curve[i + 1]
        return l0 + (elevation - e0) * (l1 - l0) / (e1 - e0)
    }

    override fun equals(other: Any?): Boolean =
        other is FloorElevations && other.elevationMetres == elevationMetres && other.levels == levels

    override fun hashCode(): Int = 31 * elevationMetres.hashCode() + levels.hashCode()

    override fun toString(): String = "FloorElevations(${describe()})"

    companion object {
        const val MIN_STOREY_METRES = 1.5
        const val MAX_STOREY_METRES = 10.0
        const val MAX_ABS_ELEVATION_METRES = 1000.0

        /**
         * Elevations for the floors in [levels], or null (nominal storey height) when there are fewer than two floors
         * or [validationError] finds a problem: a missing elevation on any floor, for example, keeps today's behaviour.
         */
        fun of(levels: Map<String, Int>, elevations: Map<String, Double?>): FloorElevations? {
            if (levels.size < 2 || validationError(levels, elevations) != null) return null
            return FloorElevations(levels.keys.associateWith { elevations.getValue(it)!! }, levels.toMap())
        }

        /**
         * Why these elevations cannot be used, or null. Every floor needs a finite elevation within ±1000 m, levels must
         * be distinct, and each step up in level must rise by 1.5-10 m per level.
         */
        fun validationError(levels: Map<String, Int>, elevations: Map<String, Double?>, names: Map<String, String> = emptyMap()): String? {
            fun name(id: String) = names[id] ?: id
            val missing = levels.keys.filter { elevations[it] == null }
            if (missing.isNotEmpty()) return "Set a height for every floor (missing: ${missing.joinToString { name(it) }})"
            levels.keys.firstOrNull { val e = elevations.getValue(it)!!; !e.isFinite() || abs(e) > MAX_ABS_ELEVATION_METRES }
                ?.let { return "${name(it)}: height must be a number within ±1000 m" }
            if (levels.values.toSet().size != levels.size) return "Two floors share a level"
            val sorted = levels.keys.sortedBy { levels.getValue(it) }
            sorted.zipWithNext().forEach { (a, b) ->
                val storey = (elevations.getValue(b)!! - elevations.getValue(a)!!) / (levels.getValue(b) - levels.getValue(a))
                if (storey < MIN_STOREY_METRES || storey > MAX_STOREY_METRES) {
                    return String.format(Locale.ROOT, "%s is %.2f m above %s: each level must be %.1f-%.0f m higher than the one below",
                        name(b), elevations.getValue(b)!! - elevations.getValue(a)!!, name(a), MIN_STOREY_METRES, MAX_STOREY_METRES)
                }
            }
            return null
        }

        /** Elevations for a uniform [storeyMetres]: level × storey (ground floor at 0, basements below). */
        fun fromStoreyHeight(levels: Map<String, Int>, storeyMetres: Double): Map<String, Double> {
            require(storeyMetres.isFinite() && storeyMetres in MIN_STOREY_METRES..MAX_STOREY_METRES) {
                "Storey height must be ${MIN_STOREY_METRES}-${MAX_STOREY_METRES.toInt()} m"
            }
            return levels.mapValues { (_, level) -> level * storeyMetres }
        }
    }
}
