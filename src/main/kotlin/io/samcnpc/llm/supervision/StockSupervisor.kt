package io.samcnpc.llm.supervision

import io.samcnpc.behavior.api.OperationStockApi
import io.samcnpc.core.api.NpcActionStatus
import io.samcnpc.core.api.NpcStockRead
import io.samcnpc.llm.context.CapturedContext
import io.samcnpc.llm.decision.*
import io.samcnpc.llm.goal.*
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer

/** Server-thread stock state transitions; polling and inference scheduling belong to the goal runtime. */
internal object StockSupervisor {
    fun read(server: MinecraftServer, actor: ServerPlayer, record: GoalRecord): Pair<NpcStockRead.Observed?, String?> {
        val target = checkNotNull(record.supervision).target
        val reply = OperationStockApi.inspect(server, actor, record.npcUuid, target.dimensionId, target.query)
        if (reply.result.status != NpcActionStatus.SUCCEEDED) return null to "STOCK_" + reply.result.code.name
        val stock = reply.stock
        return if (stock is NpcStockRead.Observed) stock to null else null to ("STOCK_" +
            ((stock as? NpcStockRead.Unavailable)?.reason?.name ?: "UNKNOWN"))
    }

    fun admitting(record: GoalRecord, decision: LlmDecision, captured: CapturedContext): GoalRecord {
        val state = checkNotNull(record.supervision)
        val stock = checkNotNull(captured.stock)
        val fingerprint = StockFingerprints.decision(decision.action)
        val world = StockFingerprints.world(state, stock,
            captured.inspection.body.inventory.filter { !it.stack.isEmpty }.map { checkNotNull(it.stack.itemId) to it.stack.count })
        val problem = state.failures.problem(fingerprint, world)
        if (problem != null) return ask(record, problem)
        return record.copy(supervision = state.copy(pendingDecision = fingerprint, pendingWorld = world, beforeCount = stock.count))
    }

    fun admitted(record: GoalRecord, decision: LlmDecision, outcome: DecisionOutcome, tick: Long): GoalRecord {
        val next = TranslatorOutcomes.admitted(record, decision, outcome)
        val state = checkNotNull(record.supervision)
        if (next.phase in setOf(GoalPhase.EXECUTING, GoalPhase.REVIEW_REQUIRED)) return next
        if (next.phase == GoalPhase.ASK_USER) return next.copy(supervision = state.clearAttempt())
        val action = decision.action
        val delay = if (outcome.state == DecisionOutcomeState.NO_EFFECT && action is DecisionAction.Wait && action.trigger == WaitTrigger.DEADLINE) checkNotNull(action.ticks) else null
        if (delay != null && tick > Long.MAX_VALUE - delay) return ask(next, "GAME_CLOCK_EXHAUSTED")
        val deadline = delay?.let { tick + it }
        val code = if (deadline != null) "WAIT_DEADLINE" else next.code
        val failures = state.failures.failed(checkNotNull(state.pendingDecision), checkNotNull(state.pendingWorld), code)
        failures.problem()?.let { return ask(next.copy(supervision = state.copy(failures = failures).clearAttempt()), it) }
        val auto = outcome.state == DecisionOutcomeState.REJECTED || deadline != null
        return next.copy(phase = GoalPhase.WAITING, code = code, manualHold = !auto,
            supervision = state.copy(failures = failures, waitUntilTick = deadline).clearAttempt())
    }

    fun terminal(record: GoalRecord, stock: NpcStockRead.Observed): GoalRecord {
        val state = checkNotNull(record.supervision)
        val progress = state.beforeCount?.let { stock.count > it } == true
        val failures = if (progress) state.failures.progressed() else
            state.failures.failed(checkNotNull(state.pendingDecision), checkNotNull(state.pendingWorld), record.code)
        val next = record.copy(phase = GoalPhase.WAITING, task = null, question = null,
            code = if (stock.count >= state.target.target) "STOCK_TARGET_REACHED" else "STOCK_STEP_FINISHED",
            supervision = state.copy(armed = stock.count >= state.target.target, failures = failures,
                progressEpoch = if (progress && state.progressEpoch < Long.MAX_VALUE) state.progressEpoch + 1 else state.progressEpoch,
                waitUntilTick = null).clearAttempt())
        return failures.problem()?.let { ask(next, it) } ?: next
    }

    fun ask(record: GoalRecord, code: String): GoalRecord = record.copy(phase = GoalPhase.ASK_USER, code = code,
        manualHold = false, question = "No stock progress. Please change the source or explain how to proceed.",
        supervision = record.supervision?.clearAttempt())

    fun unavailable(record: GoalRecord, code: String): GoalRecord =
        record.copy(phase = GoalPhase.WAITING, code = code, manualHold = true, question = null)
}
