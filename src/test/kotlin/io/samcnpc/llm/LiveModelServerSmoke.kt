package io.samcnpc.llm

import com.mojang.authlib.GameProfile
import io.netty.channel.embedded.EmbeddedChannel
import io.samcnpc.core.api.*
import io.samcnpc.behavior.api.OperationInspectionApi
import io.samcnpc.behavior.api.OperationValue
import io.samcnpc.behavior.api.OperationType
import io.samcnpc.behavior.api.OperationWorkBox
import io.samcnpc.llm.intent.*
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
import net.minecraft.world.item.enchantment.Enchantments
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
    private val failures = mutableListOf<String>()
    private val names = listOf("missing_location", "deliver_en", "navigate_pl", "supply_pl", "lumberjack_pl", "supply_en", "supply_pl_repeat", "unsupported_bulk", "rich_deliver_en",
        "bounded_target_32_from_12_pl", "bounded_32_more_from_12_en", "bounded_oak_minimum_pl", "bounded_carried_delivery_en")

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
            if (index == 0 || index == 7) {
                check(record.phase == GoalPhase.ASK_USER) { "Missing-location goal: ${record.phase}/${record.code}" }
                check(record.task == null && !record.question.isNullOrBlank())
            } else {
                check(record.phase == GoalPhase.COMPLETED && record.code == "TASK_COMPLETED") {
                    "${names[index]}: ${record.phase}/${record.code} question=${record.question}"
                }
                val body = checkNotNull(CoreNpcApi.service(server).runtime(npc))
                if (index == 8) {
                    check(record.task?.operationId == "samcnpc:deliver")
                    val chest = player.serverLevel().getBlockEntity(base.offset(8, 0, 0)) as ChestBlockEntity
                    val delivered = (0 until chest.containerSize).map { chest.getItem(it) }.filter { it.item == Items.DIAMOND_SWORD }
                    check(delivered.sumOf { it.count } == 1 && delivered.single().enchantmentTags.size == 7)
                    val carried = checkNotNull(body.inspectBody()).inventory.filter { !it.stack.isEmpty }
                    check(carried.size == 35 && carried.all { it.stack.itemId == "minecraft:diamond_sword" && it.enchantments.size == 7 })
                    val report = checkNotNull(controller.status(player, npc.npcUuid).requestReport)
                    check(checkNotNull(report.metrics).detailLevel == 1) { "Rich model fixture did not use lossless shared facts" }
                    check(record.budget.settledAttempts == 1)
                    evidence.add("richRequest=" + report.describe().replace('\n', ' '))
                } else if (index == 1 || index == 12) {
                    check(record.task?.operationId == "samcnpc:deliver")
                    val chest = player.serverLevel().getBlockEntity(base.offset(8, 0, 0)) as ChestBlockEntity
                    check((0 until chest.containerSize).sumOf { if (chest.getItem(it).item == Items.COBBLESTONE) chest.getItem(it).count else 0 } == 32)
                    check(body.inventoryContents().sumOf { if (it.stack.itemId == "minecraft:cobblestone") it.stack.count else 0 } == 0)
                    if (index == 12) check(record.constraints != null && record.intentReservation == GoalIntentReservation(delivered = 32))
                } else if (index == 9 || index == 10) {
                    check(record.task?.operationId == "samcnpc:inventory_work")
                    val acquired = if (index == 9) 20 else 32
                    val source = player.serverLevel().getBlockEntity(base.offset(3, 0, 0)) as ChestBlockEntity
                    check((0 until source.containerSize).sumOf { if (source.getItem(it).item == Items.COBBLESTONE) source.getItem(it).count else 0 } == 64 - acquired)
                    check(body.inventoryContents().sumOf { if (it.stack.itemId == "minecraft:cobblestone") it.stack.count else 0 } == 12 + acquired)
                    check(record.constraints?.initialStock == 12 && record.intentReservation == GoalIntentReservation(acquired = acquired))
                } else if (index == 3 || index == 5 || index == 6) {
                    check(record.task?.operationId == "samcnpc:inventory_work")
                    val source = player.serverLevel().getBlockEntity(base.offset(3, 0, 0)) as ChestBlockEntity
                    check((0 until source.containerSize).sumOf { if (source.getItem(it).item == Items.COBBLESTONE) source.getItem(it).count else 0 } == 32)
                    check(body.inventoryContents().sumOf { if (it.stack.itemId == "minecraft:cobblestone") it.stack.count else 0 } == 32)
                } else if (index == 4 || index == 11) {
                    check(record.task?.operationId == "samcnpc:lumberjack")
                    val definition = checkNotNull(OperationInspectionApi.inspect(server, player, npc.npcUuid).inspection)
                        .frames.single().definition.parameters
                    val area = (definition.fields["area"] as OperationValue.Record).fields["bounds"] as OperationValue.Record
                    checkPosition(area.fields["min"], base.offset(-1, 0, -2))
                    checkPosition(area.fields["max"], base.offset(1, 4, 0))
                    val wood = definition.fields["wood"] as OperationValue.Sequence
                    val selectors = wood.values.map { (it as OperationValue.Text).value }.toSet()
                    val oakSelectors = setOf("samcnpc:oak", "minecraft:oak_log", "minecraft:oak_wood",
                        "minecraft:stripped_oak_log", "minecraft:stripped_oak_wood")
                    check(selectors.isNotEmpty() && selectors.all { it in oakSelectors })
                    check("samcnpc:oak" in selectors || "minecraft:oak_log" in selectors)
                    val chest = player.serverLevel().getBlockEntity(base.offset(3, 0, 0)) as ChestBlockEntity
                    check((0 until chest.containerSize).sumOf { if (chest.getItem(it).item == Items.OAK_LOG) chest.getItem(it).count else 0 } == 3)
                    check((0..2).all { player.serverLevel().getBlockState(base.offset(0, it, -1)).isAir })
                    if (index == 11) check(record.constraints != null && record.intentReservation == GoalIntentReservation(3, 3))
                } else {
                    check(record.task?.operationId == "samcnpc:navigate")
                    val position = body.snapshot().position
                    check(kotlin.math.abs(position.x - (base.x + 6.5)) < 2 && kotlin.math.abs(position.z - (base.z + 0.5)) < 2)
                }
            }
            evidence.add("case=${names[index]} phase=${record.phase} code=${record.code} attempts=${record.budget.settledAttempts} question=${record.question}")
            Files.writeString(Path.of("live-model-progress.txt"), evidence.joinToString("\n") + "\n")
            finishCase(server, player, npc)
        } catch (error: Exception) {
            failures.add("case=${names.getOrNull(index)} ${error.message}")
            Files.writeString(Path.of("live-model-failures.txt"), failures.joinToString("\n") + "\n" + error.stackTraceToString())
            val npc = handle
            val player = actor
            if (npc != null && player != null) finishCase(server, player, npc)
            else { Files.writeString(Path.of("live-model-result.txt"), "FAIL\n" + failures.joinToString("\n")); done = true; server.halt(false) }
        }
    }

    private fun checkPosition(value: OperationValue?, expected: BlockPos) {
        val fields = (value as OperationValue.Record).fields
        for ((axis, coordinate) in listOf("x" to expected.x, "y" to expected.y, "z" to expected.z)) {
            check((fields[axis] as OperationValue.Whole).value == coordinate.toLong()) {
                "Requested area $axis=$coordinate changed to ${fields[axis]}"
            }
        }
    }

    private fun finishCase(server: MinecraftServer, player: ServerPlayer, npc: NpcHandle) {
        val controller = checkNotNull(LlmServerEvents.controller(server))
        check(controller.stop(player, npc.npcUuid).accepted)
        check(controller.forget(player, npc.npcUuid).accepted)
        check(CoreNpcApi.service(server).dismiss(npc, NpcDismissMode.DROP_INVENTORY).status == NpcActionStatus.SUCCEEDED)
        handle = null
        // Fixtures are independent: dismissed inventories must not become supplies for the next NPC.
        player.serverLevel().getEntitiesOfClass(ItemEntity::class.java,
            net.minecraft.world.phys.AABB(base.offset(-3, -2, -3), base.offset(12, 5, 4))).forEach { it.discard() }
        if (++index == names.size) {
            val status = if (failures.isEmpty()) "PASS" else "FAIL"
            Files.writeString(Path.of("live-model-result.txt"), "$status realEndpoint=true cases=${names.size}\n" +
                evidence.joinToString("\n") + "\n" + failures.joinToString("\n") + "\n")
            done = true; server.halt(false)
        }
    }

    private fun initialize(server: MinecraftServer) {
        Files.writeString(Path.of("live-model-progress.txt"), "RUNNING\n")
        Files.writeString(Path.of("live-model-failures.txt"), "")
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
        level.setBlockAndUpdate(base.offset(3, 0, 0), Blocks.CHEST.defaultBlockState())
    }

    private fun start(server: MinecraftServer, player: ServerPlayer) {
        val service = CoreNpcApi.service(server)
        val spawn = NpcPosition(base.x + 0.5, base.y.toDouble(), base.z + 0.5)
        val npc = checkNotNull(service.summon(NpcSummonRequest(player.uuid, "LiveSam", "minecraft:overworld", spawn, 0F)).handle)
        handle = npc; caseTicks = 0
        if (index in setOf(1, 9, 10, 12)) {
            val item = ItemEntity(player.serverLevel(), spawn.x, spawn.y, spawn.z, ItemStack(Items.COBBLESTONE, if (index == 9 || index == 10) 12 else 32))
            item.setNoPickUpDelay(); check(player.serverLevel().addFreshEntity(item))
            check(checkNotNull(service.runtime(npc)).pickupItem(item.uuid).status == NpcActionStatus.SUCCEEDED)
        }
        val source = player.serverLevel().getBlockEntity(base.offset(3, 0, 0)) as ChestBlockEntity
        source.clearContent()
        if (index >= 9) (player.serverLevel().getBlockEntity(base.offset(8, 0, 0)) as ChestBlockEntity).clearContent()
        if (index == 8) repeat(36) {
            val stack = ItemStack(Items.DIAMOND_SWORD)
            for ((enchantment, rank) in listOf(Enchantments.SHARPNESS to 5, Enchantments.UNBREAKING to 3, Enchantments.MENDING to 1,
                Enchantments.MOB_LOOTING to 3, Enchantments.KNOCKBACK to 2, Enchantments.FIRE_ASPECT to 2,
                Enchantments.SWEEPING_EDGE to 3)) stack.enchant(enchantment, rank)
            val item = ItemEntity(player.serverLevel(), spawn.x, spawn.y, spawn.z, stack)
            item.setNoPickUpDelay(); check(player.serverLevel().addFreshEntity(item))
            check(checkNotNull(service.runtime(npc)).pickupItem(item.uuid).status == NpcActionStatus.SUCCEEDED)
        }
        if (index in setOf(3, 5, 6, 9, 10)) source.setItem(0, ItemStack(Items.COBBLESTONE, 64))
        if (index == 4 || index == 11) {
            val item = ItemEntity(player.serverLevel(), spawn.x, spawn.y, spawn.z, ItemStack(Items.IRON_AXE))
            item.setNoPickUpDelay(); check(player.serverLevel().addFreshEntity(item))
            check(checkNotNull(service.runtime(npc)).pickupItem(item.uuid).status == NpcActionStatus.SUCCEEDED)
            player.serverLevel().setBlockAndUpdate(base.offset(0, -1, -1), Blocks.DIRT.defaultBlockState())
            for (y in 0..2) player.serverLevel().setBlockAndUpdate(base.offset(0, y, -1), Blocks.OAK_LOG.defaultBlockState())
        }
        val goal = when (index) {
            0 -> "take tools from nearest chest and get 64 wood"
            1 -> "Deliver the 32 minecraft:cobblestone in your inventory to the chest at ${base.x + 8},${base.y},${base.z} in minecraft:overworld."
            2 -> "Przejdź do pozycji ${base.x + 6.5},${base.y},${base.z + 0.5} w minecraft:overworld."
            3, 6 -> "Pobierz 32 sztuki minecraft:cobblestone z najbliższej widocznej skrzyni do swojego ekwipunku."
            4 -> "Zbierz 3 kłody dębu w obszarze ${base.x - 1},${base.y},${base.z - 2} do ${base.x + 1},${base.y + 4},${base.z} i dostarcz do najbliższej widocznej skrzyni."
            5 -> "Take 32 minecraft:cobblestone from the nearest visible chest into your inventory."
            8 -> "Deliver exactly 1 minecraft:diamond_sword that you already carry to the chest at ${base.x + 8},${base.y},${base.z} in minecraft:overworld. Keep all other carried swords. Do not acquire or craft anything."
            9 -> "Uzupełnij swój ekwipunek DO 32 minecraft:cobblestone ze skrzyni przy (${base.x + 3},${base.y},${base.z}). Masz już 12. Nie kop i nie dostarczaj ich nigdzie."
            10 -> "Take 32 MORE minecraft:cobblestone from the chest at (${base.x + 3},${base.y},${base.z}) into your inventory, in addition to the 12 you already carry. Do not mine or deliver them elsewhere."
            11 -> "Zbierz i dostarcz co najmniej 3 dębowe kłody. Ścinaj wyłącznie dąb w obszarze (${base.x - 1},${base.y},${base.z - 2}) do (${base.x + 1},${base.y + 4},${base.z}). Dostarcz do (${base.x + 3},${base.y},${base.z}). Masz siekierę; nie pobieraj wyposażenia i nie sadź drzew."
            12 -> "Deliver exactly 32 minecraft:cobblestone that you already carry to the chest at (${base.x + 8},${base.y},${base.z}). Do not acquire or mine any new resources."
            else -> "Wyciągnij wszystko ze skrzyni w pobliżu i załóż zbroję."
        }
        val selector = when (index) { 0 -> "LiveSam"; 1 -> npc.npcUuid.toString().take(8); else -> "lives" }
        if (index < 9) check(server.commands.dispatcher.execute("samcnpc llm goal $selector $goal", player.createCommandSourceStack()) == 1)
        else {
            val short = when (index) {
                9 -> "stock_to $selector minecraft:cobblestone 32 ${base.x + 3} ${base.y} ${base.z}"
                10 -> "take_more $selector minecraft:cobblestone 32 ${base.x + 3} ${base.y} ${base.z}"
                11 -> "wood_min $selector samcnpc:oak 3 ${base.x - 1} ${base.y} ${base.z - 2} ${base.x + 1} ${base.y + 4} ${base.z} ${base.x + 3} ${base.y} ${base.z}"
                else -> "deliver_carried $selector minecraft:cobblestone 32 ${base.x + 8} ${base.y} ${base.z}"
            }
            val command = "samcnpc llm $short"
            check(command.length <= 255)
            check(server.commands.dispatcher.execute(command, player.createCommandSourceStack()) == 1)
        }
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
