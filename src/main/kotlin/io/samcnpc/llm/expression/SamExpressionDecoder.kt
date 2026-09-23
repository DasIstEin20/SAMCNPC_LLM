package io.samcnpc.llm.expression

import com.google.gson.*
import io.samcnpc.llm.decision.*
import java.io.IOException
import java.util.UUID

/** Context identity comes from the original request, never model text or a later snapshot. */
internal object SamExpressionDecoder {
    fun decode(source: String, contextId: UUID, planner: Boolean): DecisionDecodeResult = try {
        val call = SamExpressionSyntax.parse(source)
        val root = JsonObject()
        root.addProperty("schemaVersion", if (planner) 2 else 1)
        root.addProperty("contextId", contextId.toString())
        root.addProperty("summary", "")
        for (name in listOf("operation", "change", "question", "wait")) root.add(name, JsonNull.INSTANCE)
        if (planner) root.add("plan", JsonNull.INSTANCE)
        val kind = when (call.name) {
            "continue_task", "pause", "resume", "cancel" -> {
                require(call.arguments.isEmpty()) { "EXPRESSION_ARGUMENT" }
                when (call.name) {
                    "continue_task" -> "CONTINUE"
                    else -> call.name.uppercase(java.util.Locale.ROOT)
                }
            }
            "ask_user" -> {
                val args = arguments(call, listOf("question"), setOf("question"))
                val question = args.getValue("question")
                require(question is SamValue.Text) { "EXPRESSION_QUESTION" }
                root.addProperty("question", question.value)
                "ASK_USER"
            }
            "wait" -> {
                val args = arguments(call, emptyList(), setOf("trigger", "ticks"))
                val trigger = args["trigger"]
                require(trigger is SamValue.Text) { "EXPRESSION_WAIT" }
                val wait = JsonObject()
                wait.addProperty("trigger", trigger.value)
                when (val ticks = args["ticks"]) {
                    null, SamValue.Null -> wait.add("ticks", JsonNull.INSTANCE)
                    is SamValue.Number -> {
                        require(ticks.integer) { "EXPRESSION_WAIT" }
                        wait.addProperty("ticks", ticks.decimal)
                    }
                    else -> throw IllegalArgumentException("EXPRESSION_WAIT")
                }
                root.add("wait", wait)
                "WAIT"
            }
            "assign" -> {
                val args = arguments(call, listOf("operation"), setOf("operation", "plan"))
                root.add("operation", SamExpressionCatalog.order(args["operation"] ?: errorArgument()))
                if (planner) root.add("plan", plan(args["plan"] ?: errorArgument()))
                else require("plan" !in args) { "EXPRESSION_PLANNER_ONLY" }
                "ASSIGN"
            }
            "amend" -> {
                val args = arguments(call, listOf("change"), setOf("change"))
                root.add("change", SamExpressionCatalog.change(args["change"] ?: errorArgument()))
                "AMEND"
            }
            else -> throw IllegalArgumentException("EXPRESSION_TOP_LEVEL")
        }
        root.addProperty("decision", kind)
        DecisionDecoder.decode(root.toString())
    } catch (error: IllegalArgumentException) {
        // Only our fixed codes are exposed; no untrusted text is echoed as a diagnostic.
        val code = error.message?.takeIf { it.startsWith("EXPRESSION_") && it.all { c -> c in 'A'..'Z' || c == '_' } }
        DecisionDecodeResult.Rejected(code ?: "INVALID_EXPRESSION")
    } catch (_: IOException) {
        DecisionDecodeResult.Rejected("INVALID_EXPRESSION_ENCODING")
    }

    private fun errorArgument(): Nothing = throw IllegalArgumentException("EXPRESSION_MISSING_ARGUMENT")

    private fun arguments(call: SamValue.Call, positional: List<String>, allowed: Set<String>): Map<String, SamValue> {
        val result = linkedMapOf<String, SamValue>()
        for ((index, argument) in call.arguments.withIndex()) {
            val name = argument.name ?: positional.getOrNull(index) ?: errorArgument()
            require(name in allowed && name !in result) { "EXPRESSION_ARGUMENT" }
            result[name] = argument.value
        }
        return result
    }

    private fun plan(value: SamValue): JsonObject {
        require(value is SamValue.Call && value.name == "plan") { "EXPRESSION_PLAN" }
        val args = arguments(value, emptyList(), setOf("steps", "required_items", "minimum_empty_slots"))
        require(args.keys == setOf("steps", "required_items", "minimum_empty_slots")) { "EXPRESSION_PLAN" }
        val steps = args.getValue("steps")
        val required = args.getValue("required_items")
        val empty = args.getValue("minimum_empty_slots")
        require(steps is SamValue.Sequence && required is SamValue.Sequence && empty is SamValue.Number && empty.integer) { "EXPRESSION_PLAN" }
        val result = JsonObject()
        result.add("steps", JsonArray().also { array -> for (step in steps.values) {
            require(step is SamValue.Text) { "EXPRESSION_PLAN" }; array.add(step.value)
        } })
        result.add("requiredItems", JsonArray().also { array -> for (item in required.values) {
            require(item is SamValue.Call && item.name == "record") { "EXPRESSION_PLAN" }
            val fields = arguments(item, emptyList(), setOf("item_id", "minimum"))
            require(fields.keys == setOf("item_id", "minimum")) { "EXPRESSION_PLAN" }
            val id = fields.getValue("item_id")
            val minimum = fields.getValue("minimum")
            require(id is SamValue.Text && minimum is SamValue.Number && minimum.integer) { "EXPRESSION_PLAN" }
            array.add(JsonObject().also { it.addProperty("itemId", id.value); it.addProperty("minimum", minimum.decimal) })
        } })
        result.addProperty("minimumEmptySlots", empty.decimal)
        return result
    }
}
