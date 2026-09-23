package io.samcnpc.llm.expression

import com.google.gson.JsonObject
import io.samcnpc.behavior.api.*
import java.util.ArrayDeque

/** Visible type notation generated from the same published definitions as the JSON contract. */
internal object SamExpressionContract {
    fun describe(schema: JsonObject): String {
        val props = schema.getAsJsonObject("properties")
        val kinds = props.getAsJsonObject("decision").getAsJsonArray("enum").map { it.asString }.toSet()
        val defs = schema.getAsJsonObject("$" + "defs")
        val planner = props.getAsJsonObject("schemaVersion")["const"].asInt == 2
        val catalog = SamExpressionCatalog.catalog
        val operations = catalog.operations.filter { defs?.has("order_" + it.type.name) == true }
        val changes = catalog.changes.filterKeys { defs?.has("change_" + it) == true }
        val pending = ArrayDeque<String>()
        val seen = mutableSetOf<String>()
        fun ref(name: String): String {
            if (name == "orderDocument") return "Operation"
            if (seen.add(name)) pending.addLast(name)
            return SamExpressionCatalog.snake(name)
        }
        fun type(input: OperationInput): String = when (input) {
            OperationInput.Flag -> "True|False"
            is OperationInput.Numeric -> (if (input.integer) "int" else "number") +
                "[" + decimal(input.minimum) + ".." + decimal(input.maximum) + "]"
            is OperationInput.Text -> input.format.name.lowercase(java.util.Locale.ROOT) + "[max=" + input.maxLength + "]"
            is OperationInput.Choice -> input.values.joinToString("|") { "\"$it\"" }
            is OperationInput.Sequence -> "list[" + type(input.item) + ";" + input.minimum + ".." + input.maximum +
                (if (input.unique) ";unique" else "") + "]"
            is OperationInput.Reference -> ref(input.name)
            is OperationInput.Alternatives -> input.names.joinToString("|") { ref(it) }
            is OperationInput.Record -> "record(" + input.fields.joinToString(",") { field ->
                describeField(field, ::type)
            } + ")"
        }
        fun signature(name: String, shape: OperationInput.Record): String {
            val fields = shape.fields.joinToString(",") { field -> describeField(field, ::type) }
            val relations = shape.relations.joinToString(" ") { it.description }
            return name + "(" + fields + ")" + if (relations.isEmpty()) "" else " ; " + relations
        }
        return buildString {
            append("SAM_EXPRESSION_V1_UNCONSTRAINED\n")
            append("Exactly one expression; the signatures below are type notation, not output. ")
            append("Arguments use name=value, strings use quotes, booleans True/False, null None. ")
            append("No Python execution, variables, operators, attributes, comments or code fences. ")
            append("Required arguments have no ?. Omit optional arguments for Behavior defaults. ")
            append("Ranges are limits, not defaults. Enum values are alternatives, not a checklist.\n")
            if ("CONTINUE" in kinds) append("continue_task()\n")
            if ("ASK_USER" in kinds) append("ask_user(question:string[1..256])\n")
            if ("WAIT" in kinds) append("wait(trigger=\"TASK_TERMINAL\"|\"USER_UPDATE\"|\"DEADLINE\",ticks?:int[20..1200]) ; ticks only for DEADLINE.\n")
            for (name in listOf("PAUSE", "RESUME", "CANCEL")) if (name in kinds)
                append(name.lowercase(java.util.Locale.ROOT)).append("()\n")
            if ("ASSIGN" in kinds) {
                append(if (planner) "assign(Operation,plan=plan(...))\n" else "assign(Operation)\n")
            }
            if ("AMEND" in kinds) {
                append("amend(Change)\n")
                for ((name, shape) in changes) {
                    check(shape is OperationInput.Record)
                    append(signature("change_" + name.lowercase(java.util.Locale.ROOT), shape)).append('\n')
                }
            }
            if ("ASSIGN" in kinds || "REPLACE" in changes) for (operation in operations)
                append(signature(operation.type.operationId.substringAfter(':'), operation.parameters))
                    .append(" ; type=").append(operation.type.operationId)
                    .append(" ; definitionVersion=").append(operation.type.definitionVersion)
                    .append(" ; ").append(operation.description).append('\n')
            if (planner) append("plan(steps:list[string[1..256];1..8],required_items:list[record(item_id:resource_id,minimum:int[1..2304]);0..8],minimum_empty_slots:int[0..36])\n")
            while (pending.isNotEmpty()) {
                val name = pending.removeFirst()
                val shape = catalog.shapes.getValue(name)
                val wire = SamExpressionCatalog.snake(name)
                if (shape is OperationInput.Record) append(signature(wire, shape))
                else append(wire).append('=').append(type(shape))
                append('\n')
            }
            append("position/block also accept (x,y,z) tuples or three positional numeric arguments. ")
            append("pos aliases position; chest aliases block (coordinates, never a world read). ")
            append("A named shape's single-valued kind may be omitted. Required dimension_id/anchor remain required. ")
            append("Use only shown constructors and exact argument names. Example: ask_user(\"Which item?\").\n")
        }
    }

    private fun describeField(field: OperationField, type: (OperationInput) -> String): String {
        val implicitKind = field.name == "kind" && (field.input as? OperationInput.Choice)?.values?.size == 1
        val name = SamExpressionCatalog.snake(field.name) + if (!field.required || implicitKind) "?" else ""
        val default = when (val value = field.defaultValue) {
            is OperationInputDefault.Literal -> " default=" + value.json
            is OperationInputDefault.FromField -> " defaultFrom=" + SamExpressionCatalog.snake(value.field) +
                (if (value.offset == 0) "" else if (value.offset > 0) "+" + value.offset else value.offset.toString()) +
                (value.target?.let { "." + it } ?: "")
            null -> ""
        }
        return name + ":" + type(field.input) + (if (field.nullable) "|None" else "") + default
    }
    private fun decimal(value: Double) = java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
}
