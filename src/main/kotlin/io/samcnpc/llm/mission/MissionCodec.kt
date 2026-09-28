package io.samcnpc.llm.mission

import com.google.gson.*
import io.samcnpc.core.api.NpcBlockPosition
import io.samcnpc.core.api.NpcPosition
import io.samcnpc.llm.provider.LlmJson

internal class MissionValidationException(val code: String) : IllegalArgumentException(code)

/** Closed versioned intent documents shared by persistence and the inference boundary. */
internal object MissionCodec {
    const val VERSION = 1
    const val MAX_BYTES = 8192

    fun contract(text: String): MissionContract {
        val root = parse(text)
        fields(root, setOf("version", "requirements"))
        require(integer(root["version"]) == VERSION)
        return MissionContract(array(root["requirements"], 24).map { value ->
            val row = fields(value, setOf("id", "source", "after", "target"))
            MissionRequirement(string(row["id"]), string(row["source"]), target(row["target"]),
                array(row["after"], 23).map(::string))
        })
    }

    fun plan(text: String): MissionPlan {
        val root = parse(text)
        fields(root, setOf("version", "steps"))
        require(integer(root["version"]) == VERSION)
        return MissionPlan(array(root["steps"], 8).map { value ->
            val row = fields(value, setOf("description", "covers"))
            MissionStep(string(row["description"]), array(row["covers"], 24).map(::string))
        })
    }

    fun encode(contract: MissionContract): JsonObject = JsonObject().also { root ->
        root.addProperty("version", VERSION)
        root.add("requirements", values(contract.requirements.map { row -> JsonObject().also {
            it.addProperty("id", row.id); it.addProperty("source", row.source)
            it.add("after", strings(row.after)); it.add("target", encode(row.target))
        } }))
    }

    fun encode(plan: MissionPlan): JsonObject = JsonObject().also { root ->
        root.addProperty("version", VERSION)
        root.add("steps", values(plan.steps.map { row -> JsonObject().also {
            it.addProperty("description", row.description); it.add("covers", strings(row.covers))
        } }))
    }

    private fun target(value: JsonElement): MissionTarget {
        require(value.isJsonObject && value.asJsonObject.has("kind"))
        val row = value.asJsonObject
        return when (string(row["kind"])) {
            "COLLECT" -> {
                fields(row, setOf("kind", "dimension", "position"))
                MissionTarget.Collect(MissionChest(string(row["dimension"]), block(row["position"])))
            }
            "ITEMS" -> {
                fields(row, setOf("kind", "items", "minimum", "chest"))
                val chest = if (row["chest"].isJsonNull) null else {
                    val location = fields(row["chest"], setOf("dimension", "position"))
                    MissionChest(string(location["dimension"]), block(location["position"]))
                }
                MissionTarget.Items(array(row["items"], 4).map(::string), integer(row["minimum"]), chest)
            }
            "VISIT" -> {
                fields(row, setOf("kind", "dimension", "position"))
                val point = fields(row["position"], setOf("x", "y", "z"))
                MissionTarget.Visit(string(row["dimension"]),
                    NpcPosition(decimal(point["x"]), decimal(point["y"]), decimal(point["z"])))
            }
            "FIELD" -> {
                fields(row, setOf("kind", "dimension", "min", "max"))
                val min = block(row["min"]); val max = block(row["max"])
                if (min.y != max.y) throw MissionValidationException("MISSION_FIELD_REQUIRES_ONE_SOIL_PLANE")
                if (min.x > max.x || min.z > max.z || (max.x.toLong() - min.x + 1) * (max.z.toLong() - min.z + 1) !in 1..256)
                    throw MissionValidationException("MISSION_FIELD_AREA_OUT_OF_BOUNDS")
                MissionTarget.Field(string(row["dimension"]), min, max)
            }
            else -> throw IllegalArgumentException("Unknown mission target")
        }
    }

    private fun encode(target: MissionTarget): JsonObject = JsonObject().also { out ->
        when (target) {
            is MissionTarget.Collect -> {
                out.addProperty("kind", "COLLECT"); out.addProperty("dimension", target.chest.dimension)
                out.add("position", block(target.chest.position))
            }
            is MissionTarget.Items -> {
                out.addProperty("kind", "ITEMS"); out.add("items", strings(target.itemIds))
                out.addProperty("minimum", target.minimum)
                out.add("chest", target.chest?.let { chest -> JsonObject().also {
                    it.addProperty("dimension", chest.dimension); it.add("position", block(chest.position))
                } } ?: JsonNull.INSTANCE)
            }
            is MissionTarget.Visit -> {
                out.addProperty("kind", "VISIT"); out.addProperty("dimension", target.dimension)
                out.add("position", JsonObject().also {
                    it.addProperty("x", target.position.x); it.addProperty("y", target.position.y); it.addProperty("z", target.position.z)
                })
            }
            is MissionTarget.Field -> {
                out.addProperty("kind", "FIELD"); out.addProperty("dimension", target.dimension)
                out.add("min", block(target.min)); out.add("max", block(target.max))
            }
        }
    }

    private fun block(value: JsonElement): NpcBlockPosition {
        val row = fields(value, setOf("x", "y", "z"))
        return NpcBlockPosition(integer(row["x"]), integer(row["y"]), integer(row["z"]))
    }
    private fun block(value: NpcBlockPosition): JsonObject = JsonObject().also {
        it.addProperty("x", value.x); it.addProperty("y", value.y); it.addProperty("z", value.z)
    }
    internal fun parse(text: String): JsonObject = try { LlmJson.parse(text, MAX_BYTES) }
        catch (error: java.io.IOException) { throw IllegalArgumentException("Invalid mission encoding", error) }
    internal fun fields(value: JsonElement, keys: Set<String>): JsonObject {
        require(value.isJsonObject && value.asJsonObject.keySet() == keys)
        return value.asJsonObject
    }
    internal fun array(value: JsonElement, max: Int): List<JsonElement> {
        require(value.isJsonArray && value.asJsonArray.size() <= max)
        return value.asJsonArray.toList()
    }
    internal fun string(value: JsonElement): String {
        require(value.isJsonPrimitive && value.asJsonPrimitive.isString)
        return value.asString
    }
    internal fun integer(value: JsonElement): Int {
        require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber)
        return try { value.asBigDecimal.intValueExact() }
            catch (error: ArithmeticException) { throw IllegalArgumentException("Expected bounded integer", error) }
    }
    private fun decimal(value: JsonElement): Double {
        require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber)
        return value.asDouble.also { require(it.isFinite()) }
    }
    internal fun values(values: List<JsonElement>): JsonArray = JsonArray().also { out -> values.forEach(out::add) }
    internal fun strings(values: List<String>): JsonArray = JsonArray().also { out -> values.forEach(out::add) }
}
