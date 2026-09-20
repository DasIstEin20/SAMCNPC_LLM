package io.samcnpc.llm.planning

import io.samcnpc.llm.context.LlmMode
import io.samcnpc.llm.decision.*
import io.samcnpc.llm.goal.*

/** Advances only from a validated receipt and the exact observed terminal task. */
internal object PlannerOutcomes {
    fun admitted(record: GoalRecord, decision: LlmDecision, outcome: DecisionOutcome): GoalRecord {
        val prepared = if (decision.action is DecisionAction.Assign && outcome.state == DecisionOutcomeState.APPLIED)
            record.copy(memory = record.memory.withPlan(checkNotNull(decision.plan).steps))
        else record
        val next = TranslatorOutcomes.admitted(prepared, decision, outcome)
        if (next.phase in setOf(GoalPhase.EXECUTING, GoalPhase.REVIEW_REQUIRED, GoalPhase.ASK_USER)) return next
        return if (outcome.state == DecisionOutcomeState.REJECTED)
            ask(next.copy(task = null), "PLAN_STEP_REJECTED",
                "The next operation was rejected (" + outcome.code + "). Please revise the goal or its resources.")
        else next.copy(manualHold = true)
    }

    fun terminal(record: GoalRecord): GoalRecord {
        require(record.mode == LlmMode.PLANNER && record.memory.plan.isNotEmpty())
        require(record.phase in setOf(GoalPhase.COMPLETED, GoalPhase.FAILED))
        if (record.phase == GoalPhase.FAILED)
            return ask(record.copy(task = null), "PLAN_STEP_FAILED",
                "The current plan step failed. Please revise the goal or explain how to proceed.")
        val remaining = record.memory.plan.drop(1)
        val next = record.copy(phase = GoalPhase.WAITING, task = null, question = null,
            code = "PLAN_NEXT_STEP", memory = record.memory.withPlan(remaining),
            planStepsCompleted = record.planStepsCompleted + 1)
        return if (remaining.isEmpty()) ask(next, "PLAN_CONFIRMATION_REQUIRED",
            "The recorded steps finished. Confirm the goal with /samcnpc llm complete " +
                record.npcUuid + " or answer with a revised goal.")
        else next
    }

    fun boundary(record: GoalRecord?): Boolean = record != null && record.mode == LlmMode.PLANNER &&
        record.phase == GoalPhase.WAITING && !record.manualHold && record.code == "PLAN_NEXT_STEP" &&
        record.memory.plan.isNotEmpty()

    private fun ask(record: GoalRecord, code: String, question: String): GoalRecord =
        record.copy(phase = GoalPhase.ASK_USER, code = code, question = question, manualHold = false)
}
