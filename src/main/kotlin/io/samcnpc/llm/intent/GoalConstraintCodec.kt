package io.samcnpc.llm.intent

import com.google.gson.*
import io.samcnpc.behavior.api.OperationType
import io.samcnpc.behavior.api.OperationWorkBox
import io.samcnpc.core.api.NpcBlockPosition
import io.samcnpc.core.api.NpcPosition
import io.samcnpc.llm.goal.GoalRecord
import io.samcnpc.llm.provider.LlmJson

internal data class BoundedGoalRequest(val text: String, val constraints: GoalConstraints)

/** Player command and saved-data codecs. Never called on model output or in the per-NPC tick loop. */
internal object GoalConstraintCodec {
    const val MAX_BYTES = 8192
    private val fields = setOf("version", "operations", "resourceIds", "dimensionId", "quantityMeaning", "quantity",
        "workBox", "exclusions", "sources", "destinations", "navigation", "allowAcquisition", "allowDestruction",
        "allowAuxiliaryBlockWork", "allowPlayers")

    fun playerDocument(text: String): BoundedGoalRequest {
        val root = parse(text)
        require(root.keySet() == setOf("goal", "constraints"))
        val goal = string(root["goal"])
        require(GoalRecord.validText(goal, 1024))
        require(root["constraints"].isJsonObject)
        return BoundedGoalRequest(goal, decode(root["constraints"].asJsonObject, persisted = false))
    }

    fun saved(text: String): GoalConstraints = decode(parse(text), persisted = true)

    private fun parse(text: String): JsonObject = try {
        LlmJson.parse(text, MAX_BYTES)
    } catch (error: java.io.IOException) {
        throw IllegalArgumentException("Malformed goal constraints", error)
    }

    fun encode(value: GoalConstraints, persisted: Boolean = true): JsonObject = JsonObject().also { root ->
        root.addProperty("version", value.version)
        root.add("operations", strings(value.operations.map { it.operationId }.sorted()))
        root.add("resourceIds", strings(value.resourceIds.sorted()))
        root.addProperty("dimensionId", value.dimensionId)
        root.addProperty("quantityMeaning", value.quantityMeaning.name)
        root.addProperty("quantity", value.quantity)
        if (persisted) root.addProperty("initialStock", value.initialStock)
        root.add("workBox", value.workBox?.let(::boxJson) ?: JsonNull.INSTANCE)
        root.add("exclusions", array(value.exclusions.map(::boxJson)))
        root.add("sources", array(value.sources.map(::positionJson)))
        root.add("destinations", array(value.destinations.map(::positionJson)))
        root.add("navigation", array(value.navigation.map {
            JsonObject().also { point -> point.addProperty("x", it.x); point.addProperty("y", it.y); point.addProperty("z", it.z) }
        }))
        root.addProperty("allowAcquisition", value.allowAcquisition)
        root.addProperty("allowDestruction", value.allowDestruction)
        root.addProperty("allowAuxiliaryBlockWork", value.allowAuxiliaryBlockWork)
        root.addProperty("allowPlayers", value.allowPlayers)
    }

    private fun decode(root: JsonObject, persisted: Boolean): GoalConstraints {
        require(root.keySet() == fields + if (persisted) setOf("initialStock") else emptySet())
        require(integer(root["version"]) == 1)
        val operations = list(root["operations"], 6).map { entry ->
            OperationType.entries.singleOrNull { it.operationId == string(entry) } ?: throw IllegalArgumentException("Unknown operation")
        }
        val ids = list(root["resourceIds"], 16).map(::string)
        require(operations.distinct().size == operations.size && ids.distinct().size == ids.size)
        require(!flag(root["allowPlayers"])) { "Bounded resource goals cannot authorize player attacks" }
        val mode = GoalQuantityMeaning.entries.singleOrNull { it.name == string(root["quantityMeaning"]) }
            ?: throw IllegalArgumentException("Unknown quantity meaning")
        return GoalConstraints(operations.toSet(), ids.toSet(), string(root["dimensionId"]), mode,
            integer(root["quantity"]), if (persisted) integer(root["initialStock"]) else 0,
            if (root["workBox"].isJsonNull) null else box(root["workBox"]),
            list(root["exclusions"], 8).map(::box), list(root["sources"], 8).map(::position),
            list(root["destinations"], 8).map(::position), list(root["navigation"], 16).map { value ->
                val point = record(value, setOf("x", "y", "z"))
                NpcPosition(decimal(point["x"]), decimal(point["y"]), decimal(point["z"]))
            }, flag(root["allowAcquisition"]), flag(root["allowDestruction"]), flag(root["allowAuxiliaryBlockWork"]))
    }

    private fun record(value: JsonElement, keys: Set<String>): JsonObject {
        require(value.isJsonObject && value.asJsonObject.keySet() == keys)
        return value.asJsonObject
    }
    private fun list(value: JsonElement, max: Int): List<JsonElement> {
        require(value.isJsonArray && value.asJsonArray.size() <= max)
        return value.asJsonArray.toList()
    }
    private fun string(value: JsonElement): String {
        require(value.isJsonPrimitive && value.asJsonPrimitive.isString)
        return value.asString
    }
    private fun flag(value: JsonElement): Boolean {
        require(value.isJsonPrimitive && value.asJsonPrimitive.isBoolean)
        return value.asBoolean
    }
    private fun integer(value: JsonElement): Int {
        require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber)
        return try { value.asBigDecimal.intValueExact() }
        catch (error: ArithmeticException) { throw IllegalArgumentException("Expected bounded integer", error) }
    }
    private fun decimal(value: JsonElement): Double {
        require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber)
        val result = value.asDouble
        require(result.isFinite())
        return result
    }
    private fun position(value: JsonElement): NpcBlockPosition {
        val point = record(value, setOf("x", "y", "z"))
        return NpcBlockPosition(integer(point["x"]), integer(point["y"]), integer(point["z"]))
    }
    private fun box(value: JsonElement): OperationWorkBox {
        val fields = record(value, setOf("min", "max"))
        return OperationWorkBox(position(fields["min"]), position(fields["max"]))
    }
    private fun positionJson(value: NpcBlockPosition): JsonObject = JsonObject().also {
        it.addProperty("x", value.x); it.addProperty("y", value.y); it.addProperty("z", value.z)
    }
    private fun boxJson(value: OperationWorkBox): JsonObject = JsonObject().also {
        it.add("min", positionJson(value.min)); it.add("max", positionJson(value.max))
    }
    private fun array(values: List<JsonElement>): JsonArray = JsonArray().also { target -> values.forEach(target::add) }
    private fun strings(values: List<String>): JsonArray = JsonArray().also { target -> values.forEach(target::add) }
}
