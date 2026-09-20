package io.samcnpc.llm.decision

import io.samcnpc.behavior.api.*
import io.samcnpc.core.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class DecisionOutcomeTest {
    @Test fun pendingIsNotAppliedAndReceiptOutcomeOverridesTransportSuccess() {
        val request = UUID.randomUUID(); val task = UUID.randomUUID()
        for ((receipt, expected) in listOf(OperationAmendmentOutcome.PENDING to DecisionOutcomeState.PENDING,
            OperationAmendmentOutcome.APPLIED to DecisionOutcomeState.APPLIED,
            OperationAmendmentOutcome.REJECTED to DecisionOutcomeState.REJECTED,
            OperationAmendmentOutcome.EXPIRED to DecisionOutcomeState.REJECTED)) {
            val reply = OperationReply(NpcActionResult.succeeded("historical receipt found"), null,
                OperationAmendmentSnapshot(request, task, 1, receipt, "private raw detail"))
            val result = DecisionOutcome.fromReply(reply, true)
            assertEquals(expected, result.state); assertEquals(receipt, result.amendment)
            assertFalse(result.toString().contains("private raw detail"))
        }
    }

    @Test fun successfulAmendmentWithoutExactReceiptRemainsUncertain() {
        val reply = OperationReply(NpcActionResult.succeeded("queued maybe"), null)
        assertEquals(DecisionOutcomeState.UNCERTAIN, DecisionOutcome.fromReply(reply, true).state)
        assertEquals(DecisionOutcomeState.APPLIED, DecisionOutcome.fromReply(reply, false).state)
        assertEquals(DecisionOutcomeState.REJECTED, DecisionOutcome.fromReply(OperationReply(
            NpcActionResult.rejected("conflict", NpcActionCode.CONFLICT), null), true).state)
    }

    @Test fun typedKindCannotDisagreeWithAction() {
        assertThrows(IllegalArgumentException::class.java) {
            LlmDecision(UUID.randomUUID(), DecisionKind.CONTINUE, DecisionAction.Control(OperationControl.CANCEL), "")
        }
    }
}
