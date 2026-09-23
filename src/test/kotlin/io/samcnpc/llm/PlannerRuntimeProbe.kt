package io.samcnpc.llm

import com.google.gson.*
import io.samcnpc.behavior.api.*
import io.samcnpc.core.api.*
import io.samcnpc.llm.config.*
import io.samcnpc.llm.goal.*
import io.samcnpc.llm.provider.*
import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.ChestBlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/** Actual commands and real resources; the emulator captures immutable operation strings only. */
internal class PlannerRuntimeProbe(private val server: MinecraftServer, private val actor: ServerPlayer,
    origin: NpcPosition, private val canFinish: (UUID) -> Boolean = { true }) : GoalRuntimeProbe {
    private val original = LlmConfig.snapshot().values
    private val level = actor.serverLevel()
    private val base = BlockPos.containing(origin.x + 16, origin.y, origin.z + 8)
    private val source = base
    private val destination = base.offset(8, 0, 0)
    private val spawn = NpcPosition(base.x + 6.5, base.y.toDouble(), base.z + 0.5)
    private val home = NpcPosition(base.x + 2.5, base.y.toDouble(), base.z + 1.5)
    private val saved = linkedMapOf<BlockPos, BlockState>()
    private val service = CoreNpcApi.service(server)
    private val script = Script(operations())
    private val endpoint = FakeOpenAiEndpoint(script::reply)
    private val settings = ProviderSettings(enabled = true, baseUrl = endpoint.baseUrl, model = "planner-emulator",
        apiKeyEnvironment = "", requestTimeoutSeconds = 4,
        inference = InferenceSettings(true, endpoint.baseUrl, "planner-emulator", "emulator-v1",
            "scripted-fixture", "test-byte-bound", "test-template", 256, 131072, 63488))
    private var index = 0
    private var handle: NpcHandle? = null
    private var ticks = 0
    private var caseTicks = 0
    private var callsBefore = 0
    private var stage = 0
    private var changedAt = 0
    private var taskId: UUID? = null
    private var restored = false
    private var closed = false
    private var result: String? = null
    private var farmDisturbed = false
    private var farmReceiptVerified = false
    private val taskCalls = linkedMapOf<UUID, Int>()

    init {
        for (x in -2..12) for (z in -3..3) for (y in -1..5) {
            val p = base.offset(x, y, z)
            check(level.getBlockEntity(p) == null)
            saved[p] = level.getBlockState(p)
        }
        check(level.getEntitiesOfClass(ItemEntity::class.java, bounds()).isEmpty())
        for (p in saved.keys) level.setBlockAndUpdate(p, if (p.y == base.y - 1) Blocks.STONE.defaultBlockState() else Blocks.AIR.defaultBlockState())
        configure(settings)
    }

    override fun renderView(): Pair<UUID, String>? = handle?.let { it.npcUuid to CASES[index] }

    override fun poll(): String? {
        result?.let { return it }
        check(++ticks < 9000) { "Planner campaign timeout" }
        val controller = checkNotNull(LlmServerEvents.controller(server))
        if (index == CASES.size) {
            if (!restored) { configure(original); restored = true; return null }
            if (controller.settings != original) return null
            check(endpoint.received.size == 11)
            result = "plannerCases=6 plannerHttpCalls=11 plannerMaxRequestBytes=" + script.maxBytes.get() +
                " physicalFoodWoodInventoryReturn=true physicalMiningDelivery=true physicalFarmReplantAndHoeRecovery=true freshInventoryRejected=true failedStepAsked=true" +
                " providerOfflineHeld=true manualPauseResumed=true cancelNoLateAssign=true healthyTaskExtraCalls=0 userConfirmationRequired=true"
            close()
            return result
        }
        if (handle == null) {
            if (controller.settings != settings) return null
            prepare()
            return null
        }
        val npc = checkNotNull(handle)
        val name = CASES[index]
        val record = checkNotNull(controller.store.get(npc.npcUuid))
        check(++caseTicks < 1800) { name + " " + record.phase + "/" + record.code + " calls=" + (endpoint.received.size - callsBefore) }
        if (caseTicks % 100 == 0) java.nio.file.Files.writeString(java.nio.file.Path.of("planner-progress.txt"),
            name + " phase=" + record.phase + " code=" + record.code + " steps=" + record.planStepsCompleted + " calls=" + (endpoint.received.size - callsBefore))
        val view = checkNotNull(OperationSupervisionApi.observe(server, actor, npc.npcUuid).observation)
        if (name == "supplies" && view.task?.frames?.firstOrNull()?.operationId == "samcnpc:farm") {
            val crop = base.offset(4, 0, -2)
            if (!farmDisturbed && view.task?.state == OperationTaskState.RUNNING && level.getBlockState(crop).isAir) {
                // An external trampling incident, as in Behavior's terrain zoo. Behavior
                // must select the hoe and repair the soil before physically replanting.
                level.setBlockAndUpdate(crop.below(), Blocks.DIRT.defaultBlockState())
                farmDisturbed = true
            }
            if (view.task?.state == OperationTaskState.COMPLETED && !farmReceiptVerified) {
                val inspection = checkNotNull(OperationInspectionApi.inspect(server, actor, npc.npcUuid).inspection)
                val ledger = inspection.frames.single().resources as OperationResourceInspection.Checkpoint
                check(!ledger.uncertain && !ledger.reconciliationRequired)
                val seeds = ledger.items.single { it.itemId == "minecraft:wheat_seeds" }.counters.associate { it.name to it.value }
                check(seeds.getValue("initial") == 1L && seeds.getValue("consumed") == 1L)
                check(seeds.getValue("supplied") == 0L && seeds.getValue("delivered") == 0L && seeds.getValue("lost") == 0L)
                check(seeds.getValue("initial") + seeds.getValue("gathered") - seeds.getValue("consumed") ==
                    carried(npc, "minecraft:wheat_seeds").toLong())
                check(farmDisturbed && count(destination, Items.WHEAT) == 1)
                farmReceiptVerified = true
            }
        }
        if (record.phase == GoalPhase.EXECUTING) {
            val id = checkNotNull(record.task).id
            val firstCalls = taskCalls.putIfAbsent(id, endpoint.received.size)
            check(firstCalls == null || firstCalls == endpoint.received.size) { "Inference during healthy execution" }
            check(!controller.complete(actor, npc.npcUuid).accepted)
        }
        when (name) {
            "stale" -> {
                if (stage == 0 && script.calls.get() > callsBefore) {
                    val runtime = checkNotNull(service.runtime(npc))
                    val slot = runtime.inventoryContents().first { it.stack.itemId == "minecraft:cobblestone" }.slot
                    check(runtime.dropInventoryStack(slot, 1).status == NpcActionStatus.SUCCEEDED)
                    for (item in level.getEntitiesOfClass(ItemEntity::class.java, bounds())) item.discard()
                    check(carried(npc, "minecraft:cobblestone") == 0)
                    script.gate.countDown(); stage = 1
                }
                if (record.phase != GoalPhase.ASK_USER) return null
                check(record.code == "PLAN_STEP_REJECTED" && view.task == null && record.planStepsCompleted == 0)
                check(record.memory.results.isEmpty())
            }
            "cancel" -> {
                if (stage == 0 && script.calls.get() > callsBefore) {
                    check(command("stop", npc) == 1); script.gate.countDown(); stage = 1; changedAt = caseTicks
                }
                if (stage == 0 || record.budget.inFlight != null || caseTicks - changedAt < 20) return null
                check(record.phase == GoalPhase.STOPPED && view.task == null && record.memory.results.isEmpty())
            }
            "failed" -> {
                if (record.phase != GoalPhase.ASK_USER) return null
                check(record.code == "PLAN_STEP_FAILED" && record.planStepsCompleted == 0)
                check(view.task?.state == OperationTaskState.FAILED)
                check(record.memory.results.single().endsWith("TASK_FAILED"))
                check(count(source, Items.APPLE) == 0 && count(destination, Items.APPLE) == 0)
            }
            "offline" -> {
                if (stage == 0 && record.phase == GoalPhase.EXECUTING) {
                    taskId = record.task?.id; configure(settings.copy(enabled = false)); stage = 1
                }
                if (!record.manualHold || record.phase != GoalPhase.WAITING) return null
                check(record.code == "LLM_DISABLED" && record.planStepsCompleted == 1)
                check(record.memory.results.size == 1 && record.memory.plan.size == 5)
                check(record.budget.settledAttempts == 1 && record.budget.chargedInputTokens == 63488L)
                check(view.task?.taskId == taskId && view.task?.state == OperationTaskState.COMPLETED)
                check(count(destination, Items.APPLE) == 4 && count(source, Items.APPLE) == 28)
            }
            "manual" -> {
                if (stage == 0 && record.phase == GoalPhase.EXECUTING) {
                    val task = checkNotNull(view.task); taskId = task.taskId
                    check(OperationSupervisionApi.control(server, actor, npc.npcUuid,
                        OperationControlRequest(task.taskId, task.controlRevision, task.definitionRevision,
                            view.observedTick, view.observedTick + 100, OperationControl.PAUSE)).result.status == NpcActionStatus.SUCCEEDED)
                    changedAt = caseTicks; stage = 1
                }
                if (stage == 1) {
                    if (caseTicks - changedAt < 20) return null
                    check(record.manualHold && view.task?.state == OperationTaskState.PAUSED)
                    check(command("resume", npc) == 1); stage = 2
                    return null
                }
                if (record.phase != GoalPhase.ASK_USER) return null
                check(record.code == "PLAN_CONFIRMATION_REQUIRED" && record.planStepsCompleted == 1)
                check(view.task?.taskId == taskId && record.memory.results.size == 1)
                check(atHome(npc))
            }
            "supplies" -> {
                if (record.phase != GoalPhase.ASK_USER) return null
                check(record.code == "PLAN_CONFIRMATION_REQUIRED" && record.planStepsCompleted == 6) {
                    "${record.code} steps=${record.planStepsCompleted} question=${record.question} " +
                        "task=${view.task?.state}/${view.task?.reason} detail=${view.task?.detail}"
                }
                check(record.memory.plan.isEmpty() && record.memory.results.size == 6 && taskCalls.size == 6)
                check(record.memory.results.all { it.endsWith("TASK_COMPLETED") })
                check(carried(npc, "minecraft:apple") == 4 && carried(npc, "minecraft:oak_log") == 3)
                check(count(source, Items.APPLE) == 28 && count(destination, Items.APPLE) == 0 && count(destination, Items.OAK_LOG) == 0)
                check((0..2).all { level.getBlockState(base.offset(0, it, -2)).isAir })
                check(level.getBlockState(base.offset(4, 0, 2)).isAir && count(destination, Items.COBBLESTONE) == 1)
                check(level.getBlockState(base.offset(4, -1, -2)).`is`(Blocks.FARMLAND))
                check(level.getBlockState(base.offset(4, 0, -2)).`is`(Blocks.WHEAT))
                check(farmReceiptVerified)
                check(atHome(npc))
            }
        }
        check(endpoint.received.size - callsBefore == if (name == "supplies") 6 else 1)
        if (record.budget.inFlight != null || !canFinish(npc.npcUuid)) return null
        if (name in setOf("supplies", "manual")) {
            check(command("complete", npc) == 1)
            val finished = checkNotNull(controller.store.get(npc.npcUuid))
            check(finished.phase == GoalPhase.COMPLETED && finished.code == "USER_CONFIRMED_GOAL")
            check(finished.budget == record.budget)
        } else if (record.phase != GoalPhase.STOPPED) check(command("stop", npc) == 1)
        check(command("forget", npc) == 1)
        check(service.dismiss(npc, NpcDismissMode.DROP_INVENTORY).status == NpcActionStatus.SUCCEEDED)
        for (item in level.getEntitiesOfClass(ItemEntity::class.java, bounds())) item.discard()
        handle = null; index++; configure(settings)
        return null
    }

    private fun prepare() {
        chest(source).clearContent(); chest(destination).clearContent()
        val name = CASES[index]
        if (name in setOf("supplies", "offline")) chest(source).setItem(0, ItemStack(Items.APPLE, 32))
        for (y in 0..2) level.setBlockAndUpdate(base.offset(0, y, -2),
            if (name == "supplies") Blocks.OAK_LOG.defaultBlockState() else Blocks.AIR.defaultBlockState())
        level.setBlockAndUpdate(base.offset(0, -1, -2), Blocks.DIRT.defaultBlockState())
        level.setBlockAndUpdate(base.offset(4, 0, 2), if (name == "supplies") Blocks.STONE.defaultBlockState() else Blocks.AIR.defaultBlockState())
        level.setBlockAndUpdate(base.offset(4, 0, -2), Blocks.AIR.defaultBlockState())
        level.setBlockAndUpdate(base.offset(4, -1, -2), Blocks.FARMLAND.defaultBlockState())
        level.setBlockAndUpdate(base.offset(5, -1, -2), Blocks.WATER.defaultBlockState())
        if (name == "supplies") {
            val wheat = Blocks.WHEAT as net.minecraft.world.level.block.CropBlock
            level.setBlockAndUpdate(base.offset(4, 0, -2), wheat.getStateForAge(wheat.maxAge))
        }
        val summoned = service.summon(NpcSummonRequest(actor.uuid, "Planner-" + name,
            level.dimension().location().toString(), spawn, 0F))
        check(summoned.result.status == NpcActionStatus.SUCCEEDED)
        val npc = checkNotNull(summoned.handle); handle = npc
        give(npc, ItemStack(Items.IRON_AXE))
        if (name == "supplies") {
            give(npc, ItemStack(Items.IRON_PICKAXE)); give(npc, ItemStack(Items.IRON_HOE))
            give(npc, ItemStack(Items.WHEAT_SEEDS))
        }
        if (name == "stale") give(npc, ItemStack(Items.COBBLESTONE))
        callsBefore = endpoint.received.size; caseTicks = 0; stage = 0; taskId = null; taskCalls.clear()
        farmDisturbed = false; farmReceiptVerified = false
        script.name = name; script.gate = CountDownLatch(if (name in setOf("stale", "cancel")) 1 else 0)
        val text = "Prepare supplies: collect 4 apples from " + source.toShortString() +
            ", collect 3 oak logs in the specified nearby tree area, deliver to " + destination.toShortString() +
            ", take these supplies, mine and deliver one cobblestone from the specified stone block," +
            " harvest and replant the specified single wheat plot and return to " + home + ". Required tools are carried."
        check(command("plan", npc, text) == 1)
    }

    private fun give(npc: NpcHandle, stack: ItemStack) {
        val item = ItemEntity(level, spawn.x, spawn.y, spawn.z, stack)
        item.setNoPickUpDelay(); item.deltaMovement = net.minecraft.world.phys.Vec3.ZERO
        check(level.addFreshEntity(item))
        check(checkNotNull(service.runtime(npc)).pickupItem(item.uuid).status == NpcActionStatus.SUCCEEDED)
    }
    private fun carried(npc: NpcHandle, id: String) = checkNotNull(service.runtime(npc)).inventoryContents()
        .sumOf { if (it.stack.itemId == id) it.stack.count else 0 }
    private fun atHome(npc: NpcHandle): Boolean {
        val p = checkNotNull(service.runtime(npc)).snapshot().position
        return (p.x-home.x)*(p.x-home.x) + (p.z-home.z)*(p.z-home.z) <= 0.75*0.75
    }
    private fun chest(pos: BlockPos): ChestBlockEntity {
        if (level.getBlockEntity(pos) !is ChestBlockEntity) level.setBlockAndUpdate(pos, Blocks.CHEST.defaultBlockState())
        return level.getBlockEntity(pos) as ChestBlockEntity
    }
    private fun count(pos: BlockPos, item: net.minecraft.world.item.Item): Int {
        val chest = chest(pos)
        return (0 until chest.containerSize).sumOf { if (chest.getItem(it).item == item) chest.getItem(it).count else 0 }
    }
    private fun command(action: String, npc: NpcHandle, text: String = "") = server.commands.dispatcher.execute(
        "samcnpc llm " + action + " " + npc.npcUuid + if (text.isEmpty()) "" else " " + text, actor.createCommandSourceStack())
    private fun configure(value: ProviderSettings) { check(LlmConfig.update(LlmConfig.snapshot().revision, value)) }
    private fun bounds() = AABB(base.offset(-3, -2, -4), base.offset(14, 7, 5))

    override fun close() {
        if (closed) return
        closed = true; script.gate.countDown(); endpoint.close()
        if (LlmConfig.snapshot().values != original) configure(original)
        handle?.let {
            LlmGoalStore.forServer(server).remove(it.npcUuid)
            if (service.runtime(it) != null) service.dismiss(it, NpcDismissMode.DROP_INVENTORY)
        }
        for (item in level.getEntitiesOfClass(ItemEntity::class.java, bounds())) item.discard()
        chest(source).clearContent(); chest(destination).clearContent()
        for ((pos, state) in saved) level.setBlockAndUpdate(pos, state)
    }

    private fun operations(): List<String> {
        fun pos(x: Number, y: Number, z: Number) = JsonObject().also { it.addProperty("x", x); it.addProperty("y", y); it.addProperty("z", z) }
        fun point(p: BlockPos) = pos(p.x, p.y, p.z)
        fun choices(p: BlockPos) = JsonObject().also { it.add("positions", JsonArray().also { a -> a.add(point(p)) }) }
        fun budget() = JsonParser.parseString("""{"ticks":1200,"attempts":2,"backoffTicks":5}""")
        fun parameters() = JsonObject().also { it.addProperty("dimensionId", level.dimension().location().toString()); it.add("budget", budget()) }
        fun order(type: String, version: Int, p: JsonObject): String {
            val root = JsonObject(); root.addProperty("documentVersion", 1); root.addProperty("type", type)
            root.addProperty("definitionVersion", version); root.add("parameters", p)
            val text = root.toString()
            val parsed = OperationDocumentApi.decodeOrder(text)
            check(parsed is OperationDocumentResult.Accepted) { parsed.toString() }
            return text
        }
        val food = parameters()
        food.add("work", JsonObject().also { it.addProperty("kind", "STORED"); it.add("sources", choices(source)) })
        food.add("outputs", JsonArray().also { it.add("minecraft:apple") }); food.add("destinations", choices(destination))
        food.addProperty("quantity", 4); food.addProperty("keepFood", 0); food.add("anchor", pos(spawn.x, spawn.y, spawn.z))
        val wood = parameters()
        wood.add("wood", JsonArray().also { it.add("minecraft:oak_log") }); wood.add("destination", point(destination)); wood.addProperty("quantity", 3)
        wood.add("area", JsonObject().also { it.add("bounds", JsonObject().also { b ->
            b.add("min", point(base.offset(-1, 0, -3))); b.add("max", point(base.offset(1, 4, -1))) }) })
        val inventory = parameters()
        val needs = JsonArray()
        for ((item, count) in listOf("minecraft:apple" to 4, "minecraft:oak_log" to 3)) needs.add(JsonObject().also {
            it.addProperty("itemId", item); it.addProperty("minimum", count); it.addProperty("target", count) })
        inventory.add("work", JsonObject().also { it.addProperty("kind", "SUPPLY"); it.add("needs", needs); it.add("sources", choices(destination)) })
        inventory.add("anchor", pos(spawn.x, spawn.y, spawn.z))
        val navigation = parameters(); navigation.add("destination", pos(home.x, home.y, home.z)); navigation.addProperty("arrivalDistance", 0.5)
        fun area(p: BlockPos) = JsonObject().also { it.add("bounds", JsonObject().also { b ->
            b.add("min", point(p)); b.add("max", point(p)) }) }
        val mining = parameters()
        mining.add("work", JsonObject().also {
            it.add("area", area(base.offset(4, 0, 2))); it.addProperty("method", "EXPOSED")
            it.add("resources", JsonArray().also { ids -> ids.add("minecraft:stone") })
        })
        mining.add("outputs", JsonArray().also { it.add("minecraft:cobblestone") })
        mining.add("destinations", choices(destination)); mining.addProperty("quantity", 1)
        mining.addProperty("counting", "DELIVERED_ITEMS"); mining.add("anchor", pos(spawn.x, spawn.y, spawn.z))
        val farm = parameters()
        farm.add("work", JsonObject().also {
            it.add("area", area(base.offset(4, 0, -2))); it.addProperty("crop", "WHEAT")
            it.addProperty("mode", "REPLANT"); it.addProperty("prepareSoil", true)
        })
        farm.add("destinations", choices(destination)); farm.addProperty("quantity", 1)
        farm.add("anchor", pos(spawn.x, spawn.y, spawn.z))
        return listOf(order("samcnpc:food", 1, food), order("samcnpc:lumberjack", 2, wood),
            order("samcnpc:inventory_work", 1, inventory), order("samcnpc:mine", 1, mining),
            order("samcnpc:farm", 1, farm), order("samcnpc:navigate", 1, navigation))
    }

    private class Script(private val orders: List<String>) {
        @Volatile var name = ""
        @Volatile var gate = CountDownLatch(0)
        val calls = AtomicInteger()
        val maxBytes = AtomicInteger()
        fun reply(received: FakeOpenAiEndpoint.Received): FakeOpenAiEndpoint.Reply {
            calls.incrementAndGet()
            val bytes = LlmJson.utf8(received.body).size
            check(bytes + 256 <= 63488)
            maxBytes.getAndUpdate { maxOf(it, bytes) }
            val http = LlmJson.parse(received.body, 65536)
            val state = LlmJson.parse(http["messages"].asJsonArray[1].asJsonObject["content"].asString, 24576)
            check(state["goal"].asJsonObject["mode"].asString == "PLANNER")
            val step = state["goal"].asJsonObject["planStepsCompleted"].asInt
            check(state["memory"].asJsonObject["confirmedResults"].asJsonArray.size() == step)
            val single = name in setOf("stale", "cancel", "manual")
            val orderIndex = if (single) orders.lastIndex else step
            check(orderIndex in orders.indices)
            val root = JsonObject()
            root.addProperty("schemaVersion", 2); root.addProperty("contextId", state["contextId"].asString)
            root.addProperty("decision", "ASSIGN"); root.addProperty("summary", "Everything is done (untrusted emulator claim)")
            for (field in listOf("change", "question", "wait")) root.add(field, JsonNull.INSTANCE)
            root.add("operation", LlmJson.parse(orders[orderIndex], 16384))
            val plan = JsonObject()
            plan.add("steps", JsonArray().also { a ->
                val descriptions = if (single) listOf("Return") else listOf("Acquire food", "Acquire wood", "Load supplies", "Mine stone", "Harvest and replant wheat", "Return").drop(step)
                descriptions.forEach(a::add)
            })
            val requirements = JsonArray()
            fun requireItem(id: String, count: Int) { requirements.add(JsonObject().also {
                it.addProperty("itemId", id); it.addProperty("minimum", count) }) }
            requireItem("minecraft:iron_axe", 1)
            if (name == "stale") requireItem("minecraft:cobblestone", 1)
            if (name == "supplies" && step == orders.lastIndex) { requireItem("minecraft:apple", 4); requireItem("minecraft:oak_log", 3) }
            plan.add("requiredItems", requirements); plan.addProperty("minimumEmptySlots", 0)
            root.add("plan", plan)
            return FakeOpenAiEndpoint.Reply(body = LlmJson.utf8(FakeOpenAiEndpoint.success(root.toString())), waitBeforeHeaders = gate)
        }
    }

    companion object {
        val CASES = listOf("supplies", "stale", "failed", "offline", "manual", "cancel")
        val MOVING = setOf("supplies", "offline", "manual")
    }
}
