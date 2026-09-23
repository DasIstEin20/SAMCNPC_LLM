package io.samcnpc.llm.provider

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.util.ArrayDeque

/** Lossless factoring of repeated validation nodes. Prompt defaults and the local decoder are unchanged. */
internal object SharedSchemaShapes {
    private const val DEFS = "$" + "defs"
    private const val REF = "$" + "ref"

    fun compact(schema: JsonObject): JsonObject {
        val counts = linkedMapOf<String, Int>()
        val nodes = linkedMapOf<String, JsonObject>()
        fun count(value: JsonElement) {
            if (value.isJsonObject) {
                val node = value.asJsonObject
                val type = node["type"]
                if (!node.has(DEFS) && (type?.isJsonPrimitive == true || node["anyOf"]?.isJsonArray == true)) {
                    val key = node.toString()
                    if (key.length > 24) {
                        counts[key] = (counts[key] ?: 0) + 1
                        nodes[key] = node
                    }
                }
                node.entrySet().forEach { count(it.value) }
            } else if (value.isJsonArray) value.asJsonArray.forEach(::count)
        }
        count(schema)
        val names = schema.getAsJsonObject(DEFS)?.keySet()?.toMutableSet() ?: mutableSetOf()
        val aliases = linkedMapOf<String, String>()
        var index = 0
        // A short scalar constraint is worth factoring only when its repetition pays for the definition.
        for ((key, _) in counts.entries.filter { it.value.toLong() * (it.key.length - 24) > it.key.length + 8 }
            .sortedByDescending { it.value.toLong() * (it.key.length - 24) - it.key.length - 8 }.take(64)) {
            var name: String
            do { name = "s" + index++ } while (!names.add(name))
            aliases[key] = name
        }
        if (aliases.isEmpty()) return schema
        val pending = ArrayDeque<String>()
        val used = mutableSetOf<String>()
        fun encode(value: JsonElement, expand: Boolean = false): JsonElement {
            if (value.isJsonObject) {
                val key = value.toString()
                val alias = aliases[key]
                if (!expand && alias != null) {
                    if (used.add(key)) pending.addLast(key)
                    return JsonObject().also { it.addProperty(REF, "#/$DEFS/$alias") }
                }
                return JsonObject().also { result ->
                    for ((name, child) in value.asJsonObject.entrySet()) result.add(name, encode(child))
                }
            }
            if (value.isJsonArray) return JsonArray().also { result -> value.asJsonArray.forEach { result.add(encode(it)) } }
            return value.deepCopy()
        }
        val result = encode(schema).asJsonObject
        val definitions = result.getAsJsonObject(DEFS) ?: JsonObject().also { result.add(DEFS, it) }
        // Every nested alias represents a strictly smaller subtree, so this graph cannot add cycles.
        while (pending.isNotEmpty()) {
            val key = pending.removeFirst()
            definitions.add(aliases.getValue(key), encode(nodes.getValue(key), expand = true))
        }
        return if (LlmJson.utf8(result.toString()).size < LlmJson.utf8(schema.toString()).size) result else schema
    }
}
