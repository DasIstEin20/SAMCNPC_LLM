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

/** CPU HTTP emulator drives actual registered commands and physical stock changes. */
internal class SupervisorRuntimeProbe(private val server: MinecraftServer, private val actor: ServerPlayer,
    origin: NpcPosition, private val canFinish: (UUID) -> Boolean = { true }) : GoalRuntimeProbe {
    private val original = LlmConfig.snapshot().values
    private val level = actor.serverLevel()
    private val base = BlockPos.containing(origin.x + 16, origin.y, origin.z + 8)
    private val destination = base.offset(8, 0, 0)
    private val sourceA = base.offset(0, 0, -2)
    private val sourceNear = base.offset(5, 0, -2)
    private val sourceB = base.offset(4, 0, 2)
    private val spawn = NpcPosition(base.x + 6.5, base.y.toDouble(), base.z + 0.5)
    private val saved = linkedMapOf<BlockPos, BlockState>()
    private val service = CoreNpcApi.service(server)
    private val script = Script(operation(sourceA), operation(sourceB), operation(sourceNear))
    private val endpoint = FakeOpenAiEndpoint(script::reply)
    private val settings = ProviderSettings(enabled = true, baseUrl = endpoint.baseUrl, model = "supervisor-emulator",
        apiKeyEnvironment = "", requestTimeoutSeconds = 4,
        inference = InferenceSettings(true, endpoint.baseUrl, "supervisor-emulator", "emulator-v1",
            "scripted-fixture", "test-byte-bound", "test-template", 256, 131072, 32768))
    private var index = 0
    private var handle: NpcHandle? = null
    private var caseTicks = 0
    private var totalTicks = 0
    private var callsBefore = 0
    private var stage = 0
    private var changedAt = 0
    private var restored = false
    private var closed = false
    private var result: String? = null
    private var lastCommandReply = ""
    private var heldTask: UUID? = null
    private val taskCalls = linkedMapOf<UUID, Int>()
    private val reasons = linkedSetOf<String>()

    init {
        for (x in -2..11) for (z in -4..4) for (y in -1..4) {
            val p = base.offset(x, y, z)
            check(level.getBlockEntity(p) == null) { "Supervisor arena must not replace block entities" }
            saved[p] = level.getBlockState(p)
        }
        check(level.getEntitiesOfClass(ItemEntity::class.java, bounds()).isEmpty())
        for (p in saved.keys) level.setBlockAndUpdate(p, if (p.y == base.y - 1) Blocks.STONE.defaultBlockState() else Blocks.AIR.defaultBlockState())
        configure(settings)
    }

    override fun renderView(): Pair<UUID, String>? = handle?.let { it.npcUuid to CASES[index] }

    override fun poll(): String? {
        result?.let { return it }
        check(++totalTicks < 9000) { "Supervisor campaign timed out" }
        val controller = checkNotNull(LlmServerEvents.controller(server))
        if (index == CASES.size) {
            if (!restored) { configure(original); restored = true; return null }
            if (controller.settings != original) return null
            check(endpoint.received.size == EXPECTED_HTTP) { "Unexpected HTTP total ${endpoint.received.size}" }
            result = "supervisorGroup=$GROUP supervisorCases=" + CASES.size +
                " supervisorHttpCalls=$EXPECTED_HTTP supervisorMaxRequestBytes=" + script.maxBytes.get() +
                " scenarios=" + CASES.joinToString(",") + " healthyTaskExtraCalls=0 stockAdmissionRechecked=true"
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
        val observation = OperationSupervisionApi.observe(server, actor, npc.npcUuid).observation
        observation?.task?.let { reasons.add(it.reason); reasons.addAll(it.frames.map { frame -> frame.reason }) }
        if (record.phase == GoalPhase.EXECUTING) {
            val task = checkNotNull(record.task).id
            val prior = taskCalls.putIfAbsent(task, endpoint.received.size)
            check(prior == null || prior == endpoint.received.size) { "Inference repeated during healthy task $task" }
        }
        if (caseTicks % 100 == 0) java.nio.file.Files.writeString(java.nio.file.Path.of("supervisor-progress.txt"),
            "$name phase=" + record.phase + " code=" + record.code + " calls=" + (endpoint.received.size-callsBefore) + " reasons=$reasons")
        check(++caseTicks < 1600) { "$name timeout phase=${record.phase} code=${record.code} calls=${endpoint.received.size-callsBefore} reasons=$reasons" }
        var complete = false
        when (name) {
            "maintain" -> {
                if (stage == 0 && record.code == "STOCK_TARGET_REACHED" && record.budget.inFlight == null) {
                    check(count(destination) == 256 && count(sourceA) == 256)
                    remove(destination, 36); stage = 1; changedAt = caseTicks
                } else if (stage == 1 && caseTicks - changedAt >= 40) {
                    check(count(destination) == 220 && endpoint.received.size - callsBefore == 1)
                    remove(destination, 44); stage = 2
                } else if (stage == 2 && count(destination) == 256 && record.phase == GoalPhase.WAITING && record.budget.inFlight == null) {
                    check(count(sourceA) == 176 && record.budget.settledAttempts == 2)
                    check(endpoint.received.size - callsBefore == 2 && record.supervision?.armed == true)
                    complete = true
                }
            }
            "full", "missing", "alternate", "wait" -> {
                if (record.phase == GoalPhase.ASK_USER && record.budget.inFlight == null) {
                    check(record.code in setOf("REPEATED_FAILED_DECISION", "NONPROGRESS_DECISION_LIMIT"))
                    check(endpoint.received.size - callsBefore == 3)
                    check(record.supervision?.failures?.entries?.size in 2..3)
                    check(count(destination) == 0)
                    if (name == "full") check("STORAGE_FULL" in reasons && count(sourceNear) + carried(npc) == 512)
                    if (name in setOf("missing", "alternate")) check("SOURCE_EMPTY" in reasons)
                    if (name == "wait") check(observation?.task == null)
                    complete = true
                }
            }
            "stale" -> {
                if (stage == 0 && script.calls.get() > callsBefore) {
                    fill(destination, 1); stage = 1; script.release.countDown()
                }
                if (stage == 1 && record.code == "STOCK_TARGET_REACHED" && record.budget.inFlight == null) {
                    check(count(destination) == 256 && count(sourceA) == 257)
                    check(record.budget.settledAttempts == 2 && endpoint.received.size - callsBefore == 2)
                    complete = true
                }
            }
            "offline" -> {
                if (stage == 0 && record.phase == GoalPhase.EXECUTING) {
                    heldTask = record.task?.id; configure(settings.copy(enabled = false)); stage = 1
                }
                if (stage == 1 && record.code == "STOCK_TARGET_REACHED" && record.budget.inFlight == null) {
                    check(!controller.settings.enabled && observation?.task?.taskId == heldTask)
                    check(count(destination) == 256 && count(sourceA) == 256 && endpoint.received.size - callsBefore == 1)
                    complete = true
                }
            }
            "authority" -> {
                if (stage == 0 && record.supervision?.armed == true) {
                    actor.teleportTo(level, spawn.x + 300, spawn.y + 6, spawn.z, 0F, 0F); stage = 1
                }
                if (stage == 1 && record.manualHold) {
                    check(record.phase == GoalPhase.WAITING && endpoint.received.size == callsBefore)
                    check(count(destination) == 256)
                    actor.teleportTo(level, spawn.x, spawn.y + 7, spawn.z + 10, 0F, 0F)
                    complete = true
                }
            }
            "manual" -> {
                if (stage == 0 && record.phase == GoalPhase.EXECUTING) {
                    val view = checkNotNull(observation); val task = checkNotNull(view.task)
                    heldTask = task.taskId
                    check(OperationSupervisionApi.control(server, actor, npc.npcUuid,
                        OperationControlRequest(task.taskId, task.controlRevision, task.definitionRevision,
                            view.observedTick, view.observedTick + 100, OperationControl.PAUSE)).result.status == NpcActionStatus.SUCCEEDED)
                    stage = 1; changedAt = caseTicks
                } else if (stage == 1 && caseTicks - changedAt >= 30) {
                    check(record.manualHold && observation?.task?.state == OperationTaskState.PAUSED)
                    check(endpoint.received.size - callsBefore == 1)
                    check(command("resume", npc) == 1); stage = 2
                } else if (stage == 2 && record.code == "STOCK_TARGET_REACHED" && record.budget.inFlight == null) {
                    check(observation?.task?.taskId == heldTask && count(destination) == 256)
                    check(endpoint.received.size - callsBefore == 1); complete = true
                }
            }
            "repair" -> {
                if (record.code == "STOCK_TARGET_REACHED" && record.budget.inFlight == null) {
                    check(record.budget.settledAttempts == 2 && endpoint.received.size - callsBefore == 2)
                    check(count(destination) == 256 && count(sourceA) == 256)
                    complete = true
                }
            }
            "provider_down" -> {
                if (record.manualHold && record.budget.inFlight == null) {
                    check(record.phase == GoalPhase.WAITING && record.code == "PROVIDER_UNAVAILABLE")
                    check(record.budget.settledAttempts == 2 && endpoint.received.size - callsBefore == 2)
                    check(observation?.task == null && count(destination) == 0 && count(sourceA) == 512)
                    if (stage == 0) { stage = 1; changedAt = caseTicks }
                    if (caseTicks - changedAt >= 40) complete = true
                }
            }
            "queue_filled" -> {
                if (stage == 0 && record.phase == GoalPhase.QUEUED) {
                    fill(destination, 256); stage = 1
                }
                if (stage == 1 && record.supervision?.armed == true) {
                    check(record.phase == GoalPhase.WAITING && record.budget.settledAttempts == 0)
                    check(endpoint.received.size == callsBefore && observation?.task == null)
                    complete = true
                }
            }
            "dismiss" -> {
                if (record.supervision?.armed == true && canFinish(npc.npcUuid)) {
                    check(endpoint.received.size == callsBefore)
                    check(service.dismiss(npc, NpcDismissMode.ONLY_IF_EMPTY).status == NpcActionStatus.SUCCEEDED)
                    check(controller.store.get(npc.npcUuid) == null)
                    handle = null; index++; return null
                }
            }
            "removed" -> {
                if (stage == 0 && record.supervision?.armed == true) {
                    chest(destination).clearContent()
                    level.setBlockAndUpdate(destination, Blocks.AIR.defaultBlockState()); stage = 1
                }
                if (stage == 1 && record.manualHold) {
                    check(record.phase == GoalPhase.WAITING && record.code.startsWith("STOCK_"))
                    check(endpoint.received.size == callsBefore && observation?.task == null)
                    complete = true
                }
            }
        }
        if (!complete || !canFinish(npc.npcUuid)) return null
        check(command("stop", npc) == 1); check(command("forget", npc) == 1)
        check(service.dismiss(npc, NpcDismissMode.DROP_INVENTORY).status == NpcActionStatus.SUCCEEDED)
        for (item in level.getEntitiesOfClass(ItemEntity::class.java, bounds())) item.discard()
        handle = null; index++; configure(settings)
        return null
    }

    private fun prepare() {
        val name = CASES[index]
        for (pos in listOf(sourceA, sourceB, sourceNear, destination)) chest(pos).clearContent()
        if (name !in setOf("missing", "alternate")) fill(if (name == "full") sourceNear else sourceA, 512)
        if (name in setOf("authority", "removed", "dismiss")) fill(destination, 256)
        if (name == "full") repeat(27) { chest(destination).setItem(it, ItemStack(Items.STONE, 64)) }
        val summoned = service.summon(NpcSummonRequest(actor.uuid, "Supervisor-$name",
            level.dimension().location().toString(), spawn, 0F))
        check(summoned.result.status == NpcActionStatus.SUCCEEDED)
        val npc = checkNotNull(summoned.handle); handle = npc
        script.name = name; script.caseCalls.set(0); script.release = CountDownLatch(1)
        stage = 0; caseTicks = 0; callsBefore = endpoint.received.size; reasons.clear(); heldTask = null
        check(command("maintain", npc, "minecraft:cobblestone 192 256 ${destination.x} ${destination.y} ${destination.z} Maintain four stacks. Source A: ${sourceA.x},${sourceA.y},${sourceA.z}; B: ${sourceB.x},${sourceB.y},${sourceB.z}; near: ${sourceNear.x},${sourceNear.y},${sourceNear.z}.") == 1) { "$name command failed: $lastCommandReply" }
    }

    private fun chest(pos: BlockPos): ChestBlockEntity {
        if (level.getBlockEntity(pos) !is ChestBlockEntity) level.setBlockAndUpdate(pos, Blocks.CHEST.defaultBlockState())
        return level.getBlockEntity(pos) as ChestBlockEntity
    }
    private fun fill(pos: BlockPos, count: Int) {
        val container = chest(pos); container.clearContent()
        var left = count; var slot = 0
        while (left > 0) { val amount = minOf(64, left); container.setItem(slot++, ItemStack(Items.COBBLESTONE, amount)); left -= amount }
    }
    private fun count(pos: BlockPos): Int {
        val container = level.getBlockEntity(pos) as? ChestBlockEntity ?: return 0
        return (0 until container.containerSize).sumOf { if (container.getItem(it).item == Items.COBBLESTONE) container.getItem(it).count else 0 }
    }
    private fun remove(pos: BlockPos, count: Int) { val prior = count(pos); check(prior >= count); fill(pos, prior - count) }
    private fun carried(npc: NpcHandle) = checkNotNull(service.runtime(npc)).inventoryContents()
        .sumOf { if (it.stack.itemId == "minecraft:cobblestone") it.stack.count else 0 }
    private fun bounds() = AABB(base.offset(-3, -2, -5), base.offset(12, 5, 5))
    private fun command(action: String, npc: NpcHandle, text: String = "") =
        server.commands.dispatcher.execute("samcnpc llm $action ${npc.npcUuid}" + if (text.isEmpty()) "" else " $text", actor.createCommandSourceStack().withSource(object : net.minecraft.commands.CommandSource {
            override fun sendSystemMessage(message: net.minecraft.network.chat.Component) { lastCommandReply = message.string }
            override fun acceptsSuccess() = true
            override fun acceptsFailure() = true
            override fun shouldInformAdmins() = false
        }))
    private fun configure(values: ProviderSettings) { check(LlmConfig.update(LlmConfig.snapshot().revision, values)) }

    private fun operation(source: BlockPos): String {
        fun position(pos: BlockPos) = JsonObject().also { it.addProperty("x", pos.x); it.addProperty("y", pos.y); it.addProperty("z", pos.z) }
        fun choices(pos: BlockPos) = JsonObject().also { it.add("positions", JsonArray().also { array -> array.add(position(pos)) }) }
        val parameters = JsonObject()
        parameters.addProperty("dimensionId", level.dimension().location().toString())
        parameters.add("sources", choices(source)); parameters.add("destinations", choices(destination))
        parameters.addProperty("itemId", "minecraft:cobblestone"); parameters.addProperty("quantity", 256)
        parameters.add("anchor", position(base.offset(6, 0, 0)))
        parameters.add("budget", LlmJson.parse("""{"ticks":480,"attempts":2,"backoffTicks":5}""", 1024))
        return JsonObject().also { it.addProperty("documentVersion", 1); it.addProperty("type", "samcnpc:transport")
            it.addProperty("definitionVersion", 1); it.add("parameters", parameters) }.toString()
    }

    override fun close() {
        if (closed) return
        closed = true; script.release.countDown(); endpoint.close()
        if (LlmConfig.snapshot().values != original) configure(original)
        handle?.let { npc ->
            LlmGoalStore.forServer(server).remove(npc.npcUuid)
            if (service.runtime(npc) != null) service.dismiss(npc, NpcDismissMode.DROP_INVENTORY)
        }
        for (item in level.getEntitiesOfClass(ItemEntity::class.java, bounds())) item.discard()
        for (pos in listOf(sourceA, sourceB, sourceNear, destination)) (level.getBlockEntity(pos) as? ChestBlockEntity)?.clearContent()
        for ((pos, state) in saved) level.setBlockAndUpdate(pos, state)
    }

    private class Script(private val first: String, private val second: String, private val near: String) {
        @Volatile var name = ""
        @Volatile var release = CountDownLatch(1)
        val calls = AtomicInteger()
        val caseCalls = AtomicInteger()
        val maxBytes = AtomicInteger()
        fun reply(request: FakeOpenAiEndpoint.Received): FakeOpenAiEndpoint.Reply {
            calls.incrementAndGet(); val current = caseCalls.incrementAndGet()
            val bytes = LlmJson.utf8(request.body).size
            maxBytes.getAndUpdate { maxOf(it, bytes) }; check(bytes + 256 <= 32768)
            val http = LlmJson.parse(request.body, 65536)
            val state = LlmJson.parse(http["messages"].asJsonArray[1].asJsonObject["content"].asString, 24576)
            val stock = state["stockSupervision"].asJsonObject
            check(stock["deficit"].asInt > 0)
            if (name == "provider_down") return FakeOpenAiEndpoint.Reply(status = 503, body = LlmJson.utf8("{}"))
            if (name == "repair" && current == 1)
                return FakeOpenAiEndpoint.Reply(body = LlmJson.utf8(FakeOpenAiEndpoint.success("{not-json")))
            val decision = JsonObject()
            decision.addProperty("schemaVersion", 1); decision.addProperty("contextId", state["contextId"].asString)
            decision.addProperty("decision", if (name == "wait") "WAIT" else "ASSIGN")
            decision.addProperty("summary", "Scripted wire contract, not model quality")
            for (field in listOf("operation", "change", "question", "wait")) decision.add(field, JsonNull.INSTANCE)
            if (name == "wait") decision.add("wait", LlmJson.parse("""{"trigger":"DEADLINE","ticks":20}""", 1024))
            else {
                val operation = LlmJson.parse(if (name == "alternate" && current % 2 == 0) second else if (name in setOf("full", "missing", "alternate")) near else first, 16384)
                operation["parameters"].asJsonObject.addProperty("quantity", stock["deficit"].asInt)
                decision.add("operation", operation)
            }
            return FakeOpenAiEndpoint.Reply(body = LlmJson.utf8(FakeOpenAiEndpoint.success(decision.toString())),
                waitBeforeHeaders = if (name == "stale" && current == 1) release else null)
        }
    }

    companion object {
        val GROUP = System.getProperty("samcnpc.supervisorGroup", "physical").also { require(it in setOf("physical", "faults")) }
        val CASES = if (GROUP == "physical") listOf("maintain", "stale", "offline", "authority", "manual", "removed", "queue_filled", "repair", "provider_down", "dismiss")
            else listOf("full", "missing", "alternate", "wait")
        val EXPECTED_HTTP = if (GROUP == "physical") 10 else 12
        val MOVING = setOf("maintain", "stale", "offline", "manual", "repair")
    }
}
