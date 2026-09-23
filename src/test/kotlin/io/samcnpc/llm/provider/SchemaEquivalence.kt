package io.samcnpc.llm.provider

import com.google.gson.*

/** Independently resolve catalog references to compare every effective grammar constraint. */
internal object SchemaEquivalence {
    fun expanded(schema: JsonObject): JsonElement {
        val definitions = schema.getAsJsonObject("$" + "defs")
        val reference = "$" + "ref"
        fun expand(value: JsonElement): JsonElement = when {
            value.isJsonObject -> {
                val fields = value.asJsonObject.entrySet().filter {
                    it.key != "default" && it.key != "$" + "defs" &&
                        !(it.key == "uniqueItems" && !it.value.asBoolean)
                }
                val ref = fields.singleOrNull { it.key == reference }
                if (ref != null) {
                    check(fields.size == 1) { "Catalog reference gained constraints requiring an explicit conjunction" }
                    val path = ref.value.asString
                    check(path.startsWith("#/$" + "defs/") && !path.removePrefix("#/$" + "defs/").contains('/'))
                    expand(checkNotNull(definitions[path.substringAfterLast('/')]))
                } else JsonObject().also { result ->
                    for ((key, child) in fields) result.add(key, expand(child))
                    val choices=result["enum"]
                    if(result["type"]?.isJsonPrimitive == true && result["type"].asString == "string" &&
                        choices?.isJsonArray == true && !choices.asJsonArray.isEmpty &&
                        choices.asJsonArray.all { it.isJsonPrimitive && it.asJsonPrimitive.isString }) result.remove("type")
                }
            }
            value.isJsonArray -> JsonArray().also { result -> value.asJsonArray.forEach { result.add(expand(it)) } }
            else -> value.deepCopy()
        }
        return expand(schema)
    }
}
