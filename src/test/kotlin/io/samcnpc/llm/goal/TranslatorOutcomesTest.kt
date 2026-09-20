package io.samcnpc.llm.goal

import io.samcnpc.behavior.api.OperationTaskState
import io.samcnpc.llm.decision.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class TranslatorOutcomesTest {
    private fun record() = GoalRecord(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, "Bring supplies",
        phase = GoalPhase.WAITING)
    private fun decision(action: DecisionAction, kind: DecisionKind) =
        LlmDecision(UUID.randomUUID(), kind, action, "Everything is already delivered")

    @Test fun modelSummaryAndContinueNeverClaimCompletion() {
        val next = TranslatorOutcomes.admitted(record(), decision(DecisionAction.Continue, DecisionKind.CONTINUE),
            DecisionOutcome(DecisionOutcomeState.NO_EFFECT, "CONTINUE"))
        assertEquals(GoalPhase.WAITING, next.phase)
        assertEquals("CONTINUE_NOT_COMPLETION", next.code)
        assertNull(next.task)
    }

    @Test fun oneQuestionIsBoundedAndInvalidDisplayTextIsRejectedWithoutThrowing() {
        val source = record()
        val good = TranslatorOutcomes.admitted(source, decision(DecisionAction.AskUser("How many?"), DecisionKind.ASK_USER),
            DecisionOutcome(DecisionOutcomeState.NO_EFFECT, "ASK_USER"))
        assertEquals(GoalPhase.ASK_USER, good.phase); assertEquals("How many?", good.question)
        val bad = TranslatorOutcomes.admitted(source, decision(DecisionAction.AskUser("secret\u0000text"), DecisionKind.ASK_USER),
            DecisionOutcome(DecisionOutcomeState.NO_EFFECT, "ASK_USER"))
        assertEquals(GoalPhase.WAITING, bad.phase); assertNull(bad.question)
        assertEquals("INVALID_QUESTION_TEXT", bad.code)
    }

    @Test fun uncertainAdmissionHoldsAndNeverConvertsSummaryIntoSuccess() {
        val next = TranslatorOutcomes.admitted(record(), decision(DecisionAction.Continue, DecisionKind.CONTINUE),
            DecisionOutcome(DecisionOutcomeState.UNCERTAIN, "ADMISSION_REPLY_UNAVAILABLE"))
        assertEquals(GoalPhase.REVIEW_REQUIRED, next.phase); assertTrue(next.manualHold)
    }

    @Test fun naturalTerminalRevisionIsAcceptedButPauseResumeCycleCannotMasqueradeAsCompletion() {
        for (state in listOf(OperationTaskState.COMPLETED, OperationTaskState.FAILED, OperationTaskState.CANCELLED)) {
            assertTrue(TranslatorOutcomes.controlMatches(7, 8, state))
            assertFalse(TranslatorOutcomes.controlMatches(7, 7, state))
            assertFalse(TranslatorOutcomes.controlMatches(7, 10, state))
            assertTrue(TranslatorOutcomes.controlMatches(Long.MAX_VALUE, Long.MAX_VALUE, state))
        }
        assertTrue(TranslatorOutcomes.controlMatches(7, 7, OperationTaskState.RUNNING))
        assertTrue(TranslatorOutcomes.controlMatches(7, 7, OperationTaskState.WAITING))
        assertFalse(TranslatorOutcomes.controlMatches(7, 8, OperationTaskState.PAUSED))
        assertFalse(TranslatorOutcomes.controlMatches(7, 9, OperationTaskState.RUNNING))
    }
}
