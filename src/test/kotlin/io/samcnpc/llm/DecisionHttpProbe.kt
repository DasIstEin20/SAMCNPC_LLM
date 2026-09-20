package io.samcnpc.llm

import com.google.gson.JsonNull
import com.google.gson.JsonObject
import io.samcnpc.behavior.api.*
import io.samcnpc.core.api.*
import io.samcnpc.llm.api.*
import io.samcnpc.llm.config.*
import io.samcnpc.llm.context.*
import io.samcnpc.llm.decision.*
import io.samcnpc.llm.provider.*
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

/** Full captured context/schema -> actual HTTP -> strict decoder -> server admission, with a delayed reply. */
internal class DecisionHttpProbe(private val server: MinecraftServer, private val actor: ServerPlayer, origin: NpcPosition) {
    private val service = CoreNpcApi.service(server)
    private val policy = ContextPolicy(1, OperationType.entries.toSet(), OperationCatalogApi.snapshot().changes.keys,
        OperationControl.entries.toSet(), 12000, 3, 100)
    private val goal = ContextGoal(UUID.randomUUID(), 1, "Wait for permission to resume", LlmMode.TRANSLATOR, null, 24)
    private val handle: NpcHandle
    private var captured: CapturedContext
    private val slot = DecisionAdmission()
    private var worker: CompletableFuture<WorkerResult>
    private val arrived = AtomicBoolean()
    private val releaseReply = CountDownLatch(1)
    private var expectedResumedState: OperationTaskState? = null
    private var phase = 0
    private var schemaBytes = 0
    private var objectBytes = 0
    private var completed: String? = null

    init {
        val position = origin.copy(x = origin.x + 3, z = origin.z + 3)
        val summoned = service.summon(NpcSummonRequest(actor.uuid, "DecisionHttpProbe",
            actor.serverLevel().dimension().location().toString(), position, 0F))
        check(summoned.result.status == NpcActionStatus.SUCCEEDED)
        handle = checkNotNull(summoned.handle)
        val view = observe()
        val assigned = OperationSupervisionApi.assign(server, actor, handle.npcUuid,
            OperationAssignmentRequest(null, view.observedTick, view.observedTick + 100,
                OperationOrder.Navigate(view.dimensionId, position.copy(x = position.x + 30))))
        check(assigned.result.status == NpcActionStatus.SUCCEEDED)
        captured = capture()
        check(slot.bind(captured))
        worker = request(captured, ResponseFormat.JSON_SCHEMA, DecisionKind.AMEND, arrived, releaseReply)
    }

    fun poll(): String? {
        completed?.let { return it }
        if (phase == 0 && arrived.get()) {
            val view = observe(); val task = checkNotNull(view.task)
            expectedResumedState = task.state
            val pause = OperationSupervisionApi.control(server, actor, handle.npcUuid,
                OperationControlRequest(task.taskId, task.controlRevision, task.definitionRevision,
                    view.observedTick, view.observedTick + 100, OperationControl.PAUSE))
            check(pause.result.status == NpcActionStatus.SUCCEEDED) { pause.result.detail }
            phase = 1
            releaseReply.countDown()
        }
        if (phase == 1 && worker.isDone) {
            val result = worker.join(); schemaBytes = result.requestBytes
            val admitted = slot.admit(server, actor, result.decision, goal, policy, false)
            check(admitted.code == "CONTROL_CHANGED") { admitted.toString() }
            check(observe().task?.state == OperationTaskState.PAUSED)
            check(observe().task?.definitionRevision == captured.binding.definitionRevision)
            captured = capture(); check(slot.bind(captured))
            worker = request(captured, ResponseFormat.JSON_OBJECT, DecisionKind.RESUME)
            phase = 2
        }
        if (phase == 2 && worker.isDone) {
            val result = worker.join(); objectBytes = result.requestBytes
            val admitted = slot.admit(server, actor, result.decision, goal, policy, false)
            check(admitted.state == DecisionOutcomeState.APPLIED) { admitted.toString() }
            val resumed = checkNotNull(observe().task)
            check(resumed.state == expectedResumedState) { "resume did not restore the pre-pause execution state" }
            check(resumed.reason == "USER_RESUMED" && resumed.taskId == captured.binding.priorTaskId)
            check(resumed.controlRevision == checkNotNull(captured.binding.controlRevision) + 1)
            check(resumed.definitionRevision == captured.binding.definitionRevision &&
                resumed.frames == captured.inspection.operation.task?.frames)

            check(slot.admit(server, actor, result.decision, goal, policy, false).code == "DECISION_ALREADY_CONSUMED")
            close()
            completed = "decisionHttpCalls=2 fullSchemaBytes=$schemaBytes jsonObjectBytes=$objectBytes lateHttpRejected=true httpResumeApplied=true"
        }
        return completed
    }

    fun close() {
        releaseReply.countDown()
        if (service.runtime(handle) != null) check(service.dismiss(handle, NpcDismissMode.ONLY_IF_EMPTY).status == NpcActionStatus.SUCCEEDED)
    }

    private fun observe() = checkNotNull(OperationSupervisionApi.observe(server, actor, handle.npcUuid).observation)
    private fun capture(): CapturedContext {
        val result = NpcContextBuilder.capture(server, actor, handle.npcUuid, goal, policy)
        check(result is ContextCaptureResult.Captured)
        return result.value
    }

    private data class WorkerResult(val decision: LlmDecision, val requestBytes: Int)

    private companion object {
        /** Static function intentionally captures only immutable DTOs and test synchronization primitives. */
        fun request(captured: CapturedContext, format: ResponseFormat, kind: DecisionKind,
                    arrived: AtomicBoolean? = null, release: CountDownLatch? = null): CompletableFuture<WorkerResult> =
            CompletableFuture.supplyAsync {
                val encoded = NpcContextEncoder.encode(captured)
                check(encoded is ContextEncodingResult.Encoded)
                val state = encoded.value
                val schema = DecisionSchema.forContext(state.binding.contextId, captured.policy)
                val candidate = JsonObject()
                candidate.addProperty("schemaVersion", 1)
                candidate.addProperty("contextId", state.binding.contextId.toString())
                candidate.addProperty("decision", kind.name)
                candidate.addProperty("summary", "Emulated response for admission testing")
                for (field in listOf("operation", "change", "question", "wait")) candidate.add(field, JsonNull.INSTANCE)
                if (kind == DecisionKind.AMEND)
                    candidate.add("change", LlmJson.parse("""{"documentVersion":1,"type":"EXTEND_TIME","parameters":{"ticks":20}}""", 1024))
                FakeOpenAiEndpoint().use { endpoint ->
                    val settings = ProviderSettings(enabled = true, baseUrl = endpoint.baseUrl, model = "test-only-emulator",
                        apiKeyEnvironment = "", requestTimeoutSeconds = 4, responseFormat = format)
                    endpoint.enqueue(FakeOpenAiEndpoint.Reply(body = LlmJson.utf8(FakeOpenAiEndpoint.success(candidate.toString())),
                        waitBeforeHeaders = release))
                    OpenAiCompatibleProvider(settings).use { provider ->
                        val request = LlmRequest(UUID.randomUUID(), DecisionPrompt.text, state.stateJson, schema)
                        val call = provider.complete(request)
                        check(endpoint.arrivals.tryAcquire(3, TimeUnit.SECONDS))
                        arrived?.set(true)
                        val response = call.result.toCompletableFuture().get(6, TimeUnit.SECONDS)
                        check(response is LlmResponse.Candidate) { "decision HTTP result=" + (response as? LlmResponse.Failed)?.code }
                        val decision = DecisionDecoder.decode(response.decisionJson)
                        check(decision is DecisionDecodeResult.Accepted)
                        val received = endpoint.received.single()
                        val bytes = LlmJson.utf8(received.body).size
                        check(bytes in 10000..65536)
                        val body = LlmJson.parse(received.body, 65536)
                        val system = body["messages"].asJsonArray[0].asJsonObject["content"].asString
                        if (format == ResponseFormat.JSON_OBJECT) check(system.contains("OUTPUT_CONTRACT_JSON_SCHEMA"))
                        else check(body["response_format"].asJsonObject["json_schema"].asJsonObject["schema"] == LlmJson.parse(schema, 65536))
                        check(provider.activeRequests() == 0)
                        WorkerResult(decision.value, bytes)
                    }
                }
            }
    }
}
