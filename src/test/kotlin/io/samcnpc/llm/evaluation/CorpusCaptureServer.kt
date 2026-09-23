package io.samcnpc.llm.evaluation

import com.google.gson.*
import com.mojang.authlib.GameProfile
import io.netty.channel.embedded.EmbeddedChannel
import io.samcnpc.behavior.api.*
import io.samcnpc.core.api.*
import io.samcnpc.llm.SamcnpcLlm
import io.samcnpc.llm.config.LlmConfig
import io.samcnpc.llm.context.*
import net.minecraft.network.Connection
import net.minecraft.network.ConnectionProtocol
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.GameRules
import net.minecraft.world.level.GameType
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.server.ServerStoppingEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** Opt-in capture only: all observations use the public authorized pipeline, provider calls stay at zero. */
@Mod.EventBusSubscriber(modid = SamcnpcLlm.MOD_ID)
internal object CorpusCaptureServer {
    private val enabled = java.lang.Boolean.getBoolean("samcnpc.corpusCapture")
    private val output = Path.of("frozen-captures")
    private var actor: ServerPlayer? = null
    private var channel: EmbeddedChannel? = null
    private var arena: CorpusArena? = null
    private var index = 0
    private var ticks = 0
    private var caseTicks = 0
    private var done = false
    private var future: CompletableFuture<JsonObject>? = null
    private val files = mutableListOf<JsonObject>()
    private val policy = ContextPolicy(1, OperationType.entries.toSet(), emptySet(), emptySet(), 72000, 16, 0)

    @SubscribeEvent
    fun tick(event: TickEvent.ServerTickEvent) {
        if (!enabled || done || event.phase != TickEvent.Phase.END) return
        val server = event.server
        try {
            check(server.isDedicatedServer && !LlmConfig.snapshot().values.enabled)
            check(++ticks < 12000) { "Corpus capture timed out" }
            if (actor == null) { initialize(server); return }
            val pending = future
            if (pending != null) {
                if (!pending.isDone) return
                files.add(pending.join()); future = null
                if (++index == CorpusFixtures.rows.size) {
                    Files.writeString(output.resolve("manifest.json"), GsonBuilder().setPrettyPrinting().create().toJson(CorpusRequestArchive.manifest(files)) + "\n")
                    Files.writeString(Path.of("corpus-capture-result.txt"), "PASS cases=$index nativePublicSnapshots=true pairedIdenticalState=true providerCalls=0\n")
                    done = true; server.halt(false); return
                }
            }
            val player = checkNotNull(actor)
            val row = CorpusFixtures.rows[index]
            if (arena == null) {
                arena = CorpusArena(server, player, CorpusFixtures.setup(row))
                caseTicks = 0
                return
            }
            val fixture = checkNotNull(arena)
            if (++caseTicks < 4) return
            val running = fixture.setup.getAsJsonObject("runningOperation")
            if (running != null && caseTicks == 4) {
                val order = OperationDocumentApi.decodeOrder(running.toString())
                check(order is OperationDocumentResult.Accepted)
                val tick = player.serverLevel().gameTime
                val assignment = OperationSupervisionApi.assign(server, player, fixture.handle.npcUuid,
                    OperationAssignmentRequest(null, tick, tick + 100, order.value))
                check(assignment.result.status == NpcActionStatus.SUCCEEDED) { "Frozen running task could not start: " + assignment.result }
                return
            }
            val notes = fixture.setup.getAsJsonArray("memoryNotes")?.flatMap { it.asString.chunked(256) } ?: emptyList()
            check(notes.size <= 8)
            val mode = row["mode"]?.asString?.let(LlmMode::valueOf) ?: LlmMode.TRANSLATOR
            val goal = ContextGoal(UUID.randomUUID(), 1, row["goal"].asString, mode, null, 24, ContextMemory(plan = notes))
            val captured = NpcContextBuilder.capture(server, player, fixture.handle.npcUuid, goal, policy)
            check(captured is ContextCaptureResult.Captured) { captured.toString() }
            for ((item, amount) in (fixture.setup.getAsJsonObject("inventory") ?: JsonObject()).entrySet()) {
                val actual = captured.value.inspection.body.inventory.sumOf { if (it.stack.itemId == item) it.stack.count else 0 }
                check(actual == amount.asInt) { "Frozen initial stock mismatch for $item" }
            }
            if (running != null) check(captured.value.inspection.operation.task?.state == OperationTaskState.RUNNING)
            val source = captured.value
            val setup = fixture.setup.deepCopy()
            val caseIndex = index
            fixture.close(); arena = null
            future = CompletableFuture.supplyAsync { CorpusRequestArchive.save(output, caseIndex, row, setup, source) }
        } catch (failure: Exception) {
            Files.writeString(Path.of("corpus-capture-result.txt"), "FAIL index=$index\n" + failure.stackTraceToString())
            done = true; server.halt(false)
        }
    }

    private fun initialize(server: MinecraftServer) {
        check(!Files.exists(output)) { "Capture needs a fresh run directory; preserve earlier evidence" }
        Files.createDirectories(output)
        val connection = Connection(PacketFlow.SERVERBOUND)
        val embedded = EmbeddedChannel(connection)
        connection.setProtocol(ConnectionProtocol.PLAY)
        val player = ServerPlayer(server, server.overworld(), GameProfile(UUID.randomUUID(), "CorpusCapture"))
        server.playerList.placeNewPlayer(connection, player)
        player.setGameMode(GameType.SPECTATOR)
        server.gameRules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server)
        server.gameRules.getRule(GameRules.RULE_DAYLIGHT).set(false, server)
        actor = player; channel = embedded
    }

    @SubscribeEvent
    fun stopping(event: ServerStoppingEvent) {
        if (!enabled) return
        arena?.close(); arena = null
        actor?.let { event.server.playerList.remove(it) }
        channel?.finishAndReleaseAll()
        actor = null; channel = null
    }
}
