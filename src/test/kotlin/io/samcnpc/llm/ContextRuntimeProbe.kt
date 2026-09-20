package io.samcnpc.llm

import io.samcnpc.behavior.api.*
import io.samcnpc.core.api.*
import io.samcnpc.llm.context.*
import io.samcnpc.llm.provider.LlmJson
import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.block.Blocks
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** Capture a real task/body, remove the fixture, then encode only detached values on a worker. */
internal object ContextRuntimeProbe {
    private var result: CompletableFuture<String>? = null

    fun start(server: MinecraftServer, actor: ServerPlayer, origin: NpcPosition) {
        val level = actor.serverLevel()
        val position = origin.copy(z = origin.z + 4)
        val service = CoreNpcApi.service(server)
        val summoned = service.summon(NpcSummonRequest(actor.uuid, "ContextProbe",
            level.dimension().location().toString(), position, 0F))
        check(summoned.result.status == NpcActionStatus.SUCCEEDED)
        val handle = checkNotNull(summoned.handle)
        val hidden = BlockPos.containing(position.x + 20, position.y, position.z)
        val oldBlock = level.getBlockState(hidden)
        val actorPosition = NpcPosition(actor.x, actor.y, actor.z)
        val policy = ContextPolicy(2, OperationType.entries.toSet(), OperationCatalogApi.snapshot().changes.keys,
            OperationControl.entries.toSet(), 6000, 3, 12000)
        val memory = ContextMemory(listOf("Return to the user-labelled place"),
            listOf(ContextPlaceAlias("storage", level.dimension().location().toString(),
                NpcBlockPosition(hidden.x, hidden.y, hidden.z))))
        val goal = ContextGoal(UUID.randomUUID(), 3, "Zażółć 漢 — return to storage", LlmMode.TRANSLATOR,
            null, 24, memory)
        try {
            level.setBlockAndUpdate(hidden, Blocks.DIAMOND_ORE.defaultBlockState())
            val observed = checkNotNull(OperationSupervisionApi.observe(server, actor, handle.npcUuid).observation)
            val assigned = OperationSupervisionApi.assign(server, actor, handle.npcUuid, OperationAssignmentRequest(null,
                observed.observedTick, observed.observedTick + 100,
                OperationOrder.Navigate(observed.dimensionId, position.copy(x = position.x + 4))))
            check(assigned.result.status == NpcActionStatus.SUCCEEDED)
            val world = OperationWorldRequest(entities = null,
                blocks = listOf(NpcBlockPosition(hidden.x, hidden.y, hidden.z)))
            fun capture(input: ContextGoal): CapturedContext {
                val value = NpcContextBuilder.capture(server, actor, handle.npcUuid, input, policy, worldRequest = world)
                check(value is ContextCaptureResult.Captured) { value.toString() }
                return value.value
            }
            val captured = capture(goal)
            val invalidText = capture(ContextGoal(goal.id, goal.revision, "\uD800", goal.mode, null, 24))
            val largeGoal = ContextGoal(goal.id, goal.revision, "漢".repeat(2048), goal.mode, null, 24,
                ContextMemory(List(8) { "漢".repeat(256) }, confirmedResults = List(16) { "漢".repeat(256) }))
            val tooLarge = capture(largeGoal)
            actor.teleportTo(level, position.x + 300, position.y, position.z, 0F, 0F)
            val denied = NpcContextBuilder.capture(server, actor, handle.npcUuid, goal, policy, worldRequest = world)
            check(denied == ContextCaptureResult.Rejected("OBSERVATION_OUT_OF_RANGE"))
            actor.teleportTo(level, actorPosition.x, actorPosition.y, actorPosition.z, 0F, 0F)
            check(service.dismiss(handle, NpcDismissMode.ONLY_IF_EMPTY).status == NpcActionStatus.SUCCEEDED)
            check(service.runtime(handle) == null)
            val captureThreadId = Thread.currentThread().id
            result = CompletableFuture.supplyAsync {
                check(Thread.currentThread().id != captureThreadId)
                verify(captured, invalidText, tooLarge, goal)
            }
        } finally {
            level.setBlockAndUpdate(hidden, oldBlock)
            actor.teleportTo(level, actorPosition.x, actorPosition.y, actorPosition.z, 0F, 0F)
            if (service.runtime(handle) != null) check(service.dismiss(handle, NpcDismissMode.ONLY_IF_EMPTY).status == NpcActionStatus.SUCCEEDED)
        }
    }

    fun poll(): String? {
        val future = checkNotNull(result)
        return if (future.isDone) future.join() else null
    }

    private fun verify(captured: CapturedContext, invalidText: CapturedContext, tooLarge: CapturedContext,
                       goal: ContextGoal): String {
        val encoded = NpcContextEncoder.encode(captured)
        check(encoded is ContextEncodingResult.Encoded)
        val context = encoded.value
        val json = LlmJson.parse(context.stateJson, NpcContextEncoder.MAX_STATE_BYTES)
        check(context.utf8Bytes == LlmJson.utf8(context.stateJson).size)
        check(context.utf8Bytes > context.stateJson.length)
        check(json["inventory"].asJsonArray.size() == 36)
        check(json["goal"].asJsonObject["text"].asString == goal.text)
        check(json["authority"].asJsonObject["summoner"].asBoolean)
        check(json["task"].asJsonObject["state"].asString == "RUNNING")
        check(json["task"].asJsonObject["taskId"].asString == captured.binding.priorTaskId.toString())
        check(json["task"].asJsonObject["frames"].asJsonArray[0].asJsonObject["operationId"].asString == "samcnpc:navigate")
        check(json["memory"].asJsonObject["aliases"].asJsonArray[0].asJsonObject["currentWorldContents"].asString == "UNKNOWN")
        check(json["world"].asJsonObject["blocks"].asJsonArray[0].asJsonObject["observation"].asJsonObject["state"].asString == "UNAVAILABLE")
        check(!context.stateJson.contains("minecraft:diamond_ore"))
        check(json["history"].asJsonObject["state"].asString == "NOT_RECORDED")
        check(json["capabilities"].asJsonObject["operations"].asJsonArray.size() == 16)
        check(NpcContextEncoder.encode(captured, 1) == ContextEncodingResult.Rejected("CONTEXT_TOO_LARGE"))
        check(NpcContextEncoder.encode(tooLarge) == ContextEncodingResult.Rejected("CONTEXT_TOO_LARGE"))
        check(NpcContextEncoder.encode(invalidText) == ContextEncodingResult.Rejected("INVALID_CONTEXT_ENCODING"))
        return "contextWorker=true detachedAfterDismiss=true inventorySlots=36 hiddenBlockOmitted=true contextBytes=" +
            context.utf8Bytes + " contextBounds=true captureAuthority=true"
    }
}
