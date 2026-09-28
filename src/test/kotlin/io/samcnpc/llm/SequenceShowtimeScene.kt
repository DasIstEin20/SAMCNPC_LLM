package io.samcnpc.llm

import io.samcnpc.behavior.api.*
import io.samcnpc.core.api.*
import io.samcnpc.llm.goal.*
import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.ChestBlockEntity
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** Clarified disposable fixture: extra pickaxe, output chest and explicit tunnel geometry.
 * Only initialize mutates it; observations never inject or repair the model's operations. */
internal object SequenceShowtimeScene {
    private val calls = linkedMapOf<UUID, Int>()
    private val completed = linkedMapOf<UUID, String>()
    private var lowestY = 63.0
    private val operations = listOf("samcnpc:inventory_work", "samcnpc:lumberjack", "samcnpc:mine", "samcnpc:prepare_field")
    val goal = "Take everything from chest (-38,63,78); it includes a pickaxe. Carry armor, do not equip. " +
        "Then cut 32 oak logs only in (-34,63,83)..(-23,68,89) and deliver to that chest. " +
        "Then excavate the complete descending tunnel: origin (-33,63,76), EAST, width1, height3, length13, stepDown1; " +
        "area (-33,51,76)..(-21,65,76), stone and coal_ore only. Deliver all resulting cobblestone and coal to the same chest; " +
        "need at least30 cobblestone and2 coal. Return to surface (-39.5,63,78.5). " +
        "Finally hoe soil (-37,62,80)..(-35,62,82), no sowing/harvesting/clearing, then return (-39.5,63,78.5). " +
        "Use anchor (-39.5,63,78.5) for all tasks. Do not enlarge work areas."

    fun initialize(player: ServerPlayer) {
        val level = player.serverLevel()
        for (x in -44..-18) for (z in 72..92) for (y in 62..71) {
            val p = BlockPos(x, y, z)
            check(level.getBlockEntity(p) == null) { "fresh disposable scene required" }
            level.setBlockAndUpdate(p, if (y == 62) Blocks.STONE.defaultBlockState() else Blocks.AIR.defaultBlockState())
        }
        for (x in -36..-18) for (z in 73..79) for (y in 49..61)
            level.setBlockAndUpdate(BlockPos(x, y, z), Blocks.STONE.defaultBlockState())
        for (y in listOf(51, 53)) level.setBlockAndUpdate(BlockPos(-21, y, 76), Blocks.COAL_ORE.defaultBlockState())
        for (x in listOf(-33, -30, -27, -24)) for (z in listOf(84, 88)) {
            level.setBlockAndUpdate(BlockPos(x, 62, z), Blocks.DIRT.defaultBlockState())
            for (y in 63..66) level.setBlockAndUpdate(BlockPos(x, y, z), Blocks.OAK_LOG.defaultBlockState())
        }
        for (x in -38..-35) for (z in 80..82)
            level.setBlockAndUpdate(BlockPos(x, 62, z), Blocks.DIRT.defaultBlockState())
        level.setBlockAndUpdate(BlockPos(-34, 62, 81), Blocks.WATER.defaultBlockState())
        Files.writeString(Path.of("sequence-goal.txt"), "CLARIFIED_FIXTURE_NOT_ORIGINAL_GUI_WORLD\n$goal\n")
    }

    fun observe(server: MinecraftServer, player: ServerPlayer, handle: NpcHandle, record: GoalRecord, gear: Map<String, Int>) {
        val npc = checkNotNull(CoreNpcApi.service(server).runtime(handle))
        lowestY = minOf(lowestY, npc.snapshot().position.y)
        val task = OperationSupervisionApi.observe(server, player, handle.npcUuid).observation?.task ?: return
        if (record.phase == GoalPhase.EXECUTING) {
            val previous = calls.putIfAbsent(task.taskId, record.budget.settledAttempts)
            check(previous == null || previous == record.budget.settledAttempts) { "inference during healthy task" }
        }
        check(task.state != OperationTaskState.FAILED) { "${task.frames.first().operationId}: ${task.reason} ${task.detail}" }
        if (task.state != OperationTaskState.COMPLETED || task.taskId in completed) return
        val operation = task.frames.first().operationId
        check(operation == operations.getOrNull(completed.size)) { "unexpected step: $operation after $completed" }
        val inspection = checkNotNull(OperationInspectionApi.inspect(server, player, handle.npcUuid).inspection)
        if (completed.isEmpty()) {
            val items = npc.inventoryContents().filter { !it.stack.isEmpty }.groupBy { it.stack.itemId }
                .mapValues { (_, rows) -> rows.sumOf { it.stack.count } }
            check(items == gear.mapKeys { "minecraft:${it.key}" }) { "collection mismatch: $items" }
            val work = inspection.frames.first().definition.parameters.fields["work"] as OperationValue.Record
            check((work.fields["kind"] as OperationValue.Text).value == "COLLECT")
        }
        if (operation == "samcnpc:mine") {
            check(lowestY <= 55.0) { "no physical descent: $lowestY" }
            check(npc.snapshot().position.y >= 62.9) { "mining did not return to surface" }
        }
        completed[task.taskId] = operation
        Files.writeString(Path.of("sequence-step-${completed.size}.json"),
            com.google.gson.GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(inspection))
        Files.writeString(Path.of("sequence-progress.txt"), "completed=$completed calls=$calls lowestY=$lowestY\n")
    }

    fun verify(server: MinecraftServer, player: ServerPlayer, handle: NpcHandle, record: GoalRecord, missionV2: Boolean = false): String {
        check(if (missionV2) record.phase == GoalPhase.COMPLETED && record.code == "MISSION_COMPLETED"
            else record.phase == GoalPhase.ASK_USER && record.code == "PLAN_CONFIRMATION_REQUIRED") {
            "${record.phase}/${record.code}: ${record.question}; completed=$completed"
        }
        check(completed.values.toList() == operations && record.planStepsCompleted == 4) { "completed=$completed" }
        check(record.budget.settledAttempts == (if (missionV2) 6 else 4) && calls.size == 4) { "unexpected calls=${record.budget}" }
        if (missionV2) {
            val mission = checkNotNull(record.mission)
            val inspection = checkNotNull(OperationInspectionApi.inspect(server, player, handle.npcUuid).inspection)
            val evaluation = io.samcnpc.llm.mission.MissionObservation.evaluate(server, player, handle.npcUuid, mission, inspection)
            check(evaluation.complete && checkNotNull(mission.plan).problem(checkNotNull(mission.contract)) == null)
            Files.writeString(Path.of("sequence-mission.json"), io.samcnpc.llm.mission.MissionCodec.encode(checkNotNull(mission.contract)).toString())
        }
        val level = player.serverLevel()
        val chest = level.getBlockEntity(BlockPos(-38, 63, 78)) as ChestBlockEntity
        val stock = (0 until chest.containerSize).map(chest::getItem).filter { !it.isEmpty }
        check(stock.filter { it.`is`(Items.OAK_LOG) }.sumOf { it.count } == 32)
        check(stock.filter { it.`is`(Items.COBBLESTONE) }.sumOf { it.count } == 31)
        check(stock.filter { it.`is`(Items.COAL) }.sumOf { it.count } == 2)
        check(stock.all { it.`is`(Items.OAK_LOG) || it.`is`(Items.COBBLESTONE) || it.`is`(Items.COAL) })
        for (step in 0..12) {
            for (height in 0..2) check(level.getBlockState(BlockPos(-33 + step, 63 - step + height, 76)).isAir)
            check(level.getBlockState(BlockPos(-33 + step, 62 - step, 76)).`is`(Blocks.STONE))
        }
        for (x in listOf(-33, -30, -27, -24)) for (z in listOf(84, 88)) for (y in 63..66)
            check(level.getBlockState(BlockPos(x, y, z)).isAir)
        for (x in -37..-35) for (z in 80..82) {
            check(level.getBlockState(BlockPos(x, 62, z)).`is`(Blocks.FARMLAND))
            check(level.getBlockState(BlockPos(x, 63, z)).isAir)
        }
        check(level.getBlockState(BlockPos(-38, 62, 80)).`is`(Blocks.DIRT))
        val npc = checkNotNull(CoreNpcApi.service(server).runtime(handle))
        for ((id, damage) in mapOf("iron_axe" to 32, "iron_pickaxe" to 33, "iron_hoe" to 9))
            check(npc.inventoryContents().single { it.stack.itemId == "minecraft:$id" }.stack.damage == damage)
        val p = npc.snapshot().position
        check((p.x+39.5)*(p.x+39.5)+(p.y-63)*(p.y-63)+(p.z-78.5)*(p.z-78.5) <= 0.75*0.75)
        return "four physical steps; oak32 cobble31 coal2 in chest; descent=$lowestY; farmland9; exact return; healthyTaskExtraCalls=0"
    }

    /** Independent physical metrics are written even when the strict call/order gate fails. */
    fun measure(server: MinecraftServer, player: ServerPlayer, handle: NpcHandle, record: GoalRecord) {
        val level = player.serverLevel()
        val chest = level.getBlockEntity(BlockPos(-38, 63, 78)) as ChestBlockEntity
        val stock = (0 until chest.containerSize).map(chest::getItem).filter { !it.isEmpty }
            .groupBy { net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(it.item).toString() }
            .mapValues { (_, items) -> items.sumOf { it.count } }
        val npc = checkNotNull(CoreNpcApi.service(server).runtime(handle))
        val tools = npc.inventoryContents().filter { it.stack.itemId in setOf("minecraft:iron_axe", "minecraft:iron_pickaxe", "minecraft:iron_hoe") }
            .associate { checkNotNull(it.stack.itemId) to it.stack.damage }
        val feet = npc.snapshot().position
        val distance = kotlin.math.sqrt((feet.x+39.5)*(feet.x+39.5)+(feet.y-63)*(feet.y-63)+(feet.z-78.5)*(feet.z-78.5))
        var logs = 0; var tunnel = 0; var floor = 0; var farmland = 0; var fieldAir = 0
        for (x in listOf(-33, -30, -27, -24)) for (z in listOf(84, 88)) for (y in 63..66)
            if (!level.getBlockState(BlockPos(x, y, z)).isAir) logs++
        for (step in 0..12) {
            for (height in 0..2) if (!level.getBlockState(BlockPos(-33 + step, 63 - step + height, 76)).isAir) tunnel++
            if (level.getBlockState(BlockPos(-33 + step, 62 - step, 76)).`is`(Blocks.STONE)) floor++
        }
        for (x in -37..-35) for (z in 80..82) {
            if (level.getBlockState(BlockPos(x, 62, z)).`is`(Blocks.FARMLAND)) farmland++
            if (level.getBlockState(BlockPos(x, 63, z)).isAir) fieldAir++
        }
        val neighbor = level.getBlockState(BlockPos(-38, 62, 80)).`is`(Blocks.DIRT)
        val physical = stock == mapOf("minecraft:oak_log" to 32, "minecraft:cobblestone" to 31, "minecraft:coal" to 2) &&
            tools == mapOf("minecraft:iron_axe" to 32, "minecraft:iron_pickaxe" to 33, "minecraft:iron_hoe" to 9) &&
            logs == 0 && tunnel == 0 && floor == 13 && farmland == 9 && fieldAir == 9 && neighbor && distance <= 0.75 && lowestY <= 55
        val metrics = linkedMapOf<String, Any?>("scope" to "CLARIFIED_FIXTURE_INDEPENDENT_PHYSICAL_ORACLE",
            "physicalSuccess" to physical, "phase" to record.phase.name, "code" to record.code,
            "calls" to record.budget.settledAttempts, "completedOperations" to completed.values.toList(),
            "stock" to stock, "toolDamage" to tools, "logsRemaining" to logs, "tunnelCellsRemaining" to tunnel,
            "floorCellsIntact" to floor, "farmland" to farmland, "fieldAir" to fieldAir, "neighborIntact" to neighbor,
            "returnDistance" to distance, "lowestY" to lowestY, "question" to record.question,
            "mission" to record.mission?.contract?.let { io.samcnpc.llm.mission.MissionCodec.encode(it) },
            "missionPlan" to record.mission?.plan?.let { io.samcnpc.llm.mission.MissionCodec.encode(it) },
            "v1RemainingPlan" to record.memory.plan)
        Files.writeString(Path.of("sequence-metrics.json"), com.google.gson.GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(metrics))
    }
}
