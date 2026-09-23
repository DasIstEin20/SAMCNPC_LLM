package io.samcnpc.llm.goal

import io.samcnpc.llm.scheduling.*
import io.samcnpc.llm.context.LlmMode
import io.samcnpc.llm.supervision.StockSupervision
import java.util.UUID

internal enum class GoalPhase {
    QUEUED, INFERENCING, ADMITTING, EXECUTING, ASK_USER, WAITING,
    COMPLETED, FAILED, STOPPED, REVIEW_REQUIRED,
}

/** Actual Behavior identity/revisions, never the model's claimed result. */
internal data class GoalTask(val id: UUID, val definitionRevision: Int, val controlRevision: Long,
                             val operationId: String) {
    init {
        require(definitionRevision >= 0 && controlRevision >= 0)
        require(operationId.length in 1..128 && operationId.none(Char::isISOControl))
    }
}

/** One bounded goal, not a chat transcript or cached world state. */
internal data class GoalRecord(
    val npcUuid: UUID,
    val actorUuid: UUID,
    val goalId: UUID,
    val revision: Long,
    val text: String,
    val answer: String? = null,
    val phase: GoalPhase = GoalPhase.QUEUED,
    val code: String = "USER_GOAL",
    val manualHold: Boolean = false,
    val question: String? = null,
    val task: GoalTask? = null,
    val contextId: UUID? = null,
    val limits: InferenceBudgetLimits = InferenceBudgetLimits(),
    val budget: InferenceBudgetView = InferenceBudgetView(0, 0, 0, 0, null),
    val mode: LlmMode = LlmMode.TRANSLATOR,
    val supervision: StockSupervision? = null,
    val memory: GoalMemory = GoalMemory(),
    val planStepsCompleted: Int = 0,
    val constraints: io.samcnpc.llm.intent.GoalConstraints? = null,
    val intentReservation: io.samcnpc.llm.intent.GoalIntentReservation = io.samcnpc.llm.intent.GoalIntentReservation(),
) {
    init {
        require(constraints == null && intentReservation == io.samcnpc.llm.intent.GoalIntentReservation() ||
            constraints != null && mode != LlmMode.SUPERVISOR && intentReservation.fits(constraints))
        require(revision >= 0)
        require(planStepsCompleted in 0..8 && (mode == LlmMode.PLANNER || planStepsCompleted == 0))
        require(mode != LlmMode.PLANNER || memory.plan.size <= 8 - planStepsCompleted)
        require(mode != LlmMode.PLANNER || phase != GoalPhase.EXECUTING || memory.plan.isNotEmpty())
        require((mode == LlmMode.SUPERVISOR) == (supervision != null))
        require(mode != LlmMode.SUPERVISOR || phase != GoalPhase.EXECUTING || supervision?.pendingDecision != null)
        require(validText(text, 1024) && (answer == null || validText(answer, 512)))
        require(question == null || validText(question, 256))
        require(code.length in 1..128 && code.all { it in 'A'..'Z' || it in '0'..'9' || it == '_' })
        require((phase == GoalPhase.ASK_USER) == (question != null))
        require(phase != GoalPhase.EXECUTING || task != null)
        require(phase !in setOf(GoalPhase.INFERENCING, GoalPhase.ADMITTING) || budget.inFlight != null)
        require(phase != GoalPhase.ADMITTING || contextId != null)
        InferenceBudget(limits, budget)
    }

    fun contextText(): String = text + if (answer == null) "" else "\nLatest user clarification:\n" + answer

    /** A held request consumes its reservation once. No recovery path repeats a world mutation. */
    fun recovered(): GoalRecord {
        val ledger = InferenceBudget(limits, budget)
        budget.inFlight?.let { check(ledger.settle(it)) }
        val uncertain = phase in setOf(GoalPhase.QUEUED, GoalPhase.INFERENCING, GoalPhase.ADMITTING)
        return copy(budget = ledger.snapshot(), contextId = null,
            phase = if (uncertain) GoalPhase.REVIEW_REQUIRED else phase,
            code = if (uncertain) "RESTART_REVIEW_REQUIRED" else code,
            manualHold = manualHold || uncertain)
    }

    companion object {
        fun validText(value: String, limit: Int): Boolean = value.length in 1..limit &&
            value.isNotBlank() && value.none { it.isISOControl() && it != '\n' && it != '\t' } &&
            wellFormedUnicode(value)

        private fun wellFormedUnicode(value: String): Boolean {
            var index = 0
            while (index < value.length) {
                val current = value[index++]
                if (current.isHighSurrogate()) {
                    if (index == value.length || !value[index++].isLowSurrogate()) return false
                } else if (current.isLowSurrogate()) return false
            }
            return true
        }
    }
}
