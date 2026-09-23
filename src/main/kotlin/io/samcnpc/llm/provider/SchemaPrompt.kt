package io.samcnpc.llm.provider

import com.google.gson.JsonElement
import com.google.gson.JsonObject

/** Prompt notation only. The complete JSON Schema still constrains decoding and local validation. */
internal object SchemaPrompt {
    private const val DEFS = "$" + "defs"
    private const val REF = "$" + "ref"

    fun describe(schema: JsonObject): String {
        // Type labels are local references, not operation IDs or property names.
        val references = schema.getAsJsonObject(DEFS)?.keySet()?.mapIndexed { index, name ->
            name to "d" + index.toString(36)
        }?.toMap() ?: emptyMap()
        val counts = linkedMapOf<String, Int>()
        fun count(value: JsonElement) {
            if (value.isJsonObject) {
                val node = value.asJsonObject
                if ((node.has("type") || node.has("oneOf") || node.has("anyOf")) && !node.has(DEFS)) {
                    val key = node.toString()
                    counts[key] = (counts[key] ?: 0) + 1
                }
                node.entrySet().forEach { count(it.value) }
            } else if (value.isJsonArray) value.asJsonArray.forEach(::count)
        }
        count(schema)
        val aliases = linkedMapOf<String, String>()
        val occupied = references.values.toMutableSet()
        var nextAlias = 0
        for ((key, count) in counts) if (count > 1 && key.length >= 48) {
            var name: String
            do { name = "p" + nextAlias++ } while (name in occupied)
            occupied.add(name)
            aliases[key] = name
        }
        return buildString {
            append("OUTPUT_CONTRACT_TYPES\n")
            append("Return JSON. @name references a definition; ? marks optional fields; ! forbids extras. ")
            append("oneOf chooses exactly one alternative; anyOf allows one or more. ")
            append("Parentheses carry JSON Schema constraints.\n")
            append("response=").append(type(schema, aliases, references)).append('\n')
            val definitions = schema.getAsJsonObject(DEFS)
            if (definitions != null) for ((name, value) in definitions.entrySet()) {
                append('@').append(references.getValue(name)).append('=').append(type(value.asJsonObject, aliases, references)).append('\n')
            }
            for ((key, name) in aliases) append('@').append(name).append('=')
                .append(type(LlmJson.parse(key, 65536), aliases, references, expand = true)).append('\n')
            append("Omitted optional fields use Behavior defaults. Bounds are not defaults.\n")
        }
    }

    private fun type(schema: JsonObject, aliases: Map<String, String>, references: Map<String, String>, expand: Boolean = false): String {
        val alias = aliases[schema.toString()]
        if (!expand && alias != null) return "@" + alias
        val reference = schema[REF]
        if (reference != null) {
            val value = reference.asString
            require(value.startsWith("#/$" + "defs/"))
            return "@" + references.getValue(value.substringAfterLast('/')) + constraints(schema, setOf(REF))
        }
        for (alternative in listOf("oneOf", "anyOf")) {
            if (schema.has(alternative)) return alternative + "(" + schema[alternative].asJsonArray
                .joinToString("|") { type(it.asJsonObject, aliases, references) } + ")" + constraints(schema, setOf(alternative))
        }
        val kind = schema["type"]?.asString
        val structural = mutableSetOf("type")
        val shape = when (kind) {
            "object" -> {
                structural.addAll(listOf("properties", "required", "additionalProperties"))
                val required = schema.getAsJsonArray("required")?.map { it.asString }?.toSet() ?: emptySet()
                val fields = schema.getAsJsonObject("properties")?.entrySet()?.joinToString(",") { (name, value) ->
                    name + (if (name in required) "" else "?") + ":" + type(value.asJsonObject, aliases, references)
                } ?: ""
                val closed = schema["additionalProperties"]?.let { it.isJsonPrimitive && !it.asBoolean } == true
                (if (closed) "!" else "") + "{" + fields + "}"
            }
            "array" -> {
                structural.add("items")
                "array<" + type(schema["items"].asJsonObject, aliases, references) + ">"
            }
            else -> kind ?: "any"
        }
        return shape + constraints(schema, structural)
    }

    private fun constraints(schema: JsonObject, structural: Set<String>): String {
        val values = schema.entrySet().filter { it.key !in structural && it.key != DEFS }
        return if (values.isEmpty()) "" else values.joinToString(",", "(", ")") { it.key + "=" + it.value }
    }
}
