package io.samcnpc.llm.scheduling

import java.util.UUID

/** Trusted upper bounds from a verified profile, not token counts inferred from string length. */
internal data class InferenceCharge(val inputTokens: Long, val outputTokens: Long, val costMicros: Long) {
    init { require(inputTokens >= 0 && outputTokens >= 0 && costMicros >= 0) }
}
internal data class InferenceBudgetLimits(val attempts: Int = 24, val inputTokens: Long = 196608,
                                         val outputTokens: Long = 24576, val costMicros: Long = 0) {
    init { require(attempts in 1..24 && inputTokens >= 0 && outputTokens >= 0 && costMicros >= 0) }
}
internal data class InferenceBudgetView(val settledAttempts: Int, val chargedInputTokens: Long,
                                       val chargedOutputTokens: Long, val chargedCostMicros: Long,
                                       val inFlight: UUID?)

/**
 * One goal's bounded reservations. Resources are charged before work and never refunded implicitly.
 * A held attempt remains in ContextGoal.remainingCalls until admission is handled, then settles.
 * Thus the last authorized response can still be admitted while availableCalls is already zero.
 */
internal class InferenceBudget(val limits: InferenceBudgetLimits = InferenceBudgetLimits(),
                               initial: InferenceBudgetView = InferenceBudgetView(0, 0, 0, 0, null)) {
    private var state = initial
    init {
        require(initial.settledAttempts in 0..limits.attempts)
        require(initial.inFlight == null || initial.settledAttempts < limits.attempts)
        require(initial.chargedInputTokens in 0..limits.inputTokens &&
            initial.chargedOutputTokens in 0..limits.outputTokens && initial.chargedCostMicros in 0..limits.costMicros)
    }
    val contextRemainingCalls: Int get() = limits.attempts - state.settledAttempts
    val availableCalls: Int get() = contextRemainingCalls - if (state.inFlight == null) 0 else 1
    fun snapshot(): InferenceBudgetView = state

    /** Preflight never changes the goal or charges unrelated server resources. */
    fun problem(charge: InferenceCharge): String? {
        if (state.inFlight != null) return "GOAL_REQUEST_IN_FLIGHT"
        if (availableCalls == 0) return "GOAL_CALL_BUDGET_EXHAUSTED"
        if (charge.inputTokens > limits.inputTokens - state.chargedInputTokens) return "GOAL_INPUT_BUDGET_EXHAUSTED"
        if (charge.outputTokens > limits.outputTokens - state.chargedOutputTokens) return "GOAL_OUTPUT_BUDGET_EXHAUSTED"
        if (charge.costMicros > limits.costMicros - state.chargedCostMicros) return "GOAL_COST_BUDGET_EXHAUSTED"
        return null
    }

    /** null means reserved; stable rejection codes never modify the previous reservation. */
    fun reserve(requestId: UUID, charge: InferenceCharge): String? {
        problem(charge)?.let { return it }
        state = state.copy(chargedInputTokens = state.chargedInputTokens + charge.inputTokens,
            chargedOutputTokens = state.chargedOutputTokens + charge.outputTokens,
            chargedCostMicros = state.chargedCostMicros + charge.costMicros, inFlight = requestId)
        return null
    }

    /** Call after handling admission/failure; cancellation and unknown provider outcomes still count. */
    fun settle(requestId: UUID): Boolean {
        if (state.inFlight != requestId) return false
        state = state.copy(settledAttempts = state.settledAttempts + 1, inFlight = null)
        return true
    }
}
