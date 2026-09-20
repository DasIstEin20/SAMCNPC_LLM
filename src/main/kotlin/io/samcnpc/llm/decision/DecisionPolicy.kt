package io.samcnpc.llm.decision

import io.samcnpc.behavior.api.*
import io.samcnpc.core.api.NpcActionStatus
import io.samcnpc.llm.context.CapturedContext
import io.samcnpc.llm.context.ContextPolicy

/** Pure policy checks, in addition to Behavior semantics and later current-world authorization. */
internal object DecisionPolicy {
    fun problem(decision: LlmDecision, captured: CapturedContext): String? {
        val policy = captured.policy
        val inspection = captured.inspection
        val task = inspection.operation.task
        val active = task != null && task.state !in terminalStates
        return when (val action = decision.action) {
            DecisionAction.Continue, is DecisionAction.AskUser -> null
            is DecisionAction.Assign -> {
                if (active) "ACTIVE_TASK_CANNOT_BE_REPLACED_BY_ASSIGN"
                else orderProblem(action.order, policy, inspection.physical.dimensionId)
            }
            is DecisionAction.Amend -> {
                if (!active) "AMEND_REQUIRES_ACTIVE_TASK"
                else {
                    val frame = checkNotNull(task).frames.first()
                    changeProblem(action.change, policy, inspection.physical.dimensionId, frame.operationId, frame.durationTicks)
                }
            }
            is DecisionAction.Control -> when {
                action.control !in policy.controls -> "CONTROL_NOT_ALLOWED"
                !active -> "CONTROL_REQUIRES_ACTIVE_TASK"
                action.control == OperationControl.RESUME && task?.state != OperationTaskState.PAUSED -> "TASK_NOT_PAUSED"
                action.control == OperationControl.PAUSE && task?.state == OperationTaskState.PAUSED -> "TASK_ALREADY_PAUSED"
                else -> null
            }
            is DecisionAction.Wait -> if (action.trigger == WaitTrigger.TASK_TERMINAL && !active)
                "WAIT_REQUIRES_ACTIVE_TASK" else null
        }
    }

    fun orderProblem(order: OperationOrder, policy: ContextPolicy, dimensionId: String): String? = when {
        order.type !in policy.operations -> "OPERATION_NOT_ALLOWED"
        order.dimensionId != dimensionId -> "DIMENSION_MISMATCH"
        order.budget.ticks > policy.maxTaskTicks -> "TASK_TIME_POLICY_EXCEEDED"
        order.budget.attempts > policy.maxTaskAttempts -> "TASK_ATTEMPT_POLICY_EXCEEDED"
        OperationSupervisionApi.validateOrder(order).status != NpcActionStatus.SUCCEEDED -> "INVALID_OPERATION"
        else -> null
    }

    fun changeProblem(change: OperationChange, policy: ContextPolicy, dimensionId: String,
                      operationId: String, durationTicks: Int): String? {
        val kind = changeKind(change)
        if (kind !in policy.changes) return "CHANGE_NOT_ALLOWED"
        val descriptor = OperationCatalogApi.snapshot().operations.firstOrNull { it.type.operationId == operationId }
            ?: return "UNKNOWN_CURRENT_OPERATION"
        if (kind !in descriptor.amendments) return "CHANGE_UNSUPPORTED_BY_CURRENT_OPERATION"
        return when (change) {
            is OperationChange.Replace -> orderProblem(change.order, policy, dimensionId)
            is OperationChange.ExtendTime -> when {
                change.ticks > policy.maxExtensionTicks -> "EXTENSION_POLICY_EXCEEDED"
                durationTicks.toLong() + change.ticks > policy.maxTaskTicks -> "TASK_TIME_POLICY_EXCEEDED"
                else -> null
            }
            else -> null
        }
    }

    fun changeKind(change: OperationChange): String = when (change) {
        is OperationChange.Quantity -> "QUANTITY"
        is OperationChange.Recipients -> "RECIPIENTS"
        is OperationChange.Sources -> "SOURCES"
        is OperationChange.ExtendTime -> "EXTEND_TIME"
        is OperationChange.Replace -> "REPLACE"
        is OperationChange.Tactics -> "TACTICS"
        is OperationChange.Reaction -> "REACTION"
        is OperationChange.Logistics -> "LOGISTICS"
    }

    private val terminalStates = setOf(OperationTaskState.COMPLETED, OperationTaskState.CANCELLED, OperationTaskState.FAILED)
}
