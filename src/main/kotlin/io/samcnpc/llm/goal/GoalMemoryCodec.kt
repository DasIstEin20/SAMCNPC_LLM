package io.samcnpc.llm.goal

import io.samcnpc.core.api.NpcBlockPosition
import io.samcnpc.llm.context.ContextPlaceAlias
import net.minecraft.nbt.*

/** Strict v3 memory payload; older goal versions migrate with empty memory. */
internal object GoalMemoryCodec {
    fun encode(memory: GoalMemory): CompoundTag {
        val tag = CompoundTag()
        tag.put("plan", strings(memory.plan)); tag.put("results", strings(memory.results))
        val aliases = ListTag()
        for (place in memory.aliases) {
            val child = CompoundTag()
            child.putString("name", place.name); child.putString("dimension", place.dimensionId)
            child.putInt("x", place.position.x); child.putInt("y", place.position.y); child.putInt("z", place.position.z)
            aliases.add(child)
        }
        tag.put("aliases", aliases)
        return tag
    }

    fun decode(tag: CompoundTag): GoalMemory? {
        if (tag.allKeys != setOf("plan", "aliases", "results")) return null
        val plan = readStrings(tag, "plan", 8) ?: return null
        val results = readStrings(tag, "results", 16) ?: return null
        if (!tag.contains("aliases", Tag.TAG_LIST.toInt())) return null
        val entries = tag.get("aliases") as ListTag
        if (entries.size > 16 || !entries.isEmpty() && entries.elementType != Tag.TAG_COMPOUND) return null
        val aliases = mutableListOf<ContextPlaceAlias>()
        return try {
            for (entry in entries) {
                val child = entry as CompoundTag
                if (child.allKeys != setOf("name", "dimension", "x", "y", "z") ||
                    !listOf("name", "dimension").all { child.contains(it, Tag.TAG_STRING.toInt()) } ||
                    !listOf("x", "y", "z").all { child.contains(it, Tag.TAG_INT.toInt()) }) return null
                aliases.add(ContextPlaceAlias(child.getString("name"), child.getString("dimension"),
                    NpcBlockPosition(child.getInt("x"), child.getInt("y"), child.getInt("z"))))
            }
            GoalMemory(plan, aliases, results)
        } catch (_: IllegalArgumentException) { null }
    }

    private fun strings(values: List<String>): ListTag {
        val result = ListTag()
        for (value in values) result.add(StringTag.valueOf(value))
        return result
    }

    private fun readStrings(tag: CompoundTag, name: String, maximum: Int): List<String>? {
        if (!tag.contains(name, Tag.TAG_LIST.toInt())) return null
        val entries = tag.get(name) as ListTag
        if (entries.size > maximum || !entries.isEmpty() && entries.elementType != Tag.TAG_STRING) return null
        return entries.map { it.asString }
    }
}
