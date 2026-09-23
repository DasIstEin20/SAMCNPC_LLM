package io.samcnpc.llm.planning

import io.samcnpc.behavior.api.OperationType
import io.samcnpc.llm.context.CapturedContext
import io.samcnpc.llm.context.LlmMode
import io.samcnpc.llm.decision.DecisionAction
import io.samcnpc.llm.decision.LlmDecision
import io.samcnpc.llm.goal.GoalMemory

internal object PlannerPolicy {
    val operations = setOf(OperationType.FOOD, OperationType.LUMBERJACK, OperationType.INVENTORY,
        OperationType.NAVIGATE, OperationType.TRANSPORT, OperationType.DELIVER,
        OperationType.MINING, OperationType.FARM, OperationType.FIELD_PREPARATION)

    fun problem(decision: LlmDecision, captured: CapturedContext): String? {
        val goal = captured.goal
        if (goal.mode != LlmMode.PLANNER)
            return if (decision.schemaVersion != 1 || decision.plan != null) "PLANNER_ENVELOPE_NOT_ALLOWED" else null
        if (decision.schemaVersion != 2) return "PLANNER_ENVELOPE_REQUIRED"
        if (decision.action !is DecisionAction.Assign)
            return if (decision.plan == null) null else "PLAN_REQUIRES_ASSIGN"
        val plan = decision.plan ?: return "PLAN_REQUIRED"
        if (plan.steps.size > 8 - goal.planStepsCompleted) return "PLAN_STEP_BUDGET_EXCEEDED"
        try {
            GoalMemory(plan.steps, goal.memory.aliases, goal.memory.confirmedResults)
        } catch (_: IllegalArgumentException) { return "PLAN_MEMORY_LIMIT" }
        return plan.preconditionProblem(captured.inspection.body)
    }
}
