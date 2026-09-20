package io.samcnpc.llm.goal

import io.samcnpc.behavior.api.*
import io.samcnpc.core.api.NpcActionStatus
import io.samcnpc.llm.api.LlmProvider
import io.samcnpc.llm.config.ProviderSettings
import io.samcnpc.llm.context.*
import io.samcnpc.llm.scheduling.*
import io.samcnpc.llm.supervision.*
import io.samcnpc.llm.planning.*
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
    private val translatorPolicy = ContextPolicy(1, OperationType.entries.toSet(), emptySet(), emptySet(), 72000, 16, 0)
    private val supervisorPolicy = ContextPolicy(2, StockDecisionPolicy.operations, emptySet(), emptySet(), 72000, 16, 0)
    private val plannerPolicy = ContextPolicy(3, PlannerPolicy.operations, emptySet(), emptySet(), 72000, 16, 0)
    private fun policy(record: GoalRecord) = when (record.mode) {
        LlmMode.TRANSLATOR -> translatorPolicy
        LlmMode.SUPERVISOR -> supervisorPolicy
        LlmMode.PLANNER -> plannerPolicy
    }
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
            else {
                val record = store.get(id)
                if (PlannerOutcomes.boundary(record) && session.budget.snapshot().inFlight == null) {
                    val queued = checkNotNull(record).copy(phase = GoalPhase.QUEUED, code = "PLAN_NEXT_DECISION")
                    persist(queued)
                    wake(queued, now, InferenceReason.TASK_TERMINAL)?.let { hold(id, it) }
                }
                if (record?.supervision != null && record.phase == GoalPhase.WAITING && !record.manualHold &&
                    session.budget.snapshot().inFlight == null && server.overworld().gameTime >= session.nextStockTick) {
                    session.nextStockTick = server.overworld().gameTime + 20
                    sample(record, now)
                }
            }
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
        // Automatic stock wakes also pass readiness; a disabled/unverified provider consumes no reservation.
        if (!settings.enabled) return InferencePreparation.Rejected("LLM_DISABLED")
        if (settings.problem() != null) return InferencePreparation.Rejected("INVALID_PROVIDER_CONFIGURATION")
        settings.inference.readiness(settings)?.let { return InferencePreparation.Rejected(it) }
        val budgetProblem = session.budget.problem(settings.inference.allocation(settings).charge())
        if (budgetProblem != null) return InferencePreparation.Rejected(budgetProblem)
        val captured = NpcContextBuilder.capture(server, actor, record.npcUuid, contextGoal(record, session), policy(record))
        if (captured is ContextCaptureResult.Rejected) return InferencePreparation.Rejected(captured.code)
        check(captured is ContextCaptureResult.Captured)
        if (record.supervision != null && checkNotNull(captured.value.stock).count >= record.supervision.target.target)
            return InferencePreparation.Rejected("STOCK_TARGET_REACHED")
        if (record.mode == LlmMode.PLANNER && record.planStepsCompleted >= 8)
            return InferencePreparation.Rejected("PLAN_STEP_BUDGET_EXHAUSTED")
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
            session.failedInference = true
            val failed = record.copy(phase = GoalPhase.WAITING, code = result.code, question = null)
            persist(failed); notify(failed)
            return
        }
        check(result is InferenceResult.Decoded)
        val actor = server.playerList.getPlayer(record.actorUuid)
        if (actor == null) { hold(record.npcUuid, "ACTOR_DISCONNECTED"); return }
        // This dirty mark is conservative recovery metadata, not an atomic transaction with Behavior.
        val prepared = if (record.supervision == null) record else StockSupervisor.admitting(record, result.decision, captured)
        if (prepared.phase == GoalPhase.ASK_USER) { persist(prepared); notify(prepared); return }
        val dispatching = prepared.copy(phase = GoalPhase.ADMITTING, code = "ADMISSION_STARTED")
        persist(dispatching)
        val outcome = session.admission.admit(server, actor, result.decision, contextGoal(record, session), policy(record), record.manualHold)
        val next = when (record.mode) {
            LlmMode.TRANSLATOR -> TranslatorOutcomes.admitted(dispatching, result.decision, outcome)
            LlmMode.SUPERVISOR -> StockSupervisor.admitted(dispatching, result.decision, outcome, server.overworld().gameTime)
            LlmMode.PLANNER -> PlannerOutcomes.admitted(dispatching, result.decision, outcome)
        }
        persist(next)
        if (next.phase != GoalPhase.EXECUTING) notify(next)
    }

    override fun settled(wake: InferenceWake, requestId: UUID, budget: InferenceBudgetView) {
        val record = store.get(wake.npcUuid)
        if (record != null && record.goalId == wake.goalId) {
            // Scheduler queues its one allowed retry before settling. Only a terminal inference failure holds the watch.
            val failedWithoutRetry = sessions[wake.npcUuid]?.failedInference == true &&
                record.mode != LlmMode.TRANSLATOR && record.phase == GoalPhase.WAITING
            val unfinished = record.phase in setOf(GoalPhase.INFERENCING, GoalPhase.ADMITTING)
            persist(record.copy(budget = budget, contextId = null,
                phase = if (unfinished) GoalPhase.REVIEW_REQUIRED else record.phase,
                code = if (unfinished) "INFERENCE_OR_ADMISSION_CANCELLED" else record.code,
                manualHold = record.manualHold || unfinished || failedWithoutRetry))
        }
        sessions[wake.npcUuid]?.failedInference = false
        sessions[wake.npcUuid]?.captured = null
        val phase = store.get(wake.npcUuid)?.phase
        if (phase !in activePhases && !isWatching(store.get(wake.npcUuid))) release(wake.npcUuid)
    }

    override fun deferred(wake: InferenceWake, code: String, retryAtMillis: Long?) {
        val record = store.get(wake.npcUuid) ?: return
        if (record.goalId != wake.goalId || record.revision != wake.goalRevision || record.manualHold) {
            scheduler.cancel(wake.npcUuid); return
        }
        if (code == "STOCK_TARGET_REACHED" && record.supervision != null) {
            persist(record.copy(phase = GoalPhase.WAITING, code = code, question = null,
                supervision = record.supervision.copy(armed = true, waitUntilTick = null)))
            return
        }
        val next = record.copy(phase = if (retryAtMillis == null) GoalPhase.WAITING else GoalPhase.QUEUED,
            code = code, question = null, manualHold = record.manualHold || retryAtMillis == null && record.mode != LlmMode.TRANSLATOR)
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
        var next = if (view == null || reply.result.status != NpcActionStatus.SUCCEEDED)
            record.copy(phase = GoalPhase.WAITING, code = "OBSERVATION_" + reply.result.code.name, manualHold = true)
        else TranslatorOutcomes.observed(record, view)
        if (next.phase in setOf(GoalPhase.COMPLETED, GoalPhase.FAILED, GoalPhase.STOPPED) &&
            next.code in setOf("TASK_COMPLETED", "TASK_FAILED", "TASK_CANCELLED")) {
            next = next.copy(memory = next.memory.withOutcome(checkNotNull(next.task), next.code))
        }
        if (record.supervision != null && next.phase in setOf(GoalPhase.COMPLETED, GoalPhase.FAILED)) {
            val (stock, problem) = StockSupervisor.read(server, actor, record)
            next = if (stock == null) StockSupervisor.unavailable(record, checkNotNull(problem))
                else StockSupervisor.terminal(next.copy(code = view?.task?.frames?.firstOrNull()?.reason ?: next.code), stock)
        }
        if (record.mode == LlmMode.PLANNER && next.phase in setOf(GoalPhase.COMPLETED, GoalPhase.FAILED))
            next = PlannerOutcomes.terminal(next)
        if (next != record) {
            persist(next); notify(next)
            if (next.phase != GoalPhase.EXECUTING && !isWatching(next)) cancel(record.npcUuid)
        }
        return next
    }

    private fun events(batch: OperationEventBatch) {
        val record = store.get(batch.npcUuid) ?: return
        if (batch.journal.events.none { it.kind in significant }) return
        val actor = server.playerList.getPlayer(record.actorUuid) ?: return
        if (record.phase == GoalPhase.EXECUTING) observeKnown(record, actor)
        else if (record.phase in setOf(GoalPhase.QUEUED, GoalPhase.INFERENCING) || isWatching(record)) {
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
        record.revision, record.contextText(), record.mode, null, session.budget.contextRemainingCalls, memory = record.memory.context(), supervision = record.supervision,
        planStepsCompleted = record.planStepsCompleted)

    private fun isWatching(record: GoalRecord?): Boolean = PlannerOutcomes.boundary(record) ||
        record?.supervision != null && record.phase == GoalPhase.WAITING && !record.manualHold

    private fun sample(record: GoalRecord, now: Long) {
        val actor = server.playerList.getPlayer(record.actorUuid)
        if (actor == null) { hold(record.npcUuid, "ACTOR_DISCONNECTED"); return }
        val (stock, problem) = StockSupervisor.read(server, actor, record)
        if (stock == null) { hold(record.npcUuid, checkNotNull(problem)); return }
        val state = checkNotNull(record.supervision)
        if (state.waitUntilTick != null && stock.observedTick < state.waitUntilTick) return
        if (!state.target.needsRefill(stock.count, state.armed)) {
            if (!state.armed || state.waitUntilTick != null) persist(record.copy(code = "STOCK_TARGET_REACHED",
                supervision = state.copy(armed = true, waitUntilTick = null)))
            return
        }
        state.failures.problem()?.let { persist(StockSupervisor.ask(record, it)); cancel(record.npcUuid); return }
        val queued = record.copy(phase = GoalPhase.QUEUED, code = "STOCK_SHORTAGE",
            supervision = state.copy(armed = false, waitUntilTick = null))
        persist(queued)
        wake(queued, now, InferenceReason.STOCK_CHANGED)?.let { hold(record.npcUuid, it) }
    }

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
