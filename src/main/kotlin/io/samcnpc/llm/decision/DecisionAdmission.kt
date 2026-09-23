package io.samcnpc.llm.decision

import com.mojang.logging.LogUtils
import io.samcnpc.behavior.api.*
import io.samcnpc.core.api.NpcActionStatus
import io.samcnpc.llm.context.*
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer

/**
 * One transient slot per goal/NPC, owned by its server-thread controller. Rebinding invalidates
 * an older in-flight context; consumed responses cannot dispatch again. Durable recovery is a
 * separate goal-store responsibility, not a claim of exactly-once assignment across restarts.
 */
internal class DecisionAdmission(private val gateway: DecisionGateway = DecisionGateway.BEHAVIOR) {
    private var captured: CapturedContext? = null
    private var consumed = false
    private var invalidated: String? = null
    private var amendment: OperationAmendmentRequest? = null
    var outcome: DecisionOutcome? = null
        private set

    fun bind(value: CapturedContext): Boolean {
        if (outcome?.state in setOf(DecisionOutcomeState.PENDING, DecisionOutcomeState.UNCERTAIN)) return false
        if (captured?.binding?.contextId == value.binding.contextId) return false
        captured = value
        consumed = false
        invalidated = null
        amendment = null
        outcome = null
        return true
    }

    /** Controller calls this on manual stop, goal replacement and lifecycle invalidation. */
    fun invalidate() { invalidated = "CONTEXT_INVALIDATED" }

    fun admit(server: MinecraftServer, actor: ServerPlayer, decision: LlmDecision, goal: ContextGoal,
              policy: ContextPolicy, manualHold: Boolean,
              reserveIntent: ((io.samcnpc.llm.intent.GoalIntentReservation) -> Boolean)? = null): DecisionOutcome {
        if (!server.isSameThread) return DecisionOutcome.rejected("SERVER_THREAD_REQUIRED")
        val source = captured ?: return DecisionOutcome.rejected("NO_ISSUED_CONTEXT")
        if (decision.contextId != source.binding.contextId) return DecisionOutcome.rejected("CONTEXT_MISMATCH")
        if (consumed) return DecisionOutcome.rejected("DECISION_ALREADY_CONSUMED")
        consumed = true
        val result = admitOnce(server, actor, decision, source, goal, policy, manualHold, reserveIntent)
        outcome = result
        return result
    }

    private fun admitOnce(server: MinecraftServer, actor: ServerPlayer, decision: LlmDecision,
                          source: CapturedContext, goal: ContextGoal, policy: ContextPolicy,
                          manualHold: Boolean,
                          reserveIntent: ((io.samcnpc.llm.intent.GoalIntentReservation) -> Boolean)?): DecisionOutcome {
        invalidated?.let { return DecisionOutcome.rejected(it) }
        if (actor.uuid != source.binding.actorUuid) return DecisionOutcome.rejected("ACTOR_CHANGED")
        val reply = OperationInspectionApi.inspect(server, actor, source.binding.npcUuid)
        val current = reply.inspection
        if (reply.result.status != NpcActionStatus.SUCCEEDED || current == null)
            return DecisionOutcome.rejected("OBSERVATION_" + reply.result.code.name)
        DecisionFreshness.problem(source, current, actor.uuid, goal, policy, manualHold)?.let {
            return DecisionOutcome.rejected(it)
        }
        DecisionPolicy.problem(decision, source)?.let { return DecisionOutcome.rejected(it) }
        val intent = io.samcnpc.llm.intent.GoalIntentPolicy.check(decision.action, goal, current.body, current.physical.position)
        intent.problem?.let { return DecisionOutcome.rejected(it) }
        decision.plan?.preconditionProblem(current.body)?.let { return DecisionOutcome.rejected(it) }
        val stockTarget = goal.supervision?.target
        if (stockTarget != null) {
            val stockReply = OperationStockApi.inspect(server, actor, source.binding.npcUuid,
                stockTarget.dimensionId, stockTarget.query)
            if (stockReply.result.status != NpcActionStatus.SUCCEEDED)
                return DecisionOutcome.rejected("STOCK_" + stockReply.result.code.name)
            val stock = stockReply.stock as? io.samcnpc.core.api.NpcStockRead.Observed
                ?: return DecisionOutcome.rejected("STOCK_NO_LONGER_OBSERVED")
            if (stock.count != source.stock?.count || stock.slots != source.stock?.slots)
                return DecisionOutcome.rejected("STOCK_CHANGED_DURING_INFERENCE")
            io.samcnpc.llm.supervision.StockDecisionPolicy.problem(decision.action, stockTarget, stock)?.let {
                return DecisionOutcome.rejected(it)
            }
        }
        val action = decision.action
        if (action is DecisionAction.Continue || action is DecisionAction.Wait || action is DecisionAction.AskUser)
            return DecisionOutcome(DecisionOutcomeState.NO_EFFECT, decision.kind.name, current.operation.task?.taskId)
        val binding = source.binding
        if (action is DecisionAction.Amend) {
            amendment = OperationAmendmentRequest(checkNotNull(binding.priorTaskId), binding.contextId,
                checkNotNull(binding.definitionRevision), binding.issuedTick, binding.expiresTick, action.change)
        }
        // Persist conservatively before any foreign mutation. No receipt, exception or rejection refunds intent.
        if (goal.constraints != null && (reserveIntent == null || !reserveIntent(intent.charge)))
            return DecisionOutcome.rejected("INTENT_RESERVATION_UNAVAILABLE")
        // Mark the outcome uncertain before crossing the mutation boundary, including an unexpected exception.
        outcome = DecisionOutcome(DecisionOutcomeState.UNCERTAIN, "ADMISSION_DISPATCH_STARTED")
        return try {
            DecisionOutcome.fromReply(gateway.dispatch(server, actor, binding, action, amendment), action is DecisionAction.Amend)
        } catch (error: RuntimeException) {
            LOGGER.warn("LLM admission reply unavailable context={} exception={}", binding.contextId, error.javaClass.simpleName)
            DecisionOutcome(DecisionOutcomeState.UNCERTAIN, "ADMISSION_REPLY_UNAVAILABLE")
        }
    }

    /** Read only. An absent receipt or a matching current state never causes a mutation retry. */
    fun reconcile(server: MinecraftServer, actor: ServerPlayer): DecisionOutcome {
        if (!server.isSameThread) return DecisionOutcome.rejected("SERVER_THREAD_REQUIRED")
        val source = captured ?: return DecisionOutcome.rejected("NO_ISSUED_CONTEXT")
        val previous = outcome ?: return DecisionOutcome.rejected("NO_ADMISSION_ATTEMPT")
        if (previous.state !in setOf(DecisionOutcomeState.PENDING, DecisionOutcomeState.UNCERTAIN)) return previous
        if (actor.uuid != source.binding.actorUuid) return DecisionOutcome.rejected("ACTOR_CHANGED")
        val reply = OperationInspectionApi.inspect(server, actor, source.binding.npcUuid)
        val current = reply.inspection
        if (reply.result.status != NpcActionStatus.SUCCEEDED || current == null)
            return DecisionOutcome.rejected("OBSERVATION_" + reply.result.code.name)
        if (current.generations.serverSession != source.binding.generations.serverSession ||
            current.generations.body != source.binding.generations.body)
            return DecisionOutcome.rejected("GENERATION_CHANGED")
        val request = amendment
        if (request != null) {
            val receipt = OperationSupervisionApi.amendmentReceipt(server, actor, source.binding.npcUuid, request)
            if (receipt.amendment != null) {
                val resolved = DecisionOutcome.fromReply(receipt, true)
                outcome = resolved
                return resolved
            }
        }
        val task = current.operation.task
        val unresolved = DecisionOutcome(DecisionOutcomeState.UNCERTAIN,
            if (request == null) "CURRENT_STATE_IS_NOT_A_RECEIPT" else "AMENDMENT_RECEIPT_NOT_RECORDED",
            task?.taskId, task?.definitionRevision, task?.controlRevision)
        outcome = unresolved
        return unresolved
    }

    private companion object { val LOGGER = LogUtils.getLogger() }
}
