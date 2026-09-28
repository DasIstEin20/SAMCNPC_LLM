package io.samcnpc.llm.mission

import com.google.gson.*
import io.samcnpc.llm.context.CapturedContext
import io.samcnpc.llm.context.ContextJson.obj
import io.samcnpc.llm.context.ContextJson.text
import io.samcnpc.llm.context.ContextJson.number
import io.samcnpc.llm.context.ContextJson.array
import io.samcnpc.llm.context.ContextJson.position
import io.samcnpc.llm.context.ContextEncodingResult
import io.samcnpc.llm.context.NpcLlmContext
import io.samcnpc.llm.provider.LlmJson

internal object MissionProjection {
    fun encode(captured: CapturedContext): JsonObject {
        return encode(checkNotNull(captured.goal.mission), captured.missionEvaluation)
    }

    fun encode(mission: MissionState, evaluation: MissionEvaluation?): JsonObject {
        return obj("version" to number(1), "stage" to text(mission.stage.name),
            "contract" to (mission.contract?.let(MissionCodec::encode) ?: JsonNull.INSTANCE),
            "plan" to (mission.plan?.let(MissionCodec::encode) ?: JsonNull.INSTANCE),
            "progress" to array(evaluation?.progress.orEmpty().map {
                obj("id" to text(it.id), "state" to text(it.state.name), "count" to number(it.count), "minimum" to number(it.minimum))
            }),
            "currentStep" to number(mission.plan?.let { plan -> evaluation?.currentStep(plan)?.plus(1) }),
            "semantics" to text("ITEMS_CURRENT_AUTHORIZED_STOCK; COLLECT_VISIT_FIELD_COMPLETED_MILESTONES; UNKNOWN_IS_NOT_ZERO"))
    }

    /** Extraction/coverage does not need a full body/operation schema or pretend access to chest contents. */
    fun preparation(captured: CapturedContext): ContextEncodingResult {
        val state = obj("contextId" to text(captured.binding.contextId.toString()),
            "USER_GOAL" to text(captured.goal.text), "dimension" to text(captured.inspection.physical.dimensionId),
            "npcFeetPosition" to position(captured.inspection.physical.position),
            "MISSION" to encode(captured),
            "availableOperations" to array(captured.policy.operations.sortedBy { it.operationId }.map { text(it.operationId) }),
            "constraints" to (captured.goal.constraints?.let { io.samcnpc.llm.intent.GoalConstraintCodec.encode(it) } ?: JsonNull.INSTANCE),
            "aliases" to array(captured.goal.memory.aliases.map {
                obj("name" to text(it.name), "dimension" to text(it.dimensionId), "position" to position(it.position))
            })).toString()
        return ContextEncodingResult.Encoded(NpcLlmContext(captured.binding, state, LlmJson.utf8(state).size))
    }
}
