package io.samcnpc.llm.supervision

import io.samcnpc.core.api.NpcBlockPosition
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag

internal object StockSupervisionCodec {
    private val required = setOf("dimension", "x", "y", "z", "item", "low", "target", "armed", "epoch", "consecutive", "failures")
    private val optional = setOf("decision", "world", "before", "waitUntil")

    fun encode(value: StockSupervision): CompoundTag {
        val tag = CompoundTag(); val target = value.target
        tag.putString("dimension", target.dimensionId); tag.putString("item", target.itemId)
        tag.putInt("x", target.position.x); tag.putInt("y", target.position.y); tag.putInt("z", target.position.z)
        tag.putInt("low", target.low); tag.putInt("target", target.target)
        tag.putBoolean("armed", value.armed); tag.putLong("epoch", value.progressEpoch)
        tag.putInt("consecutive", value.failures.consecutive)
        val failures = ListTag()
        for (entry in value.failures.entries) {
            val child = CompoundTag()
            child.putString("decision", entry.decision); child.putString("world", entry.world); child.putString("reason", entry.reason)
            failures.add(child)
        }
        tag.put("failures", failures)
        value.pendingDecision?.let { tag.putString("decision", it) }
        value.pendingWorld?.let { tag.putString("world", it) }
        value.beforeCount?.let { tag.putInt("before", it) }
        value.waitUntilTick?.let { tag.putLong("waitUntil", it) }
        return tag
    }

    fun decode(tag: CompoundTag): StockSupervision? {
        if (!tag.allKeys.containsAll(required) || tag.allKeys.any { it !in required && it !in optional }) return null
        if (!listOf("dimension", "item").all { tag.contains(it, Tag.TAG_STRING.toInt()) }) return null
        if (!listOf("x", "y", "z", "low", "target", "consecutive").all { tag.contains(it, Tag.TAG_INT.toInt()) }) return null
        if (!tag.contains("armed", Tag.TAG_BYTE.toInt()) || tag.getByte("armed").toInt() !in 0..1) return null
        if (!tag.contains("epoch", Tag.TAG_LONG.toInt()) || !tag.contains("failures", Tag.TAG_LIST.toInt())) return null
        if (listOf("decision", "world").any { tag.contains(it) && !tag.contains(it, Tag.TAG_STRING.toInt()) }) return null
        if (tag.contains("before") && !tag.contains("before", Tag.TAG_INT.toInt())) return null
        if (tag.contains("waitUntil") && !tag.contains("waitUntil", Tag.TAG_LONG.toInt())) return null
        val entries = tag.get("failures") as ListTag
        if (entries.size > 8 || !entries.isEmpty() && entries.elementType != Tag.TAG_COMPOUND) return null
        return try {
            val values = entries.map {
                val entry = it as CompoundTag
                if (entry.allKeys != setOf("decision", "world", "reason") ||
                    entry.allKeys.any { key -> !entry.contains(key, Tag.TAG_STRING.toInt()) }) return null
                FailedDecision(entry.getString("decision"), entry.getString("world"), entry.getString("reason"))
            }
            StockSupervision(StockTarget(tag.getString("dimension"),
                NpcBlockPosition(tag.getInt("x"), tag.getInt("y"), tag.getInt("z")),
                tag.getString("item"), tag.getInt("low"), tag.getInt("target")), tag.getBoolean("armed"),
                if (tag.contains("decision")) tag.getString("decision") else null,
                if (tag.contains("world")) tag.getString("world") else null,
                if (tag.contains("before")) tag.getInt("before") else null,
                FailedDecisions(values, tag.getInt("consecutive")),
                if (tag.contains("waitUntil")) tag.getLong("waitUntil") else null, tag.getLong("epoch"))
        } catch (_: IllegalArgumentException) { null }
    }
}
