package io.samcnpc.llm.scheduling

import io.samcnpc.behavior.api.OperationTaskState
import io.samcnpc.llm.context.LlmMode
import io.samcnpc.llm.context.NpcContextEncoder
import io.samcnpc.llm.decision.DecisionPrompt
import io.samcnpc.llm.decision.DecisionSchema

/** Shared detached preparation for inference and the explicit diagnostic projection. No provider access. */
internal object InferenceRequestPreparation {
    fun prepare(input: InferenceInput, levels: IntRange = 0..NpcContextEncoder.MAX_DETAIL_LEVEL): RequestPreparation {
        val basePrompt = if (input.settings.responseFormat == io.samcnpc.llm.config.ResponseFormat.SAM_EXPRESSION_V1)
            io.samcnpc.llm.expression.SamExpressionPrompt.text else DecisionPrompt.text
        val prompt = basePrompt + if (input.feedbackCode == null) "" else
            "\nThe previous candidate was rejected with code " + input.feedbackCode +
                ". Produce a corrected decision using the current STATE and output contract."
        val taskState = input.captured.inspection.operation.task?.state
        val activeTask = taskState != null && taskState !in setOf(
            OperationTaskState.COMPLETED, OperationTaskState.CANCELLED, OperationTaskState.FAILED)
        val schema = DecisionSchema.forContext(input.captured.binding.contextId, input.captured.policy,
            planner = input.captured.goal.mode == LlmMode.PLANNER, hasActiveTask = activeTask)
        return WholeRequestBudget.prepare(input.requestId, prompt, schema, input.settings,
            input.profile, input.allocation, levels) { level -> NpcContextEncoder.encodeProjection(input.captured, level) }
    }
}
