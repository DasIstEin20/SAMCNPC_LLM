package io.samcnpc.llm.provider

import com.google.gson.JsonElement
import com.google.gson.JsonObject

/** Prompt notation only. The complete JSON Schema still constrains decoding and local validation. */
internal object SchemaPrompt {
    private const val DEFS = "$" + "defs"
    private const val REF = "$" + "ref"

    fun describe(schema: JsonObject): String {
        val counts = linkedMapOf<String, Int>()
        fun count(value: JsonElement) {
            if (value.isJsonObject) {
                val node = value.asJsonObject
                if (node.has("type") && !node.has(DEFS)) {
                    val key = node.toString()
                    counts[key] = (counts[key] ?: 0) + 1
                }
                node.entrySet().forEach { count(it.value) }
            } else if (value.isJsonArray) value.asJsonArray.forEach(::count)
        }
        count(schema)
        val aliases = linkedMapOf<String, String>()
        for ((key, count) in counts) if (count > 1 && key.length >= 48)
            aliases[key] = "promptType" + aliases.size
        return buildString {
            append("OUTPUT_CONTRACT_TYPES\n")
            append("Return JSON, not this type notation. @name refers to a definition below. ")
            append("Object fields are required unless marked ?. ! forbids extra fields. ")
            append("oneOf means exactly one alternative; anyOf means one or more. ")
            append("Constraints in parentheses are JSON Schema constraints.\n")
            append("response=").append(type(schema, aliases)).append('\n')
            val definitions = schema.getAsJsonObject(DEFS)
            if (definitions != null) for ((name, value) in definitions.entrySet()) {
                append('@').append(name).append('=').append(type(value.asJsonObject, aliases)).append('\n')
            }
            for ((key, name) in aliases) append('@').append(name).append('=')
                .append(type(LlmJson.parse(key, 65536), aliases, expand = true)).append('\n')
        }
    }

    private fun type(schema: JsonObject, aliases: Map<String, String>, expand: Boolean = false): String {
        val alias = aliases[schema.toString()]
        if (!expand && alias != null) return "@" + alias
        val reference = schema[REF]
        if (reference != null) {
            val value = reference.asString
            require(value.startsWith("#/$" + "defs/"))
            return "@" + value.substringAfterLast('/') + constraints(schema, setOf(REF))
        }
        for (alternative in listOf("oneOf", "anyOf")) {
            if (schema.has(alternative)) return alternative + "(" + schema[alternative].asJsonArray
                .joinToString("|") { type(it.asJsonObject, aliases) } + ")" + constraints(schema, setOf(alternative))
        }
        val kind = schema["type"]?.asString
        val structural = mutableSetOf("type")
        val shape = when (kind) {
            "object" -> {
                structural.addAll(listOf("properties", "required", "additionalProperties"))
                val required = schema.getAsJsonArray("required")?.map { it.asString }?.toSet() ?: emptySet()
                val fields = schema.getAsJsonObject("properties")?.entrySet()?.joinToString(",") { (name, value) ->
                    name + (if (name in required) "" else "?") + ":" + type(value.asJsonObject, aliases)
                } ?: ""
                val closed = schema["additionalProperties"]?.let { it.isJsonPrimitive && !it.asBoolean } == true
                (if (closed) "!" else "") + "{" + fields + "}"
            }
            "array" -> {
                structural.add("items")
                "array<" + type(schema["items"].asJsonObject, aliases) + ">"
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
