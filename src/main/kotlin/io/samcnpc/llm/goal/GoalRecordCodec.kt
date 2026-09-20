package io.samcnpc.llm.goal

import io.samcnpc.llm.scheduling.*
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtIo
import net.minecraft.nbt.Tag
import java.io.DataOutputStream
import java.io.IOException
import java.io.OutputStream

/** Strict typed NBT. Unknown fields/partial identities never become a partially authorized goal. */
internal object GoalRecordCodec {
    const val MAX_BYTES = 16 * 1024
    private val required = setOf("npc", "actor", "goal", "revision", "text", "phase", "code", "hold",
        "attemptLimit", "inputLimit", "outputLimit", "costLimit", "attempts", "input", "output", "cost")
    private val optional = setOf("answer", "question", "task", "context", "inFlight")
    private val taskFields = setOf("id", "definition", "control", "operation")

    fun encode(record: GoalRecord): CompoundTag {
        val tag = CompoundTag()
        tag.putUUID("npc", record.npcUuid); tag.putUUID("actor", record.actorUuid); tag.putUUID("goal", record.goalId)
        tag.putLong("revision", record.revision); tag.putString("text", record.text)
        tag.putString("phase", record.phase.name); tag.putString("code", record.code)
        tag.putBoolean("hold", record.manualHold)
        record.answer?.let { tag.putString("answer", it) }; record.question?.let { tag.putString("question", it) }
        record.contextId?.let { tag.putUUID("context", it) }
        val task = record.task
        if (task != null) {
            val child = CompoundTag()
            child.putUUID("id", task.id); child.putInt("definition", task.definitionRevision)
            child.putLong("control", task.controlRevision); child.putString("operation", task.operationId)
            tag.put("task", child)
        }
        tag.putInt("attemptLimit", record.limits.attempts)
        tag.putLong("inputLimit", record.limits.inputTokens); tag.putLong("outputLimit", record.limits.outputTokens)
        tag.putLong("costLimit", record.limits.costMicros)
        tag.putInt("attempts", record.budget.settledAttempts)
        tag.putLong("input", record.budget.chargedInputTokens); tag.putLong("output", record.budget.chargedOutputTokens)
        tag.putLong("cost", record.budget.chargedCostMicros)
        record.budget.inFlight?.let { tag.putUUID("inFlight", it) }
        return tag
    }

    fun decode(tag: CompoundTag): GoalRecord? {
        if (!tag.allKeys.containsAll(required) || tag.allKeys.any { it !in required && it !in optional }) return null
        if (!listOf("npc", "actor", "goal").all(tag::hasUUID)) return null
        if (!listOf("text", "phase", "code").all { tag.contains(it, Tag.TAG_STRING.toInt()) }) return null
        if (!listOf("revision", "inputLimit", "outputLimit", "costLimit", "input", "output", "cost")
                .all { tag.contains(it, Tag.TAG_LONG.toInt()) }) return null
        if (!listOf("attemptLimit", "attempts").all { tag.contains(it, Tag.TAG_INT.toInt()) }) return null
        if (!tag.contains("hold", Tag.TAG_BYTE.toInt()) || tag.getByte("hold").toInt() !in 0..1) return null
        if (listOf("answer", "question").any { tag.contains(it) && !tag.contains(it, Tag.TAG_STRING.toInt()) }) return null
        if (listOf("context", "inFlight").any { tag.contains(it) && !tag.hasUUID(it) }) return null
        val phase = GoalPhase.entries.firstOrNull { it.name == tag.getString("phase") } ?: return null
        return try {
            val task = if (!tag.contains("task")) null else {
                if (!tag.contains("task", Tag.TAG_COMPOUND.toInt())) return null
                val child = tag.getCompound("task")
                if (child.allKeys != taskFields || !child.hasUUID("id") ||
                    !child.contains("definition", Tag.TAG_INT.toInt()) || !child.contains("control", Tag.TAG_LONG.toInt()) ||
                    !child.contains("operation", Tag.TAG_STRING.toInt())) return null
                GoalTask(child.getUUID("id"), child.getInt("definition"), child.getLong("control"), child.getString("operation"))
            }
            val result = GoalRecord(tag.getUUID("npc"), tag.getUUID("actor"), tag.getUUID("goal"), tag.getLong("revision"),
                tag.getString("text"), if (tag.contains("answer")) tag.getString("answer") else null,
                phase, tag.getString("code"), tag.getBoolean("hold"),
                if (tag.contains("question")) tag.getString("question") else null, task,
                if (tag.hasUUID("context")) tag.getUUID("context") else null,
                InferenceBudgetLimits(tag.getInt("attemptLimit"), tag.getLong("inputLimit"),
                    tag.getLong("outputLimit"), tag.getLong("costLimit")),
                InferenceBudgetView(tag.getInt("attempts"), tag.getLong("input"), tag.getLong("output"),
                    tag.getLong("cost"), if (tag.hasUUID("inFlight")) tag.getUUID("inFlight") else null))
            if (fits(tag)) result else null
        } catch (_: IllegalArgumentException) { null }
    }

    /** Count bounded serialized bytes in memory, without allocating a second encoded document. */
    fun fits(tag: CompoundTag): Boolean {
        val sink = object : OutputStream() {
            var bytes = 0
            override fun write(value: Int) { add(1) }
            override fun write(value: ByteArray, offset: Int, length: Int) { add(length) }
            fun add(length: Int) {
                if (length > MAX_BYTES - bytes) throw IOException("GOAL_RECORD_TOO_LARGE")
                bytes += length
            }
        }
        return try { DataOutputStream(sink).use { NbtIo.write(tag, it) }; true }
        catch (_: IOException) { false }
    }
}
