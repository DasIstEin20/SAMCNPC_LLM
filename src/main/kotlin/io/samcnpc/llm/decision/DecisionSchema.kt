package io.samcnpc.llm.decision

import com.google.gson.*
import io.samcnpc.behavior.api.OperationDocumentApi
import io.samcnpc.llm.context.ContextPolicy
import java.util.ArrayDeque
import java.util.UUID

/** Uses published Behavior definitions; only non-validating annotations and unreachable definitions are removed. */
internal object DecisionSchema {
    private const val DEFS = "$" + "defs"
    private const val REF = "$" + "ref"
    private const val PREFIX = "#/$" + "defs/"
    private val definitions: String by lazy {
        val exported = JsonParser.parseString(OperationDocumentApi.orderSchema()).asJsonObject
        compact(exported.getAsJsonObject(DEFS)).toString()
    }

    fun forContext(contextId: UUID, policy: ContextPolicy, planner: Boolean = false, hasActiveTask: Boolean? = null): String {
        val defs = JsonParser.parseString(definitions).asJsonObject
        val operations = policy.operations.sortedBy { it.ordinal }
        if (operations.isNotEmpty()) {
            defs.add("orderDocument", alternatives(operations.map { reference("order_" + it.name) }))
        }
        val changes = policy.changes.sorted().filter { it != "REPLACE" || operations.isNotEmpty() }
        val props = JsonObject()
        props.add("schemaVersion", typed("integer").also { it.addProperty("const", if (planner) 2 else DecisionDecoder.VERSION) })
        props.add("contextId", typed("string").also { it.add("enum", strings(listOf(contextId.toString()))) })
        val decisions = DecisionKind.entries.filter { kind -> when (kind) {
            DecisionKind.CONTINUE -> hasActiveTask != false || planner
            DecisionKind.ASSIGN -> operations.isNotEmpty() && hasActiveTask != true
            DecisionKind.AMEND -> changes.isNotEmpty() && hasActiveTask != false
            DecisionKind.PAUSE, DecisionKind.RESUME, DecisionKind.CANCEL ->
                policy.controls.any { it.name == kind.name } && hasActiveTask != false
            else -> true
        } }
        props.add("decision", typed("string").also { it.add("enum", strings(decisions.map { it.name })) })
        props.add("summary", boundedText(256))
        props.add("operation", if (operations.isEmpty()) typed("null") else nullable(reference("orderDocument")))
        props.add("change", if (changes.isEmpty()) typed("null") else nullable(alternatives(changes.map { reference("change_" + it) })))
        props.add("question", nullable(boundedText(256).also { it.addProperty("minLength", 1) }))
        val waitProperties = JsonObject()
        waitProperties.add("trigger", typed("string").also { it.add("enum", strings(WaitTrigger.entries.map { trigger -> trigger.name })) })
        waitProperties.add("ticks", nullable(typed("integer").also {
            it.addProperty("minimum", 20); it.addProperty("maximum", 1200)
        }))
        props.add("wait", nullable(record(waitProperties)))
        if (planner) props.add("plan", nullable(planSchema()))
        val root = record(props)

        val pending = ArrayDeque<String>()
        references(root).forEach(pending::addLast)
        val used = linkedSetOf<String>()
        while (pending.isNotEmpty()) {
            val name = pending.removeFirst()
            if (!used.add(name)) continue
            val definition = checkNotNull(defs[name]) { "Published operation schema reference is missing" }
            for (child in references(definition)) pending.addLast(child)
        }
        val reachable = JsonObject()
        for (name in used.sorted()) reachable.add(name, defs[name])
        if (used.isNotEmpty()) root.add(DEFS, reachable)
        return root.toString()
    }

    private fun compact(value: JsonElement): JsonElement = when {
        value.isJsonObject -> {
            val result = JsonObject()
            for ((key, child) in value.asJsonObject.entrySet()) {
                if (key == "description" || key == "$" + "schema" || key.startsWith("x-")) continue
                result.add(key, compact(child))
            }
            result
        }
        value.isJsonArray -> JsonArray().also { out -> value.asJsonArray.forEach { out.add(compact(it)) } }
        else -> value.deepCopy()
    }

    private fun references(value: JsonElement): List<String> {
        val result = mutableListOf<String>()
        fun visit(element: JsonElement) {
            if (element.isJsonObject) {
                for ((key, child) in element.asJsonObject.entrySet()) {
                    if (key == REF) {
                        val ref = child.asString
                        check(ref.startsWith(PREFIX)) { "Only local published operation schema references are supported" }
                        result.add(ref.removePrefix(PREFIX))
                    } else visit(child)
                }
            } else if (element.isJsonArray) element.asJsonArray.forEach(::visit)
        }
        visit(value)
        return result
    }

    private fun planSchema(): JsonObject {
        val props = JsonObject()
        props.add("steps", typed("array").also {
            it.addProperty("minItems", 1); it.addProperty("maxItems", 8)
            it.add("items", boundedText(256).also { text -> text.addProperty("minLength", 1) })
        })
        val item = JsonObject()
        item.add("itemId", boundedText(256).also { it.addProperty("minLength", 1) })
        item.add("minimum", typed("integer").also { it.addProperty("minimum", 1); it.addProperty("maximum", 2304) })
        props.add("requiredItems", typed("array").also {
            it.addProperty("maxItems", 8); it.add("items", record(item))
        })
        props.add("minimumEmptySlots", typed("integer").also {
            it.addProperty("minimum", 0); it.addProperty("maximum", 36)
        })
        return record(props)
    }

    private fun typed(type: String) = JsonObject().also { it.addProperty("type", type) }
    private fun boundedText(maximum: Int) = typed("string").also { it.addProperty("maxLength", maximum) }
    private fun reference(name: String) = JsonObject().also { it.addProperty(REF, PREFIX + name) }
    private fun strings(values: List<String>) = JsonArray().also { array -> values.forEach(array::add) }
    private fun nullable(value: JsonElement) = JsonObject().also {
        it.add("anyOf", JsonArray().also { choices -> choices.add(value); choices.add(typed("null")) })
    }
    private fun alternatives(values: List<JsonElement>): JsonObject {
        require(values.isNotEmpty())
        return JsonObject().also { it.add("oneOf", JsonArray().also { array -> values.forEach(array::add) }) }
    }
    private fun record(properties: JsonObject) = typed("object").also {
        it.add("properties", properties)
        it.add("required", strings(properties.keySet().toList()))
        it.addProperty("additionalProperties", false)
    }
}
