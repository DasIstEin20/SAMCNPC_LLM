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
                worldRequest: OperationWorldRequest? = null, diagnostic: Boolean = false): ContextCaptureResult {
        if (!server.isSameThread || ttlTicks !in 1..1200) return ContextCaptureResult.Rejected("INVALID_CAPTURE_REQUEST")
        val reply = OperationInspectionApi.inspect(server, actor, npcUuid, worldRequest ?: OperationWorldRequest())
        var inspection = reply.inspection
        if (reply.result.status != NpcActionStatus.SUCCEEDED || inspection == null) {
            return ContextCaptureResult.Rejected("OBSERVATION_" + reply.result.code.name)
        }
        if (worldRequest == null) {
            // Authorization above precedes all reads; the final snapshot is taken on this same server tick.
            val service = io.samcnpc.core.api.CoreNpcApi.service(server)
            val handle = service.find(npcUuid) ?: return ContextCaptureResult.Rejected("NPC_UNAVAILABLE")
            val body = service.runtime(handle) ?: return ContextCaptureResult.Rejected("NPC_UNAVAILABLE")
            val nearby = NearbyVisualCells.discover(inspection.physical.position, body.worldView()::observeVisibleBlock)
            val aliases = goal.memory.aliases.filter { it.dimensionId == inspection.physical.dimensionId }.map { it.position }.take(8)
            val cells = (nearby + aliases).distinct().take(16)
            val refreshed = OperationInspectionApi.inspect(server, actor, npcUuid, OperationWorldRequest(blocks = cells))
            inspection = refreshed.inspection
            if (refreshed.result.status != NpcActionStatus.SUCCEEDED || inspection == null)
                return ContextCaptureResult.Rejected("OBSERVATION_" + refreshed.result.code.name)
        }
        val target = goal.supervision?.target
        val stock = if (target == null) null else {
            val stockReply = io.samcnpc.behavior.api.OperationStockApi.inspect(server, actor, npcUuid, target.dimensionId, target.query)
            if (stockReply.result.status != NpcActionStatus.SUCCEEDED)
                return ContextCaptureResult.Rejected("STOCK_" + stockReply.result.code.name)
            val read = stockReply.stock
            if (read !is io.samcnpc.core.api.NpcStockRead.Observed)
                return ContextCaptureResult.Rejected("STOCK_" +
                    ((read as? io.samcnpc.core.api.NpcStockRead.Unavailable)?.reason?.name ?: "UNKNOWN"))
            read
        }
        val physical = inspection.physical
        val tick = physical.gameTime
        if (tick < 0 || tick > Long.MAX_VALUE - ttlTicks ||
            inspection.body.observedTick != tick || inspection.operation.observedTick != tick ||
            inspection.world?.observedTick != tick || stock != null && stock.observedTick != tick) return ContextCaptureResult.Rejected("INCONSISTENT_CAPTURE_CLOCK")
        val expiry = minOf(tick + ttlTicks, goal.deadlineTick ?: Long.MAX_VALUE)
        if (expiry <= tick || !diagnostic && goal.remainingCalls == 0) return ContextCaptureResult.Rejected("GOAL_BUDGET_EXHAUSTED")
        val task = inspection.operation.task
        val binding = ContextBinding(UUID.randomUUID(), npcUuid, actor.uuid, goal.id, goal.revision, policy.revision,
            inspection.generations, catalogHash, tick, expiry, task?.taskId, task?.definitionRevision, task?.controlRevision)
        return ContextCaptureResult.Captured(CapturedContext(binding, inspection, goal, policy,
            physical.summonerUuid == actor.uuid, actor.hasPermissions(2), stock))
    }
}
