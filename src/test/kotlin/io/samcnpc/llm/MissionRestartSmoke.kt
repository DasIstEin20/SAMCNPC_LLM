package io.samcnpc.llm

import com.google.gson.*
import com.mojang.authlib.GameProfile
import io.netty.channel.embedded.EmbeddedChannel
import io.samcnpc.behavior.api.*
import io.samcnpc.core.api.*
import io.samcnpc.llm.config.*
import io.samcnpc.llm.goal.*
import io.samcnpc.llm.mission.*
import io.samcnpc.llm.provider.*
import io.samcnpc.llm.scheduling.InferenceBudgetView
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtIo
import net.minecraft.network.Connection
import net.minecraft.network.ConnectionProtocol
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.GameType
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.server.ServerStoppingEvent
import net.minecraftforge.event.server.ServerStoppedEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Two JVMs: one real V2 operation and explicit held-inference snapshots at every preparation stage. */
@Mod.EventBusSubscriber(modid = SamcnpcLlm.MOD_ID)
internal object MissionRestartSmoke {
    private val phase = System.getProperty("samcnpc.missionRestartPhase")
    private val markerPath = Path.of("mission-restart-checkpoint.nbt")
    private var marker = CompoundTag()
    private var actor: ServerPlayer? = null
    private var channel: EmbeddedChannel? = null
    private var endpoint: FakeOpenAiEndpoint? = null
    private var original: ProviderSettings? = null
    private var settings: ProviderSettings? = null
    private var ticks = 0
    private var started = false
    private var done = false
    private var report: String? = null
    private val calls = AtomicInteger()

    @SubscribeEvent fun tick(event: TickEvent.ServerTickEvent) {
        if (phase == null || done || event.phase != TickEvent.Phase.END) return
        try {
            check(event.server.isDedicatedServer && phase in setOf("save", "load"))
            check(++ticks < 2400) { "mission restart timeout phase=$phase" }
            if (ticks < 20) return
            if (actor == null) { initialize(event.server); return }
            if (phase == "save") save(event.server) else load(event.server)
        } catch (error: Exception) {
            Files.writeString(Path.of("server-mission-$phase.txt"), "FAIL " + error.stackTraceToString())
            done = true; report = null; event.server.halt(false)
        }
    }

    private fun initialize(server: MinecraftServer) {
        val level = server.overworld()
        if (phase == "save") check(!Files.exists(markerPath)) else {
            marker = NbtIo.readCompressed(markerPath.toFile())
            check(marker.getLong("pid") != ProcessHandle.current().pid())
        }
        val actorId = if (phase == "save") UUID.randomUUID() else marker.getUUID("actor")
        val connection = Connection(PacketFlow.SERVERBOUND)
        channel = EmbeddedChannel(connection); connection.setProtocol(ConnectionProtocol.PLAY)
        val player = ServerPlayer(server, level, GameProfile(actorId, "MissionRestart"))
        server.playerList.placeNewPlayer(connection, player); player.setGameMode(GameType.SPECTATOR)
        val ground = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, level.sharedSpawnPos)
        if (phase == "save") {
            marker.putUUID("actor", actorId); marker.putLong("pid", ProcessHandle.current().pid())
            marker.putDouble("x", ground.x + 24.5); marker.putDouble("y", ground.y.toDouble()); marker.putDouble("z", ground.z + 0.5)
            val result = CoreNpcApi.service(server).summon(NpcSummonRequest(actorId, "MissionRestart",
                "minecraft:overworld", NpcPosition(ground.x + 0.5, ground.y.toDouble(), ground.z + 0.5), 0F))
            check(result.result.status == NpcActionStatus.SUCCEEDED)
            marker.putUUID("npc", checkNotNull(result.handle).npcUuid)
        }
        player.teleportTo(level, marker.getDouble("x") - 12, marker.getDouble("y") + 3, marker.getDouble("z"), 0F, 0F)
        actor = player
        if (phase == "load") return
        val contract = contract(); val plan = plan()
        val destination = """{"x":${marker.getDouble("x")},"y":${marker.getDouble("y")},"z":${marker.getDouble("z")}}"""
        val order = """{"documentVersion":1,"type":"samcnpc:navigate","definitionVersion":1,"parameters":{"dimensionId":"minecraft:overworld","destination":$destination,"arrivalDistance":0.5,"budget":{"ticks":2400,"attempts":8}}}"""
        check(OperationDocumentApi.decodeOrder(order) is OperationDocumentResult.Accepted)
        // Captures immutable payloads and an atomic counter only, never a world object.
        val transport = FakeOpenAiEndpoint { received -> reply(received, contract, plan, order, calls) }
        endpoint = transport; original = LlmConfig.snapshot().values
        val config = ProviderSettings(enabled = true, baseUrl = transport.baseUrl, model = "mission-restart-emulator",
            apiKeyEnvironment = "", requestTimeoutSeconds = 5, maxOutputTokens = 4096,
            inference = InferenceSettings(true, transport.baseUrl, "mission-restart-emulator", "emulator-v1",
                "fixture", "test-byte-bound", "test-template", 2048, 65536, 61440))
        settings = config
        check(LlmConfig.update(LlmConfig.snapshot().revision, config))
    }

    private fun save(server: MinecraftServer) {
        val controller = checkNotNull(LlmServerEvents.controller(server))
        if (controller.settings != settings) return
        val player = checkNotNull(actor)
        if (!started) {
            check(server.commands.dispatcher.execute("samcnpc llm plan_v2 ${marker.getUUID("npc")} Reach destination", player.createCommandSourceStack()) == 1)
            started = true; return
        }
        val record = checkNotNull(controller.store.get(marker.getUUID("npc")))
        check(record.phase in setOf(GoalPhase.QUEUED, GoalPhase.INFERENCING, GoalPhase.EXECUTING, GoalPhase.WAITING)) { record.toString() }
        if (record.phase != GoalPhase.EXECUTING || record.budget.inFlight != null) return
        check(calls.get() == 3 && record.budget.settledAttempts == 3)
        check(record.mission == MissionState(contract(), plan()))
        marker.putUUID("task", checkNotNull(record.task).id)
        marker.put("mission", MissionStateCodec.encode(checkNotNull(record.mission)))
        // Fault fixtures exercise SavedData recovery at each stage; these did not execute HTTP or world actions.
        for ((index, state) in listOf(MissionState(), MissionState(contract()), MissionState(contract(), plan())).withIndex()) {
            val id = UUID.randomUUID(); marker.putUUID("held$index", id)
            val held = GoalRecord(id, player.uuid, UUID.randomUUID(), 1, "Reach destination", phase = GoalPhase.INFERENCING,
                manualHold = true, budget = InferenceBudgetView(0, 100, 100, 0, UUID.randomUUID()),
                mode = io.samcnpc.llm.context.LlmMode.PLANNER, mission = state)
            check(controller.store.put(held) == null)
        }
        NbtIo.writeCompressed(marker, markerPath.toFile())
        report = "PASS realV2Stages=3 httpCalls=3 knownTaskSaved=true injectedHeldStages=3"
        done = true; server.halt(false)
    }

    private fun load(server: MinecraftServer) {
        val controller = checkNotNull(LlmServerEvents.controller(server))
        check(!controller.settings.enabled && endpoint == null && calls.get() == 0)
        val service = CoreNpcApi.service(server)
        val npc = marker.getUUID("npc")
        val body = service.find(npc)?.let(service::runtime) ?: return
        if (!started) { controller.reconnect(checkNotNull(actor)); started = true }
        for (index in 0..2) {
            val held = checkNotNull(controller.store.get(marker.getUUID("held$index")))
            check(held.phase == GoalPhase.REVIEW_REQUIRED && held.manualHold && held.code == "RESTART_REVIEW_REQUIRED")
            check(held.mission?.stage == MissionStage.entries[index] && held.budget.settledAttempts == 1 && held.budget.inFlight == null)
            check(held.budget.chargedInputTokens == 100L && held.budget.chargedOutputTokens == 100L)
        }
        val record = checkNotNull(controller.store.get(npc))
        val task = checkNotNull(OperationSupervisionApi.observe(server, checkNotNull(actor), npc).observation?.task)
        check(task.taskId == marker.getUUID("task"))
        check(record.mission?.contract == contract() && record.mission?.plan == plan())
        if (record.phase == GoalPhase.EXECUTING) return
        check(record.phase == GoalPhase.COMPLETED && record.code == "MISSION_COMPLETED") { record.toString() }
        check(task.state == OperationTaskState.COMPLETED && record.planStepsCompleted == 1 && record.budget.settledAttempts == 3)
        check(record.mission?.receipts == listOf(MissionReceipt("R1", task.taskId)))
        val feet = body.snapshot().position
        val dx = feet.x - marker.getDouble("x"); val dy = feet.y - marker.getDouble("y"); val dz = feet.z - marker.getDouble("z")
        check(dx*dx + dy*dy + dz*dz <= 0.75*0.75)
        report = "PASS newJvm=true missionContractPlanRetained=true exactTaskRetained=true physicalCompletion=true providerDisabled=true replayAssignments=0 heldStages=3 budgetSettledOnce=true"
        done = true; server.halt(false)
    }

    private fun contract() = MissionContract(listOf(MissionRequirement("R1", "Reach destination",
        MissionTarget.Visit("minecraft:overworld", NpcPosition(marker.getDouble("x"), marker.getDouble("y"), marker.getDouble("z"))))))
    private fun plan() = MissionPlan(listOf(MissionStep("Reach destination", listOf("R1"))))

    @SubscribeEvent fun stopping(event: ServerStoppingEvent) {
        if (phase == null) return
        endpoint?.close(); endpoint = null
        original?.let { check(LlmConfig.update(LlmConfig.snapshot().revision, it)) }
        actor?.let { event.server.playerList.remove(it) }; actor = null
        channel?.finishAndReleaseAll(); channel = null
    }
    @SubscribeEvent fun stopped(event: ServerStoppedEvent) {
        if (phase != null) report?.let { Files.writeString(Path.of("server-mission-$phase.txt"), it + "\n") }
    }

    private fun reply(received: FakeOpenAiEndpoint.Received, contract: MissionContract, plan: MissionPlan,
                      operation: String, count: AtomicInteger): FakeOpenAiEndpoint.Reply {
        val call = count.incrementAndGet()
        val http = LlmJson.parse(received.body, 65536)
        val state = LlmJson.parse(http["messages"].asJsonArray[1].asJsonObject["content"].asString, 40960)
        val response = JsonObject()
        response.addProperty("schemaVersion", 1); response.add("contextId", state["contextId"])
        when (call) {
            1 -> response.add("contract", MissionCodec.encode(contract))
            2 -> response.add("plan", MissionCodec.encode(plan))
            3 -> {
                response.addProperty("decision", "ASSIGN"); response.addProperty("summary", "Visit intent")
                response.add("operation", JsonParser.parseString(operation))
                response.add("change", JsonNull.INSTANCE); response.add("wait", JsonNull.INSTANCE)
            }
            else -> error("Unexpected model call after the one admitted operation")
        }
        response.add("question", JsonNull.INSTANCE)
        return FakeOpenAiEndpoint.Reply(body = LlmJson.utf8(FakeOpenAiEndpoint.success(response.toString())))
    }
}
