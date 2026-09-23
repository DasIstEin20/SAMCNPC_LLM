package io.samcnpc.llm

import com.mojang.authlib.GameProfile
import io.netty.channel.embedded.EmbeddedChannel
import io.samcnpc.behavior.api.*
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
import net.minecraft.world.phys.AABB
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.server.ServerStoppingEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** Opt-in real provider regression. Only fixture setup seeds a known failed Behavior return. */
@Mod.EventBusSubscriber(modid = SamcnpcLlm.MOD_ID)
internal object ShowtimeServerSmoke {
    private val enabled = java.lang.Boolean.getBoolean("samcnpc.showtimeSmoke")
    private val sequenceMode = java.lang.Boolean.getBoolean("samcnpc.sequenceSmoke")
    private val boundedField = java.lang.Boolean.getBoolean("samcnpc.boundedFieldSmoke")
    private val fieldMode = java.lang.Boolean.getBoolean("samcnpc.fieldSmoke")
    private val spawn = NpcPosition(-39.5, 63.0, 78.5)
    private val chestPosition = BlockPos(-38, 63, 78)
    private val source = OperationContainers(listOf(NpcBlockPosition(-38, 63, 78)))
    private val names = if(sequenceMode) listOf("clarified_full_sequence_en") else if(fieldMode) FieldShowtimeScene.names else listOf("tools_en", "armor_after_failed_return_pl", "full_gear_en", "full_gear_pl", "planner_full_gear", "helmet_after_failed_return_en",
        "collect_all_en", "collect_all_pl", "planner_collect_all")
    private val counts = linkedMapOf("iron_axe" to 1, "iron_shovel" to 1, "iron_pickaxe" to 1,
        "iron_sword" to 1, "iron_hoe" to 1, "bow" to 1, "arrow" to 64, "dirt" to 64,
        "iron_helmet" to 1, "iron_chestplate" to 1, "iron_leggings" to 1, "iron_boots" to 1, "wheat_seeds" to 16).apply { if (sequenceMode) remove("wheat_seeds") }
    private var actor: ServerPlayer? = null
    private var channel: EmbeddedChannel? = null
    private var npc: NpcHandle? = null
    private var index = 0
    private var ticks = 0
    private var stage = "SETUP"
    private var done = false
    private val evidence = mutableListOf<String>()
    private val failures = mutableListOf<String>()
    private val case: Int get() = index % names.size
    private val failedReturn: Boolean get() = !fieldMode && (case == 1 || case == 5)

    @SubscribeEvent fun tick(event: TickEvent.ServerTickEvent) {
        if (!enabled || done || event.phase != TickEvent.Phase.END) return
        val server = event.server
        if (Files.exists(Path.of("showtime-stop"))) {
            done = true
            Files.writeString(Path.of("showtime-result.txt"), "STOPPED checkpoint requested; no success asserted\n" + evidence.joinToString("\n"))
            server.halt(false)
            return
        }
        try {
            if (actor == null) { initialize(server); return }
            val player = checkNotNull(actor)
            if (npc == null) { prepare(server, player); return }
            val handle = checkNotNull(npc)
            check(++ticks < if (sequenceMode) 12000 else 3000) { "timeout stage=$stage" }
            if (stage == "SETTLE") {
                if (ticks < 4) return
                if (failedReturn) {
                    val observation = checkNotNull(OperationSupervisionApi.observe(server, player, handle.npcUuid).observation)
                    val order = OperationInventoryOrder("minecraft:overworld",
                        OperationInventoryWork.Supply(listOf(OperationStockNeed("minecraft:iron_axe", 1, 1)), source),
                        spawn, spawn.copy(y = 64.0))
                    val reply = OperationSupervisionApi.assign(server, player, handle.npcUuid,
                        OperationAssignmentRequest(null, observation.observedTick, observation.observedTick + 100, order))
                    check(reply.result.status == NpcActionStatus.SUCCEEDED)
                    stage = "SEED_FAILURE"
                } else request(server, player, handle)
                return
            }
            if (stage == "SEED_FAILURE") {
                val task = checkNotNull(OperationSupervisionApi.observe(server, player, handle.npcUuid).observation?.task)
                if (task.state !in setOf(OperationTaskState.COMPLETED, OperationTaskState.FAILED)) return
                check(task.state == OperationTaskState.FAILED && task.reason == "INVENTORY_INCOMPLETE") { "seed task: ${task.state}/${task.reason}" }
                check(carried(server, handle)["minecraft:iron_axe"] == 1)
                evidence.add("seededActualReturnFailure case=${names[case]} task=${task.taskId} retainedAxe=1")
                request(server, player, handle)
                return
            }
            val controller = checkNotNull(LlmServerEvents.controller(server))
            val record = checkNotNull(controller.store.get(handle.npcUuid))
            if (sequenceMode) SequenceShowtimeScene.observe(server, player, handle, record, counts)
            if (record.phase in setOf(GoalPhase.QUEUED, GoalPhase.INFERENCING, GoalPhase.ADMITTING, GoalPhase.EXECUTING)) return
            if (sequenceMode) {
                evidence.add("PASS " + SequenceShowtimeScene.verify(server, player, handle, record))
                retire(server, player); return
            }
            if(fieldMode) {
                val proof=FieldShowtimeScene.verify(server,player,handle,record,case)
                evidence.add("PASS case=${names[case]} repetition=${index/names.size+1} attempts=1 $proof " +
                    controller.status(player,handle.npcUuid).requestReport?.describe()?.replace('\n',' '))
                retire(server,player);return
            }
            val expected = if (case == 4 || case == 8) record.phase == GoalPhase.ASK_USER && record.code == "PLAN_CONFIRMATION_REQUIRED"
                else record.phase == GoalPhase.COMPLETED && record.code == "TASK_COMPLETED"
            check(expected) {
                val task = OperationSupervisionApi.observe(server, player, handle.npcUuid).observation?.task
                "${record.phase}/${record.code} question=${record.question} task=$task"
            }
            check(record.budget.settledAttempts == 1) { "unnecessary repair/clarification attempts=${record.budget.settledAttempts}" }
            val task = checkNotNull(OperationSupervisionApi.observe(server, player, handle.npcUuid).observation?.task)
            check(task.state == OperationTaskState.COMPLETED)
            check(task.frames.first().operationId == "samcnpc:inventory_work")
            val inspection = checkNotNull(OperationInspectionApi.inspect(server, player, handle.npcUuid).inspection)
            val work = inspection.frames.first().definition.parameters.fields["work"] as OperationValue.Record
            check((work.fields["kind"] as OperationValue.Text).value == if (case >= 6) "COLLECT" else "SUPPLY")
            val expectedItems = requested().mapKeys { "minecraft:" + it.key }.toMutableMap()
            if (failedReturn) expectedItems["minecraft:iron_axe"] = 1
            check(carried(server, handle) == expectedItems) { "carried=${carried(server, handle)} expected=$expectedItems" }
            val chest = player.serverLevel().getBlockEntity(chestPosition) as ChestBlockEntity
            for ((offset, entry) in counts.entries.withIndex()) {
                val remaining = entry.value - (expectedItems["minecraft:" + entry.key] ?: 0)
                check(chest.getItem(offset).count == remaining) { "unexpected source delta for ${entry.key}" }
            }
            val body = checkNotNull(CoreNpcApi.service(server).runtime(handle))
            check(kotlin.math.abs(body.snapshot().position.y - spawn.y) < 0.1)
            evidence.add("PASS case=${names[case]} repetition=${index / names.size + 1} attempts=1 items=$expectedItems phase=${record.phase} " +
                controller.status(player, handle.npcUuid).requestReport?.describe()?.replace('\n', ' '))
            retire(server, player)
        } catch (error: Exception) {
            failures.add("FAIL case=${names[case]} repetition=${index / names.size + 1} ${error.message}")
            Files.writeString(Path.of("showtime-failures.txt"), failures.joinToString("\n") + "\n" + error.stackTraceToString())
            val player = actor
            val handle = npc
            if (player != null && handle != null) {
                val snapshot = OperationInspectionApi.inspect(server, player, handle.npcUuid)
                Files.writeString(Path.of("showtime-failure-$index.json"), com.google.gson.GsonBuilder().serializeNulls()
                    .setPrettyPrinting().create().toJson(snapshot) + "\n")
            }
            if (player != null && npc != null) retire(server, player)
            else { done = true; Files.writeString(Path.of("showtime-result.txt"), failures.joinToString("\n")); server.halt(false) }
        }
    }

    private fun requested(): Map<String, Int> = when (case) {
        0 -> counts.filterKeys { it in setOf("iron_axe", "iron_shovel", "iron_pickaxe", "iron_sword", "iron_hoe") }
        1 -> counts.filterKeys { it in setOf("iron_helmet", "iron_chestplate", "iron_leggings", "iron_boots") }
        5 -> mapOf("iron_helmet" to 1)
        else -> counts
    }

    private fun request(server: MinecraftServer, player: ServerPlayer, handle: NpcHandle) {
        val items = requested().entries.joinToString(", ") { "${it.value} minecraft:${it.key}" }
        val goal = if(sequenceMode) SequenceShowtimeScene.goal else if(fieldMode) FieldShowtimeScene.goal(case) else if (case == 7)
            "Zabierz wszystko ze skrzyni (-38,63,78) do swojego ekwipunku. Zachowaj rzeczy w plecaku; nie zakładaj zbroi."
        else if (case == 6 || case == 8)
            "Take everything from the chest at (-38,63,78) into your inventory. Carry the items; do not equip armor."
        else if (case == 1 || case == 3)
            "Pobierz ze skrzyni (-38,63,78) do swojego ekwipunku: $items. Zachowaj je w plecaku; nie zakładaj zbroi."
        else "Take $items from the chest at (-38,63,78) into your inventory. Carry these items; do not equip armor."
        val reply = checkNotNull(LlmServerEvents.controller(server)).start(player, handle.npcUuid, goal,
            player.serverLevel().gameTime, planner = if(sequenceMode) true else if(fieldMode) case == 2 else case == 4 || case == 8,
            constraints = if (fieldMode && boundedField && case != 3) FieldShowtimeScene.constraints() else null)
        check(reply.accepted) { reply.code }
        stage = "INFERENCE"
    }

    private fun carried(server: MinecraftServer, handle: NpcHandle): Map<String, Int> =
        checkNotNull(CoreNpcApi.service(server).runtime(handle)).inventoryContents().filter { !it.stack.isEmpty }
            .groupBy { checkNotNull(it.stack.itemId) }.mapValues { (_, rows) -> rows.sumOf { it.stack.count } }

    private fun prepare(server: MinecraftServer, player: ServerPlayer) {
        val items = listOf(Items.IRON_AXE, Items.IRON_SHOVEL, Items.IRON_PICKAXE, Items.IRON_SWORD, Items.IRON_HOE,
            Items.BOW, Items.ARROW, Items.DIRT, Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS,
            Items.IRON_BOOTS, Items.WHEAT_SEEDS)
        val chest = player.serverLevel().getBlockEntity(chestPosition) as ChestBlockEntity
        chest.clearContent()
        counts.values.forEachIndexed { slot, amount -> chest.setItem(slot, ItemStack(items[slot], amount)) }
        npc = checkNotNull(CoreNpcApi.service(server).summon(NpcSummonRequest(player.uuid, "ShowtimeProbe",
            "minecraft:overworld", spawn, 0F)).handle)
        if(fieldMode) FieldShowtimeScene.prepare(server,player,checkNotNull(npc))
        ticks = 0; stage = "SETTLE"
    }

    private fun initialize(server: MinecraftServer) {
        check(server.isDedicatedServer)
        val settings = LlmConfig.snapshot().values
        check(settings.enabled && settings.problem() == null && settings.inference.readiness(settings) == null)
        val level = server.overworld()
        val connection = Connection(PacketFlow.SERVERBOUND)
        channel = EmbeddedChannel(connection); connection.setProtocol(ConnectionProtocol.PLAY)
        val player = ServerPlayer(server, level, GameProfile(UUID.randomUUID(), "ShowtimeProbe"))
        server.playerList.placeNewPlayer(connection, player); player.setGameMode(GameType.SPECTATOR)
        actor = player
        player.teleportTo(level, -39.5, 66.0, 80.5, 0F, 0F)
        for (x in -44..-33) for (z in 74..83) for (y in 62..67) {
            val cell = BlockPos(x, y, z)
            check(level.getBlockEntity(cell) == null) { "Use a fresh disposable showtime world" }
            level.setBlockAndUpdate(cell, if (y == 62) Blocks.STONE.defaultBlockState() else Blocks.AIR.defaultBlockState())
        }
        if (sequenceMode) SequenceShowtimeScene.initialize(player)
        level.setBlockAndUpdate(chestPosition, Blocks.CHEST.defaultBlockState())
        Files.writeString(Path.of("showtime-progress.txt"), "RUNNING realEndpoint=true repetitions=${if(sequenceMode) 1 else 3} boundedField=$boundedField\n")
    }

    private fun retire(server: MinecraftServer, player: ServerPlayer) {
        val handle = checkNotNull(npc)
        val controller = checkNotNull(LlmServerEvents.controller(server))
        check(controller.stop(player, handle.npcUuid).accepted)
        check(controller.forget(player, handle.npcUuid).accepted)
        check(CoreNpcApi.service(server).dismiss(handle, NpcDismissMode.DROP_INVENTORY).status == NpcActionStatus.SUCCEEDED)
        npc = null
        player.serverLevel().getEntitiesOfClass(ItemEntity::class.java, AABB(-44.0, 61.0, 74.0, -32.0, 68.0, 84.0)).forEach { it.discard() }
        Files.writeString(Path.of("showtime-progress.txt"), evidence.joinToString("\n") + "\n")
        if (++index == names.size * if (sequenceMode) 1 else 3) {
            done = true
            Files.writeString(Path.of("showtime-result.txt"), "${if (failures.isEmpty()) "PASS" else "FAIL"} realEndpoint=true cases=$index boundedField=$boundedField\n" +
                evidence.joinToString("\n") + "\n" + failures.joinToString("\n"))
            server.halt(false)
        }
    }

    @SubscribeEvent fun stopping(event: ServerStoppingEvent) {
        if (!enabled) return
        actor?.let { event.server.playerList.remove(it) }; actor = null
        channel?.finishAndReleaseAll(); channel = null
    }
}
