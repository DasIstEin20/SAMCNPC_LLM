package io.samcnpc.llm.goal

import io.samcnpc.behavior.api.*
import io.samcnpc.core.api.NpcActionStatus
import io.samcnpc.llm.api.LlmProvider
import io.samcnpc.llm.config.ProviderSettings
import io.samcnpc.llm.provider.OpenAiCompatibleProvider
import io.samcnpc.llm.scheduling.*
import io.samcnpc.llm.supervision.*
import io.samcnpc.llm.context.LlmMode
import io.samcnpc.llm.planning.PlannerOutcomes
import io.samcnpc.llm.intent.*
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

internal data class GoalReply(val accepted: Boolean, val code: String, val record: GoalRecord? = null,
    val requestReport: InferenceReport? = null, val compactionReport: CompactionReport? = null)

/** Player-facing goal lifecycle; all world authorization uses the published Behavior gateway. */
internal class LlmGoalController(
    private val server: MinecraftServer,
    val settings: ProviderSettings,
    val store: LlmGoalStore = LlmGoalStore.forServer(server),
    rates: InferenceRateGate = InferenceRateGate(settings.inference.serverResources(settings.maxOutputTokens)),
    provider: LlmProvider = OpenAiCompatibleProvider(settings),
) : AutoCloseable {
    private val runtime = TranslatorRuntime(server, store, settings, provider, rates, ::notify)
    private val compaction = GoalCompaction(server, store, settings)
    private var closed = false

    fun startBounded(actor: ServerPlayer, npc: UUID, document: String, now: Long, planner: Boolean = false,
                     plannerVariant: io.samcnpc.llm.mission.PlannerVariant = io.samcnpc.llm.mission.PlannerVariant.V1): GoalReply {
        authorization(actor, npc)?.let { return it }
        val request = try { GoalConstraintCodec.playerDocument(document) }
            catch (_: IllegalArgumentException) { return rejected("INVALID_GOAL_CONSTRAINTS") }
        return start(actor, npc, request.text, now, planner = planner, constraints = request.constraints, plannerVariant = plannerVariant)
    }

    fun start(actor: ServerPlayer, npc: UUID, text: String, now: Long, stockTarget: StockTarget? = null, planner: Boolean = false,
              constraints: GoalConstraints? = null,
              plannerVariant: io.samcnpc.llm.mission.PlannerVariant = io.samcnpc.llm.mission.PlannerVariant.V1): GoalReply {
        authorization(actor, npc)?.let { return it }
        readiness()?.let { return rejected(it) }
        val mission = plannerVariant == io.samcnpc.llm.mission.PlannerVariant.MISSION_V2
        if (mission && !planner) return rejected("CONFLICTING_GOAL_MODES")
        if (mission && settings.responseFormat == io.samcnpc.llm.config.ResponseFormat.SAM_EXPRESSION_V1)
            return rejected("MISSION_REQUIRES_JSON_PROTOCOL")
        if (planner && stockTarget != null) return rejected("CONFLICTING_GOAL_MODES")
        if (constraints != null && stockTarget != null) return rejected("CONFLICTING_GOAL_MODES")
        if (!GoalRecord.validText(text, 1024)) return rejected("INVALID_GOAL_TEXT")
        val previous = store.get(npc)
        if (previous != null && previous.phase !in finished) return rejected("ACTIVE_GOAL_REQUIRES_STOP")
        if (previous?.budget?.inFlight != null) return rejected("PREVIOUS_INFERENCE_DRAINING")
        if (activeTask(actor, npc)) return rejected("ACTIVE_BEHAVIOR_TASK_REQUIRES_REVIEW")
        val bound = if (constraints == null) null else {
            val read = OperationInspectionApi.inspect(server, actor, npc)
            val inspection = read.inspection
            if (read.result.status != NpcActionStatus.SUCCEEDED || inspection == null) return rejected("INTENT_INSPECTION_UNAVAILABLE")
            if (constraints.dimensionId != inspection.physical.dimensionId) return rejected("INTENT_DIMENSION_NOT_ALLOWED")
            val stock = GoalIntentPolicy.stock(inspection.body, constraints.resourceIds)
            if (stock !in 0..65536) return rejected("INTENT_INITIAL_STOCK_OUT_OF_RANGE")
            try { constraints.withInitialStock(stock) }
            catch (_: IllegalArgumentException) { return rejected("INTENT_CONSTRAINTS_TOO_LARGE") }
        }
        if (stockTarget != null && store.records().any { it.npcUuid != npc && it.phase !in finished &&
            it.supervision?.target?.sameStorage(stockTarget) == true }) return rejected("STOCK_ALREADY_SUPERVISED")
        val record = GoalRecord(npc, actor.uuid, UUID.randomUUID(), 1, text, limits = settings.inference.goalLimits(settings.maxOutputTokens),
            phase = if (stockTarget == null) GoalPhase.QUEUED else GoalPhase.WAITING,
            mode = if (planner) LlmMode.PLANNER else if (stockTarget == null) LlmMode.TRANSLATOR else LlmMode.SUPERVISOR,
            supervision = stockTarget?.let { StockSupervision(it) },
            memory = previous?.memory?.placesOnly() ?: GoalMemory(), constraints = bound,
            mission = if (mission) io.samcnpc.llm.mission.MissionState() else null)
        if (stockTarget != null) {
            val (_, problem) = StockSupervisor.read(server, actor, record)
            if (problem != null) return rejected(problem)
        }
        return queue(actor, record, now, InferenceReason.USER_GOAL)
    }

    fun answer(actor: ServerPlayer, npc: UUID, text: String, now: Long): GoalReply {
        authorization(actor, npc)?.let { return it }
        readiness()?.let { return rejected(it) }
        val record = store.get(npc) ?: return rejected("GOAL_NOT_FOUND")
        if (record.phase != GoalPhase.ASK_USER || record.manualHold) return rejected("NO_ACTIVE_QUESTION")
        if (record.mode == LlmMode.PLANNER && record.planStepsCompleted >= 8) return rejected("PLAN_STEP_BUDGET_EXHAUSTED")
        if (!GoalRecord.validText(text, 512)) return rejected("INVALID_ANSWER_TEXT")
        val answer = record.answer?.let { it + "\n" + text } ?: text
        if (!GoalRecord.validText(answer, 512)) return rejected("CLARIFICATION_LIMIT")
        if (record.revision == Long.MAX_VALUE) return rejected("GOAL_REVISION_EXHAUSTED")
        if (record.budget.inFlight != null) return rejected("PREVIOUS_INFERENCE_DRAINING")
        if (activeTask(actor, npc)) return rejected("ACTIVE_BEHAVIOR_TASK_REQUIRES_REVIEW")
        return queue(actor, record.copy(actorUuid = actor.uuid, revision = record.revision + 1,
            answer = answer, phase = if (record.supervision == null) GoalPhase.QUEUED else GoalPhase.WAITING,
            supervision = record.supervision?.userIntervention(), code = "USER_ANSWER", question = null),
            now, InferenceReason.USER_ANSWER)
    }

    fun resume(actor: ServerPlayer, npc: UUID, now: Long): GoalReply {
        authorization(actor, npc)?.let { return it }
        val record = store.get(npc) ?: return rejected("GOAL_NOT_FOUND")
        if (record.budget.inFlight != null) return rejected("PREVIOUS_INFERENCE_DRAINING")
        if (record.phase == GoalPhase.REVIEW_REQUIRED) return rejected("REVIEW_REQUIRES_NEW_GOAL")
        if (record.phase in finished) return rejected("FINISHED_GOAL_REQUIRES_NEW_GOAL")
        if (record.phase == GoalPhase.ASK_USER) return rejected("ANSWER_REQUIRED")
        if (record.revision == Long.MAX_VALUE) return rejected("GOAL_REVISION_EXHAUSTED")
        val known = record.task
        if (known != null) {
            val view = checkNotNull(OperationSupervisionApi.observe(server, actor, npc).observation)
            val task = view.task
            if (task == null || task.taskId != known.id) return rejected("TASK_REVIEW_REQUIRED")
            if (record.constraints != null && task.definitionRevision != known.definitionRevision)
                return rejected("INTENT_TASK_CHANGED_REQUIRES_NEW_GOAL")
            if (record.supervision != null && task.state in setOf(OperationTaskState.COMPLETED, OperationTaskState.FAILED)) {
                val (stock, problem) = StockSupervisor.read(server, actor, record)
                if (stock == null) return rejected(checkNotNull(problem))
                val terminal = StockSupervisor.terminal(record.copy(code = "TASK_" + task.state.name), stock)
                val resumed = terminal.copy(actorUuid = actor.uuid, revision = record.revision + 1,
                    phase = GoalPhase.WAITING, manualHold = false, question = null,
                    supervision = terminal.supervision?.userIntervention())
                return queue(actor, resumed, now, InferenceReason.USER_GOAL)
            }
            if (record.mode == LlmMode.PLANNER && task.state in setOf(OperationTaskState.COMPLETED, OperationTaskState.FAILED)) {
                if (task.definitionRevision != known.definitionRevision ||
                    !TranslatorOutcomes.controlMatches(known.controlRevision, task.controlRevision, task.state))
                    return rejected("TASK_REVIEW_REQUIRED")
                val current = runtime.observeKnown(record.copy(actorUuid = actor.uuid, revision = record.revision + 1,
                    phase = GoalPhase.EXECUTING, manualHold = false, question = null), actor)
                if (PlannerOutcomes.boundary(current)) runtime.attach(current, actor)?.let { return waiting(current, it) }
                return GoalReply(true, current.code, current)
            }
            if (task.state in terminalTaskStates) {
                val terminal = record.copy(phase = when (task.state) {
                    OperationTaskState.COMPLETED -> GoalPhase.COMPLETED
                    OperationTaskState.FAILED -> GoalPhase.FAILED
                    else -> GoalPhase.STOPPED
                }, code = "TASK_" + task.state.name, manualHold = task.state == OperationTaskState.CANCELLED)
                store.put(terminal)?.let { return rejected(it) }
                runtime.cancel(npc)
                return GoalReply(true, terminal.code, terminal)
            }
            var current = task
            if (task.state == OperationTaskState.PAUSED) {
                val reply = OperationSupervisionApi.control(server, actor, npc,
                    OperationControlRequest(task.taskId, task.controlRevision, task.definitionRevision,
                        view.observedTick, view.observedTick + 100, OperationControl.RESUME))
                if (reply.result.status != NpcActionStatus.SUCCEEDED) return rejected("RESUME_" + reply.result.code.name)
                current = reply.observation?.task ?: return rejected("RESUME_OBSERVATION_MISSING")
            }
            val resumed = record.copy(actorUuid = actor.uuid, revision = record.revision + 1,
                phase = GoalPhase.EXECUTING, code = "USER_ACCEPTED_CURRENT_TASK", manualHold = false,
                task = GoalTask(current.taskId, current.definitionRevision, current.controlRevision, known.operationId))
            store.put(resumed)?.let { return rejected(it) }
            runtime.attach(resumed, actor)?.let { return waiting(resumed, it) }
            return GoalReply(true, resumed.code, runtime.observeKnown(resumed, actor))
        }
        readiness()?.let { return rejected(it) }
        if (activeTask(actor, npc)) return rejected("ACTIVE_BEHAVIOR_TASK_REQUIRES_REVIEW")
        return queue(actor, record.copy(actorUuid = actor.uuid, revision = record.revision + 1,
            phase = if (record.supervision == null) GoalPhase.QUEUED else GoalPhase.WAITING,
            supervision = record.supervision?.userIntervention(), code = "USER_RESUMED", manualHold = false, question = null),
            now, InferenceReason.USER_GOAL)
    }

    fun stop(actor: ServerPlayer, npc: UUID): GoalReply {
        authorization(actor, npc)?.let { return it }
        val record = store.get(npc) ?: return rejected("GOAL_NOT_FOUND")
        val stopped = record.copy(phase = GoalPhase.STOPPED, code = "USER_STOPPED", manualHold = true, question = null)
        store.put(stopped)?.let { return rejected(it) }
        runtime.cancel(npc)
        val view = checkNotNull(OperationSupervisionApi.observe(server, actor, npc).observation)
        val task = view.task
        if (task != null && task.taskId == record.task?.id && task.state !in terminalTaskStates) {
            val reply = OperationSupervisionApi.control(server, actor, npc,
                OperationControlRequest(task.taskId, task.controlRevision, task.definitionRevision,
                    view.observedTick, view.observedTick + 100, OperationControl.CANCEL))
            if (reply.result.status != NpcActionStatus.SUCCEEDED) {
                val failed = stopped.copy(code = "STOP_CONTROL_" + reply.result.code.name)
                check(store.put(failed) == null)
                return GoalReply(false, failed.code, failed)
            }
        }
        return GoalReply(true, stopped.code, store.get(npc))
    }

    /** Only the current authorized player can confirm an open goal after its recorded steps. */
    fun complete(actor: ServerPlayer, npc: UUID): GoalReply {
        authorization(actor, npc)?.let { return it }
        val record = store.get(npc) ?: return rejected("GOAL_NOT_FOUND")
        if (record.mode != LlmMode.PLANNER || record.mission != null || record.phase != GoalPhase.ASK_USER ||
            record.code != "PLAN_CONFIRMATION_REQUIRED" || record.manualHold ||
            record.memory.plan.isNotEmpty() || record.task != null || record.planStepsCompleted == 0)
            return rejected("PLAN_NOT_READY_FOR_CONFIRMATION")
        if (record.budget.inFlight != null) return rejected("PREVIOUS_INFERENCE_DRAINING")
        if (activeTask(actor, npc)) return rejected("ACTIVE_BEHAVIOR_TASK_REQUIRES_REVIEW")
        val completed = record.copy(phase = GoalPhase.COMPLETED, code = "USER_CONFIRMED_GOAL", question = null)
        store.put(completed)?.let { return rejected(it) }
        runtime.cancel(npc)
        return GoalReply(true, completed.code, completed)
    }

    fun status(actor: ServerPlayer, npc: UUID): GoalReply {
        authorization(actor, npc)?.let { return it }
        val record = store.get(npc) ?: return rejected("GOAL_NOT_FOUND")
        val current = runtime.observeKnown(record, actor)
        return GoalReply(true, current.code, current, runtime.requestReport(npc, current.goalId), compaction.report(current))
    }

    fun compact(actor: ServerPlayer, npc: UUID): GoalReply {
        authorization(actor, npc)?.let { return it }
        val record = store.get(npc) ?: return rejected("GOAL_NOT_FOUND")
        val reply = compaction.submit(actor, record)
        if (reply.accepted) runtime.invalidateDecision(npc)
        return reply
    }

    /** Editing a label is intent only and never observes or loads the named world position. */
    fun place(actor: ServerPlayer, npc: UUID, name: String,
              position: io.samcnpc.core.api.NpcBlockPosition?): GoalReply {
        authorization(actor, npc)?.let { return it }
        val record = store.get(npc) ?: return rejected("GOAL_NOT_FOUND")
        if (!GoalMemory.validName(name)) return rejected("INVALID_PLACE_NAME")
        if (record.phase in setOf(GoalPhase.QUEUED, GoalPhase.INFERENCING, GoalPhase.ADMITTING, GoalPhase.EXECUTING) ||
            record.budget.inFlight != null) return rejected("MEMORY_EDIT_REQUIRES_IDLE_GOAL")
        if (record.revision == Long.MAX_VALUE) return rejected("GOAL_REVISION_EXHAUSTED")
        val memory = try {
            if (position == null) record.memory.withoutPlace(name)
            else record.memory.withPlace(io.samcnpc.llm.context.ContextPlaceAlias(name,
                actor.serverLevel().dimension().location().toString(), position))
        } catch (_: IllegalArgumentException) { return rejected("MEMORY_LIMIT_OR_INVALID_PLACE") }
        if (memory == record.memory) return GoalReply(true, "MEMORY_UNCHANGED", record)
        val updated = record.copy(revision = record.revision + 1, memory = memory)
        store.put(updated)?.let { return rejected(it) }
        return GoalReply(true, if (position == null) "PLACE_FORGOTTEN" else "PLACE_REMEMBERED", updated)
    }

    fun forget(actor: ServerPlayer, npc: UUID): GoalReply {
        authorization(actor, npc)?.let { return it }
        val record = store.get(npc) ?: return rejected("GOAL_NOT_FOUND")
        if (record.phase !in finished || record.budget.inFlight != null) return rejected("STOP_GOAL_BEFORE_FORGET")
        runtime.cancel(npc)
        store.remove(npc)?.let { return rejected(it) }
        runtime.forgetReport(npc)
        compaction.forget(npc)
        return GoalReply(true, "GOAL_FORGOTTEN")
    }

    /** Known tasks and waiting watches reconnect; only a new authorized sample may wake a watch. */
    fun reconnect(actor: ServerPlayer) {
        check(server.isSameThread)
        if (closed || store.problem != null) return
        for (record in store.records().filter { it.actorUuid == actor.uuid && !it.manualHold &&
            (it.phase == GoalPhase.EXECUTING || it.supervision != null && it.phase == GoalPhase.WAITING || PlannerOutcomes.boundary(it)) }) {
            if (authorization(actor, record.npcUuid) != null) continue
            val current = runtime.observeKnown(record, actor)
            if (current.phase == GoalPhase.EXECUTING || PlannerOutcomes.boundary(current) ||
                current.supervision != null && current.phase == GoalPhase.WAITING && !current.manualHold)
                runtime.attach(current, actor)
        }
    }

    /** An explicit Core dismissal is authoritative; an unload/missing lookup is not deletion. */
    fun dismissed(npc: UUID) {
        check(server.isSameThread)
        if (closed || store.problem != null) return
        runtime.cancel(npc)
        check(store.remove(npc) == null)
        runtime.forgetReport(npc)
        compaction.forget(npc)
    }

    fun poll(now: Long) {
        check(server.isSameThread)
        if (!closed) { runtime.poll(now); compaction.poll() }
    }

    private fun queue(actor: ServerPlayer, record: GoalRecord, now: Long, reason: InferenceReason): GoalReply {
        if (InferenceBudget(record.limits, record.budget).availableCalls == 0) return rejected("GOAL_CALL_BUDGET_EXHAUSTED")
        store.put(record)?.let { return rejected(it) }
        runtime.attach(record, actor)?.let { return waiting(record, it) }
        if (record.supervision != null && record.phase == GoalPhase.WAITING) return GoalReply(true, record.code, record)
        runtime.wake(record, now, reason)?.let { runtime.cancel(record.npcUuid); return waiting(record, it) }
        return GoalReply(true, record.code, record)
    }

    private fun waiting(record: GoalRecord, code: String): GoalReply {
        val next = record.copy(phase = GoalPhase.WAITING, code = code, question = null, manualHold = true)
        check(store.put(next) == null)
        return GoalReply(false, code, next)
    }

    private fun authorization(actor: ServerPlayer, npc: UUID): GoalReply? {
        check(server.isSameThread)
        if (closed) return rejected("GOAL_CONTROLLER_CLOSED")
        val reply = OperationSupervisionApi.observe(server, actor, npc)
        if (reply.result.status != NpcActionStatus.SUCCEEDED || reply.observation == null)
            return rejected("OBSERVATION_" + reply.result.code.name)
        store.problem?.let { return rejected(it) }
        return null
    }

    private fun readiness(): String? = when {
        !settings.enabled -> "LLM_DISABLED"
        settings.problem() != null -> "INVALID_PROVIDER_CONFIGURATION"
        else -> settings.inference.readiness(settings)
    }

    private fun activeTask(actor: ServerPlayer, npc: UUID): Boolean =
        OperationSupervisionApi.observe(server, actor, npc).observation?.task?.state?.let { it !in terminalTaskStates } == true

    private fun notify(record: GoalRecord) {
        val actor = server.playerList.getPlayer(record.actorUuid) ?: return
        actor.sendSystemMessage(Component.literal("SAMCNPC LLM " + record.npcUuid + ": " + record.phase.name +
            " / " + record.code + (record.question?.let { "\n" + it } ?: "")))
    }

    override fun close() {
        check(server.isSameThread)
        if (closed) return
        closed = true
        compaction.close()
        runtime.close()
    }

    companion object {
        private val finished = setOf(GoalPhase.COMPLETED, GoalPhase.FAILED, GoalPhase.STOPPED)
        private val terminalTaskStates = setOf(OperationTaskState.COMPLETED, OperationTaskState.FAILED, OperationTaskState.CANCELLED)
        private fun rejected(code: String) = GoalReply(false, code)
    }
}
