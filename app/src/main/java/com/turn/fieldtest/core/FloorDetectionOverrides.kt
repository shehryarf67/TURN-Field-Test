package com.turn.fieldtest.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.util.Locale
import kotlin.math.roundToInt

/**
 * One floor-detection threshold the user may override on the phone (Diagnostics → Floor detection thresholds).
 * [min]..[max] is the editor's accepted range, narrower than or equal to what [FloorDetectionConfig] itself requires.
 */
class ThresholdSpec(
    val key: String,
    val group: String,
    val label: String,
    val unit: String,
    val min: Double,
    val max: Double,
    val integer: Boolean = false,
    val read: (FloorDetectionConfig) -> Double,
    val write: (FloorDetectionConfig, Double) -> FloorDetectionConfig,
) {
    fun format(value: Double): String =
        if (integer) value.roundToInt().toString() else String.format(Locale.ROOT, "%.3f", value).trimEnd('0').trimEnd('.')

    /** Why [value] cannot be used for this threshold, or null. */
    fun problem(value: Double): String? = when {
        !value.isFinite() -> "Enter a number"
        integer && value != Math.rint(value) -> "Enter a whole number"
        value < min || value > max -> "Allowed ${format(min)}–${format(max)}${if (unit.isEmpty()) "" else " $unit"}"
        else -> null
    }
}

/** The result of applying overrides: the session config, the overrides it used and those it had to drop (with why). */
data class AppliedFloorDetection(
    val config: FloorDetectionConfig,
    val used: Map<String, Double>,
    val rejected: Map<String, String>,
)

/**
 * User overrides of [FloorDetectionConfig] values, persisted as a small JSON object (key → number) in the settings
 * DataStore. Nothing overridden gives exactly `FloorDetectionConfig()`. A session snapshots the result at start (the
 * coordinator keeps its config), so edits take effect at the next tracking session, never mid-session.
 */
object FloorDetectionOverrides {
    private val json = Json { ignoreUnknownKeys = true }

    val specs: List<ThresholdSpec> = listOf(
        // Barometric vertical events.
        spec("vertical.floorHeightMetres", "Vertical events", "Nominal storey height (when floor heights are not set)", "m", 2.0, 6.0,
            { it.vertical.floorHeightMetres }) { c, v -> c.copy(vertical = c.vertical.copy(floorHeightMetres = v)) },
        spec("vertical.minimumFloorFraction", "Vertical events", "Smallest change that counts, share of a storey", "", 0.3, 0.95,
            { it.vertical.minimumFloorFraction }) { c, v -> c.copy(vertical = c.vertical.copy(minimumFloorFraction = v)) },
        spec("vertical.integerTolerance", "Vertical events", "Ambiguous beyond this share of a storey", "", 0.1, 0.5,
            { it.vertical.integerTolerance }) { c, v -> c.copy(vertical = c.vertical.copy(integerTolerance = v)) },
        spec("vertical.enterRateMps", "Vertical events", "Start moving at vertical speed", "m/s", 0.05, 0.5,
            { it.vertical.enterRateMps }) { c, v -> c.copy(vertical = c.vertical.copy(enterRateMps = v)) },
        spec("vertical.exitRateMps", "Vertical events", "Settle below vertical speed", "m/s", 0.01, 0.3,
            { it.vertical.exitRateMps }) { c, v -> c.copy(vertical = c.vertical.copy(exitRateMps = v)) },
        spec("vertical.settleSeconds", "Vertical events", "Settle time before an event", "s", 0.0, 10.0,
            { it.vertical.settleSeconds }) { c, v -> c.copy(vertical = c.vertical.copy(settleSeconds = v)) },
        spec("vertical.liftMinimumRateMps", "Vertical events", "Lift when peak speed reaches", "m/s", 0.2, 2.0,
            { it.vertical.liftMinimumRateMps }) { c, v -> c.copy(vertical = c.vertical.copy(liftMinimumRateMps = v)) },
        spec("vertical.maximumSegmentSeconds", "Vertical events", "Longest climb or ride", "s", 30.0, 600.0,
            { it.vertical.maximumSegmentSeconds }) { c, v -> c.copy(vertical = c.vertical.copy(maximumSegmentSeconds = v)) },
        // Barometer hygiene.
        spec("barometer.emaTauSeconds", "Barometer", "Smoothing time constant", "s", 0.2, 5.0,
            { it.barometer.emaTauSeconds }) { c, v -> c.copy(barometer = c.barometer.copy(emaTauSeconds = v)) },
        spec("barometer.stepRejectionHpa", "Barometer", "Door/HVAC step candidate above", "hPa", 0.05, 1.0,
            { it.barometer.stepRejectionHpa }) { c, v -> c.copy(barometer = c.barometer.copy(stepRejectionHpa = v)) },
        spec("barometer.staleAfterSeconds", "Barometer", "Stale after silence of", "s", 1.0, 30.0,
            { it.barometer.staleAfterSeconds }) { c, v -> c.copy(barometer = c.barometer.copy(staleAfterSeconds = v)) },
        // No-barometer cues.
        spec("cues.stairsMinimumSteps", "Stairs and lift without barometer", "Stairs: steps in the link", "steps", 2.0, 30.0,
            { it.cues.stairsMinimumSteps.toDouble() }, integer = true) { c, v -> c.copy(cues = c.cues.copy(stairsMinimumSteps = v.roundToInt())) },
        spec("cues.stairsMinimumSeconds", "Stairs and lift without barometer", "Stairs: time in the link", "s", 0.0, 30.0,
            { it.cues.stairsMinimumSeconds }) { c, v -> c.copy(cues = c.cues.copy(stairsMinimumSeconds = v)) },
        spec("cues.liftMinimumSeconds", "Stairs and lift without barometer", "Lift: still in the car for", "s", 2.0, 60.0,
            { it.cues.liftMinimumSeconds }) { c, v -> c.copy(cues = c.cues.copy(liftMinimumSeconds = v)) },
        spec("cues.stairsSeedShare", "Stairs and lift without barometer", "Stairs: share of hypotheses moved", "", 0.0, 1.0,
            { it.cues.stairsSeedShare }) { c, v -> c.copy(cues = c.cues.copy(stairsSeedShare = v)) },
        spec("cues.liftSeedShare", "Stairs and lift without barometer", "Lift: share of hypotheses moved", "", 0.0, 1.0,
            { it.cues.liftSeedShare }) { c, v -> c.copy(cues = c.cues.copy(liftSeedShare = v)) },
        spec("cues.cooldownSeconds", "Stairs and lift without barometer", "Pause after a cue", "s", 0.0, 120.0,
            { it.cues.cooldownSeconds }) { c, v -> c.copy(cues = c.cues.copy(cooldownSeconds = v)) },
        // Transition.
        spec("transition.barometricShare", "Floor change", "Share moved by a barometer event", "", 0.1, 1.0,
            { it.transition.barometricShare }) { c, v -> c.copy(transition = c.transition.copy(barometricShare = v)) },
        spec("transition.outOfPortalLikelihood", "Floor change", "Weight kept away from links", "", 0.0, 1.0,
            { it.transition.outOfPortalLikelihood }) { c, v -> c.copy(transition = c.transition.copy(outOfPortalLikelihood = v)) },
        // Wi-Fi across floors.
        spec("wifi.strongConfidence", "Wi-Fi on another floor", "Strong match confidence", "", 0.3, 1.0,
            { it.wifi.strongConfidence }) { c, v -> c.copy(wifi = c.wifi.copy(strongConfidence = v)) },
        spec("wifi.matchesWithoutBarometer", "Wi-Fi on another floor", "Matches needed without barometer", "scans", 1.0, 10.0,
            { it.wifi.matchesWithoutBarometer.toDouble() }, integer = true) { c, v -> c.copy(wifi = c.wifi.copy(matchesWithoutBarometer = v.roundToInt())) },
        spec("wifi.probationSeconds", "Wi-Fi on another floor", "Probation after a Wi-Fi start", "s", 0.0, 600.0,
            { it.wifi.probationSeconds }) { c, v -> c.copy(wifi = c.wifi.copy(probationSeconds = v)) },
        spec("wifi.pendingWindowSeconds", "Wi-Fi on another floor", "Unexplained height kept for", "s", 10.0, 600.0,
            { it.wifi.pendingWindowSeconds }) { c, v -> c.copy(wifi = c.wifi.copy(pendingWindowSeconds = v)) },
        // Decision.
        spec("decision.switchVote", "Displayed floor", "Switch at vote", "", 0.5, 1.0,
            { it.decision.switchVote }) { c, v -> c.copy(decision = c.decision.copy(switchVote = v)) },
        spec("decision.switchUpdates", "Displayed floor", "Switch after updates", "updates", 1.0, 20.0,
            { it.decision.switchUpdates.toDouble() }, integer = true) { c, v -> c.copy(decision = c.decision.copy(switchUpdates = v.roundToInt())) },
        spec("decision.switchSeconds", "Displayed floor", "Switch after holding for", "s", 0.0, 20.0,
            { it.decision.switchSeconds }) { c, v -> c.copy(decision = c.decision.copy(switchSeconds = v)) },
        spec("decision.uncertainVote", "Displayed floor", "Uncertain below vote", "", 0.1, 0.95,
            { it.decision.uncertainVote }) { c, v -> c.copy(decision = c.decision.copy(uncertainVote = v)) },
        spec("decision.announceVote", "Displayed floor", "Announce \"You're on\" at vote", "", 0.5, 1.0,
            { it.decision.announceVote }) { c, v -> c.copy(decision = c.decision.copy(announceVote = v)) },
        spec("decision.changingHoldSeconds", "Displayed floor", "\"Changing floor\" lasts", "s", 0.0, 60.0,
            { it.decision.changingHoldSeconds }) { c, v -> c.copy(decision = c.decision.copy(changingHoldSeconds = v)) },
    )

    private val byKey = specs.associateBy { it.key }

    fun spec(key: String): ThresholdSpec? = byKey[key]

    /** The default of every editable threshold. */
    fun defaults(): Map<String, Double> = specs.associate { it.key to it.read(FloorDetectionConfig()) }

    /**
     * [overrides] applied in editor order on top of the defaults. Unknown keys and out-of-range values are rejected; a
     * value the config itself refuses in combination with the others (e.g. a settle speed above the start speed) is
     * rejected too, keeping the rest. An empty map gives `FloorDetectionConfig()` unchanged.
     */
    fun apply(overrides: Map<String, Double>, base: FloorDetectionConfig = FloorDetectionConfig()): AppliedFloorDetection {
        var config = base
        val used = linkedMapOf<String, Double>()
        val rejected = linkedMapOf<String, String>()
        overrides.keys.filter { it !in byKey }.sorted().forEach { rejected[it] = "Unknown threshold" }
        specs.filter { it.key in overrides }.forEach { spec ->
            val value = overrides.getValue(spec.key)
            spec.problem(value)?.let { rejected[spec.key] = it; return@forEach }
            try {
                config = spec.write(config, value)
                used[spec.key] = value
            } catch (e: IllegalArgumentException) {
                rejected[spec.key] = "Conflicts with another threshold"
            }
        }
        return AppliedFloorDetection(config, used, rejected)
    }

    /** Keeps only overrides that apply cleanly and differ from the default (what is worth persisting). */
    fun sanitize(overrides: Map<String, Double>): Map<String, Double> {
        val applied = apply(overrides)
        val defaults = defaults()
        return applied.used.filter { (k, v) -> v != defaults[k] }.toSortedMap()
    }

    /** Compact, sorted JSON object; "" for no overrides. */
    fun encode(overrides: Map<String, Double>): String =
        if (overrides.isEmpty()) "" else JsonObject(overrides.toSortedMap().mapValues { JsonPrimitive(it.value) }).toString()

    /** Reads [encode]'s output; unreadable text or non-numeric values are dropped (never a crash at app start). */
    fun decode(text: String?): Map<String, Double> {
        if (text.isNullOrBlank()) return emptyMap()
        val element = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return emptyMap()
        return element.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.doubleOrNull?.let { k to it } }.toMap().toSortedMap()
    }

    /** "vertical.floorHeightMetres=2.8, …" for logs, or "" when nothing is overridden. */
    fun describe(overrides: Map<String, Double>): String =
        overrides.toSortedMap().entries.joinToString(", ") { (k, v) -> "$k=${byKey[k]?.format(v) ?: v}" }

    private fun spec(
        key: String, group: String, label: String, unit: String, min: Double, max: Double,
        read: (FloorDetectionConfig) -> Double, integer: Boolean = false, write: (FloorDetectionConfig, Double) -> FloorDetectionConfig,
    ) = ThresholdSpec(key, group, label, unit, min, max, integer, read, write)
}
