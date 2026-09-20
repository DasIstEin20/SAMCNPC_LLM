package io.samcnpc.llm.decision

import io.samcnpc.behavior.api.*
import java.util.UUID

internal enum class DecisionOutcomeState { NO_EFFECT, APPLIED, PENDING, REJECTED, UNCERTAIN }

/** Stable codes and actual gateway facts only. Model summary never becomes an execution receipt. */
internal data class DecisionOutcome(
    val state: DecisionOutcomeState,
    val code: String,
    val taskId: UUID? = null,
    val definitionRevision: Int? = null,
    val controlRevision: Long? = null,
    val amendment: OperationAmendmentOutcome? = null,
) {
    companion object {
        fun rejected(code: String) = DecisionOutcome(DecisionOutcomeState.REJECTED, code)
        fun fromReply(reply: OperationReply, amendmentExpected: Boolean): DecisionOutcome {
            val task = reply.observation?.task
            val receipt = reply.amendment
            val state = when {
                receipt != null -> when (receipt.outcome) {
                    OperationAmendmentOutcome.APPLIED -> DecisionOutcomeState.APPLIED
                    OperationAmendmentOutcome.PENDING -> DecisionOutcomeState.PENDING
                    OperationAmendmentOutcome.REJECTED, OperationAmendmentOutcome.EXPIRED -> DecisionOutcomeState.REJECTED
                }
                reply.result.status != io.samcnpc.core.api.NpcActionStatus.SUCCEEDED -> DecisionOutcomeState.REJECTED
                amendmentExpected -> DecisionOutcomeState.UNCERTAIN
                else -> DecisionOutcomeState.APPLIED
            }
            return DecisionOutcome(state, if (receipt != null) "AMENDMENT_" + receipt.outcome.name
                else if (amendmentExpected && state == DecisionOutcomeState.UNCERTAIN) "AMENDMENT_RECEIPT_MISSING"
                else "BEHAVIOR_" + reply.result.code.name, task?.taskId, task?.definitionRevision,
                task?.controlRevision, receipt?.outcome)
        }
    }
}
