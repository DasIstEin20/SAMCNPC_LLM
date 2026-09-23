package io.samcnpc.llm.goal

import io.samcnpc.behavior.api.OperationType
import io.samcnpc.llm.context.ContextPolicy
import io.samcnpc.llm.context.LlmMode
import io.samcnpc.llm.planning.PlannerPolicy
import io.samcnpc.llm.supervision.StockDecisionPolicy

internal object GoalPolicies {
    private val translator = ContextPolicy(1, OperationType.entries.toSet(), emptySet(), emptySet(), 72000, 16, 0)
    private val supervisor = ContextPolicy(2, StockDecisionPolicy.operations, emptySet(), emptySet(), 72000, 16, 0)
    private val planner = ContextPolicy(3, PlannerPolicy.operations, emptySet(), emptySet(), 72000, 16, 0)
    fun forRecord(record: GoalRecord): ContextPolicy {
        val base = when (record.mode) {
            LlmMode.TRANSLATOR -> translator
            LlmMode.SUPERVISOR -> supervisor
            LlmMode.PLANNER -> planner
        }
        val constraints = record.constraints ?: return base
        return ContextPolicy(base.revision, base.operations.intersect(constraints.operations),
            base.changes.intersect(setOf("EXTEND_TIME")), base.controls, base.maxTaskTicks,
            base.maxTaskAttempts, base.maxExtensionTicks)
    }
}
