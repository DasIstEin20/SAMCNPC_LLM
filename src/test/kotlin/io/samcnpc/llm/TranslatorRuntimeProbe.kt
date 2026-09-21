package io.samcnpc.llm

import com.google.gson.JsonNull
import com.google.gson.JsonObject
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/** Real registered commands, real provider configuration and actual container transfers. No model process. */
internal class TranslatorRuntimeProbe(private val server: MinecraftServer, private val actor: ServerPlayer,
                                      origin: NpcPosition,
                                      private val canFinish: (java.util.UUID) -> Boolean = { true }) : GoalRuntimeProbe {
    private val original = LlmConfig.snapshot().values
    private val level = actor.serverLevel()
    private val base = BlockPos.containing(origin.x + 16, origin.y, origin.z + 8)
    private val sourcePos = base.offset(0, 0, 0)
    private val destinationPos = base.offset(8, 0, 0)
    private val spawn = NpcPosition(base.x + 2.5, base.y.toDouble(), base.z + 0.5)
    private val savedBlocks = linkedMapOf<BlockPos, BlockState>()
    private val service = CoreNpcApi.service(server)
    private val cases = listOf("clarify", "deliver", "lumberjack", "pause", "offline", "missing", "full", "cancel")
    private val script = Script(operation(), delivery(), lumberjack())
    private val endpoint = FakeOpenAiEndpoint(script::reply)
    // Nine fault/command attempts fit the unchanged rolling quota with the single-schema JSON_OBJECT wire.
    // JSON_SCHEMA is independently exercised by DecisionHttpProbe and opt-in LiveModelServerSmoke.
    private val settings = ProviderSettings(enabled = true, baseUrl = endpoint.baseUrl, model = "translator-emulator",
        apiKeyEnvironment = "", requestTimeoutSeconds = 4, responseFormat = ResponseFormat.JSON_OBJECT,
        inference = InferenceSettings(true, endpoint.baseUrl, "translator-emulator", "emulator-v1",
            "scripted-fixture", "test-byte-bound", "test-template", 256, 131072, 63488))
    private var index = 0
    private var current: NpcHandle? = null
    private var ticks = 0
    private var caseTicks = 0
    private var answered = false
    private var busyMemoryChecked = false
    private var offline = false
    private var cancelled = false
    private var pausedAt: Int? = null
    private var resumed = false
    private var restored = false
    private var closed = false
    private var callsBefore = 0
    private val reasons = mutableSetOf<String>()
    private var final: String? = null
    private var authorityChecks = 0
    private var stockReads = 0

    override fun renderView(): Pair<java.util.UUID, String>? = current?.let { it.npcUuid to cases[index] }

    init {
        for (x in -2..10) for (z in -2..2) for (y in -1..5) {
            val p = base.offset(x, y, z)
            check(level.getBlockEntity(p) == null) { "Translator arena must not replace existing block entities" }
            savedBlocks[p] = level.getBlockState(p)
        }
        check(level.getEntitiesOfClass(ItemEntity::class.java, bounds()).isEmpty())
        for ((p, _) in savedBlocks) level.setBlockAndUpdate(p, if (p.y == base.y - 1) Blocks.STONE.defaultBlockState() else Blocks.AIR.defaultBlockState())
        configure(settings)
    }

    override fun poll(): String? {
        final?.let { return it }
        check(++ticks < 3000) { "Translator probe timed out" }
        val controller = checkNotNull(LlmServerEvents.controller(server))
        if (index == cases.size) {
            if (!restored) { configure(original); restored = true; return null }
            if (controller.settings != original) return null
            val calls = endpoint.received.size
            check(stockReads == 5)
            close()
            final = "translatorCases=8 translatorHttpCalls=$calls translatorMaxRequestBytes=${script.maxRequestBytes.get()} registeredGoalCommands=true goalAuthorityChecks=$authorityChecks physicalStockReads=$stockReads physicalTransport=true physicalDeliver=true physicalLumberjack=true manualPauseHeld=true " +
                "memoryCommands=true memoryContext=true confirmedResults=true clarificationBudgetRetained=true offlineBehaviorContinues=true missingResourceFailed=true fullDestinationFailed=true userCancelNoLateAssign=true"
            return final
        }
        if (current == null) {
            if (controller.settings != settings) return null
            prepare()
            return null
        }
        val handle = checkNotNull(current)
        val name = cases[index]
        val record = controller.store.get(handle.npcUuid) ?: error("Registered command failed to persist goal")
        check(++caseTicks < 800) { "$name phase=${record.phase} code=${record.code} reasons=$reasons" }
        val observed = OperationSupervisionApi.observe(server, actor, handle.npcUuid).observation
        observed?.task?.let { task ->
            reasons.add(task.reason)
            reasons.addAll(task.frames.map { it.reason })
        }
        if (name == "clarify" && record.phase == GoalPhase.ASK_USER && !answered) {
            check(record.question == "Ile bloków przetransportować?")
            check(record.budget.settledAttempts == 1 && record.budget.inFlight == null)
            check(count(source(), Items.COBBLESTONE) == 64 && count(destination(), Items.COBBLESTONE) == 0)
            check(command("remember", handle, "main_storage " + destinationPos.x + " " + destinationPos.y + " " + destinationPos.z) == 1)
            val remembered = checkNotNull(controller.store.get(handle.npcUuid))
            check(remembered.revision == record.revision + 1 && remembered.budget == record.budget)
            check(remembered.memory.aliases.single().name == "main_storage")
            check(command("remember", handle, "temporary 0 64 0") == 1)
            check(command("forget_place", handle, "temporary") == 1)
            check(controller.store.get(handle.npcUuid)?.memory?.aliases?.size == 1)
            check(command("answer", handle, "32") == 1)
            check(controller.store.get(handle.npcUuid)?.goalId == record.goalId)
            check(controller.store.get(handle.npcUuid)?.budget == record.budget)
            answered = true
        }
        if (name == "clarify" && record.phase == GoalPhase.EXECUTING && !busyMemoryChecked) {
            val reply = controller.place(actor, handle.npcUuid, "main_storage", NpcBlockPosition(0, 64, 0))
            check(!reply.accepted && reply.code == "MEMORY_EDIT_REQUIRES_IDLE_GOAL")
            check(controller.store.get(handle.npcUuid) == record)
            busyMemoryChecked = true
        }
        if (name == "pause" && record.phase == GoalPhase.EXECUTING && pausedAt == null) {
            val view = checkNotNull(observed); val task = checkNotNull(view.task)
            check(OperationSupervisionApi.control(server, actor, handle.npcUuid,
                OperationControlRequest(task.taskId, task.controlRevision, task.definitionRevision,
                    view.observedTick, view.observedTick + 100, OperationControl.PAUSE)).result.status == NpcActionStatus.SUCCEEDED)
            pausedAt = caseTicks
        }
        val pausedTick = pausedAt
        if (name == "pause" && pausedTick != null && caseTicks - pausedTick >= 12 && !resumed) {
            check(record.manualHold && record.phase == GoalPhase.WAITING && record.code == "MANUAL_TASK_CHANGE")
            check(observed?.task?.state == OperationTaskState.PAUSED)
            check(endpoint.received.size - callsBefore == 1)
            check(command("resume", handle) == 1)
            resumed = true
        }
        if (name == "offline" && record.phase == GoalPhase.EXECUTING && !offline) {
            offline = true
            configure(settings.copy(enabled = false))
        }
        if (name == "cancel" && script.calls.get() > callsBefore && !cancelled) {
            check(command("stop", handle) == 1)
            cancelled = true
            script.release.countDown()
        }
        if (name == "cancel") {
            if (!cancelled || record.budget.inFlight != null || caseTicks < 12) return null
            check(record.phase == GoalPhase.STOPPED && record.manualHold)
            check(observed?.task == null)
            check(count(source(), Items.COBBLESTONE) == 64 && count(destination(), Items.COBBLESTONE) == 0)
        } else {
            if (record.phase !in setOf(GoalPhase.COMPLETED, GoalPhase.FAILED)) return null
            if (name in setOf("clarify", "deliver", "lumberjack", "pause", "offline")) {
                check(record.phase == GoalPhase.COMPLETED) { "$name failed $reasons" }
                if (name == "lumberjack") {
                    check(count(destination(), Items.OAK_LOG) == 3)
                    check((0..2).all { level.getBlockState(base.offset(0, it, -1)).isAir })
                } else {
                    check(count(source(), Items.COBBLESTONE) == if (name == "deliver") 0 else 32)
                    check(count(destination(), Items.COBBLESTONE) == 32)
                }
                if (name == "pause") check(resumed)
                check(carried(handle) == 0)
                if (name == "clarify") check(answered && record.budget.settledAttempts == 2)
                if (name == "offline") check(offline && !controller.settings.enabled && record.budget.settledAttempts == 1)
            } else {
                check(record.phase == GoalPhase.FAILED && record.code == "TASK_FAILED")
                check(if (name == "missing") "SOURCE_EMPTY" in reasons else "STORAGE_FULL" in reasons) { reasons.toString() }
                check(count(destination(), Items.COBBLESTONE) == 0)
                if (name == "full") check(count(source(), Items.COBBLESTONE) + carried(handle) == 64)
            }
        }
        if (name != "cancel") {
            check(record.memory.results.size == 1)
            val expected = checkNotNull(record.task).id.toString() + " " + record.task.operationId + " " + record.code
            check(record.memory.results.single() == expected)
        } else check(record.memory.results.isEmpty())
        if (name == "clarify") check(busyMemoryChecked && record.memory.aliases.single().name == "main_storage")
        check(endpoint.received.size - callsBefore == if (name == "clarify") 2 else 1)
        if (!canFinish(handle.npcUuid)) return null
        if (record.phase == GoalPhase.COMPLETED) {
            val itemId = if (name == "lumberjack") "minecraft:oak_log" else "minecraft:cobblestone"
            val stock = OperationStockApi.inspect(server, actor, handle.npcUuid, level.dimension().location().toString(),
                NpcStockQuery(NpcBlockPosition(destinationPos.x, destinationPos.y, destinationPos.z), itemId))
            val value = stock.stock
            check(stock.result.status == NpcActionStatus.SUCCEEDED && value is NpcStockRead.Observed) { "Physical stock unavailable: $stock" }
            check(value.count == if (name == "lumberjack") 3 else 32)
            stockReads++
        }
        check(command("status", handle) == 1)
        check(command("forget", handle) == 1)
        check(controller.store.get(handle.npcUuid) == null)
        check(service.dismiss(handle, NpcDismissMode.DROP_INVENTORY).status == NpcActionStatus.SUCCEEDED)
        for (item in level.getEntitiesOfClass(ItemEntity::class.java, bounds())) item.discard()
        current = null
        index++
        configure(settings)
        return null
    }

    private fun prepare() {
        source().clearContent(); destination().clearContent()
        val name = cases[index]
        if (name !in setOf("missing", "deliver", "lumberjack")) source().setItem(0, ItemStack(Items.COBBLESTONE, 64))
        if (name == "full") for (slot in 0 until destination().containerSize) destination().setItem(slot, ItemStack(Items.STONE, 64))
        val summoned = service.summon(NpcSummonRequest(actor.uuid, "Translator-$name",
            level.dimension().location().toString(), spawn, 0F))
        check(summoned.result.status == NpcActionStatus.SUCCEEDED)
        val handle = checkNotNull(summoned.handle)
        current = handle
        if (name == "deliver") give(handle, ItemStack(Items.COBBLESTONE, 32))
        if (name == "lumberjack") {
            give(handle, ItemStack(Items.IRON_AXE))
            level.setBlockAndUpdate(base.offset(0, -1, -1), Blocks.DIRT.defaultBlockState())
            for (y in 0..2) level.setBlockAndUpdate(base.offset(0, y, -1), Blocks.OAK_LOG.defaultBlockState())
        }
        pausedAt = null; resumed = false
        caseTicks = 0; answered = false; offline = false; cancelled = false; reasons.clear()
        callsBefore = endpoint.received.size
        script.caseName = name
        script.release = CountDownLatch(1)
        val transportText = "Transportuj cobblestone ze skrzyni ${sourcePos.x},${sourcePos.y},${sourcePos.z} " +
            "do ${destinationPos.x},${destinationPos.y},${destinationPos.z}." +
            if (name == "clarify") "" else " Ilość: 32."
        val text = when (name) {
            "deliver" -> "Dostarcz niesione 32 cobblestone do ${destinationPos.x},${destinationPos.y},${destinationPos.z}."
            "lumberjack" -> "Zbierz 3 kłody dębu w obszarze podanym w poleceniu i dostarcz do ${destinationPos.x},${destinationPos.y},${destinationPos.z}. Obszar: ${base.x-1},${base.y},${base.z-2} do ${base.x+1},${base.y+4},${base.z}."
            else -> transportText
        }
        check(command("goal", handle, text) == 1)
        if (index == 0) authorityChecks = GoalAuthorityProbe.verify(server, actor, handle.npcUuid, checkNotNull(LlmServerEvents.controller(server)))
    }

    private fun give(handle: NpcHandle, stack: ItemStack) {
        val item = ItemEntity(level, spawn.x, spawn.y, spawn.z, stack)
        item.setNoPickUpDelay(); item.deltaMovement = net.minecraft.world.phys.Vec3.ZERO
        check(level.addFreshEntity(item))
        check(checkNotNull(service.runtime(handle)).pickupItem(item.uuid).status == NpcActionStatus.SUCCEEDED)
    }

    private fun delivery(): String {
        val order = LlmJson.parse(operation(), 16384)
        order.addProperty("type", "samcnpc:deliver"); order.addProperty("definitionVersion", 2)
        val parameters = order["parameters"].asJsonObject
        parameters.add("destination", parameters["destinations"].asJsonObject["positions"].asJsonArray[0])
        parameters.remove("sources"); parameters.remove("destinations")
        return order.toString()
    }

    private fun lumberjack(): String {
        val order = LlmJson.parse(delivery(), 16384)
        order.addProperty("type", "samcnpc:lumberjack")
        val p = order["parameters"].asJsonObject
        p.remove("itemId"); p.remove("anchor"); p.addProperty("quantity", 3)
        p.add("wood", com.google.gson.JsonArray().also { it.add("samcnpc:oak") })
        p.add("area", LlmJson.parse("""{"bounds":{"min":{"x":${base.x-1},"y":${base.y},"z":${base.z-2}},"max":{"x":${base.x+1},"y":${base.y+4},"z":${base.z}}}}""", 2048))
        p["budget"].asJsonObject.addProperty("ticks", 600)
        return order.toString()
    }

    private fun source(): ChestBlockEntity = chest(sourcePos)
    private fun destination(): ChestBlockEntity = chest(destinationPos)
    private fun chest(position: BlockPos): ChestBlockEntity {
        if (level.getBlockEntity(position) !is ChestBlockEntity) level.setBlockAndUpdate(position, Blocks.CHEST.defaultBlockState())
        return level.getBlockEntity(position) as ChestBlockEntity
    }
    private fun count(chest: ChestBlockEntity, item: net.minecraft.world.item.Item) =
        (0 until chest.containerSize).sumOf { if (chest.getItem(it).item == item) chest.getItem(it).count else 0 }
    private fun carried(handle: NpcHandle) = checkNotNull(service.runtime(handle)).inventoryContents()
        .sumOf { if (it.stack.itemId == "minecraft:cobblestone") it.stack.count else 0 }
    private fun bounds() = AABB(base.offset(-3, -2, -3), base.offset(12, 5, 4))
    private fun command(action: String, handle: NpcHandle, text: String = ""): Int =
        server.commands.dispatcher.execute("samcnpc llm $action ${selector(handle)}" + if (text.isEmpty()) "" else " $text",
            actor.createCommandSourceStack())
    private fun selector(handle: NpcHandle): String = when (index % 4) {
        0 -> handle.displayName
        1 -> handle.displayName.dropLast(2).uppercase()
        2 -> handle.npcUuid.toString().take(8)
        else -> handle.npcUuid.toString()
    }

    private fun configure(values: ProviderSettings) {
        check(LlmConfig.update(LlmConfig.snapshot().revision, values))
    }

    private fun operation(): String {
        fun position(pos: BlockPos): JsonObject = JsonObject().also {
            it.addProperty("x", pos.x); it.addProperty("y", pos.y); it.addProperty("z", pos.z)
        }
        fun choices(pos: BlockPos): JsonObject = JsonObject().also {
            val list = com.google.gson.JsonArray(); list.add(position(pos)); it.add("positions", list)
        }
        val parameters = JsonObject()
        parameters.addProperty("dimensionId", level.dimension().location().toString())
        parameters.add("sources", choices(sourcePos)); parameters.add("destinations", choices(destinationPos))
        parameters.addProperty("itemId", "minecraft:cobblestone"); parameters.addProperty("quantity", 32)
        parameters.add("anchor", position(base.offset(2, 0, 0)))
        parameters.add("budget", LlmJson.parse("""{"ticks":240,"attempts":2,"backoffTicks":5}""", 1024))
        return JsonObject().also {
            it.addProperty("documentVersion", 1); it.addProperty("type", "samcnpc:transport")
            it.addProperty("definitionVersion", 1); it.add("parameters", parameters)
        }.toString()
    }

    override fun close() {
        if (closed) return
        closed = true
        script.release.countDown()
        endpoint.close()
        if (LlmConfig.snapshot().values != original) configure(original)
        current?.let { handle ->
            LlmGoalStore.forServer(server).remove(handle.npcUuid)
            if (service.runtime(handle) != null) service.dismiss(handle, NpcDismissMode.DROP_INVENTORY)
        }
        for (item in level.getEntitiesOfClass(ItemEntity::class.java, bounds())) item.discard()
        source().clearContent(); destination().clearContent()
        for ((position, state) in savedBlocks) level.setBlockAndUpdate(position, state)
    }

    /** Worker emulator holds immutable operation text and test synchronization only, never the world. */
    private class Script(private val operation: String, private val delivery: String, private val lumberjack: String) {
        @Volatile var caseName = ""
        @Volatile var release = CountDownLatch(1)
        val calls = AtomicInteger()
        val maxRequestBytes = AtomicInteger()
        fun reply(request: FakeOpenAiEndpoint.Received): FakeOpenAiEndpoint.Reply {
            calls.incrementAndGet()
            if (caseName in setOf("clarify", "deliver", "lumberjack"))
                java.nio.file.Files.writeString(java.nio.file.Path.of("translator-wire-" + caseName + ".json"), request.body)
            val bytes = LlmJson.utf8(request.body).size
            check(bytes + 256 <= 63488)
            maxRequestBytes.getAndUpdate { previous -> maxOf(previous, bytes) }
            val http = LlmJson.parse(request.body, 65536)
            val state = LlmJson.parse(http["messages"].asJsonArray[1].asJsonObject["content"].asString, 24576)
            val ask = caseName == "clarify" && !state["goal"].asJsonObject["text"].asString.contains("Latest user clarification:")
            if (caseName == "clarify" && !ask) {
                val memory = state["memory"].asJsonObject
                val alias = memory["aliases"].asJsonArray.single().asJsonObject
                check(alias["name"].asString == "main_storage")
                check(alias["source"].asString == "USER_LABEL" && alias["currentWorldContents"].asString == "UNKNOWN")
                check(memory["confirmedResults"].asJsonArray.isEmpty)
            }
            val result = JsonObject()
            result.addProperty("schemaVersion", 1); result.addProperty("contextId", state["contextId"].asString)
            result.addProperty("decision", if (ask) "ASK_USER" else "ASSIGN")
            result.addProperty("summary", "Emulator statement is never an execution receipt")
            for (field in listOf("operation", "change", "question", "wait")) result.add(field, JsonNull.INSTANCE)
            if (ask) result.addProperty("question", "Ile bloków przetransportować?")
            else result.add("operation", LlmJson.parse(when (caseName) { "deliver" -> delivery; "lumberjack" -> lumberjack; else -> operation }, 16384))
            return FakeOpenAiEndpoint.Reply(body = LlmJson.utf8(FakeOpenAiEndpoint.success(result.toString())),
                waitBeforeHeaders = if (caseName == "cancel") release else null)
        }
    }
}
