package io.samcnpc.llm

import io.samcnpc.behavior.api.*
import io.samcnpc.core.api.*
import io.samcnpc.llm.context.*
import io.samcnpc.llm.mission.*
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

/** The real public authorization and current snapshot are checked; preparation must create no task. */
internal object MissionAdmissionProbe {
    fun verify(server: MinecraftServer, actor: ServerPlayer, handle: NpcHandle): String {
        val policy = ContextPolicy(1, OperationType.entries.toSet(), emptySet(), emptySet(), 72000, 16, 0)
        val id = UUID.randomUUID()
        fun goal(state: MissionState = MissionState(), revision: Long = 1, remaining: Int = 24) =
            ContextGoal(id, revision, "Get 2 coal", LlmMode.PLANNER, null, remaining, mission = state)
        val first = goal()
        val capture = NpcContextBuilder.capture(server, actor, handle.npcUuid, first, policy)
        check(capture is ContextCaptureResult.Captured)
        val source = capture.value
        fun contract(item: String) = MissionContract(listOf(MissionRequirement("R1", "2 coal", MissionTarget.Items(listOf(item), 2))))
        val valid = MissionReply.Requirements(contract("minecraft:coal"))
        var checks = 0
        fun verify(expected: String?, current: ContextGoal = first, reply: MissionReply = valid,
                   held: Boolean = false, captured: CapturedContext = source) {
            val before = checkNotNull(OperationInspectionApi.inspect(server, actor, handle.npcUuid).inspection)
            val actual = MissionAdmission.problem(server, actor, captured, current, policy, reply, held)
            check(actual == expected) { "mission admission expected=$expected actual=$actual" }
            val after = checkNotNull(OperationInspectionApi.inspect(server, actor, handle.npcUuid).inspection)
            check(before.operation.task == after.operation.task && before.physical.position == after.physical.position)
            check(before.body.inventory == after.body.inventory && before.body.equipment == after.body.equipment)
            checks++
        }
        verify(null)
        verify("MANUAL_HOLD", held = true)
        verify("GOAL_CHANGED", current = goal(revision = 2))
        verify("GOAL_BUDGET_EXHAUSTED", current = goal(remaining = 0))
        verify("GOAL_MISSION_CHANGED", current = goal(MissionState(valid.contract)))
        verify("MISSION_UNKNOWN_ITEM", reply = MissionReply.Requirements(contract("samcnpc:invented_coal")))
        verify("MISSION_STAGE_CHANGED", reply = MissionReply.Plan(MissionPlan(listOf(MissionStep("Mine", listOf("R1"))))))
        val expired = CapturedContext(source.binding.copy(expiresTick = source.binding.issuedTick), source.inspection,
            source.goal, source.policy, source.actorIsSummoner, source.actorIsOperator)
        verify("CONTEXT_EXPIRED", captured = expired)
        val before = actor.position()
        actor.teleportTo(actor.serverLevel(), before.x + 1024, before.y, before.z, 0F, 0F)
        try {
            val problem = MissionAdmission.problem(server, actor, source, first, policy, valid, false)
            check(problem?.startsWith("MISSION_OBSERVATION_") == true) { "range authorization: $problem" }
            checks++
        } finally { actor.teleportTo(actor.serverLevel(), before.x, before.y, before.z, 0F, 0F) }
        check(OperationSupervisionApi.observe(server, actor, handle.npcUuid).observation?.task == null)
        return "PASS missionAdmissionChecks=$checks preparationWorldEffects=0 manualHoldWins=true staleMissionRejected=true rangeRechecked=true"
    }
}
