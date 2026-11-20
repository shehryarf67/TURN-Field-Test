package com.turn.fieldtest.core

import java.io.File
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Characterisation (golden) tests: today's single-floor [ParticleFilter] must keep producing bit-identical
 * particles, summaries, results and events for fixed seeds. Every scenario runs once per [variants] entry
 * and is compared against the same snapshot, so a later constructor (for example one with an empty floor
 * topology) is proved inert by adding one line there.
 *
 * Regenerate the snapshot only on purpose, from the repository root (`--rerun` because the environment
 * variable is not a task input, so Gradle would otherwise consider the test task up to date):
 * `GOLDEN_UPDATE=1 ./gradlew.bat testDebugUnitTest --tests 'com.turn.fieldtest.core.ParticleFilterGoldenTest' --rerun`
 * (the system property `golden.update=true` works too where it reaches the test JVM).
 */
class ParticleFilterGoldenTest {
    private fun interface FilterFactory {
        fun create(map: MetricMap, config: ParticleFilterConfig, seed: Long): ParticleFilter
    }

    /** Constructor variants that must all reproduce the snapshot; the first one generates it. */
    private val variants: List<Pair<String, FilterFactory>> = listOf(
        "default" to FilterFactory { map, config, seed -> ParticleFilter(map, config, seed) },
    )

    @Test fun a_predictStepsAgainstWallsReplenishSurvivors() = verify("a_predict_walls") { records ->
        assertTrue(records.any { it.result["constraints"].toString().contains("CROSSES_WALL") })
        assertTrue(records.drop(1).any { it.intResult("survivingParticleCountBeforeReplenishment") in 1 until PARTICLES })
        assertTrue(records.drop(1).all { it.result["accepted"] == JsonPrimitive(true) })
    }

    @Test fun b_freshWifiCorrectionInjectsAroundTheFix() = verify("b_wifi_injection") { records ->
        assertTrue(records.count { it.resultKind() == "APPLIED" } >= 2)
        assertTrue(records.any { it.resultKind() == "IGNORED_NOT_FRESH" })
        assertTrue(records.any { it.resultKind() == "IGNORED_NO_ESTIMATE" })
    }

    @Test fun c_magneticCorrectionResamplesOnLowEffectiveSize() = verify("c_magnetic_resample") { records ->
        assertTrue(records.any { it.resultKind() == "APPLIED" && it.result["resampled"] == JsonPrimitive(true) })
        assertTrue(records.any { it.resultKind() == "IGNORED_DISTURBED" })
        assertTrue(records.any { it.resultKind() == "IGNORED_NO_MAP" })
    }

    @Test fun d_forceResampleKeepsTheSeededSequence() = verify("d_force_resample") { records ->
        assertTrue(records.count { it.op.startsWith("forceResample") } == 2)
    }

    @Test fun e_strongDistantWifiRelocalizesGlobally() = verify("e_global_relocalization") { records ->
        assertTrue(records.any { it.op.startsWith("wifi:strongFarNoReloc") && it.resultKind() == "APPLIED" })
        assertTrue(records.any { it.op.startsWith("wifi:strongFar") && it.resultKind() == "GLOBAL_RELOCALIZATION" })
    }

    @Test fun f_weightCollapseRelocalizesOrLosesTheFilter() = verify("f_weight_collapse") { records ->
        val kinds = records.map { it.op to it.resultKind() }
        assertTrue(("relocalize:wifi:collapse" to "GLOBAL_RELOCALIZATION") in kinds)
        assertTrue(("lose:wifi:collapse" to "FILTER_LOST") in kinds)
        assertTrue(("lose:wifi:weakWhileLost" to "FILTER_LOST") in kinds)
        assertTrue(("lose:wifi:strongWhileLost" to "GLOBAL_RELOCALIZATION") in kinds)
    }

    private fun verify(name: String, coverage: (List<Record>) -> Unit) {
        val expected = snapshot["scenarios"]?.jsonObject?.get(name) ?: fail("Snapshot has no scenario $name")
        variants.forEach { (variant, factory) ->
            val records = scenarios.getValue(name)(factory)
            coverage(records)
            assertSame(expected, JsonArray(records.map { it.toJson() }), "$name[$variant]")
        }
    }

    // ----- Scenarios -------------------------------------------------------------------------------------

    private val scenarios: Map<String, (FilterFactory) -> List<Record>> = mapOf(
        "a_predict_walls" to { factory ->
            Recorder(factory.create(map, config, 101L)).run {
                record("initialize") { filter.initialize(fix(7.0, 6.0)).json() }
                val legs = listOf(0.0 to 4, PI / 2 to 10, 0.0 to 10, -PI / 2 to 8, PI to 8)
                var step = 0
                legs.forEach { (heading, count) ->
                    repeat(count) {
                        step++
                        record("predictStep#$step", particles = step % 20 == 0) {
                            filter.predictStep(0.7, heading).json()
                        }
                    }
                }
                records
            }
        },
        "b_wifi_injection" to { factory ->
            Recorder(factory.create(map, config, 202L)).run {
                record("initialize") { filter.initialize(fix(12.5, 6.0)).json() }
                repeat(3) { record("predictStep#${it + 1}") { filter.predictStep(0.65, 0.2).json() } }
                record("wifi:fresh") {
                    filter.correctWithWifi(wifi(MetricPoint(15.0, 5.0), listOf(2 to 0.6, 3 to 0.25, 4 to 0.15), 0.75)).json()
                }
                record("wifi:cached") {
                    filter.correctWithWifi(wifi(MetricPoint(15.0, 5.0), listOf(2 to 1.0), 0.9,
                        freshness = WifiScanFreshness.CACHED)).json()
                }
                record("wifi:ambiguous") {
                    filter.correctWithWifi(wifi(MetricPoint(15.0, 5.0), listOf(2 to 1.0), 0.9, ambiguous = true)).json()
                }
                record("predictStep#4") { filter.predictStep(0.65, 0.0).json() }
                record("wifi:freshSecond", particles = true) {
                    filter.correctWithWifi(wifi(MetricPoint(16.5, 6.5), listOf(2 to 0.5, 3 to 0.5), 0.55,
                        uncertainty = 5.0)).json()
                }
                records
            }
        },
        "c_magnetic_resample" to { factory ->
            Recorder(factory.create(map, config, 303L)).run {
                record("initialize") { filter.initialize(fix(3.0, 13.0)).json() }
                var previous: MagneticObservation? = null
                for (step in 1..10) {
                    record("predictStep#$step") { filter.predictStep(0.7, 0.0).json() }
                    val observation = observationAt(MetricPoint(3.0 + 0.7 * step, 13.0))
                    record("magnetic#$step", particles = step == 10) {
                        filter.correctWithMagnetic(magneticMaps, observation, previous, sharpMagnetic).json()
                    }
                    previous = observation
                }
                record("magnetic:disturbed") {
                    filter.correctWithMagnetic(magneticMaps,
                        MagneticObservation(MagneticFeature(200.0, 10.0), 20, true), previous).json()
                }
                record("magnetic:noMap") {
                    filter.correctWithMagnetic(emptyMap(), observationAt(MetricPoint(10.0, 13.0)), previous).json()
                }
                records
            }
        },
        "d_force_resample" to { factory ->
            Recorder(factory.create(map, config, 404L)).run {
                record("initialize") { filter.initialize(fix(25.0, 15.0), positionStdMetres = 2.0).json() }
                repeat(3) { record("predictStep#${it + 1}") { filter.predictStep(0.6, PI).json() } }
                record("wifi:weak") {
                    filter.correctWithWifi(wifi(MetricPoint(24.0, 15.5), listOf(5 to 0.7, 3 to 0.3), 0.3)).json()
                }
                record("forceResample#1") { JsonObject(mapOf("summary" to summaryJson(filter.forceResample()))) }
                record("predictStep#4") { filter.predictStep(0.6, PI / 2).json() }
                record("forceResample#2", particles = true) { JsonObject(mapOf("summary" to summaryJson(filter.forceResample()))) }
                records
            }
        },
        "e_global_relocalization" to { factory ->
            Recorder(factory.create(map, config, 505L)).run {
                record("initialize") { filter.initialize(fix(4.0, 4.0)).json() }
                repeat(3) { record("predictStep#${it + 1}") { filter.predictStep(0.7, PI / 4).json() } }
                val far = wifi(MetricPoint(27.0, 16.0), listOf(5 to 0.8, 4 to 0.2), 0.9, uncertainty = 2.5)
                record("wifi:strongFarNoReloc") { filter.correctWithWifi(far, allowGlobalRelocalization = false).json() }
                record("wifi:strongFar", particles = true) { filter.correctWithWifi(far).json() }
                repeat(3) {
                    record("predictStep#${it + 4}", particles = it == 2) { filter.predictStep(0.7, -PI / 2).json() }
                }
                records
            }
        },
        "f_weight_collapse" to { factory ->
            // The estimate sits on the cloud (no early relocalisation) but every neighbour is far away, so every
            // likelihood hits the 1e-300 floor. Whether the total then rounds to <= 1e-300 depends on the
            // particle count; 64 does (the coverage check below guards it). Injection is off so it cannot
            // perturb the uniform weights.
            val collapseConfig = config.copy(particleCount = 64, correctionSpatialSigmaMetres = 0.05,
                wifiInjectionFraction = 0.0)
            val collapse = wifi(MetricPoint(5.0, 4.0), listOf(4 to 1.0), 1.0, uncertainty = 1.0, distance = 0.0)
            val relocalized = Recorder(factory.create(map, collapseConfig, 606L), "relocalize:").run {
                record("initialize") { filter.initialize(fix(4.0, 4.0)).json() }
                record("predictStep#1") { filter.predictStep(0.7, 0.0).json() }
                record("wifi:collapse", particles = true) { filter.correctWithWifi(collapse).json() }
                records
            }
            val lost = Recorder(factory.create(map, collapseConfig, 607L), "lose:").run {
                record("initialize") { filter.initialize(fix(4.0, 4.0)).json() }
                record("predictStep#1") { filter.predictStep(0.7, 0.0).json() }
                record("wifi:collapse") { filter.correctWithWifi(collapse, allowGlobalRelocalization = false).json() }
                record("predictStep#2") { filter.predictStep(0.7, 0.0).json() }
                record("wifi:weakWhileLost") {
                    filter.correctWithWifi(wifi(MetricPoint(15.0, 15.0), listOf(3 to 1.0), 0.5)).json()
                }
                record("wifi:strongWhileLost", particles = true) {
                    filter.correctWithWifi(wifi(MetricPoint(15.0, 15.0), listOf(3 to 1.0), 0.95, uncertainty = 2.0)).json()
                }
                records
            }
            relocalized + lost
        },
    )

    // ----- Fixture: 30 x 20 m single floor, 3 walls, 6 Wi-Fi fingerprints, 0.6 m magnetic map ----------

    private val floor = MapFloor(
        FLOOR,
        listOf(MetricPolygon(listOf(MetricPoint(0.0, 0.0), MetricPoint(30.0, 0.0), MetricPoint(30.0, 20.0),
            MetricPoint(0.0, 20.0)))),
        listOf(
            WallSegment("w1", MetricPoint(10.0, 0.0), MetricPoint(10.0, 12.0)),
            WallSegment("w2", MetricPoint(20.0, 8.0), MetricPoint(20.0, 20.0)),
            WallSegment("w3", MetricPoint(0.0, 16.0), MetricPoint(6.0, 16.0)),
        ),
    )
    private val map = MetricMap(mapOf(FLOOR to floor))
    private val config = ParticleFilterConfig(particleCount = PARTICLES)

    private val fingerprints = listOf(
        "fp-1" to MetricPoint(3.0, 3.0), "fp-2" to MetricPoint(8.0, 17.5), "fp-3" to MetricPoint(15.0, 5.0),
        "fp-4" to MetricPoint(15.0, 15.0), "fp-5" to MetricPoint(25.0, 4.0), "fp-6" to MetricPoint(27.0, 16.0),
    )

    private val magneticMaps: Map<String, MagneticFingerprintMap> = mapOf(FLOOR to MagneticFingerprintMap(FLOOR).apply {
        val cell = MagneticFingerprintMap.CELL_SIZE_METRES
        for (i in 0 until 37) for (j in 0 until 34) {
            val centre = MetricPoint((i + 0.5) * cell, (j + 0.5) * cell)
            add(centre, magneticField(centre))
        }
    })

    /** Stays plausible everywhere (magnitude 28-68 uT, |vertical| at most 24 uT) yet varies within a stride. */
    private fun magneticField(p: MetricPoint) =
        MagneticFeature(48.0 + 12.0 * sin(p.x * 1.3) + 8.0 * cos(p.y * 0.9), 20.0 + 4.0 * cos(p.x * 0.5) * sin(p.y * 0.4))

    /** Sharper than the default so the effective sample size drops and the filter resamples. */
    private val sharpMagnetic = MagneticCorrectionConfig(absoluteBiasMicroTesla = 1.0, outlierProbability = 0.02,
        likelihoodExponent = 1.0)

    private fun observationAt(p: MetricPoint): MagneticObservation {
        val f = magneticField(p)
        return MagneticObservation(MagneticFeature(f.magnitude + 0.4, f.vertical - 0.3), 25, true)
    }

    private fun fix(x: Double, y: Double) = AbsoluteFix(MetricPoint(x, y), FLOOR, AbsoluteFixSource.MANUAL)

    /** A hand-built match citing the fingerprint reference points, so the snapshot pins only the filter. */
    private fun wifi(
        estimate: MetricPoint,
        neighbours: List<Pair<Int, Double>>,
        confidence: Double,
        uncertainty: Double = 3.0,
        freshness: WifiScanFreshness = WifiScanFreshness.FRESH,
        ambiguous: Boolean = false,
        distance: Double = 4.0,
    ) = WifiMatchResult(
        estimatedPosition = estimate,
        estimatedFloorId = FLOOR,
        neighbours = neighbours.map { (index, weight) ->
            val (id, position) = fingerprints[index]
            WifiNeighbour(id, FLOOR, position, distance, weight, 0.0, 3)
        },
        confidence = confidence,
        uncertaintyRadiusMetres = uncertainty,
        floorAgreement = 1.0,
        weightedMatchDistance = distance,
        freshness = freshness,
        unlikeDatabase = false,
        spatiallyAmbiguous = ambiguous,
    )

    // ----- Recording -------------------------------------------------------------------------------------

    private class Record(val op: String, val result: JsonObject, val state: JsonObject) {
        fun resultKind(): String? = (result["kind"] as? JsonPrimitive)?.content
        fun intResult(key: String): Int = (result[key] as JsonPrimitive).content.toInt()
        fun toJson() = JsonObject(mapOf("op" to JsonPrimitive(op), "result" to result) + state)
    }

    private class Recorder(val filter: ParticleFilter, private val prefix: String = "") {
        val records = mutableListOf<Record>()
        private var seenEvents = 0

        fun record(op: String, particles: Boolean = false, action: () -> JsonObject) {
            val result = action()
            val events = filter.events
            val newEvents = events.drop(seenEvents).map {
                JsonArray(listOf(JsonPrimitive(it.sequence), JsonPrimitive(it.type.name), JsonPrimitive(it.rejectedParticleCount)))
            }
            seenEvents = events.size
            val current = filter.particles
            val state = linkedMapOf<String, JsonElement>(
                "summary" to summaryJson(filter.summary()),
                "particleCount" to JsonPrimitive(current.size),
                "effectiveParticleCount" to bits(filter.effectiveParticleCount()),
                "digest" to JsonPrimitive(digest(current)),
                "events" to JsonArray(newEvents),
            )
            if (particles) state["particles"] = JsonArray(current.map { it.json() })
            records += Record(prefix + op, result, JsonObject(state))
        }
    }

    private companion object {
        const val FLOOR = "F1"
        const val PARTICLES = 150
        const val RESOURCE = "/golden/particle_filter_v1.json"
        const val SOURCE_PATH = "src/test/resources/golden/particle_filter_v1.json"

        val updating: Boolean get() = System.getenv("GOLDEN_UPDATE") == "1" || System.getProperty("golden.update") == "true"

        /** Loaded once; in update mode it is regenerated from the first variant and written to the source tree. */
        val snapshot: JsonObject by lazy {
            if (updating) {
                val test = ParticleFilterGoldenTest()
                val factory = test.variants.first().second
                val generated = JsonObject(mapOf(
                    "format" to JsonPrimitive(1),
                    "note" to JsonPrimitive("Doubles are java.lang.Double.doubleToLongBits; particle rows are " +
                        "[floorId, x, y, weight, previousX, previousY, headingOffsetRadians, strideScale]."),
                    "scenarios" to JsonObject(test.scenarios.mapValues { (_, run) -> JsonArray(run(factory).map { it.toJson() }) }),
                ))
                val file = File(SOURCE_PATH)
                file.parentFile.mkdirs()
                file.writeText(render(generated) + "\n")
                generated
            } else {
                val text = ParticleFilterGoldenTest::class.java.getResourceAsStream(RESOURCE)?.bufferedReader()?.use { it.readText() }
                    ?: fail("Missing $RESOURCE; generate it with GOLDEN_UPDATE=1")
                Json.parseToJsonElement(text).jsonObject
            }
        }

        fun bits(value: Double) = JsonPrimitive(value.toBits())

        fun Particle.json() = JsonArray(listOf(JsonPrimitive(floorId), bits(x), bits(y), bits(weight), bits(previousX),
            bits(previousY), bits(headingOffsetRadians), bits(strideScale)))

        fun digest(particles: List<Particle>): String {
            val sha = MessageDigest.getInstance("SHA-256")
            particles.forEach { p ->
                sha.update(p.floorId.toByteArray())
                listOf(p.x, p.y, p.weight, p.previousX, p.previousY, p.headingOffsetRadians, p.strideScale)
                    .forEach { sha.update(it.toBits().toString(16).toByteArray()); sha.update(0) }
            }
            return sha.digest().take(12).joinToString("") { "%02x".format(it) }
        }

        fun summaryJson(summary: ParticleSummary?): JsonElement = summary?.let {
            JsonObject(mapOf(
                "x" to bits(it.position.x), "y" to bits(it.position.y), "floorId" to JsonPrimitive(it.floorId),
                "floorConfidence" to bits(it.floorConfidence), "confidence" to bits(it.confidence),
                "uncertaintyRadiusMetres" to bits(it.uncertaintyRadiusMetres),
                "spatialSpreadMetres" to bits(it.spatialSpreadMetres),
                "clusterParticleCount" to JsonPrimitive(it.clusterParticleCount),
            ))
        } ?: JsonNull

        fun ParticleSummary.json() = JsonObject(mapOf("summary" to summaryJson(this)))

        fun PredictionResult.json() = JsonObject(mapOf(
            "accepted" to JsonPrimitive(accepted),
            "rejectedParticleCount" to JsonPrimitive(rejectedParticleCount),
            "survivingParticleCountBeforeReplenishment" to JsonPrimitive(survivingParticleCountBeforeReplenishment),
            "constraints" to JsonObject(constraints.entries.sortedBy { it.key.name }
                .associate { it.key.name to JsonPrimitive(it.value) }),
            "summary" to summaryJson(summary),
        ))

        fun WifiCorrectionResult.json() = JsonObject(mapOf(
            "kind" to JsonPrimitive(kind.name),
            "resampled" to JsonPrimitive(resampled),
            "effectiveParticleCountBeforeResampling" to bits(effectiveParticleCountBeforeResampling),
            "summary" to summaryJson(summary),
        ))

        fun MagneticCorrectionResult.json() = JsonObject(mapOf(
            "kind" to JsonPrimitive(kind.name),
            "mappedWeight" to bits(mappedWeight),
            "resampled" to JsonPrimitive(resampled),
            "summary" to summaryJson(summary),
        ))

        /** Fails at the first differing path, decoding bit patterns so the drift is readable. */
        fun assertSame(expected: JsonElement, actual: JsonElement, path: String) {
            when {
                expected is JsonObject && actual is JsonObject -> {
                    assertEquals(expected.keys, actual.keys, "Keys differ at $path")
                    expected.keys.forEach { assertSame(expected.getValue(it), actual.getValue(it), "$path.$it") }
                }
                expected is JsonArray && actual is JsonArray -> {
                    val label = (expected.firstOrNull() as? JsonObject)?.get("op")
                    expected.zip(actual).forEachIndexed { i, (e, a) ->
                        val op = ((e as? JsonObject)?.get("op") as? JsonPrimitive)?.content
                        assertSame(e, a, if (label != null && op != null) "$path[$i:$op]" else "$path[$i]")
                    }
                    assertEquals(expected.size, actual.size, "Length differs at $path")
                }
                expected != actual -> {
                    fun describe(e: JsonElement) = (e as? JsonPrimitive)?.content?.toLongOrNull()
                        ?.let { "$it (as double ${Double.fromBits(it)})" } ?: e.toString()
                    fail("Golden mismatch at $path: expected ${describe(expected)}, actual ${describe(actual)}")
                }
            }
        }

        /** Pretty JSON with arrays of primitives on one line, so each particle is one diffable row. */
        fun render(element: JsonElement, indent: String = ""): String {
            val inner = "$indent  "
            return when (element) {
                is JsonObject -> if (element.isEmpty()) "{}" else element.entries.joinToString(",\n", "{\n", "\n$indent}") { (k, v) ->
                    "$inner${JsonPrimitive(k)}: ${render(v, inner)}"
                }
                is JsonArray -> when {
                    element.isEmpty() -> "[]"
                    element.all { it is JsonPrimitive } -> element.joinToString(", ", "[", "]")
                    else -> element.joinToString(",\n", "[\n", "\n$indent]") { "$inner${render(it, inner)}" }
                }
                else -> element.toString()
            }
        }
    }
}
