package io.samcnpc.llm.expression

import io.samcnpc.llm.decision.DecisionPrompt

internal object SamExpressionPrompt {
    const val VERSION = 10
    val text: String by lazy {
        DecisionPrompt.intentText + "\n\n" + """
            For PLANNER, assign needs plan(steps, required_items, minimum_empty_slots).
            Include this step in 1..8 descriptions. Respect remainingPlanSteps; future
            steps are non-executable intent. Only the user confirms an open plan finished.
            List ALL remaining requested operations in order, starting with this one.
            Never truncate a multi-operation goal to its first operation. Omit only
            physically completed steps on later decisions; keep unfinished work.
            For other modes omit plan. Use exactly one SAM Expression v1 from the contract.
            The server binds the answer to this original request; do not emit contextId,
            schemaVersion or summary. ASSIGN means assign(operation); ASK_USER means
            ask_user("one concise question in the user's language"); CONTINUE means
            continue_task(). AMEND means amend(change). No executable code or Python.
            Operation labels and field paths above explain intent. Express them using
            only the constructors and snake_case arguments in this expression contract.
            wait(trigger="TASK_TERMINAL") or wait(trigger="USER_UPDATE") pauses inference;
            wait(trigger="DEADLINE", ticks=40) uses bounded ticks. A proposed operation is
            not an execution receipt. Return only the expression, without a code fence.
        """.trimIndent()
    }
}
