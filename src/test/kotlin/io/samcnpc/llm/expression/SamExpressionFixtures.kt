package io.samcnpc.llm.expression

import com.google.gson.*
import io.samcnpc.behavior.api.*

/** Test-only inverse mapping of frozen JSON fixtures, never used to repair a model response. */
internal object SamExpressionFixtures {
    private val catalog = OperationCatalogApi.snapshot()
    fun order(document: JsonObject): String {
        val descriptor = catalog.operations.single { it.type.operationId == document["type"].asString }
        return record(descriptor.type.operationId.substringAfter(':'), document["parameters"].asJsonObject, descriptor.parameters)
    }
    private fun record(name: String, value: JsonObject, shape: OperationInput.Record): String =
        value.entrySet().joinToString(",", "$name(", ")") { (key, child) ->
            val field = shape.fields.single { it.name == key }
            SamExpressionCatalog.snake(key) + "=" + encode(child, field.input)
        }
    private fun encode(value: JsonElement, input: OperationInput, name: String = "record"): String {
        if (value.isJsonNull) return "None"
        return when (input) {
            OperationInput.Flag -> if (value.asBoolean) "True" else "False"
            is OperationInput.Numeric -> value.asBigDecimal.toPlainString()
            is OperationInput.Text, is OperationInput.Choice -> value.toString()
            is OperationInput.Sequence -> value.asJsonArray.joinToString(",", "[", "]") { encode(it, input.item) }
            is OperationInput.Record -> record(name, value.asJsonObject, input)
            is OperationInput.Reference -> if (input.name == "orderDocument") order(value.asJsonObject)
                else encode(value, catalog.shapes.getValue(input.name), SamExpressionCatalog.snake(input.name))
            is OperationInput.Alternatives -> {
                val variant = input.names.single { candidate ->
                    val shape = catalog.shapes.getValue(candidate) as OperationInput.Record
                    val choice = shape.fields.single { it.name == "kind" }.input as OperationInput.Choice
                    value.asJsonObject["kind"].asString in choice.values
                }
                encode(value, catalog.shapes.getValue(variant), SamExpressionCatalog.snake(variant))
            }
        }
    }
}
