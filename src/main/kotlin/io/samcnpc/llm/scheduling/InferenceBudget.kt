package io.samcnpc.llm.scheduling

import java.util.UUID

internal enum class InferenceQuotaMode { LIMITED, UNLIMITED }

/** Trusted upper bounds from a verified profile, not token counts inferred from string length. */
internal data class InferenceCharge(val inputTokens: Long, val outputTokens: Long, val costMicros: Long) {
    init { require(inputTokens >= 0 && outputTokens >= 0 && costMicros >= 0) }
}
internal data class InferenceBudgetLimits(val attempts: Int = 24, val inputTokens: Long = 196608,
                                         val outputTokens: Long = 24576, val costMicros: Long = 0,
                                         val quotaMode: InferenceQuotaMode = InferenceQuotaMode.LIMITED) {
    init { require(attempts in 1..24 && inputTokens >= 0 && outputTokens >= 0 && costMicros >= 0) }
}
internal data class InferenceBudgetView(val settledAttempts: Int, val chargedInputTokens: Long,
                                       val chargedOutputTokens: Long, val chargedCostMicros: Long,
                                       val inFlight: UUID?)

/**
 * One goal's bounded reservations. Only a proven-unsent in-memory reservation can be released.
 * A held attempt remains in ContextGoal.remainingCalls until admission is handled, then settles.
 * Thus the last authorized response can still be admitted while availableCalls is already zero.
 */
internal class InferenceBudget(val limits: InferenceBudgetLimits = InferenceBudgetLimits(),
                               initial: InferenceBudgetView = InferenceBudgetView(0, 0, 0, 0, null)) {
    private var state = initial
    private var refundableCharge: InferenceCharge? = null
    init {
        require(initial.settledAttempts >= 0 && (initial.inFlight == null || initial.settledAttempts < Int.MAX_VALUE))
        require(initial.chargedInputTokens >= 0 && initial.chargedOutputTokens >= 0 && initial.chargedCostMicros >= 0)
        if (limits.quotaMode == InferenceQuotaMode.LIMITED) {
            require(initial.settledAttempts <= limits.attempts)
            require(initial.inFlight == null || initial.settledAttempts < limits.attempts)
            require(initial.chargedInputTokens <= limits.inputTokens &&
                initial.chargedOutputTokens <= limits.outputTokens && initial.chargedCostMicros <= limits.costMicros)
        }
    }
    val contextRemainingCalls: Int? get() = if (limits.quotaMode == InferenceQuotaMode.UNLIMITED) null else limits.attempts - state.settledAttempts
    val availableCalls: Int? get() = contextRemainingCalls?.minus(if (state.inFlight == null) 0 else 1)
    fun snapshot(): InferenceBudgetView = state

    /** Preflight never changes the goal or charges unrelated server resources. */
    fun problem(charge: InferenceCharge): String? {
        if (state.inFlight != null) return "GOAL_REQUEST_IN_FLIGHT"
        if (availableCalls == 0) return "GOAL_CALL_BUDGET_EXHAUSTED"
        if (limits.quotaMode == InferenceQuotaMode.UNLIMITED) {
            if (state.settledAttempts == Int.MAX_VALUE || charge.inputTokens > Long.MAX_VALUE - state.chargedInputTokens ||
                charge.outputTokens > Long.MAX_VALUE - state.chargedOutputTokens || charge.costMicros > Long.MAX_VALUE - state.chargedCostMicros)
                return "GOAL_ACCOUNTING_EXHAUSTED"
            return null
        }
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
        refundableCharge = charge
        return null
    }

    /** A restored in-flight reservation has no proof of non-submission and cannot use this path. */
    fun releaseUnsent(requestId: UUID): Boolean {
        if (state.inFlight != requestId) return false
        val charge = refundableCharge ?: return false
        state = state.copy(chargedInputTokens = state.chargedInputTokens - charge.inputTokens,
            chargedOutputTokens = state.chargedOutputTokens - charge.outputTokens,
            chargedCostMicros = state.chargedCostMicros - charge.costMicros, inFlight = null)
        refundableCharge = null
        return true
    }

    /** Call after handling admission/failure; cancellation and unknown provider outcomes still count. */
    fun settle(requestId: UUID): Boolean {
        if (state.inFlight != requestId) return false
        state = state.copy(settledAttempts = state.settledAttempts + 1, inFlight = null)
        refundableCharge = null
        return true
    }
}
