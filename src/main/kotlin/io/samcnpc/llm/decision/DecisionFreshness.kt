package io.samcnpc.llm.decision

import io.samcnpc.behavior.api.OperationInspection
import io.samcnpc.llm.context.*
import java.util.UUID

/** Reads only immutable captures; the caller obtains current through the authorized server API. */
internal object DecisionFreshness {
    fun problem(captured: CapturedContext, current: OperationInspection, actor: UUID,
                goal: ContextGoal, policy: ContextPolicy, manualHold: Boolean): String? {
        val binding = captured.binding
        val task = current.operation.task
        val tick = current.physical.gameTime
        return when {
            manualHold -> "MANUAL_HOLD"
            actor != binding.actorUuid -> "ACTOR_CHANGED"
            current.physical.npcUuid != binding.npcUuid -> "NPC_CHANGED"
            goal.id != binding.goalId || goal.revision != binding.goalRevision -> "GOAL_CHANGED"
            goal.mode != captured.goal.mode || goal.supervision != captured.goal.supervision -> "GOAL_POLICY_CHANGED"
            goal.planStepsCompleted != captured.goal.planStepsCompleted -> "GOAL_PLAN_CHANGED"
            goal.memory.plan != captured.goal.memory.plan || goal.memory.aliases != captured.goal.memory.aliases ||
                goal.memory.confirmedResults != captured.goal.memory.confirmedResults -> "GOAL_MEMORY_CHANGED"
            goal.remainingCalls == 0 -> "GOAL_BUDGET_EXHAUSTED"
            policy.revision != binding.policyRevision || !samePolicy(captured.policy, policy) -> "POLICY_CHANGED"
            current.generations != binding.generations -> "GENERATION_CHANGED"
            NpcContextBuilder.catalogHash != binding.catalogHash -> "CATALOG_CHANGED"
            current.physical.dimensionId != captured.inspection.physical.dimensionId -> "DIMENSION_CHANGED"
            tick < binding.issuedTick || tick >= binding.expiresTick -> "CONTEXT_EXPIRED"
            goal.deadlineTick != null && tick >= goal.deadlineTick -> "GOAL_EXPIRED"
            task?.taskId != binding.priorTaskId -> "TASK_CHANGED"
            task?.definitionRevision != binding.definitionRevision -> "DEFINITION_CHANGED"
            task?.controlRevision != binding.controlRevision -> "CONTROL_CHANGED"
            task?.state != captured.inspection.operation.task?.state -> "TASK_STATE_CHANGED"
            task?.frames?.map { it.frameId } != captured.inspection.operation.task?.frames?.map { it.frameId } -> "TASK_STACK_CHANGED"
            task?.pendingAmendmentId != captured.inspection.operation.task?.pendingAmendmentId -> "AMENDMENT_CHANGED"
            else -> null
        }
    }

    private fun samePolicy(a: ContextPolicy, b: ContextPolicy): Boolean =
        a.operations == b.operations && a.changes == b.changes && a.controls == b.controls &&
            a.maxTaskTicks == b.maxTaskTicks && a.maxTaskAttempts == b.maxTaskAttempts &&
            a.maxExtensionTicks == b.maxExtensionTicks
}
