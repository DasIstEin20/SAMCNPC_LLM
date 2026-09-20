package io.samcnpc.llm.decision

import io.samcnpc.behavior.api.OperationChange
import io.samcnpc.behavior.api.OperationControl
import io.samcnpc.behavior.api.OperationOrder
import java.util.UUID

internal enum class DecisionKind { CONTINUE, ASSIGN, AMEND, PAUSE, RESUME, CANCEL, WAIT, ASK_USER }
internal enum class WaitTrigger { TASK_TERMINAL, USER_UPDATE, DEADLINE }

internal sealed interface DecisionAction {
    data object Continue : DecisionAction
    data class Assign(val order: OperationOrder) : DecisionAction
    data class Amend(val change: OperationChange) : DecisionAction
    data class Control(val control: OperationControl) : DecisionAction
    data class Wait(val trigger: WaitTrigger, val ticks: Int?) : DecisionAction
    data class AskUser(val question: String) : DecisionAction
}

/** Parsed candidate only. It is not an admission receipt or evidence of any world effect. */
internal data class LlmDecision(val contextId: UUID, val kind: DecisionKind,
                                val action: DecisionAction, val summary: String,
                                val schemaVersion: Int = 1,
                                val plan: io.samcnpc.llm.planning.PlanProposal? = null) {
    init {
        val expected = when (action) {
            DecisionAction.Continue -> DecisionKind.CONTINUE
            is DecisionAction.Assign -> DecisionKind.ASSIGN
            is DecisionAction.Amend -> DecisionKind.AMEND
            is DecisionAction.Control -> DecisionKind.valueOf(action.control.name)
            is DecisionAction.Wait -> DecisionKind.WAIT
            is DecisionAction.AskUser -> DecisionKind.ASK_USER
        }
        require(kind == expected && summary.length <= 256)
        require(schemaVersion in 1..2 && (schemaVersion == 2 || plan == null))
        require(plan == null || action is DecisionAction.Assign)
    }
}
internal sealed interface DecisionDecodeResult {
    data class Accepted(val value: LlmDecision) : DecisionDecodeResult
    data class Rejected(val code: String) : DecisionDecodeResult
}
