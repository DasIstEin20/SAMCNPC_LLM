package io.samcnpc.llm

import com.mojang.authlib.GameProfile
import io.netty.channel.embedded.EmbeddedChannel
import io.samcnpc.core.api.*
import io.samcnpc.llm.config.LlmConfig
import io.samcnpc.llm.goal.*
import net.minecraft.core.BlockPos
import net.minecraft.network.Connection
import net.minecraft.network.ConnectionProtocol
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.GameType
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.ChestBlockEntity
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.server.ServerStoppingEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** Explicit opt-in only: uses the real endpoint/profile configured in this disposable server directory. */
@Mod.EventBusSubscriber(modid = SamcnpcLlm.MOD_ID)
internal object LiveModelServerSmoke {
    private val enabled = java.lang.Boolean.getBoolean("samcnpc.liveModelSmoke")
    private var actor: ServerPlayer? = null
    private var channel: EmbeddedChannel? = null
    private var handle: NpcHandle? = null
    private var base = BlockPos.ZERO
    private var index = 0
    private var caseTicks = 0
    private var done = false
    private val evidence = mutableListOf<String>()
    private val names = listOf("missing_location", "deliver_en", "navigate_pl")

    @SubscribeEvent
    fun tick(event: TickEvent.ServerTickEvent) {
        if (!enabled || done || event.phase != TickEvent.Phase.END) return
        val server = event.server
        try {
            check(server.isDedicatedServer)
            if (actor == null) { initialize(server); return }
            val player = checkNotNull(actor)
            val controller = checkNotNull(LlmServerEvents.controller(server))
            if (handle == null) { start(server, player); return }
            val npc = checkNotNull(handle)
            val record = checkNotNull(controller.store.get(npc.npcUuid))
            check(++caseTicks < 2400) { "${names[index]} timeout: ${record.phase}/${record.code}" }
            if (record.phase in setOf(GoalPhase.QUEUED, GoalPhase.INFERENCING, GoalPhase.ADMITTING, GoalPhase.EXECUTING)) return
            if (index == 0) {
                check(record.phase == GoalPhase.ASK_USER) { "Missing-location goal: ${record.phase}/${record.code}" }
                check(record.task == null && !record.question.isNullOrBlank())
            } else {
                check(record.phase == GoalPhase.COMPLETED && record.code == "TASK_COMPLETED") {
                    "${names[index]}: ${record.phase}/${record.code} question=${record.question}"
                }
                val body = checkNotNull(CoreNpcApi.service(server).runtime(npc))
                if (index == 1) {
                    check(record.task?.operationId == "samcnpc:deliver")
                    val chest = player.serverLevel().getBlockEntity(base.offset(8, 0, 0)) as ChestBlockEntity
                    check((0 until chest.containerSize).sumOf { if (chest.getItem(it).item == Items.COBBLESTONE) chest.getItem(it).count else 0 } == 32)
                    check(body.inventoryContents().sumOf { if (it.stack.itemId == "minecraft:cobblestone") it.stack.count else 0 } == 0)
                } else {
                    check(record.task?.operationId == "samcnpc:navigate")
                    val position = body.snapshot().position
                    check(kotlin.math.abs(position.x - (base.x + 6.5)) < 2 && kotlin.math.abs(position.z - (base.z + 0.5)) < 2)
                }
            }
            evidence.add("case=${names[index]} phase=${record.phase} code=${record.code} attempts=${record.budget.settledAttempts} question=${record.question}")
            Files.writeString(Path.of("live-model-progress.txt"), evidence.joinToString("\n") + "\n")
            check(controller.stop(player, npc.npcUuid).accepted)
            check(controller.forget(player, npc.npcUuid).accepted)
            check(CoreNpcApi.service(server).dismiss(npc, NpcDismissMode.ONLY_IF_EMPTY).status == NpcActionStatus.SUCCEEDED)
            handle = null
            if (++index == names.size) {
                Files.writeString(Path.of("live-model-result.txt"), "PASS realEndpoint=true physicalDelivery=32 physicalNavigation=true\n" + evidence.joinToString("\n") + "\n")
                done = true; server.halt(false)
            }
        } catch (error: Exception) {
            Files.writeString(Path.of("live-model-result.txt"), "FAIL\n" + evidence.joinToString("\n") + "\n" + error.stackTraceToString())
            done = true; server.halt(false)
        }
    }

    private fun initialize(server: MinecraftServer) {
        val settings = LlmConfig.snapshot().values
        check(settings.enabled && settings.problem() == null && settings.inference.readiness(settings) == null) {
            "Configure and verify a real model in run-server-live-model-smoke/config/samcnpc-llm-common.toml before this opt-in test"
        }
        val level = server.overworld()
        val connection = Connection(PacketFlow.SERVERBOUND)
        channel = EmbeddedChannel(connection); connection.setProtocol(ConnectionProtocol.PLAY)
        val player = ServerPlayer(server, level, GameProfile(UUID.randomUUID(), "LiveModelSmoke"))
        server.playerList.placeNewPlayer(connection, player); player.setGameMode(GameType.SPECTATOR)
        actor = player
        base = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, level.sharedSpawnPos).offset(16, 2, 8)
        player.teleportTo(level, base.x + 4.5, base.y + 5.0, base.z + 6.5, 0F, 0F)
        for (x in -2..10) for (z in -2..2) for (y in -1..4) {
            val position = base.offset(x, y, z)
            check(level.getBlockEntity(position) == null) { "Use a fresh disposable live-model world" }
            level.setBlockAndUpdate(position, if (y == -1) Blocks.STONE.defaultBlockState() else Blocks.AIR.defaultBlockState())
        }
        level.setBlockAndUpdate(base.offset(8, 0, 0), Blocks.CHEST.defaultBlockState())
    }

    private fun start(server: MinecraftServer, player: ServerPlayer) {
        val service = CoreNpcApi.service(server)
        val spawn = NpcPosition(base.x + 0.5, base.y.toDouble(), base.z + 0.5)
        val npc = checkNotNull(service.summon(NpcSummonRequest(player.uuid, "LiveSam", "minecraft:overworld", spawn, 0F)).handle)
        handle = npc; caseTicks = 0
        if (index == 1) {
            val item = ItemEntity(player.serverLevel(), spawn.x, spawn.y, spawn.z, ItemStack(Items.COBBLESTONE, 32))
            item.setNoPickUpDelay(); check(player.serverLevel().addFreshEntity(item))
            check(checkNotNull(service.runtime(npc)).pickupItem(item.uuid).status == NpcActionStatus.SUCCEEDED)
        }
        val goal = when (index) {
            0 -> "take tools from nearest chest and get 64 wood"
            1 -> "Deliver the 32 minecraft:cobblestone in your inventory to the chest at ${base.x + 8},${base.y},${base.z} in minecraft:overworld."
            else -> "Przejdź do pozycji ${base.x + 6.5},${base.y},${base.z + 0.5} w minecraft:overworld."
        }
        val selector = when (index) { 0 -> "LiveSam"; 1 -> npc.npcUuid.toString().take(8); else -> "lives" }
        check(server.commands.dispatcher.execute("samcnpc llm goal $selector $goal", player.createCommandSourceStack()) == 1)
    }

    @SubscribeEvent
    fun stopping(event: ServerStoppingEvent) {
        if (!enabled) return
        handle?.let { npc ->
            LlmServerEvents.controller(event.server)?.stop(checkNotNull(actor), npc.npcUuid)
            LlmGoalStore.forServer(event.server).remove(npc.npcUuid)
            CoreNpcApi.service(event.server).dismiss(npc, NpcDismissMode.DROP_INVENTORY)
        }
        handle = null
        actor?.let { event.server.playerList.remove(it) }; actor = null
        channel?.finishAndReleaseAll(); channel = null
    }
}
