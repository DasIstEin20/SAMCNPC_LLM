package io.samcnpc.llm.goal

import io.samcnpc.behavior.api.OperationObservation
import io.samcnpc.behavior.api.OperationTaskState
import io.samcnpc.llm.decision.*

/** Only gateway receipts and observed task state establish execution or completion. */
internal object TranslatorOutcomes {
    fun admitted(record: GoalRecord, decision: LlmDecision, outcome: DecisionOutcome): GoalRecord {
        val cleared = record.copy(phase = GoalPhase.WAITING, code = outcome.code, question = null)
        if (outcome.state in setOf(DecisionOutcomeState.PENDING, DecisionOutcomeState.UNCERTAIN))
            return cleared.copy(phase = GoalPhase.REVIEW_REQUIRED, manualHold = true)
        if (outcome.state == DecisionOutcomeState.REJECTED) return cleared
        return when (val action = decision.action) {
            is DecisionAction.Assign -> {
                if (outcome.state != DecisionOutcomeState.APPLIED || outcome.taskId == null ||
                    outcome.definitionRevision == null || outcome.controlRevision == null)
                    cleared.copy(phase = GoalPhase.REVIEW_REQUIRED, code = "ASSIGNMENT_RECEIPT_INCOMPLETE", manualHold = true)
                else cleared.copy(phase = GoalPhase.EXECUTING, code = "TASK_ASSIGNED",
                    task = GoalTask(outcome.taskId, outcome.definitionRevision, outcome.controlRevision, action.order.type.operationId))
            }
            is DecisionAction.AskUser -> if (GoalRecord.validText(action.question, 256))
                cleared.copy(phase = GoalPhase.ASK_USER, code = "ASK_USER", question = action.question)
            else cleared.copy(code = "INVALID_QUESTION_TEXT")
            DecisionAction.Continue -> cleared.copy(code = "CONTINUE_NOT_COMPLETION")
            is DecisionAction.Wait -> cleared.copy(code = "WAIT_FOR_USER")
            else -> cleared.copy(phase = GoalPhase.REVIEW_REQUIRED, code = "TRANSLATOR_ACTION_UNSUPPORTED", manualHold = true)
        }
    }

    /** Behavior increments once on terminal finish as well as on explicit pause/resume. */
    internal fun controlMatches(expected: Long, current: Long, state: OperationTaskState): Boolean {
        val terminal = state in setOf(OperationTaskState.COMPLETED, OperationTaskState.FAILED, OperationTaskState.CANCELLED)
        val expectedCurrent = if (terminal && expected < Long.MAX_VALUE) expected + 1 else expected
        return current == expectedCurrent
    }

    fun observed(record: GoalRecord, view: OperationObservation): GoalRecord {
        val expected = record.task ?: return record.copy(phase = GoalPhase.REVIEW_REQUIRED,
            code = "TASK_BINDING_MISSING", manualHold = true, question = null)
        val task = view.task
        if (task == null || task.taskId != expected.id)
            return record.copy(phase = GoalPhase.REVIEW_REQUIRED, code = "TASK_REPLACED_OR_MISSING", manualHold = true, question = null)
        if (task.definitionRevision != expected.definitionRevision || !controlMatches(expected.controlRevision, task.controlRevision, task.state) ||
            task.state == OperationTaskState.PAUSED)
            return record.copy(phase = GoalPhase.WAITING, code = "MANUAL_TASK_CHANGE", manualHold = true, question = null)
        return when (task.state) {
            OperationTaskState.COMPLETED -> record.copy(phase = GoalPhase.COMPLETED, code = "TASK_COMPLETED", question = null)
            OperationTaskState.FAILED -> record.copy(phase = GoalPhase.FAILED, code = "TASK_FAILED", question = null)
            OperationTaskState.CANCELLED -> record.copy(phase = GoalPhase.STOPPED, code = "TASK_CANCELLED", manualHold = true, question = null)
            else -> record
        }
    }
}
