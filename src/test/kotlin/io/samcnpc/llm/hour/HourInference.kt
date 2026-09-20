package io.samcnpc.llm.hour

import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.samcnpc.behavior.api.OperationType
import io.samcnpc.llm.api.*
import io.samcnpc.llm.config.ProviderSettings
import io.samcnpc.llm.context.*
import io.samcnpc.llm.decision.*
import io.samcnpc.llm.provider.*
import io.samcnpc.llm.scheduling.*
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/** Restricted benchmark host around the production scheduler/context/admission; not an alternate product controller. */
internal class HourInference(private val server: MinecraftServer, private val actor: ServerPlayer,
    private val scenes: List<HourArena>, private val metrics: HourMeasurements) : InferenceHost, AutoCloseable {
    private val policy = ContextPolicy(1, setOf(OperationType.PATROL), emptySet(), emptySet(), 72000, 16, 0)
    private val goals = scenes.associate { it.handle.npcUuid to UUID.randomUUID() }
    private val budgets = scenes.associate { it.handle.npcUuid to InferenceBudget(InferenceBudgetLimits()) }
    private val admissions = scenes.associate { it.handle.npcUuid to DecisionAdmission() }
    private val pending = mutableSetOf<UUID>()
    private val began = mutableMapOf<UUID,Long>()
    private val rates = InferenceRateGate()
    private val script = Script()
    private var endpoint: FakeOpenAiEndpoint? = null
    private var scheduler: InferenceScheduler? = null
    private var ticks = 0
    private var now = 0L
    private var initialArrivalTick = 0
    var slowTicks = 0
        private set
    var calls = 0
        private set
    var retiredRequests = 0
        private set
    var admitted = 0
        private set
    var offline = false
        private set
    val maxRequestBytes get() = script.maxBytes.get()

    init { connect() }
    private fun connect() {
        check(endpoint == null && scheduler == null)
        val next = FakeOpenAiEndpoint(script::reply)
        endpoint = next
        val settings = ProviderSettings(enabled=true, baseUrl=next.baseUrl, model="hour-patrol-emulator",
            apiKeyEnvironment="", requestTimeoutSeconds=4)
        val profile = InferenceTokenProfile.VerifiedByteLevel(settings.baseUrl, settings.model,
            "emulator-v1","scripted-patrol-v1","test-byte-bound","test-template",256)
        scheduler = InferenceScheduler(OpenAiCompatibleProvider(settings), settings, profile,
            InferenceAllocation(inputTokens=32768), this, rates)
    }
    fun poll(millis: Long, tick: Int) {
        check(server.isSameThread)
        now = millis; ticks = tick
        val current = scheduler ?: return
        for (scene in scenes) if (scene.task == null && scene.handle.npcUuid !in pending) {
            val id = scene.handle.npcUuid
            check(current.offer(InferenceWake(id,goals.getValue(id),scene.completed.toLong()+1,
                now,setOf(InferenceReason.USER_GOAL))) == null)
            pending.add(id)
        }
        metrics.peakQueue = maxOf(metrics.peakQueue,current.queuedCount())
        current.poll(now)
        metrics.peakWorkers = maxOf(metrics.peakWorkers,current.activeCount())
        if (script.calls.get() >= 2 && initialArrivalTick == 0) initialArrivalTick = ticks
        if (initialArrivalTick != 0 && ticks-initialArrivalTick >= 20 && slowTicks == 0) {
            slowTicks = ticks-initialArrivalTick
            script.gate.countDown()
        }
        check(current.queuedCount() <= 32 && current.activeCount() <= 2)
        calls = script.calls.get()
    }
    fun setOffline(value: Boolean) {
        check(server.isSameThread && offline != value && pending.isEmpty())
        if (value) {
            check(scheduler?.activeCount() == 0 && scheduler?.queuedCount() == 0)
            retire()
        } else connect()
        offline = value
    }
    private fun retire() {
        scheduler?.close(); scheduler = null
        val old = endpoint
        if (old != null) { retiredRequests += old.received.size; old.close(); endpoint = null }
        check(budgets.values.all { it.snapshot().inFlight == null })
    }
    private fun scene(id: UUID) = scenes.single { it.handle.npcUuid == id }
    private fun goal(id: UUID): ContextGoal {
        val scene = scene(id)
        return ContextGoal(goals.getValue(id),scene.completed.toLong()+1,"Patrol the explicit finite route.",
            LlmMode.TRANSLATOR,null,budgets.getValue(id).contextRemainingCalls)
    }
    override fun prepare(wake: InferenceWake): InferencePreparation {
        check(server.isSameThread && wake.npcUuid in pending)
        val start = System.nanoTime()
        val captured = NpcContextBuilder.capture(server,actor,wake.npcUuid,goal(wake.npcUuid),policy)
        metrics.capture(System.nanoTime()-start)
        check(captured is ContextCaptureResult.Captured) { captured.toString() }
        check(admissions.getValue(wake.npcUuid).bind(captured.value))
        return InferencePreparation.Ready(captured.value,budgets.getValue(wake.npcUuid))
    }
    override fun started(wake: InferenceWake, captured: CapturedContext, requestId: UUID) {
        check(server.isSameThread)
        began[wake.npcUuid] = System.nanoTime()
        val decision = JsonObject()
        decision.addProperty("schemaVersion",1)
        decision.addProperty("contextId",captured.binding.contextId.toString())
        decision.addProperty("decision","ASSIGN"); decision.addProperty("summary","Scripted bounded patrol")
        for (n in listOf("change","question","wait")) decision.add(n,JsonNull.INSTANCE)
        decision.add("operation",JsonParser.parseString(scene(wake.npcUuid).orderJson))
        script.replies[captured.binding.contextId.toString()] = FakeOpenAiEndpoint.success(decision.toString())
    }
    override fun completed(wake: InferenceWake, captured: CapturedContext, result: InferenceResult) {
        check(server.isSameThread)
        check(result is InferenceResult.Decoded) { result.toString() }
        check(result.requestBytes+256 <= 32768)
        val start = System.nanoTime()
        val outcome = admissions.getValue(wake.npcUuid).admit(server,actor,result.decision,goal(wake.npcUuid),policy,false)
        metrics.admission(System.nanoTime()-start)
        check(outcome.state == DecisionOutcomeState.APPLIED) { outcome.toString() }
        val scene = scene(wake.npcUuid)
        check(scene.task == null)
        scene.task = checkNotNull(outcome.taskId)
        scene.taskStartedTick = ticks
        metrics.latency(System.nanoTime()-checkNotNull(began.remove(wake.npcUuid)))
        admitted++
        check(admitted <= 30)
    }
    override fun settled(wake: InferenceWake, requestId: UUID, budget: InferenceBudgetView) {
        check(server.isSameThread && budget.inFlight == null)
        check(pending.remove(wake.npcUuid))
    }
    override fun deferred(wake: InferenceWake, code: String, retryAtMillis: Long?) {
        error("Unexpected endurance deferral: " + code)
    }
    fun settled() = pending.isEmpty() && (scheduler?.activeCount() ?: 0) == 0
    fun evidence() = mapOf("httpCalls" to calls,"admittedTasks" to admitted,"slowResponseTicks" to slowTicks,
        "maxRequestBytes" to maxRequestBytes,"serverWindowReservations" to rates.reservedInCurrentWindow(),
        "trackedNpcBudgets" to rates.trackedNpcs(),"inputTokensCharged" to budgets.values.sumOf { it.snapshot().chargedInputTokens },
        "outputTokensCharged" to budgets.values.sumOf { it.snapshot().chargedOutputTokens },
        "providerUsageScope" to "SCRIPTED_USAGE; conservative byte upper bound charged",
        "pending" to pending.size,"activeWorkers" to (scheduler?.activeCount() ?: 0),
        "queue" to (scheduler?.queuedCount() ?: 0))
    override fun close() {
        script.gate.countDown()
        retire()
        check(script.replies.isEmpty() && pending.isEmpty())
        check(retiredRequests == script.calls.get())
    }
    /** All worker-visible data is detached text/counters; no server, entity or arena reference. */
    private class Script {
        val replies = ConcurrentHashMap<String,String>()
        val calls = AtomicInteger()
        val maxBytes = AtomicInteger()
        val gate = CountDownLatch(1)
        fun reply(request: FakeOpenAiEndpoint.Received): FakeOpenAiEndpoint.Reply {
            val bytes = LlmJson.utf8(request.body).size
            check(bytes+256 <= 32768)
            maxBytes.getAndUpdate { maxOf(it,bytes) }
            calls.incrementAndGet()
            val http = LlmJson.parse(request.body,65536)
            val state = LlmJson.parse(http["messages"].asJsonArray[1].asJsonObject["content"].asString,24576)
            val body = checkNotNull(replies.remove(state["contextId"].asString))
            return FakeOpenAiEndpoint.Reply(body=LlmJson.utf8(body),waitBeforeHeaders=gate)
        }
    }
}
