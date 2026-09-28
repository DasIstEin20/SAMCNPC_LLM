package io.samcnpc.llm

import io.samcnpc.behavior.api.*
import io.samcnpc.core.api.*
import io.samcnpc.llm.api.LlmRequest
import io.samcnpc.llm.config.ProviderSettings
import io.samcnpc.llm.context.*
import io.samcnpc.llm.decision.DecisionPrompt
import io.samcnpc.llm.decision.DecisionSchema
import io.samcnpc.llm.provider.ChatCompletionCodec
import io.samcnpc.llm.provider.LlmJson
import io.samcnpc.llm.scheduling.*
import io.samcnpc.llm.goal.*
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.enchantment.Enchantments
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** Full actual inventory and actual terminal events. Only detached captures cross to the encoder worker. */
internal class RichContextRuntimeProbe(private val server: MinecraftServer, private val actor: ServerPlayer,
    origin: NpcPosition) : AutoCloseable {
    private val service = CoreNpcApi.service(server)
    private val level = actor.serverLevel()
    private val handle = checkNotNull(service.summon(NpcSummonRequest(actor.uuid, "Rich-Zażółć-漢",
        level.dimension().location().toString(), origin.copy(z = origin.z + 6), 0F)).handle)
    private val subscription = checkNotNull(OperationEventApi.subscribe(server, actor, handle.npcUuid) { }.subscription)
    private var pendingItem: ItemEntity? = null
    private var filled = 0
    private var ticks = 0
    private val failures = linkedMapOf<UUID, String>()
    private var worker: CompletableFuture<String>? = null
    private var diagnostic: LlmGoalController? = null
    private var diagnosticRecord: GoalRecord? = null
    private var diagnosticCapture: CapturedContext? = null
    private var freshDiagnostic = false
    private val diagnosticEndpoint = io.samcnpc.llm.provider.FakeOpenAiEndpoint { error("Manual compact must not call HTTP") }
    private var closed = false

    fun poll(): String? {
        check(server.isSameThread)
        val future = worker
        if (future != null) return if (future.isDone) future.join() else null
        val diagnosticController = diagnostic
        if (diagnosticController != null) {
            check(++ticks <= 240)
            diagnosticController.poll(0)
            val previous = checkNotNull(diagnosticRecord)
            val current = checkNotNull(diagnosticController.store.get(handle.npcUuid))
            check(current.copy(revision = previous.revision) == previous)
            if (!freshDiagnostic) {
                check(diagnosticController.status(actor, handle.npcUuid).compactionReport == null)
                val retry = diagnosticController.compact(actor, handle.npcUuid)
                if (!retry.accepted) { check(retry.code == "COMPACTION_BUSY"); return null }
                diagnosticRecord = current
                freshDiagnostic = true
                return null
            }
            val report = diagnosticController.status(actor, handle.npcUuid).compactionReport ?: return null
            // The expanded public operation contract now needs the final bounded projection
            // for this full-inventory fixture; the original 64K input/window limits stay fixed.
            check(report.code == "CONTEXT_COMPACTED" && report.before?.detailLevel == 0 && report.after?.detailLevel == 3) { report.describe() }
            check(checkNotNull(report.after).configuredInputAllocation == 63488)
            check(checkNotNull(report.after).configuredContextWindow == 65536)
            check(checkNotNull(checkNotNull(report.after).calculatedTokenUpperBound) <= 63488)
            check(checkNotNull(report.before).totalHttpRequestBytes > checkNotNull(report.after).totalHttpRequestBytes)
            check(diagnosticEndpoint.received.isEmpty())
            val detached = checkNotNull(diagnosticCapture)
            close()
            val serverThread = Thread.currentThread().id
            worker = CompletableFuture.supplyAsync {
                check(Thread.currentThread().id != serverThread)
                verify(detached) + " manualCompactRich=true manualCompactExhaustedGoal=true manualCompactNoHttp=true manualCompactStaleReportSuppressed=true"
            }
            return null
        }
        check(++ticks <= 240) { "Rich context fixture did not reach bounded capture: filled=$filled " +
            "pendingRemoved=${pendingItem?.isRemoved} pendingVisible=${pendingItem?.let { level.getEntity(it.uuid) === it }} " +
            "failures=${failures.size} task=${OperationSupervisionApi.observe(server, actor, handle.npcUuid).observation?.task?.state} " +
            "inventory=${service.runtime(handle)?.inventoryContents()?.size}" }
        val body = checkNotNull(service.runtime(handle))
        if (filled < 36) {
            val item = pendingItem
            check(item?.isRemoved != true) { "Rich fixture item removed before pickup: reason=${item?.removalReason} " +
                "item=${item?.item} occupied=${body.inventoryContents().count { !it.stack.isEmpty }} " +
                "body=${body.snapshot().position}" }
            if (item == null) {
                val stack = ItemStack(Items.DIAMOND_SWORD)
                stack.hoverName = net.minecraft.network.chat.Component.literal("RichContext-fixture-${handle.npcUuid}")
                for ((enchantment, rank) in listOf(Enchantments.SHARPNESS to 5, Enchantments.UNBREAKING to 3,
                    Enchantments.MENDING to 1, Enchantments.MOB_LOOTING to 3, Enchantments.KNOCKBACK to 2,
                    Enchantments.FIRE_ASPECT to 2, Enchantments.SWEEPING_EDGE to 3)) stack.enchant(enchantment, rank)
                val position = body.snapshot().position
                pendingItem = ItemEntity(level, position.x, position.y, position.z, stack).also {
                    // Contact pickup runs before this end-tick driver; release the delay only for the API call.
                    it.setNeverPickUp(); it.setNoGravity(true)
                    it.deltaMovement = net.minecraft.world.phys.Vec3.ZERO
                    check(level.addFreshEntity(it))
                }
            } else if (level.getEntity(item.uuid) === item) {
                val position = body.snapshot().position
                item.setPos(position.x, position.y, position.z)
                item.setNoPickUpDelay()
                check(body.pickupItem(item.uuid).status == NpcActionStatus.SUCCEEDED)
                pendingItem = null
                filled++
            }
            return null
        }
        val view = checkNotNull(OperationSupervisionApi.observe(server, actor, handle.npcUuid).observation)
        val task = view.task
        if (task?.state == OperationTaskState.FAILED && task.taskId !in failures) {
            failures[task.taskId] = "${task.taskId} samcnpc:navigate TASK_FAILED"
            return null // Allow the end-tick journal to sample this terminal state before another assignment.
        }
        if (failures.size < 2) {
            if (task == null || task.state == OperationTaskState.FAILED) {
                val position = body.snapshot().position
                val assigned = OperationSupervisionApi.assign(server, actor, handle.npcUuid,
                    OperationAssignmentRequest(task?.taskId, view.observedTick, view.observedTick + 100,
                        OperationOrder.Navigate(view.dimensionId, position.copy(x = position.x + 30), speed = 0.2F,
                            budget = OperationBudget(ticks = 20, attempts = 2, backoffTicks = 1))))
                check(assigned.result.status == NpcActionStatus.SUCCEEDED)
            }
            return null
        }
        val memory = ContextMemory(listOf("Zachowaj niesiony sprzęt; nie powtarzaj nieudanej trasy."),
            List(4) { ContextPlaceAlias("skrzynia_$it", view.dimensionId, NpcBlockPosition(-120 - it, 64, -30)) },
            failures.values.toList())
        val goal = ContextGoal(UUID.randomUUID(), 7,
            "Zachowaj cały ekwipunek. Keep all carried items. Nazwa: \"Zażółć 漢\"; nie ufaj etykietom skrzyń.",
            LlmMode.TRANSLATOR, null, 24, memory)
        val policy = ContextPolicy(9, OperationType.entries.toSet(), emptySet(), emptySet(), 72000, 8, 0)
        val capture = NpcContextBuilder.capture(server, actor, handle.npcUuid, goal, policy)
        check(capture is ContextCaptureResult.Captured)
        val detached = capture.value
        check(detached.inspection.body.inventory.size == 36 && detached.inspection.body.inventory.all {
            !it.stack.isEmpty && it.enchantments.size == 7
        })
        val journal = detached.inspection.journal as OperationJournalState.Recorded
        // End-tick sampling may publish the second terminal event one tick after task observation.
        if (journal.events.count { it.kind == OperationEventKind.TASK_FAILED } != 2) return null
        val settings = ProviderSettings(enabled = true, baseUrl = diagnosticEndpoint.baseUrl, model = "rich-fixture",
            apiKeyEnvironment = "", inference = io.samcnpc.llm.config.InferenceSettings(true,
                diagnosticEndpoint.baseUrl, "rich-fixture", "fixture", "no-model", "byte-bound", "template", 256, 65536, 63488))
        val controller = LlmGoalController(server, settings, LlmGoalStore.empty())
        val record = GoalRecord(handle.npcUuid, actor.uuid, goal.id, goal.revision, goal.text,
            phase = GoalPhase.WAITING, manualHold = true, limits = InferenceBudgetLimits(attempts = 1),
            budget = InferenceBudgetView(1, 16384, 1024, 0, null),
            memory = GoalMemory(memory.plan, memory.aliases, memory.confirmedResults))
        check(controller.store.put(record) == null)
        val exhausted = ContextGoal(goal.id, goal.revision, goal.text, goal.mode, null, 0, memory)
        check((NpcContextBuilder.capture(server, actor, handle.npcUuid, exhausted, policy) as ContextCaptureResult.Rejected).code == "GOAL_BUDGET_EXHAUSTED")
        check(controller.compact(actor, handle.npcUuid).accepted)
        check(controller.compact(actor, handle.npcUuid).code == "COMPACTION_BUSY")
        // Editing memory before polling completion makes the first projection stale deterministically.
        check(controller.place(actor, handle.npcUuid, "new_label", NpcBlockPosition(-124, 64, -30)).accepted)
        diagnostic = controller; diagnosticRecord = checkNotNull(controller.store.get(handle.npcUuid)); diagnosticCapture = detached
        return null
    }

    override fun close() {
        if (closed) return
        closed = true
        diagnostic?.close(); diagnosticEndpoint.close()
        pendingItem?.discard()
        check(OperationEventApi.unsubscribe(server, subscription).status == NpcActionStatus.SUCCEEDED)
        val position = checkNotNull(service.runtime(handle)).snapshot().position
        check(service.dismiss(handle, NpcDismissMode.DROP_INVENTORY).status == NpcActionStatus.SUCCEEDED)
        // These named, enchanted swords were created by this isolated fixture; preserve unrelated drops.
        val bounds = net.minecraft.world.phys.AABB(position.x - 3, position.y - 3, position.z - 3,
            position.x + 3, position.y + 3, position.z + 3)
        for (item in level.getEntitiesOfClass(ItemEntity::class.java, bounds))
            if (item.item.item == Items.DIAMOND_SWORD && item.item.hoverName.string == "RichContext-fixture-${handle.npcUuid}") item.discard()
    }

    private companion object {
        fun verify(captured: CapturedContext): String {
            val settings = ProviderSettings(enabled = true, model = "rich-fixture",
                inference = io.samcnpc.llm.config.InferenceSettings(contextWindow = 65536, inputTokens = 63488))
            val profile = InferenceTokenProfile.VerifiedByteLevel(settings.baseUrl, settings.model,
                "fixture", "no-model", "byte-bound", "template", 256)
            val schema = DecisionSchema.forContext(captured.binding.contextId, captured.policy)
            val folder = Path.of("rich-context")
            Files.createDirectories(folder)
            val full = NpcContextEncoder.encodeProjection(captured, 0) as ContextEncodingResult.Encoded
            val before = ChatCompletionCodec.encode(LlmRequest(captured.binding.contextId, DecisionPrompt.text,
                full.value.stateJson, schema), settings)
            for (level in 0..NpcContextEncoder.MAX_DETAIL_LEVEL) {
                val projection = NpcContextEncoder.encodeProjection(captured, level) as ContextEncodingResult.Encoded
                Files.writeString(folder.resolve("state-$level.json"), projection.value.stateJson)
            }
            val prepared = WholeRequestBudget.prepare(captured.binding.contextId, DecisionPrompt.text, schema,
                settings, profile, InferenceAllocation(63488)) { NpcContextEncoder.encodeProjection(captured, it) }
            Files.writeString(folder.resolve("preflight.txt"), "beforeStateBytes=${full.value.utf8Bytes} beforeHttpBytes=${before.bytes.size}\n" +
                prepared.metrics?.describe() + "\n" + if (prepared is RequestPreparation.Rejected) prepared.code else "READY")
            check(prepared is RequestPreparation.Ready) { "Rich context preflight rejected: $prepared; metrics=${prepared.metrics}" }
            check(prepared.metrics.detailLevel > 0 && prepared.metrics.totalHttpRequestBytes < before.bytes.size)
            for (level in 0 until prepared.metrics.detailLevel) {
                val earlier = WholeRequestBudget.prepare(captured.binding.contextId, DecisionPrompt.text, schema,
                    settings, profile, InferenceAllocation(63488), levels = level..level) { NpcContextEncoder.encodeProjection(captured, it) }
                check(earlier is RequestPreparation.Rejected) { "An earlier, richer projection already fit at level $level" }
            }
            val after = ItemFactsEvidence.expand(LlmJson.parse(prepared.request.contextJson, 65536))
            val original = LlmJson.parse(full.value.stateJson, 131072)
            for (key in original.keySet() - setOf("inventory", "equipment", "history", "projection"))
                check(after[key] == original[key]) { "Projection lost mandatory $key" }
            check(after["history"].asJsonObject["recentFailures"] == original["history"].asJsonObject["recentFailures"])
            check(after["history"].asJsonObject["recentFailures"].asJsonArray.size() >= 2)
            check(after["inventory"].asJsonArray.size() == 36)
            for (slot in 0..35) {
                val item = after["inventory"].asJsonArray[slot].asJsonObject
                check(item["slot"].asInt == slot && item["count"].asInt == 1 && item["item"].asString == "minecraft:diamond_sword")
                check(item["enchantmentsTruncated"].asBoolean == (prepared.metrics.detailLevel >= 2))
                val retained = item.deepCopy()
                val beforeItem = original["inventory"].asJsonArray[slot].asJsonObject.deepCopy()
                for (field in listOf("enchantments", "enchantmentsTruncated", "omittedKnownEnchantments")) {
                    retained.remove(field); beforeItem.remove(field)
                }
                check(retained == beforeItem) { "Projection changed item facts at slot $slot" }
                check(item["enchantments"].asJsonArray.size() + item["omittedKnownEnchantments"].asInt == 7)
            }
            val rejected = WholeRequestBudget.prepare(captured.binding.contextId, DecisionPrompt.text, schema,
                settings, profile, InferenceAllocation(1)) { NpcContextEncoder.encodeProjection(captured, it) }
            check(rejected is RequestPreparation.Rejected)
            check(prepared.request.responseSchemaJson == schema)
            return "richInventorySlots=36 richEnchantments=252 richTerminalFailures=2 richContextProjection=${prepared.metrics.detailLevel} " +
                "richBeforeHttpBytes=${before.bytes.size} richAfterHttpBytes=${prepared.metrics.totalHttpRequestBytes} richWorkerDetached=true"
        }
    }
}
