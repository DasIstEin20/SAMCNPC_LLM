package io.samcnpc.llm.goal

import io.samcnpc.behavior.api.OperationSupervisionApi
import io.samcnpc.core.api.NpcActionStatus
import io.samcnpc.llm.config.ProviderSettings
import io.samcnpc.llm.context.*
import io.samcnpc.llm.scheduling.*
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

/** Explicit user projection only; it cannot dispatch operations or wake the inference scheduler. */
internal class GoalCompaction(private val server: MinecraftServer, private val store: LlmGoalStore,
    private val settings: ProviderSettings) : AutoCloseable {
    private val worker = CompactionWork()
    private val reports = linkedMapOf<UUID, CompactionReport>()

    fun submit(actor: ServerPlayer, record: GoalRecord): GoalReply {
        check(server.isSameThread)
        if (!worker.available) return GoalReply(false, "COMPACTION_BUSY")
        if (record.revision == Long.MAX_VALUE) return GoalReply(false, "GOAL_REVISION_EXHAUSTED")
        val pending = record.phase in setOf(GoalPhase.QUEUED, GoalPhase.INFERENCING, GoalPhase.ADMITTING)
        val next = record.copy(revision = record.revision + 1,
            phase = if (pending) GoalPhase.WAITING else record.phase,
            code = if (pending) "CONTEXT_COMPACTED_INFERENCE_HELD" else record.code,
            manualHold = record.manualHold || pending)
        val goal = ContextGoal(next.goalId, next.revision, next.contextText(), next.mode, null,
            InferenceBudget(next.limits, next.budget).contextRemainingCalls, next.memory.context(),
            next.supervision, next.planStepsCompleted, next.constraints, next.intentReservation)
        val capture = NpcContextBuilder.capture(server, actor, next.npcUuid, goal, GoalPolicies.forRecord(next), diagnostic = true)
        if (capture is ContextCaptureResult.Rejected) return GoalReply(false, capture.code)
        check(capture is ContextCaptureResult.Captured)
        val input = InferenceInput(UUID.randomUUID(), capture.value, settings, settings.inference.profile(settings),
            settings.inference.allocation(settings))
        if (!worker.submit(input)) return GoalReply(false, "COMPACTION_BUSY")
        check(store.put(next) == null) { "Compaction cannot change bounded goal payload sizes" }
        reports.remove(record.npcUuid)
        return GoalReply(true, if (pending) "COMPACTION_STARTED_INFERENCE_HELD" else "COMPACTION_STARTED", next)
    }

    fun poll() {
        check(server.isSameThread)
        val report = worker.poll() ?: return
        val binding = report.binding
        val record = store.get(binding.npcUuid) ?: return
        if (record.goalId != binding.goalId || record.revision != binding.goalRevision) return
        val actor = server.playerList.getPlayer(binding.actorUuid) ?: return
        val observed = OperationSupervisionApi.observe(server, actor, binding.npcUuid)
        if (observed.result.status != NpcActionStatus.SUCCEEDED || observed.observation == null) return
        reports.remove(binding.npcUuid)
        reports[binding.npcUuid] = report
        while (reports.size > TranslatorRuntime.MAX_ACTIVE) reports.remove(reports.keys.first())
        actor.sendSystemMessage(Component.literal("SAMCNPC LLM " + binding.npcUuid + ": " + report.describe()))
    }

    fun report(record: GoalRecord): CompactionReport? = reports[record.npcUuid]?.takeIf {
        it.binding.goalId == record.goalId && it.binding.goalRevision == record.revision
    }

    fun forget(npc: UUID) { reports.remove(npc) }
    override fun close() { worker.close(); reports.clear() }
}
