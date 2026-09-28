package io.samcnpc.llm.mission

import io.samcnpc.behavior.api.OperationInspectionApi
import io.samcnpc.core.api.NpcActionStatus
import io.samcnpc.llm.context.*
import io.samcnpc.llm.decision.DecisionFreshness
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer

/** Planning stages only persist intent. They still cannot accept stale or unauthorized replies. */
internal object MissionAdmission {
    fun problem(server: MinecraftServer, actor: ServerPlayer, captured: CapturedContext, goal: ContextGoal,
                policy: ContextPolicy, reply: MissionReply, manualHold: Boolean): String? {
        check(server.isSameThread)
        val read = OperationInspectionApi.inspect(server, actor, captured.binding.npcUuid)
        val current = read.inspection
        if (read.result.status != NpcActionStatus.SUCCEEDED || current == null) return "MISSION_OBSERVATION_" + read.result.code.name
        DecisionFreshness.problem(captured, current, actor.uuid, goal, policy, manualHold)?.let { return it }
        val mission = goal.mission ?: return "MISSION_NOT_ENABLED"
        return when (reply) {
            is MissionReply.Question -> null
            is MissionReply.Plan -> if (mission.stage != MissionStage.PLAN) "MISSION_STAGE_CHANGED"
                else reply.plan.problem(checkNotNull(mission.contract))
            is MissionReply.Requirements -> {
                if (mission.stage != MissionStage.REQUIREMENTS) return "MISSION_STAGE_CHANGED"
                reply.contract.groundingProblem(goal.text)?.let { return it }
                for (requirement in reply.contract.requirements) {
                    val target = requirement.target
                    if (target is MissionTarget.Items && target.itemIds.any {
                        !BuiltInRegistries.ITEM.containsKey(ResourceLocation(it)) || it == "minecraft:air"
                    }) return "MISSION_UNKNOWN_ITEM"
                    val dimension = when (target) {
                        is MissionTarget.Items -> target.chest?.dimension
                        is MissionTarget.Collect -> target.chest.dimension
                        is MissionTarget.Visit -> target.dimension
                        is MissionTarget.Field -> target.dimension
                    }
                    if (dimension != null && dimension != current.physical.dimensionId) return "MISSION_DIMENSION_NOT_ALLOWED"
                }
                null
            }
        }
    }
}
