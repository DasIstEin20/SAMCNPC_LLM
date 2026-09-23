package io.samcnpc.llm.expression

import com.google.gson.*
import io.samcnpc.behavior.api.*

/** Published data definitions are the allow-list. All cross-field semantics stay in Behavior. */
internal object SamExpressionCatalog {
    val catalog = OperationCatalogApi.snapshot()
    val operations = catalog.operations.associateBy { it.type.operationId.substringAfter(':') }
    val changes = catalog.changes.keys.associateBy { "change_" + it.lowercase(java.util.Locale.ROOT) }
    private val shapeNames = catalog.shapes.keys.associateBy(::snake)

    init {
        check(operations.size == catalog.operations.size && shapeNames.size == catalog.shapes.size)
    }

    fun snake(name: String): String = buildString {
        for (c in name) {
            if (c in 'A'..'Z') append('_').append(c.lowercaseChar()) else append(c)
        }
    }

    fun order(value: SamValue): JsonObject {
        require(value is SamValue.Call) { "EXPRESSION_OPERATION" }
        val descriptor = operations[value.name] ?: throw IllegalArgumentException("EXPRESSION_OPERATION")
        val parameters = record(value, descriptor.parameters, value.name)
        return JsonObject().also {
            it.addProperty("documentVersion", catalog.documentVersion)
            it.addProperty("type", descriptor.type.operationId)
            it.addProperty("definitionVersion", descriptor.type.definitionVersion)
            it.add("parameters", parameters)
        }
    }

    fun change(value: SamValue): JsonObject {
        require(value is SamValue.Call) { "EXPRESSION_CHANGE" }
        val kind = changes[value.name] ?: throw IllegalArgumentException("EXPRESSION_CHANGE")
        val shape = catalog.changes.getValue(kind)
        check(shape is OperationInput.Record)
        return JsonObject().also {
            it.addProperty("documentVersion", catalog.documentVersion)
            it.addProperty("type", kind)
            it.add("parameters", record(value, shape, value.name))
        }
    }

    private fun encode(value: SamValue, shape: OperationInput, expectedName: String? = null): JsonElement = when (shape) {
        OperationInput.Flag -> {
            require(value is SamValue.Flag) { "EXPRESSION_BOOLEAN" }
            JsonPrimitive(value.value)
        }
        is OperationInput.Numeric -> {
            require(value is SamValue.Number && (!shape.integer || value.integer)) { "EXPRESSION_NUMBER_TYPE" }
            val number = value.decimal
            require(number >= java.math.BigDecimal.valueOf(shape.minimum) &&
                number <= java.math.BigDecimal.valueOf(shape.maximum)) { "EXPRESSION_NUMBER_RANGE" }
            JsonPrimitive(number)
        }
        is OperationInput.Text -> {
            require(value is SamValue.Text && value.value.length <= shape.maxLength) { "EXPRESSION_TEXT" }
            JsonPrimitive(value.value)
        }
        is OperationInput.Choice -> {
            require(value is SamValue.Text && value.value in shape.values) { "EXPRESSION_CHOICE" }
            JsonPrimitive(value.value)
        }
        is OperationInput.Sequence -> {
            require(value is SamValue.Sequence && value.values.size in shape.minimum..shape.maximum) { "EXPRESSION_SEQUENCE" }
            val items = value.values.map { encode(it, shape.item) }
            require(!shape.unique || items.distinct().size == items.size) { "EXPRESSION_DUPLICATE_ITEM" }
            JsonArray().also { array -> items.forEach(array::add) }
        }
        is OperationInput.Record -> record(value, shape, expectedName ?: "record")
        is OperationInput.Reference -> {
            if (shape.name == "orderDocument") order(value)
            else encode(value, catalog.shapes.getValue(shape.name), snake(shape.name))
        }
        is OperationInput.Alternatives -> {
            require(value is SamValue.Call) { "EXPRESSION_ALTERNATIVE" }
            val name = shapeNames[value.name]
            require(name != null && name in shape.names) { "EXPRESSION_ALTERNATIVE" }
            encode(value, catalog.shapes.getValue(name), value.name)
        }
    }

    private fun record(value: SamValue, shape: OperationInput.Record, expected: String): JsonObject {
        if (expected == "position" || expected == "block") {
            val coordinates = if (value is SamValue.Coordinates) value.values else null
            if (coordinates != null) return coordinateRecord(coordinates, shape)
        }
        require(value is SamValue.Call) { "EXPRESSION_RECORD" }
        val actual = when (value.name) { "pos" -> "position"; "chest" -> "block"; else -> value.name }
        require(actual == expected) { "EXPRESSION_CONSTRUCTOR" }
        if ((expected == "position" || expected == "block") && value.arguments.any { it.name == null }) {
            require(value.arguments.size == 3 && value.arguments.all { it.name == null && it.value is SamValue.Number }) { "EXPRESSION_COORDINATE" }
            return coordinateRecord(value.arguments.map { it.value }, shape)
        }
        val fields = shape.fields.associateBy { snake(it.name) }
        check(fields.size == shape.fields.size)
        val result = JsonObject()
        for (argument in value.arguments) {
            val field = fields[argument.name] ?: throw IllegalArgumentException("EXPRESSION_ARGUMENT")
            require(!result.has(field.name)) { "EXPRESSION_DUPLICATE_ARGUMENT" }
            if (argument.value == SamValue.Null) {
                require(field.nullable) { "EXPRESSION_NULL" }
                result.add(field.name, JsonNull.INSTANCE)
            } else result.add(field.name, encode(argument.value, field.input))
        }
        for (field in shape.fields) if (!result.has(field.name) && field.required) {
            val discriminator = field.input as? OperationInput.Choice
            if (field.name == "kind" && discriminator != null && discriminator.values.size == 1)
                result.addProperty(field.name, discriminator.values.single())
            else throw IllegalArgumentException("EXPRESSION_MISSING_ARGUMENT")
        }
        return result
    }

    private fun coordinateRecord(values: List<SamValue>, shape: OperationInput.Record): JsonObject {
        check(shape.fields.map { it.name } == listOf("x", "y", "z"))
        return JsonObject().also { output ->
            for ((index, field) in shape.fields.withIndex()) output.add(field.name, encode(values[index], field.input))
        }
    }
}
