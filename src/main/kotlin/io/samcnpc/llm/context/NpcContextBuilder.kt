package io.samcnpc.llm.context

import io.samcnpc.behavior.api.OperationDocumentApi
import io.samcnpc.behavior.api.OperationInspectionApi
import io.samcnpc.behavior.api.OperationWorldRequest
import io.samcnpc.core.api.NpcActionStatus
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

/** One authorized server-thread capture. The returned immutable values can be encoded by a worker. */
internal object NpcContextBuilder {
    // Published schema strings are cached immutable catalog data, independent of a world.
    val catalogHash: String by lazy {
        val bytes = (OperationDocumentApi.orderSchema() + "\n" + OperationDocumentApi.changeSchema())
            .toByteArray(StandardCharsets.UTF_8)
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    fun capture(server: MinecraftServer, actor: ServerPlayer, npcUuid: UUID, goal: ContextGoal,
                policy: ContextPolicy, ttlTicks: Int = 1200,
                worldRequest: OperationWorldRequest = OperationWorldRequest()): ContextCaptureResult {
        if (!server.isSameThread || ttlTicks !in 1..1200) return ContextCaptureResult.Rejected("INVALID_CAPTURE_REQUEST")
        val reply = OperationInspectionApi.inspect(server, actor, npcUuid, worldRequest)
        val inspection = reply.inspection
        if (reply.result.status != NpcActionStatus.SUCCEEDED || inspection == null) {
            return ContextCaptureResult.Rejected("OBSERVATION_" + reply.result.code.name)
        }
        val physical = inspection.physical
        val tick = physical.gameTime
        if (tick < 0 || tick > Long.MAX_VALUE - ttlTicks ||
            inspection.body.observedTick != tick || inspection.operation.observedTick != tick ||
            inspection.world?.observedTick != tick) return ContextCaptureResult.Rejected("INCONSISTENT_CAPTURE_CLOCK")
        val expiry = minOf(tick + ttlTicks, goal.deadlineTick ?: Long.MAX_VALUE)
        if (expiry <= tick || goal.remainingCalls == 0) return ContextCaptureResult.Rejected("GOAL_BUDGET_EXHAUSTED")
        val task = inspection.operation.task
        val binding = ContextBinding(UUID.randomUUID(), npcUuid, actor.uuid, goal.id, goal.revision, policy.revision,
            inspection.generations, catalogHash, tick, expiry, task?.taskId, task?.definitionRevision, task?.controlRevision)
        return ContextCaptureResult.Captured(CapturedContext(binding, inspection, goal, policy,
            physical.summonerUuid == actor.uuid, actor.hasPermissions(2)))
    }
}
