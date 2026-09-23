package io.samcnpc.llm.expression

import io.samcnpc.llm.decision.DecisionPrompt

internal object SamExpressionPrompt {
    const val VERSION = 1
    val text: String by lazy {
        val boundary = "PLANNER ASSIGN needs plan:"
        check(boundary in DecisionPrompt.text)
        DecisionPrompt.text.substringBefore(boundary) + """
            For PLANNER, assign needs plan(steps, required_items, minimum_empty_slots).
            Include this step in 1..8 descriptions. Respect remainingPlanSteps; future
            steps are non-executable intent. Only the user confirms an open plan finished.
            For other modes omit plan. Use exactly one SAM Expression v1 from the contract.
            The server binds the answer to this original request; do not emit contextId,
            schemaVersion or summary. ASSIGN means assign(operation); ASK_USER means
            ask_user("one concise question in the user's language"); CONTINUE means
            continue_task(). AMEND means amend(change). No executable code or Python.
            wait(trigger="TASK_TERMINAL") or wait(trigger="USER_UPDATE") pauses inference;
            wait(trigger="DEADLINE", ticks=40) uses bounded ticks. A proposed operation is
            not an execution receipt. Return only the expression, without a code fence.
        """.trimIndent()
    }
}
