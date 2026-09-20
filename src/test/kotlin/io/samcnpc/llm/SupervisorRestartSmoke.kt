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
import net.minecraft.core.BlockPos
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.ChestBlockEntity
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
internal object SupervisorRestartSmoke {
    private val phase = System.getProperty("samcnpc.supervisorRestartPhase")
    private val markerPath = Path.of("supervisor-restart-checkpoint.nbt")
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
    private var reduced = false
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
            Files.writeString(Path.of("server-supervisor-$phase.txt"), "FAIL " + failure)
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
        val player = ServerPlayer(server, level, GameProfile(actorId, "StockRestart"))
        server.playerList.placeNewPlayer(connection, player)
        player.setGameMode(GameType.SPECTATOR)
        val ground = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, level.sharedSpawnPos)
        player.teleportTo(level, ground.x + 0.5, ground.y + 3.0, ground.z + 4.5, 0F, 0F)
        actor = player; channel = embedded
        if (phase == "load") return
        marker.putUUID("actor", actorId); marker.putLong("pid", ProcessHandle.current().pid())
        val service = CoreNpcApi.service(server)
        val base = ground.offset(16, 1, 0)
        marker.putInt("baseX", base.x); marker.putInt("baseY", base.y); marker.putInt("baseZ", base.z)
        for (x in -2..14) for (z in 4..28) for (y in -1..3) {
            val pos = base.offset(x, y, z)
            check(level.getBlockEntity(pos) == null)
            level.setBlockAndUpdate(pos, if (y == -1) Blocks.STONE.defaultBlockState() else Blocks.AIR.defaultBlockState())
        }
        for (index in 1..3) {
            val source = base.offset(0, 0, index * 8)
            val destination = base.offset(10, 0, index * 8)
            for (pos in listOf(source, destination)) level.setBlockAndUpdate(pos, Blocks.CHEST.defaultBlockState())
            (level.getBlockEntity(source) as ChestBlockEntity).setItem(0, ItemStack(Items.COBBLESTONE, 32))
            if (index == 3) repeat(4) { (level.getBlockEntity(destination) as ChestBlockEntity).setItem(it, ItemStack(Items.COBBLESTONE, 64)) }
            val spawn = NpcPosition(base.x + 8.5, base.y.toDouble(), base.z + index * 8 + 0.5)
            val summoned = service.summon(NpcSummonRequest(actorId, "StockRestart$index",
                level.dimension().location().toString(), spawn, 0F))
            check(summoned.result.status == NpcActionStatus.SUCCEEDED)
            marker.putUUID("npc$index", checkNotNull(summoned.handle).npcUuid)
            marker.putInt("x$index", destination.x); marker.putInt("y$index", destination.y); marker.putInt("z$index", destination.z)
        }
        val operations = (1..2).associate { index ->
            marker.getUUID("npc$index").toString() to transport(base, index)
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
                "fixture", "test-byte-bound", "test-template", 256, 131072, 24576))
        configured = settings
        check(LlmConfig.update(LlmConfig.snapshot().revision, settings))
    }

    private fun saveTick(server: MinecraftServer) {
        val controller = checkNotNull(LlmServerEvents.controller(server))
        check(controller.settings == configured) { "Restart config was unexpectedly replaced" }
        val player = checkNotNull(actor)
        val first = marker.getUUID("npc1"); val second = marker.getUUID("npc2")
        if (!assigned) {
            for (index in 1..3) {
                val id = marker.getUUID("npc$index")
                val thresholds = if (index == 3) "192 256" else "24 32"
                val text = "minecraft:cobblestone $thresholds " + marker.getInt("x$index") + " " +
                    marker.getInt("y$index") + " " + marker.getInt("z$index") + " Maintain stock using the supplied source."
                check(server.commands.dispatcher.execute("samcnpc llm maintain $id $text", player.createCommandSourceStack()) == 1)
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
            check(record.phase in setOf(GoalPhase.WAITING, GoalPhase.QUEUED, GoalPhase.INFERENCING, GoalPhase.ADMITTING, GoalPhase.EXECUTING)) {
                "Restart setup failed: phase=${record.phase} code=${record.code} calls=${calls.get()} held=${held != null}"
            }
        }
        if (ticks % 100 == 0) Files.writeString(Path.of("supervisor-restart-progress.txt"),
            "first=${firstRecord.phase}/${firstRecord.code} second=${secondRecord.phase}/${secondRecord.code} calls=${calls.get()} held=${held != null}\n")
        if (firstRecord.phase != GoalPhase.EXECUTING || secondRecord.phase != GoalPhase.EXECUTING ||
            firstRecord.budget.inFlight != null || secondRecord.budget.inFlight != null) return
        check(calls.get() == 2 && held != null)
        val waiting = checkNotNull(controller.store.get(marker.getUUID("npc3")))
        if (waiting.supervision?.armed != true) return
        check(waiting.phase == GoalPhase.WAITING && waiting.budget.settledAttempts == 0)
        for (index in 1..2) {
            val record = checkNotNull(controller.store.get(marker.getUUID("npc$index")))
            check(record.budget.settledAttempts == 1)
            marker.putUUID("task$index", checkNotNull(record.task).id)
        }
        NbtIo.writeCompressed(marker, markerPath.toFile())
        expectedReport = "PASS savePid=" + ProcessHandle.current().pid() + " httpCalls=2 realAssignments=2 waitingWatch=true lostReceiptInjection=true"
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
        if (!assigned) {
            val uncertain = checkNotNull(controller.store.get(second))
            check(uncertain.phase == GoalPhase.REVIEW_REQUIRED && uncertain.manualHold)
            check(uncertain.code == "RESTART_REVIEW_REQUIRED" && uncertain.contextId == null)
            check(uncertain.budget.inFlight == null && uncertain.budget.settledAttempts == 1 &&
                uncertain.budget.chargedInputTokens == 24576L)
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
        check(healthy.phase == GoalPhase.WAITING && healthy.code == "STOCK_TARGET_REACHED" &&
            healthy.supervision?.armed == true && healthy.budget.settledAttempts == 1 &&
            healthy.budget.chargedInputTokens == 24576L) { healthy.toString() }
        fun chest(index: Int) = server.overworld().getBlockEntity(
            BlockPos(marker.getInt("x$index"), marker.getInt("y$index"), marker.getInt("z$index"))) as ChestBlockEntity
        fun count(index: Int): Int = (0 until 27).sumOf { chest(index).getItem(it).count }
        check(count(1) == 32)
        check(controller.store.get(second)?.phase == GoalPhase.REVIEW_REQUIRED)
        if (OperationSupervisionApi.observe(server, player, second).observation?.task?.state != OperationTaskState.COMPLETED) return
        check(count(2) == 32)
        val watch = checkNotNull(controller.store.get(marker.getUUID("npc3")))
        check(watch.budget.settledAttempts == 0 && watch.task == null) { watch.toString() }
        if (!reduced) {
            check(watch.phase == GoalPhase.WAITING && watch.supervision?.armed == true && !watch.manualHold && count(3) == 256)
            chest(3).clearContent()
            chest(3).setItem(0, ItemStack(Items.COBBLESTONE, 64))
            chest(3).setItem(1, ItemStack(Items.COBBLESTONE, 64))
            chest(3).setItem(2, ItemStack(Items.COBBLESTONE, 48))
            reduced = true
            return
        }
        if (!watch.manualHold) return
        check(watch.phase == GoalPhase.WAITING && count(3) == 176)
        for (index in 1..3) {
            val npc = marker.getUUID("npc$index")
            check(controller.store.remove(npc) == null)
            check(service.dismiss(checkNotNull(service.find(npc)), NpcDismissMode.ONLY_IF_EMPTY).status == NpcActionStatus.SUCCEEDED)
        }
        expectedReport = "PASS newJvm=true knownTaskPhysicallyDelivered32=true exactTaskIdsRetained=true waitingWatchFreshRead=true removed80Detected=true " +
            "uncertainAdmissionHeld=true heldBudgetSettledOnce=true providerDisabled=true replayAssignments=0"
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
        expectedReport?.let { Files.writeString(Path.of("server-supervisor-$phase.txt"), it + "\n") }
    }

    private fun transport(base: BlockPos, index: Int): String {
        val z = base.z + index * 8
        return """{"documentVersion":1,"type":"samcnpc:transport","definitionVersion":1,"parameters":{"dimensionId":"minecraft:overworld","sources":{"positions":[{"x":${base.x},"y":${base.y},"z":$z}]},"destinations":{"positions":[{"x":${base.x+10},"y":${base.y},"z":$z}]},"itemId":"minecraft:cobblestone","quantity":32,"anchor":{"x":${base.x+8},"y":${base.y},"z":$z},"budget":{"ticks":2400,"attempts":8}}}"""
    }

    private fun reply(received: FakeOpenAiEndpoint.Received, operations: Map<String, String>,
                      gate: CountDownLatch, counter: AtomicInteger): FakeOpenAiEndpoint.Reply {
        counter.incrementAndGet()
        val body = LlmJson.parse(received.body, 65536)
        val state = LlmJson.parse(body["messages"].asJsonArray[1].asJsonObject["content"].asString, 24576)
        val result = JsonObject()
        result.addProperty("schemaVersion", 1); result.addProperty("contextId", state["contextId"].asString)
        result.addProperty("decision", "ASSIGN"); result.addProperty("summary", "Test-only stock transport")
        for (name in listOf("change", "question", "wait")) result.add(name, JsonNull.INSTANCE)
        result.add("operation", LlmJson.parse(operations.getValue(state["identity"].asJsonObject["npcUuid"].asString), 16384))
        return FakeOpenAiEndpoint.Reply(body = LlmJson.utf8(FakeOpenAiEndpoint.success(result.toString())), waitBeforeHeaders = gate)
    }
}
