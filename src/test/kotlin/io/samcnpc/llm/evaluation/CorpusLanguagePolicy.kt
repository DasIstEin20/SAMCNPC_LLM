package io.samcnpc.llm.evaluation

import com.google.gson.JsonObject
import io.samcnpc.behavior.api.OperationType
import io.samcnpc.llm.context.ContextPolicy
import io.samcnpc.llm.decision.*
import java.util.UUID

/** Detached subset only: never claims freshness, authority recheck or world admission for expired snapshots. */
internal object CorpusLanguagePolicy {
    fun problem(decision: LlmDecision, state: JsonObject, schema: JsonObject): String? {
        if (decision.contextId != UUID.fromString(state["contextId"].asString)) return "DECISION_CONTEXT_MISMATCH"
        val allowed = schema.getAsJsonObject("properties").getAsJsonObject("decision").getAsJsonArray("enum").map { it.asString }
        if (decision.kind.name !in allowed) return "DECISION_NOT_ALLOWED"
        check(state.getAsJsonObject("goal")["intentAuthority"].asString == "FREE_TEXT_UNCONTRACTED")
        val active = state.getAsJsonObject("task")["state"].asString !in setOf("NO_TASK", "COMPLETED", "CANCELLED", "FAILED")
        return when (val action = decision.action) {
            is DecisionAction.Assign -> {
                if (active) "ACTIVE_TASK_CANNOT_BE_REPLACED_BY_ASSIGN"
                else {
                    val ids = state.getAsJsonObject("capabilities").getAsJsonArray("operations").map { it.asJsonObject["id"].asString }
                    val limits = state.getAsJsonObject("policy")
                    val policy = ContextPolicy(limits["revision"].asLong, OperationType.entries.filter { it.operationId in ids }.toSet(),
                        emptySet(), emptySet(), limits["maxTaskTicks"].asInt, limits["maxTaskAttempts"].asInt, 0)
                    DecisionPolicy.orderProblem(action.order, policy, state.getAsJsonObject("identity")["dimension"].asString)
                }
            }
            is DecisionAction.AskUser -> null
            DecisionAction.Continue -> if (!active && state.getAsJsonObject("goal")["mode"].asString == "TRANSLATOR") "CONTINUE_REQUIRES_ACTIVE_TASK" else null
            is DecisionAction.Wait -> if (action.trigger == WaitTrigger.TASK_TERMINAL && !active) "WAIT_REQUIRES_ACTIVE_TASK" else null
            else -> "CONTROL_OR_CHANGE_NOT_ALLOWED_IN_FROZEN_CORPUS"
        }
    }
}
