package io.samcnpc.llm.context

import com.google.gson.*
import io.samcnpc.core.api.NpcBlockPosition
import io.samcnpc.core.api.NpcPosition
import io.samcnpc.core.api.NpcVector

/** Explicit projection helpers, never reflective serialization of Behavior/Core objects. */
internal object ContextJson {
    fun obj(vararg fields: Pair<String, JsonElement>): JsonObject {
        val value = JsonObject()
        for ((name, field) in fields) value.add(name, field)
        return value
    }
    fun text(value: String?): JsonElement = if (value == null) JsonNull.INSTANCE else JsonPrimitive(value)
    fun number(value: Number?): JsonElement {
        if (value == null) return JsonNull.INSTANCE
        require(value.toDouble().isFinite())
        return JsonPrimitive(value)
    }
    fun flag(value: Boolean?): JsonElement = if (value == null) JsonNull.INSTANCE else JsonPrimitive(value)
    fun array(values: Iterable<JsonElement>): JsonArray {
        val array = JsonArray()
        for (value in values) array.add(value)
        return array
    }
    fun position(value: NpcPosition): JsonElement = array(listOf(number(value.x), number(value.y), number(value.z)))
    fun position(value: NpcBlockPosition): JsonElement = array(listOf(number(value.x), number(value.y), number(value.z)))
    fun vector(value: NpcVector): JsonElement = array(listOf(number(value.x), number(value.y), number(value.z)))
}
