package io.samcnpc.llm.evaluation

import com.google.gson.*
import io.samcnpc.behavior.api.*
import io.samcnpc.llm.decision.*

/** Frozen-intent comparison, independent of the model's explanation and never an execution receipt. */
internal object CorpusSemanticScore {
    private val resources = setOf("itemId", "itemIds", "outputs", "resources", "wood", "species", "crop", "typeIds", "tagIds")
    private val quantities = setOf("quantity", "catches", "needs", "reserves", "keepAtLeast", "sourceKeepAtLeast", "keepFood", "keepSeeds", "keepSaplings", "limit", "maxItems")
    private val areas = setOf("area", "tunnel", "leash", "radius", "verticalRange")
    private val sources = setOf("sources", "feeds", "supplySources", "seedSources")
    private val destinations = setOf("destination", "destinations", "output", "returnTo", "anchor", "standing", "water", "targetUuid", "subjectUuid", "supportTargetUuid", "waypoints")
    private val knobs = setOf("budget", "speed", "arrivalDistance", "travelRadius", "workTicks", "maxSteps", "pollTicks", "noProgressTicks", "pickupWaitTicks", "tools", "tactics", "growthWaitTicks", "growthCheckTicks")

    fun score(row: JsonObject, decision: LlmDecision?, state: JsonObject, policyProblem: String?): JsonObject {
        val result = JsonObject()
        val expectedKind = row["expectedDecision"].asString
        val decisionCorrect = decision?.kind?.name == expectedKind
        result.addProperty("decision", decisionCorrect)
        result.addProperty("clarification", decision != null && (decision.kind == DecisionKind.ASK_USER) == (expectedKind == "ASK_USER"))
        result.addProperty("typedValid", decision != null)
        result.addProperty("policyAccepted", decision != null && policyProblem == null)
        for (name in listOf("family", "resource", "quantitySemantics", "area", "source", "destination", "additionalParameters", "strictTypedOracle")) result.add(name, JsonNull.INSTANCE)
        if (expectedKind != "ASSIGN") {
            result.addProperty("semanticCorrect", decisionCorrect && policyProblem == null)
            return result
        }
        val original = row["expectedOperation"].asJsonObject
        val expected = OperationDocumentApi.decodeOrder(original.toString())
        check(expected is OperationDocumentResult.Accepted) { "Frozen oracle must be valid: " + row["id"].asString }
        val actual = (decision?.action as? DecisionAction.Assign)?.order
        val family = actual?.type == expected.value.type
        result.addProperty("family", family)
        if (actual == null || !family) {
            for (name in listOf("resource", "quantitySemantics", "area", "source", "destination", "additionalParameters", "strictTypedOracle", "semanticCorrect")) result.addProperty(name, false)
            return result
        }
        val expectedTree = tree(expected.value)
        val actualTree = tree(actual)
        result.addProperty("strictTypedOracle", Gson().toJsonTree(expected.value) == Gson().toJsonTree(actual))
        val explicit = fieldNames(original["parameters"])
        val ignored = knobs - explicit
        val expectedFlat = flatten(expectedTree).filterKeys { !has(it, ignored) }
        val actualFlat = flatten(actualTree).filterKeys { !has(it, ignored) }
        fun equal(fields: Set<String>) = expectedFlat.filterKeys { has(it, fields) } == actualFlat.filterKeys { has(it, fields) }
        val supply = expected.value as? OperationInventoryOrder
        val needs = supply?.work as? OperationInventoryWork.Supply
        val actualNeeds = (actual as? OperationInventoryOrder)?.work as? OperationInventoryWork.Supply
        val quantity = if (needs == null) equal(quantities) else actualNeeds != null &&
            needs.needs.map { it.itemId }.toSet() == actualNeeds.needs.map { it.itemId }.toSet() && needs.needs.all { need ->
                val value = actualNeeds.needs.singleOrNull { it.itemId == need.itemId } ?: return@all false
                val current = state["inventory"].asJsonArray.sumOf { slot ->
                    val item = slot.asJsonObject["item"]
                    if (!item.isJsonNull && item.asString == need.itemId) slot.asJsonObject["count"].asInt else 0
                }
                value.target == need.target && value.minimum <= value.target &&
                    (current >= need.target || value.minimum > current) && value.sourceReserve == need.sourceReserve
            }
        val grouped = resources + quantities + areas + sources + destinations
        val additional = expectedFlat.filterKeys { !has(it, grouped) } == actualFlat.filterKeys { !has(it, grouped) }
        val measures = mapOf("resource" to equal(resources), "quantitySemantics" to quantity,
            "area" to equal(areas), "source" to equal(sources), "destination" to equal(destinations), "additionalParameters" to additional)
        for ((name, value) in measures) result.addProperty(name, value)
        result.addProperty("semanticCorrect", decisionCorrect && policyProblem == null && measures.values.all { it })
        return result
    }

    private fun tree(order: OperationOrder): JsonObject {
        val root = Gson().toJsonTree(order).asJsonObject
        // Gson test serialization omits computed discriminators; retain these distinct semantic variants.
        val work = when (order) {
            is OperationInventoryOrder -> order.work.javaClass.simpleName
            is OperationHarvestOrder.Food -> order.work.javaClass.simpleName
            else -> null
        }
        if (work != null) root.getAsJsonObject("work").addProperty("variant", work)
        if (order is OperationHarvestOrder.Lumberjack) {
            val normalized = order.wood.selectors.flatMap { selector -> when (selector) {
                "samcnpc:oak_and_birch" -> listOf("species:oak", "species:birch")
                "samcnpc:oak", "minecraft:oak_log", "minecraft:oak_wood", "minecraft:stripped_oak_log", "minecraft:stripped_oak_wood" -> listOf("species:oak")
                "samcnpc:birch", "minecraft:birch_log", "minecraft:birch_wood", "minecraft:stripped_birch_log", "minecraft:stripped_birch_wood" -> listOf("species:birch")
                "samcnpc:dark_oak", "minecraft:dark_oak_log", "minecraft:dark_oak_wood", "minecraft:stripped_dark_oak_log", "minecraft:stripped_dark_oak_wood" -> listOf("species:dark_oak")
                else -> listOf(selector)
            } }.distinct().sorted()
            root.getAsJsonObject("wood").add("selectors", JsonArray().also { array -> normalized.forEach(array::add) })
        }
        return root
    }
    private fun fieldNames(value: JsonElement): Set<String> = when {
        value.isJsonObject -> value.asJsonObject.entrySet().flatMap { listOf(it.key) + fieldNames(it.value) }.toSet()
        value.isJsonArray -> value.asJsonArray.flatMap { fieldNames(it) }.toSet()
        else -> emptySet()
    }
    private fun has(path: String, fields: Set<String>) = path.split('.').any { it.substringBefore('[') in fields }
    private fun flatten(value: JsonElement): Map<String, String> {
        val result = linkedMapOf<String, String>()
        fun visit(node: JsonElement, path: String) {
            when {
                node.isJsonObject && node.asJsonObject.size() > 0 -> node.asJsonObject.entrySet().forEach { visit(it.value, "$path.${it.key}") }
                node.isJsonArray && !node.asJsonArray.isEmpty -> node.asJsonArray.forEachIndexed { index, item -> visit(item, "$path[$index]") }
                node.isJsonPrimitive && node.asJsonPrimitive.isNumber -> result[path] = node.asBigDecimal.stripTrailingZeros().toPlainString()
                else -> result[path] = node.toString()
            }
        }
        visit(value, "")
        return result
    }
}
