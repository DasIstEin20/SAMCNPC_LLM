package io.samcnpc.llm.scheduling

import io.samcnpc.behavior.api.OperationTaskState
import io.samcnpc.llm.context.LlmMode
import io.samcnpc.llm.context.NpcContextEncoder
import io.samcnpc.llm.decision.DecisionPrompt
import io.samcnpc.llm.decision.DecisionSchema
import io.samcnpc.llm.mission.*

/** Shared detached preparation for inference and the explicit diagnostic projection. No provider access. */
internal object InferenceRequestPreparation {
    fun prepare(input: InferenceInput, levels: IntRange = 0..NpcContextEncoder.MAX_DETAIL_LEVEL): RequestPreparation {
        val mission = input.captured.goal.mission
        if (mission != null && input.settings.responseFormat == io.samcnpc.llm.config.ResponseFormat.SAM_EXPRESSION_V1)
            return RequestPreparation.Rejected("MISSION_REQUIRES_JSON_PROTOCOL")
        if (mission != null && mission.stage != MissionStage.OPERATION) {
            val base = if (mission.stage == MissionStage.REQUIREMENTS) MissionProtocol.requirementsPrompt else MissionProtocol.planPrompt
            val prompt = base + feedback(input)
            return WholeRequestBudget.prepare(input.requestId, prompt, MissionProtocol.schema(input.captured), input.settings,
                input.profile, input.allocation, levels, NpcContextEncoder.stateByteLimit(input.captured)) {
                MissionProjection.preparation(input.captured) }
        }
        val basePrompt = if (mission != null) MissionProtocol.operationPrompt
        else if (input.settings.responseFormat == io.samcnpc.llm.config.ResponseFormat.SAM_EXPRESSION_V1)
            io.samcnpc.llm.expression.SamExpressionPrompt.text else DecisionPrompt.text
        val prompt = basePrompt + feedback(input)
        val taskState = input.captured.inspection.operation.task?.state
        val activeTask = taskState != null && taskState !in setOf(
            OperationTaskState.COMPLETED, OperationTaskState.CANCELLED, OperationTaskState.FAILED)
        val schema = DecisionSchema.forContext(input.captured.binding.contextId, input.captured.policy,
            planner = input.captured.goal.mode == LlmMode.PLANNER && mission == null, hasActiveTask = activeTask,
            plannerInventory = input.captured.inspection.body,
            remainingPlanSteps = 8 - input.captured.goal.planStepsCompleted)
        return WholeRequestBudget.prepare(input.requestId, prompt, schema, input.settings,
            input.profile, input.allocation, levels, NpcContextEncoder.stateByteLimit(input.captured)) {
            level -> NpcContextEncoder.encodeProjection(input.captured, level) }
    }
    private fun feedback(input: InferenceInput): String = if (input.feedbackCode == null) "" else
        "\nThe previous candidate was rejected with code " + input.feedbackCode +
            ". Produce a corrected decision using the current STATE and output contract."
}
