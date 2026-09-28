package io.samcnpc.llm.provider

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/** Factoring leaves original definitions pointing at shared definitions. Collapse only those exact aliases. */
internal object CompactSchemaReferences {
    private const val DEFS = "$" + "defs"
    private const val REF = "$" + "ref"
    fun compact(schema: JsonObject): JsonObject {
        val definitions = schema.getAsJsonObject(DEFS) ?: return schema
        val prefix = "#/$DEFS/"
        val aliases = linkedMapOf<String,String>()
        for ((name,value) in definitions.entrySet()) {
            if (!value.isJsonObject || value.asJsonObject.size() != 1) continue
            val reference = value.asJsonObject[REF] ?: continue
            if (!reference.isJsonPrimitive || !reference.asJsonPrimitive.isString || !reference.asString.startsWith(prefix)) continue
            val target = reference.asString.removePrefix(prefix)
            if (definitions.has(target)) aliases[name] = target
        }
        val resolved = linkedMapOf<String,String>()
        for (name in aliases.keys) {
            val seen = hashSetOf<String>(); var target = name
            while (target in aliases && seen.add(target)) target = aliases.getValue(target)
            if (target !in aliases) resolved[name] = target
        }
        val nestedReferences = mutableSetOf<String>()
        fun encode(value: JsonElement): JsonElement {
            if (value.isJsonArray) return JsonArray().also { result -> value.asJsonArray.forEach { result.add(encode(it)) } }
            if (!value.isJsonObject) return value.deepCopy()
            return JsonObject().also { result ->
                for ((key, child) in value.asJsonObject.entrySet()) {
                    val reference = if (key == REF && child.isJsonPrimitive && child.asJsonPrimitive.isString) child.asString else null
                    val path = reference?.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)
                    if (path != null && '/' in path) nestedReferences.add(path.substringBefore('/'))
                    val replacement = path?.let(resolved::get)
                    if (replacement != null) result.addProperty(key, prefix+replacement) else result.add(key, encode(child))
                }
            }
        }
        val result = encode(schema).asJsonObject
        for (name in resolved.keys - nestedReferences) result.getAsJsonObject(DEFS).remove(name)
        return result
    }
}
