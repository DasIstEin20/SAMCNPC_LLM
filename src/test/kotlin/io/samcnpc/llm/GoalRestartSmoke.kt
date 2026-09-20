package io.samcnpc.llm

import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.mojang.authlib.GameProfile
import io.netty.channel.embedded.EmbeddedChannel
import io.samcnpc.behavior.api.*
import io.samcnpc.core.api.*
import io.samcnpc.llm.config.*
import io.samcnpc.llm.goal.*
import io.samcnpc.llm.provider.*
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
import net.minecraftforge.eventbus.api.EventPriority
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/** Two real JVMs. Lost-receipt state is explicitly injected AFTER normal controller shutdown, before SavedData save. */
@Mod.EventBusSubscriber(modid = SamcnpcLlm.MOD_ID)
internal object GoalRestartSmoke {
    private val phase = System.getProperty("samcnpc.goalRestartPhase")
    private val markerPath = Path.of("goal-restart-checkpoint.nbt")
    private var marker = CompoundTag()
    private var actor: ServerPlayer? = null
    private var channel: EmbeddedChannel? = null
    private var endpoint: FakeOpenAiEndpoint? = null
    private var original: ProviderSettings? = null
    private var configured: ProviderSettings? = null
    private var held: GoalRecord? = null
    private val release = CountDownLatch(1)
    private val calls = AtomicInteger()
    private var ticks = 0
    private var assigned = false
    private var done = false
    private var expectedReport: String? = null
    private var failure: String? = null

    @SubscribeEvent
    fun tick(event: TickEvent.ServerTickEvent) {
        if (phase == null || done || event.phase != TickEvent.Phase.END) return
        val server = event.server
        try {
            check(server.isDedicatedServer && phase in setOf("save", "load"))
            check(++ticks < 2400) { "Goal restart $phase timeout" }
            if (ticks < 20) return
            if (actor == null) { initialize(server); return }
            if (phase == "save") saveTick(server) else loadTick(server)
        } catch (error: Exception) {
            failure = error.stackTraceToString()
            Files.writeString(Path.of("server-goals-$phase.txt"), "FAIL " + failure)
            done = true
            release.countDown()
            server.halt(false)
        }
    }

    private fun initialize(server: MinecraftServer) {
        val level = server.overworld()
        if (phase == "save") check(!Files.exists(markerPath)) { "Restart save needs a fresh run directory" }
        else {
            marker = NbtIo.readCompressed(markerPath.toFile())
            check(marker.getLong("pid") != ProcessHandle.current().pid()) { "Restart must use a different JVM" }
        }
        val actorId = if (phase == "save") UUID.randomUUID() else marker.getUUID("actor")
        val connection = Connection(PacketFlow.SERVERBOUND)
        val embedded = EmbeddedChannel(connection)
        connection.setProtocol(ConnectionProtocol.PLAY)
        val player = ServerPlayer(server, level, GameProfile(actorId, "GoalRestart"))
        server.playerList.placeNewPlayer(connection, player)
        player.setGameMode(GameType.SPECTATOR)
        val ground = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, level.sharedSpawnPos)
        player.teleportTo(level, ground.x + 0.5, ground.y + 3.0, ground.z + 4.5, 0F, 0F)
        actor = player; channel = embedded
        if (phase == "load") return
        marker.putUUID("actor", actorId); marker.putLong("pid", ProcessHandle.current().pid())
        val service = CoreNpcApi.service(server)
        for (index in 1..2) {
            val spawn = NpcPosition(ground.x + 0.5, ground.y.toDouble(), ground.z + index * 3.0)
            val result = service.summon(NpcSummonRequest(actorId, "GoalRestart$index",
                level.dimension().location().toString(), spawn, 0F))
            check(result.result.status == NpcActionStatus.SUCCEEDED)
            marker.putUUID("npc$index", checkNotNull(result.handle).npcUuid)
            marker.putDouble("x$index", spawn.x + if (index == 1) 20 else 24)
            marker.putDouble("y$index", spawn.y); marker.putDouble("z$index", spawn.z)
        }
        val operations = (1..2).associate { index ->
            marker.getUUID("npc$index").toString() to navigate(marker.getDouble("x$index"),
                marker.getDouble("y$index"), marker.getDouble("z$index"))
        }
        for (document in operations.values) {
            val validation = OperationDocumentApi.decodeOrder(document)
            check(validation is OperationDocumentResult.Accepted) { validation.toString() }
        }
        // Captures detached strings/synchronizers only.
        val transport = FakeOpenAiEndpoint { received -> reply(received, operations, release, calls) }
        endpoint = transport
        original = LlmConfig.snapshot().values
        val settings = ProviderSettings(enabled = true, baseUrl = transport.baseUrl, model = "restart-emulator",
            apiKeyEnvironment = "", requestTimeoutSeconds = 4,
            inference = InferenceSettings(true, transport.baseUrl, "restart-emulator", "emulator-v1",
                "fixture", "test-byte-bound", "test-template", 256, 131072, 49152))
        configured = settings
        check(LlmConfig.update(LlmConfig.snapshot().revision, settings))
    }

    private fun saveTick(server: MinecraftServer) {
        val controller = checkNotNull(LlmServerEvents.controller(server))
        check(controller.settings == configured) { "Restart config was unexpectedly replaced" }
        val player = checkNotNull(actor)
        val first = marker.getUUID("npc1"); val second = marker.getUUID("npc2")
        if (!assigned) {
            for (index in 1..2) {
                val id = marker.getUUID("npc$index")
                val text = "Idź do " + marker.getDouble("x$index") + "," + marker.getDouble("y$index") + "," + marker.getDouble("z$index")
                val previous = GoalRecord(id, player.uuid, UUID.randomUUID(), 1, "Previous goal", phase = GoalPhase.STOPPED)
                check(controller.store.put(previous) == null)
                check(server.commands.dispatcher.execute("samcnpc llm remember $id home 0 64 0", player.createCommandSourceStack()) == 1)
                check(server.commands.dispatcher.execute("samcnpc llm goal $id $text", player.createCommandSourceStack()) == 1)
                check(controller.store.get(id)?.memory?.aliases?.single()?.name == "home")
            }
            assigned = true
            return
        }
        val secondRecord = checkNotNull(controller.store.get(second))
        if (held == null && secondRecord.phase == GoalPhase.INFERENCING) {
            check(secondRecord.contextId != null && secondRecord.budget.inFlight != null)
            held = secondRecord.copy(phase = GoalPhase.ADMITTING, code = "ADMISSION_STARTED")
        }
        if (held != null && calls.get() == 2) release.countDown()
        val firstRecord = checkNotNull(controller.store.get(first))
        for (record in listOf(firstRecord, secondRecord)) {
            check(record.phase in setOf(GoalPhase.QUEUED, GoalPhase.INFERENCING, GoalPhase.ADMITTING, GoalPhase.EXECUTING)) {
                "Restart setup failed: phase=${record.phase} code=${record.code} calls=${calls.get()} held=${held != null}"
            }
        }
        if (ticks % 100 == 0) Files.writeString(Path.of("restart-progress.txt"),
            "first=${firstRecord.phase}/${firstRecord.code} second=${secondRecord.phase}/${secondRecord.code} calls=${calls.get()} held=${held != null}\n")
        if (firstRecord.phase != GoalPhase.EXECUTING || secondRecord.phase != GoalPhase.EXECUTING ||
            firstRecord.budget.inFlight != null || secondRecord.budget.inFlight != null) return
        check(calls.get() == 2 && held != null)
        for (index in 1..2) {
            val record = checkNotNull(controller.store.get(marker.getUUID("npc$index")))
            check(record.budget.settledAttempts == 1)
            marker.putUUID("task$index", checkNotNull(record.task).id)
        }
        NbtIo.writeCompressed(marker, markerPath.toFile())
        expectedReport = "PASS savePid=" + ProcessHandle.current().pid() + " httpCalls=2 realAssignments=2 lostReceiptInjection=true"
        done = true
        server.halt(false)
    }

    private fun loadTick(server: MinecraftServer) {
        val controller = checkNotNull(LlmServerEvents.controller(server))
        check(!controller.settings.enabled) { "Restart must not contact a model" }
        val player = checkNotNull(actor)
        val first = marker.getUUID("npc1"); val second = marker.getUUID("npc2")
        val service = CoreNpcApi.service(server)
        if (service.find(first)?.let(service::runtime) == null || service.find(second)?.let(service::runtime) == null) return
        for (id in listOf(first, second)) {
            val remembered = checkNotNull(controller.store.get(id))
            check(remembered.memory.aliases.single().name == "home")
            check(remembered.memory.aliases.single().position == NpcBlockPosition(0, 64, 0))
        }
        if (!assigned) {
            val uncertain = checkNotNull(controller.store.get(second))
            check(uncertain.phase == GoalPhase.REVIEW_REQUIRED && uncertain.manualHold)
            check(uncertain.code == "RESTART_REVIEW_REQUIRED" && uncertain.contextId == null)
            check(uncertain.budget.inFlight == null && uncertain.budget.settledAttempts == 1 &&
                uncertain.budget.chargedInputTokens == 49152L)
            check(controller.resume(player, second, LlmServerEvents.nowMillis()).code == "REVIEW_REQUIRES_NEW_GOAL")
            controller.reconnect(player)
            assigned = true
        }
        for (index in 1..2) {
            val observed = OperationSupervisionApi.observe(server, player, marker.getUUID("npc$index"))
            check(observed.result.status == NpcActionStatus.SUCCEEDED)
            check(observed.observation?.task?.taskId == marker.getUUID("task$index")) { "Restart replaced a task" }
        }
        val healthy = checkNotNull(controller.store.get(first))
        if (healthy.phase == GoalPhase.EXECUTING) return
        check(healthy.phase == GoalPhase.COMPLETED && healthy.budget.settledAttempts == 1 &&
            healthy.budget.chargedInputTokens == 49152L) { healthy.toString() }
        val body = checkNotNull(service.find(first)?.let(service::runtime)).snapshot()
        val dx = body.position.x - marker.getDouble("x1"); val dz = body.position.z - marker.getDouble("z1")
        check(dx * dx + dz * dz <= 0.75 * 0.75)
        check(controller.store.get(second)?.phase == GoalPhase.REVIEW_REQUIRED)
        check(healthy.memory.results.single().startsWith(marker.getUUID("task1").toString()))
        check(healthy.memory.results.single().endsWith("TASK_COMPLETED"))
        check(controller.store.get(second)?.memory?.results?.isEmpty() == true)
        for (index in 1..2) {
            val npc = marker.getUUID("npc$index")
            check(controller.store.remove(npc) == null)
            check(service.dismiss(checkNotNull(service.find(npc)), NpcDismissMode.ONLY_IF_EMPTY).status == NpcActionStatus.SUCCEEDED)
        }
        expectedReport = "PASS newJvm=true knownTaskPhysicallyCompleted=true exactTaskIdsRetained=true " +
            "uncertainAdmissionHeld=true heldBudgetSettledOnce=true providerDisabled=true replayAssignments=0 memoryRetained=true authoritativeResultRecorded=true"
        done = true
        server.halt(false)
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    fun stopping(event: ServerStoppingEvent) {
        if (phase == null) return
        release.countDown()
        endpoint?.close(); endpoint = null
        original?.let { check(LlmConfig.update(LlmConfig.snapshot().revision, it)) }
        if (phase == "save" && failure == null && expectedReport != null) {
            // Deliberate fault snapshot: real task already exists, but the LLM receipt was lost.
            check(LlmGoalStore.forServer(event.server).put(checkNotNull(held)) == null)
        }
        actor?.let { event.server.playerList.remove(it) }
        channel?.finishAndReleaseAll()
        actor = null; channel = null
    }

    @SubscribeEvent
    fun stopped(event: ServerStoppedEvent) {
        if (phase == null || failure != null) return
        expectedReport?.let { Files.writeString(Path.of("server-goals-$phase.txt"), it + "\n") }
    }

    private fun navigate(x: Double, y: Double, z: Double): String =
        """{"documentVersion":1,"type":"samcnpc:navigate","definitionVersion":1,"parameters":{"dimensionId":"minecraft:overworld","destination":{"x":$x,"y":$y,"z":$z},"arrivalDistance":0.5,"budget":{"ticks":2400,"attempts":8}}}"""

    private fun reply(received: FakeOpenAiEndpoint.Received, operations: Map<String, String>,
                      gate: CountDownLatch, counter: AtomicInteger): FakeOpenAiEndpoint.Reply {
        counter.incrementAndGet()
        val body = LlmJson.parse(received.body, 65536)
        val state = LlmJson.parse(body["messages"].asJsonArray[1].asJsonObject["content"].asString, 24576)
        val place = state["memory"].asJsonObject["aliases"].asJsonArray.single().asJsonObject
        check(place["name"].asString == "home" && place["currentWorldContents"].asString == "UNKNOWN")
        val result = JsonObject()
        result.addProperty("schemaVersion", 1); result.addProperty("contextId", state["contextId"].asString)
        result.addProperty("decision", "ASSIGN"); result.addProperty("summary", "Test-only navigation")
        for (name in listOf("change", "question", "wait")) result.add(name, JsonNull.INSTANCE)
        result.add("operation", LlmJson.parse(operations.getValue(state["identity"].asJsonObject["npcUuid"].asString), 16384))
        return FakeOpenAiEndpoint.Reply(body = LlmJson.utf8(FakeOpenAiEndpoint.success(result.toString())), waitBeforeHeaders = gate)
    }
}
