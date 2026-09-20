package io.samcnpc.llm.goal

import io.samcnpc.behavior.api.*
import io.samcnpc.core.api.NpcActionStatus
import io.samcnpc.llm.api.LlmProvider
import io.samcnpc.llm.config.ProviderSettings
import io.samcnpc.llm.context.*
import io.samcnpc.llm.scheduling.*
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

/** Server-thread bridge between durable goals, detached inference, and the existing admission gateway. */
internal class TranslatorRuntime(
    private val server: MinecraftServer,
    private val store: LlmGoalStore,
    private val settings: ProviderSettings,
    provider: LlmProvider,
    rates: InferenceRateGate,
    private val notify: (GoalRecord) -> Unit,
) : InferenceHost, AutoCloseable {
    private val sessions = linkedMapOf<UUID, GoalSession>()
    private val policy = ContextPolicy(1, OperationType.entries.toSet(), emptySet(), emptySet(), 72000, 16, 0)
    private val scheduler = InferenceScheduler(provider, settings, settings.inference.profile(settings),
        settings.inference.allocation(settings), this, rates)
    private var closed = false
    private var cursor = 0

    fun attach(record: GoalRecord, actor: ServerPlayer): String? {
        check(server.isSameThread)
        if (closed) return "GOAL_RUNTIME_CLOSED"
        val previous = sessions[record.npcUuid]
        if (previous?.goalId == record.goalId && previous.actorUuid == actor.uuid &&
            previous.subscription?.state == OperationSubscriptionState.ACTIVE) return null
        if (previous != null) {
            if (previous.budget.snapshot().inFlight != null) return "PREVIOUS_GOAL_DRAINING"
            release(record.npcUuid)
        }
        if (sessions.size >= MAX_ACTIVE) return "ACTIVE_GOAL_LIMIT"
        val session = GoalSession(record)
        val subscribed = OperationEventApi.subscribe(server, actor, record.npcUuid) { batch -> events(batch) }
        if (subscribed.result.status != NpcActionStatus.SUCCEEDED || subscribed.subscription == null)
            return "SUBSCRIPTION_" + subscribed.result.code.name
        session.subscription = subscribed.subscription
        sessions[record.npcUuid] = session
        return null
    }

    fun wake(record: GoalRecord, now: Long, reason: InferenceReason): String? =
        scheduler.offer(InferenceWake(record.npcUuid, record.goalId, record.revision, now, setOf(reason)))

    fun cancel(npcUuid: UUID) {
        check(server.isSameThread)
        scheduler.cancel(npcUuid)
        val session = sessions[npcUuid] ?: return
        session.admission.invalidate()
        session.captured = null
        if (session.budget.snapshot().inFlight == null) release(npcUuid)
    }

    fun poll(now: Long) {
        check(server.isSameThread)
        if (closed) return
        scheduler.poll(now)
        // At most two subscription-state checks per tick. Ordinary healthy task progress does no inference.
        val ids = sessions.keys.toList()
        repeat(minOf(2, ids.size)) {
            if (cursor >= ids.size) cursor = 0
            val id = ids[cursor++]
            val session = sessions[id] ?: return@repeat
            val state = session.subscription?.state
            if (state != OperationSubscriptionState.ACTIVE)
                hold(id, "SUPERVISION_" + (state?.name ?: "UNAVAILABLE"))
        }
    }

    override fun prepare(wake: InferenceWake): InferencePreparation {
        check(server.isSameThread)
        val record = store.get(wake.npcUuid) ?: return InferencePreparation.Rejected("GOAL_NOT_FOUND")
        val session = sessions[wake.npcUuid] ?: return InferencePreparation.Rejected("GOAL_SESSION_MISSING")
        if (record.goalId != wake.goalId || record.revision != wake.goalRevision || record.manualHold ||
            session.goalId != record.goalId || record.phase != GoalPhase.QUEUED)
            return InferencePreparation.Rejected("GOAL_NO_LONGER_CURRENT")
        val actor = server.playerList.getPlayer(record.actorUuid)
            ?: return InferencePreparation.Rejected("ACTOR_DISCONNECTED")
        val budgetProblem = session.budget.problem(settings.inference.allocation(settings).charge())
        if (budgetProblem != null) return InferencePreparation.Rejected(budgetProblem)
        val captured = NpcContextBuilder.capture(server, actor, record.npcUuid, contextGoal(record, session), policy)
        if (captured is ContextCaptureResult.Rejected) return InferencePreparation.Rejected(captured.code)
        check(captured is ContextCaptureResult.Captured)
        if (!session.admission.bind(captured.value)) return InferencePreparation.Rejected("ADMISSION_REVIEW_REQUIRED")
        session.captured = captured.value
        return InferencePreparation.Ready(captured.value, session.budget)
    }

    override fun started(wake: InferenceWake, captured: CapturedContext, requestId: UUID) {
        val record = checkNotNull(store.get(wake.npcUuid))
        val session = checkNotNull(sessions[wake.npcUuid])
        check(record.goalId == wake.goalId && record.revision == wake.goalRevision)
        persist(record.copy(phase = GoalPhase.INFERENCING, code = "INFERENCE_STARTED", question = null,
            contextId = captured.binding.contextId, budget = session.budget.snapshot()))
    }

    override fun completed(wake: InferenceWake, captured: CapturedContext, result: InferenceResult) {
        val record = store.get(wake.npcUuid) ?: return
        val session = sessions[wake.npcUuid] ?: return
        if (record.goalId != wake.goalId || record.revision != wake.goalRevision || record.manualHold) {
            scheduler.cancel(wake.npcUuid)
            return
        }
        if (result is InferenceResult.Failed) {
            persist(record.copy(phase = GoalPhase.WAITING, code = result.code, question = null))
            return
        }
        check(result is InferenceResult.Decoded)
        val actor = server.playerList.getPlayer(record.actorUuid)
        if (actor == null) { hold(record.npcUuid, "ACTOR_DISCONNECTED"); return }
        // This dirty mark is conservative recovery metadata, not an atomic transaction with Behavior.
        val dispatching = record.copy(phase = GoalPhase.ADMITTING, code = "ADMISSION_STARTED")
        persist(dispatching)
        val outcome = session.admission.admit(server, actor, result.decision, contextGoal(record, session), policy, record.manualHold)
        val next = TranslatorOutcomes.admitted(dispatching, result.decision, outcome)
        persist(next)
        if (next.phase != GoalPhase.EXECUTING) notify(next)
    }

    override fun settled(wake: InferenceWake, requestId: UUID, budget: InferenceBudgetView) {
        val record = store.get(wake.npcUuid)
        if (record != null && record.goalId == wake.goalId) {
            val unfinished = record.phase in setOf(GoalPhase.INFERENCING, GoalPhase.ADMITTING)
            persist(record.copy(budget = budget, contextId = null,
                phase = if (unfinished) GoalPhase.REVIEW_REQUIRED else record.phase,
                code = if (unfinished) "INFERENCE_OR_ADMISSION_CANCELLED" else record.code,
                manualHold = record.manualHold || unfinished))
        }
        sessions[wake.npcUuid]?.captured = null
        val phase = store.get(wake.npcUuid)?.phase
        if (phase !in activePhases) release(wake.npcUuid)
    }

    override fun deferred(wake: InferenceWake, code: String, retryAtMillis: Long?) {
        val record = store.get(wake.npcUuid) ?: return
        if (record.goalId != wake.goalId || record.revision != wake.goalRevision || record.manualHold) {
            scheduler.cancel(wake.npcUuid); return
        }
        val next = record.copy(phase = if (retryAtMillis == null) GoalPhase.WAITING else GoalPhase.QUEUED,
            code = code, question = null)
        persist(next)
        if (retryAtMillis == null) {
            notify(next)
            if (sessions[wake.npcUuid]?.budget?.snapshot()?.inFlight == null) release(wake.npcUuid)
        }
    }

    fun observeKnown(record: GoalRecord, actor: ServerPlayer): GoalRecord {
        if (record.phase != GoalPhase.EXECUTING) return record
        val reply = OperationSupervisionApi.observe(server, actor, record.npcUuid)
        val view = reply.observation
        val next = if (view == null || reply.result.status != NpcActionStatus.SUCCEEDED)
            record.copy(phase = GoalPhase.WAITING, code = "OBSERVATION_" + reply.result.code.name, manualHold = true)
        else TranslatorOutcomes.observed(record, view)
        if (next != record) {
            persist(next); notify(next)
            if (next.phase != GoalPhase.EXECUTING) cancel(record.npcUuid)
        }
        return next
    }

    private fun events(batch: OperationEventBatch) {
        val record = store.get(batch.npcUuid) ?: return
        if (batch.journal.events.none { it.kind in significant }) return
        val actor = server.playerList.getPlayer(record.actorUuid) ?: return
        if (record.phase == GoalPhase.EXECUTING) observeKnown(record, actor)
        else if (record.phase in setOf(GoalPhase.QUEUED, GoalPhase.INFERENCING)) {
            // Any meaningful manual/task transition makes this pending translator decision obsolete.
            hold(record.npcUuid, "TASK_CHANGED_DURING_INFERENCE")
        }
    }

    private fun hold(npcUuid: UUID, code: String) {
        val record = store.get(npcUuid) ?: return
        persist(record.copy(phase = GoalPhase.WAITING, code = code, question = null, manualHold = true))
        cancel(npcUuid)
        notify(checkNotNull(store.get(npcUuid)))
    }

    private fun contextGoal(record: GoalRecord, session: GoalSession) = ContextGoal(record.goalId,
        record.revision, record.contextText(), LlmMode.TRANSLATOR, null, session.budget.contextRemainingCalls)

    private fun persist(record: GoalRecord) { check(store.put(record) == null) { "Goal persistence rejected bounded state" } }

    private fun release(npcUuid: UUID) {
        val session = sessions.remove(npcUuid) ?: return
        session.subscription?.let { OperationEventApi.unsubscribe(server, it) }
        session.captured = null
    }

    override fun close() {
        check(server.isSameThread)
        if (closed) return
        closed = true
        scheduler.close()
        for (id in sessions.keys.toList()) {
            val record = store.get(id)
            if (record != null && record.phase in setOf(GoalPhase.QUEUED, GoalPhase.INFERENCING, GoalPhase.ADMITTING))
                persist(record.copy(phase = GoalPhase.WAITING, code = "RUNTIME_STOPPED", manualHold = true,
                    question = null, contextId = null, budget = sessions.getValue(id).budget.snapshot()))
            release(id)
        }
    }

    companion object {
        const val MAX_ACTIVE = 32
        private val activePhases = setOf(GoalPhase.QUEUED, GoalPhase.INFERENCING, GoalPhase.ADMITTING, GoalPhase.EXECUTING)
        private val significant = setOf(OperationEventKind.TASK_ASSIGNED, OperationEventKind.TASK_COMPLETED,
            OperationEventKind.TASK_FAILED, OperationEventKind.TASK_CANCELLED,
            OperationEventKind.CONTROL_CHANGED, OperationEventKind.DEFINITION_CHANGED)
    }
}
