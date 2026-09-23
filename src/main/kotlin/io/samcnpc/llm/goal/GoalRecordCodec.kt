package io.samcnpc.llm.goal

import io.samcnpc.llm.scheduling.*
import io.samcnpc.llm.context.LlmMode
import io.samcnpc.llm.intent.*
import io.samcnpc.llm.supervision.StockSupervisionCodec
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
        tag.putString("mode", record.mode.name)
        tag.put("memory", GoalMemoryCodec.encode(record.memory))
        tag.putInt("planSteps", record.planStepsCompleted)
        tag.putString("intentMode", if (record.constraints == null) "FREE_TEXT" else "BOUNDED_V1")
        record.constraints?.let {
            tag.putString("constraints", it.persistenceJson)
            tag.putInt("acquiredIntent", record.intentReservation.acquired)
            tag.putInt("deliveredIntent", record.intentReservation.delivered)
        }
        record.supervision?.let { tag.put("supervisor", StockSupervisionCodec.encode(it)) }
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
        tag.putString("quotaMode", record.limits.quotaMode.name)
        tag.putLong("inputLimit", record.limits.inputTokens); tag.putLong("outputLimit", record.limits.outputTokens)
        tag.putLong("costLimit", record.limits.costMicros)
        tag.putInt("attempts", record.budget.settledAttempts)
        tag.putLong("input", record.budget.chargedInputTokens); tag.putLong("output", record.budget.chargedOutputTokens)
        tag.putLong("cost", record.budget.chargedCostMicros)
        record.budget.inFlight?.let { tag.putUUID("inFlight", it) }
        return tag
    }

    fun decode(tag: CompoundTag, version: Int = 6): GoalRecord? {
        if (version !in 1..6) return null
        val requiredFields = required + (if (version >= 2) setOf("mode") else emptySet()) +
            (if (version >= 3) setOf("memory") else emptySet()) +
            (if (version >= 4) setOf("planSteps") else emptySet()) +
            (if (version >= 5) setOf("quotaMode") else emptySet()) +
            (if (version >= 6) setOf("intentMode") else emptySet())
        val optionalFields = (if (version == 1) optional else optional + "supervisor") +
            (if (version >= 6) setOf("constraints", "acquiredIntent", "deliveredIntent") else emptySet())
        if (!tag.allKeys.containsAll(requiredFields) || tag.allKeys.any { it !in requiredFields && it !in optionalFields }) return null
        val quotaMode = if (version < 5) InferenceQuotaMode.LIMITED else {
            if (!tag.contains("quotaMode", Tag.TAG_STRING.toInt())) return null
            InferenceQuotaMode.entries.firstOrNull { it.name == tag.getString("quotaMode") } ?: return null
        }
        val mode = if (version == 1) LlmMode.TRANSLATOR else {
            if (!tag.contains("mode", Tag.TAG_STRING.toInt())) return null
            LlmMode.entries.firstOrNull { it.name == tag.getString("mode") } ?: return null
        }
        if (version < 4 && mode == LlmMode.PLANNER) return null
        if (version >= 4 && !tag.contains("planSteps", Tag.TAG_INT.toInt())) return null
        val planSteps = if (version >= 4) tag.getInt("planSteps") else 0
        val memory = if (version < 3) GoalMemory() else {
            if (!tag.contains("memory", Tag.TAG_COMPOUND.toInt())) return null
            GoalMemoryCodec.decode(tag.getCompound("memory")) ?: return null
        }
        val supervision = if (tag.contains("supervisor")) {
            if (!tag.contains("supervisor", Tag.TAG_COMPOUND.toInt())) return null
            StockSupervisionCodec.decode(tag.getCompound("supervisor")) ?: return null
        } else null
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
            val intentFields = setOf("constraints", "acquiredIntent", "deliveredIntent")
            val bounded = if (version < 6) false else {
                if (!tag.contains("intentMode", Tag.TAG_STRING.toInt())) return null
                when (tag.getString("intentMode")) { "FREE_TEXT" -> false; "BOUNDED_V1" -> true; else -> return null }
            }
            if (!bounded && intentFields.any(tag::contains)) return null
            val constraints = if (!bounded) null else {
                if (!tag.contains("constraints", Tag.TAG_STRING.toInt()) ||
                    !tag.contains("acquiredIntent", Tag.TAG_INT.toInt()) || !tag.contains("deliveredIntent", Tag.TAG_INT.toInt())) return null
                GoalConstraintCodec.saved(tag.getString("constraints"))
            }
            val reservation = if (!bounded) GoalIntentReservation() else
                GoalIntentReservation(tag.getInt("acquiredIntent"), tag.getInt("deliveredIntent"))
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
                    tag.getLong("outputLimit"), tag.getLong("costLimit"), quotaMode),
                InferenceBudgetView(tag.getInt("attempts"), tag.getLong("input"), tag.getLong("output"),
                    tag.getLong("cost"), if (tag.hasUUID("inFlight")) tag.getUUID("inFlight") else null), mode, supervision, memory, planSteps, constraints, reservation)
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
