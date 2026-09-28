package io.samcnpc.llm

import com.google.gson.JsonNull
import com.google.gson.JsonObject
import io.samcnpc.behavior.api.*
import io.samcnpc.core.api.*
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

/** Actual Forge snapshots/admission and loopback HTTP. Rate time is explicitly virtual; game ticks are real. */
internal class SchedulerRuntimeProbe(private val server: MinecraftServer, private val actor: ServerPlayer,
                                     origin: NpcPosition) : AutoCloseable {
    private val service = CoreNpcApi.service(server)
    private val serverThread = Thread.currentThread().id
    private val handles = (0..2).map { index ->
        val result = service.summon(NpcSummonRequest(actor.uuid, "SchedulerProbe$index",
            actor.serverLevel().dimension().location().toString(),
            origin.copy(x = origin.x + index * 2, z = origin.z - 3), 0F))
        check(result.result.status == NpcActionStatus.SUCCEEDED)
        checkNotNull(result.handle)
    }
    private val policy = ContextPolicy(1, setOf(OperationType.NAVIGATE), setOf("EXTEND_TIME"),
        OperationControl.entries.toSet(), 12000, 3, 100)
    private val cases = listOf("parallel", "retry", "repair", "last", "unverified", "small",
        "goal_budget", "cancel", "close", "cancel_started", "close_started",
        "cancel_completed", "close_completed", "rate_limit", "usage", "local_provider", "unknown_provider",
        "parallel_unlimited", "retry_unlimited", "repair_unlimited", "small_unlimited", "local_provider_unlimited",
        "unknown_provider_unlimited", "many_unlimited")
    private var index = 0
    private var current = Case(cases.first())
    private var finished: String? = null
    private var httpCalls = 0
    private var workerCalls = 0

    fun poll(): String? {
        finished?.let { return it }
        if (!current.poll()) return null
        httpCalls += current.endpoint.received.size
        workerCalls += current.invocations.get()
        current.close()
        index++
        if (index < cases.size) { current = Case(cases[index]); return null }
        close()
        finished = "schedulerCases=$index schedulerHttpCalls=$httpCalls schedulerWorkerCalls=$workerCalls " +
            "schedulerThreadChecks=true schedulerVirtualClock=true lastGoalCallAdmitted=true schedulerLateReplySuppressed=true"
        return finished
    }

    override fun close() {
        current.close()
        for (handle in handles) if (service.runtime(handle) != null)
            check(service.dismiss(handle, NpcDismissMode.ONLY_IF_EMPTY).status == NpcActionStatus.SUCCEEDED)
    }

    private inner class Case(caseId: String) : InferenceHost, AutoCloseable {
        private val unlimited = caseId.endsWith("_unlimited")
        val name = caseId.removeSuffix("_unlimited")
        val invocations = AtomicInteger()
        private val replies = ConcurrentHashMap<String, FakeOpenAiEndpoint.Reply>()
        val endpoint = FakeOpenAiEndpoint { request ->
            check(Thread.currentThread().id != serverThread)
            val json = LlmJson.parse(request.body, 65536)
            val state = LlmJson.parse(json["messages"].asJsonArray[1].asJsonObject["content"].asString, 24576)
            checkNotNull(replies.remove(state["contextId"].asString))
        }
        private val settings = ProviderSettings(enabled = true, baseUrl = endpoint.baseUrl, model = "scheduler-emulator",
            apiKeyEnvironment = "", requestTimeoutSeconds = 4)
        private val transport = OpenAiCompatibleProvider(settings)
        private val provider = object : LlmProvider {
            override fun status() = transport.status()
            override fun complete(request: LlmRequest): LlmCall {
                check(Thread.currentThread().id != serverThread)
                invocations.incrementAndGet()
                if (name == "local_provider" || name == "unknown_provider") {
                    val future = java.util.concurrent.CompletableFuture.completedFuture<LlmResponse>(
                        LlmResponse.Failed(request.requestId, LlmFailure.INVALID_REQUEST))
                    return if (name == "local_provider") LlmCall(future, LlmSubmission.NOT_SENT) { true }
                    else LlmCall(future) { true }
                }
                return transport.complete(request)
            }
            override fun close() = transport.close()
        }
        private val profile = if (name == "unverified") InferenceTokenProfile.Unverified else
            InferenceTokenProfile.VerifiedByteLevel(settings.baseUrl, settings.model,
                "emulator-fixture", "no-real-model", "test-byte-bound", "test-template", 256)
        private val budgets = handles.associate { it.npcUuid to InferenceBudget(InferenceBudgetLimits(
            attempts = if (name == "last" || name == "parallel") 1 else 4,
            inputTokens = if (name == "goal_budget") 0 else 196608,
            quotaMode = if (unlimited) InferenceQuotaMode.UNLIMITED else InferenceQuotaMode.LIMITED)) }
        private val goals = handles.associate { it.npcUuid to UUID.randomUUID() }
        private val admissions = handles.associate { it.npcUuid to DecisionAdmission() }
        private val starts = mutableMapOf<UUID, Int>()
        private val outcomes = mutableListOf<UUID>()
        private val failures = mutableListOf<String>()
        private val deferrals = mutableListOf<String>()
        private val gateA = CountDownLatch(1)
        private val gateB = CountDownLatch(1)
        private val rates = InferenceRateGate(ServerInferenceResources(
            quotaMode = if (unlimited) InferenceQuotaMode.UNLIMITED else InferenceQuotaMode.LIMITED))
        // This tests scheduling, not a historical prompt size. The explicit one-token
        // case below still proves that preflight rejects/refunds an undersized request.
        private val inputAllocation = if (name == "small") 1 else 24576
        private val scheduler = InferenceScheduler(provider, settings, profile,
            InferenceAllocation(inputTokens = inputAllocation), this, rates)
        private var now = 0L
        private var ticks = 0
        private var arrivalTick: Int? = null
        private var cancelled = false
        private var closed = false

        init {
            check(scheduler.offer(wake(handles[0].npcUuid)) == null)
            if (name == "parallel") {
                check(scheduler.offer(wake(handles[0].npcUuid)) == null)
                check(scheduler.offer(wake(handles[1].npcUuid)) == null)
                check(scheduler.offer(wake(handles[2].npcUuid)) == null)
                check(scheduler.queuedCount() == 3)
            }
        }

        private fun wake(id: UUID) = InferenceWake(id, checkNotNull(goals[id]), 1, now, setOf(InferenceReason.USER_GOAL))
        private fun goal(id: UUID) = ContextGoal(checkNotNull(goals[id]), 1, "Wait for the next user goal",
            LlmMode.TRANSLATOR, null, checkNotNull(budgets[id]).contextRemainingCalls)
        private fun assertServer() = check(Thread.currentThread().id == serverThread)

        override fun prepare(wake: InferenceWake): InferencePreparation {
            assertServer()
            if (budgets.getValue(wake.npcUuid).availableCalls == 0)
                return InferencePreparation.Rejected("GOAL_CALL_BUDGET_EXHAUSTED")
            val captured = NpcContextBuilder.capture(server, actor, wake.npcUuid, goal(wake.npcUuid), policy)
            check(captured is ContextCaptureResult.Captured)
            check(checkNotNull(admissions[wake.npcUuid]).bind(captured.value))
            return InferencePreparation.Ready(captured.value, checkNotNull(budgets[wake.npcUuid]))
        }

        override fun started(wake: InferenceWake, captured: CapturedContext, requestId: UUID) {
            assertServer()
            check(checkNotNull(budgets[wake.npcUuid]).snapshot().inFlight == requestId)
            val count = (starts[wake.npcUuid] ?: 0) + 1
            starts[wake.npcUuid] = count
            if (name == "cancel_started") { scheduler.cancel(wake.npcUuid); return }
            if (name == "close_started") { scheduler.close(); return }
            val candidate = JsonObject()
            candidate.addProperty("schemaVersion", 1)
            candidate.addProperty("contextId", captured.binding.contextId.toString())
            candidate.addProperty("decision", "WAIT")
            candidate.addProperty("summary", "Deterministic emulator fixture")
            for (field in listOf("operation", "change", "question", "wait")) candidate.add(field, JsonNull.INSTANCE)
            candidate.add("wait", JsonObject().also {
                it.addProperty("trigger", "USER_UPDATE"); it.add("ticks", JsonNull.INSTANCE)
            })
            val body = when {
                name == "usage" -> FakeOpenAiEndpoint.success(candidate.toString()).replace("\"prompt_tokens\":12", "\"prompt_tokens\":999999")
                name == "repair" -> FakeOpenAiEndpoint.success("{broken")
                else -> FakeOpenAiEndpoint.success(candidate.toString())
            }
            val gate = when {
                name in setOf("cancel", "close") -> gateA
                name == "parallel" && wake.npcUuid == handles[0].npcUuid -> gateA
                name == "parallel" && wake.npcUuid == handles[1].npcUuid -> gateB
                else -> null
            }
            replies[captured.binding.contextId.toString()] = FakeOpenAiEndpoint.Reply(body = LlmJson.utf8(body),
                status = when {
                    name == "rate_limit" && count == 1 -> 429
                    name in setOf("retry", "cancel_completed", "close_completed") && count == 1 -> 503
                    else -> 200
                }, headers = if (name == "rate_limit") mapOf("Retry-After" to "120") else emptyMap(), waitBeforeHeaders = gate)
        }

        override fun completed(wake: InferenceWake, captured: CapturedContext, result: InferenceResult) {
            assertServer()
            if (name == "local_provider" || name == "unknown_provider") {
                check(result is InferenceResult.Failed && result.providerInvoked)
                check(result.submission == if (name == "local_provider") LlmSubmission.NOT_SENT else LlmSubmission.UNKNOWN)
            }
            if (name == "cancel_completed") scheduler.cancel(wake.npcUuid)
            if (name == "close_completed") scheduler.close()
            val budget = checkNotNull(budgets[wake.npcUuid])
            check(budget.snapshot().inFlight != null)
            if (unlimited) check(budget.contextRemainingCalls == null)
            else check(checkNotNull(budget.contextRemainingCalls) > 0)
            when (result) {
                is InferenceResult.Decoded -> {
                    check(result.requestBytes + 256 <= inputAllocation)
                    val outcome = checkNotNull(admissions[wake.npcUuid]).admit(server, actor, result.decision,
                        goal(wake.npcUuid), policy, false)
                    check(outcome.state == DecisionOutcomeState.NO_EFFECT) { "$name $outcome" }
                    outcomes.add(wake.npcUuid)
                }
                is InferenceResult.Failed -> failures.add(result.code)
                is InferenceResult.Mission -> error("Legacy scheduler fixture must not receive mission-stage output")
            }
        }

        override fun settled(wake: InferenceWake, requestId: UUID, budget: InferenceBudgetView) {
            assertServer()
            check(budget.inFlight == null && budget == budgets.getValue(wake.npcUuid).snapshot())
        }

        override fun deferred(wake: InferenceWake, code: String, retryAtMillis: Long?) {
            assertServer()
            deferrals.add(code)
            if (code in setOf("TRANSPORT_RETRY_QUEUED", "OUTPUT_REPAIR_QUEUED"))
                now = checkNotNull(retryAtMillis)
        }

        fun poll(): Boolean {
            assertServer()
            check(++ticks < if (name == "many") 1800 else 180) { "$name scheduler timeout starts=$starts failures=$failures deferrals=$deferrals" }
            scheduler.poll(now)
            check(scheduler.activeCount() <= 2 && scheduler.queuedCount() <= 32)
            if (name == "parallel") {
                if (endpoint.received.size >= 2 && arrivalTick == null) arrivalTick = ticks
                val arrival = arrivalTick
                if (arrival != null && ticks - arrival >= 10) gateB.countDown()
                if (outcomes.size == 2) {
                    check(outcomes == listOf(handles[1].npcUuid, handles[2].npcUuid))
                    check(starts.values.all { it == 1 })
                    gateA.countDown()
                }
            }
            if (name in setOf("cancel", "close") && endpoint.received.size == 1 && !cancelled) {
                cancelled = true
                if (name == "cancel") scheduler.cancel(handles[0].npcUuid) else scheduler.close()
                gateA.countDown()
            }
            if (scheduler.activeCount() != 0 || scheduler.queuedCount() != 0) return false
            when (name) {
                "parallel" -> {
                    check(outcomes == listOf(handles[1].npcUuid, handles[2].npcUuid, handles[0].npcUuid)) {
                        "$name outcomes=$outcomes starts=$starts failures=$failures deferrals=$deferrals httpCalls=${endpoint.received.size}"
                    }
                    check(budgets.values.all { it.contextRemainingCalls == if (unlimited) null else 0 })
                }
                "retry" -> {
                    check(failures == listOf("PROVIDER_UNAVAILABLE") && outcomes.size == 1)
                    check(endpoint.received.size == 2 && deferrals == listOf("TRANSPORT_RETRY_QUEUED"))
                }
                "rate_limit" -> {
                    check(failures == listOf("PROVIDER_RATE_LIMITED") && outcomes.size == 1)
                    check(now == 120000L && endpoint.received.size == 2)
                }
                "usage" -> check(failures == listOf("PROFILE_USAGE_BOUND_VIOLATED") && outcomes.isEmpty())
                "cancel_completed", "close_completed" -> {
                    check(failures == listOf("PROVIDER_UNAVAILABLE") && deferrals.isEmpty())
                    check(endpoint.received.size == 1 && outcomes.isEmpty())
                }
                "repair" -> {
                    check(failures.size == 2 && outcomes.isEmpty() && endpoint.received.size == 2)
                    check(deferrals == listOf("OUTPUT_REPAIR_QUEUED"))
                    check(endpoint.received.last().body.contains("previous candidate was rejected"))
                }
                "last" -> {
                    check(outcomes.size == 1 && budgets.getValue(handles[0].npcUuid).contextRemainingCalls == 0)
                    check(scheduler.offer(wake(handles[0].npcUuid)) == null)
                    now += 10000; scheduler.poll(now)
                    check(deferrals.last() == "GOAL_CALL_BUDGET_EXHAUSTED")
                    check(endpoint.received.size == 1)
                }
                "unverified" -> check(failures == listOf("UNVERIFIED_TOKEN_PROFILE") && invocations.get() == 0)
                "small" -> check(failures == listOf("INPUT_TOKEN_BOUND_EXCEEDED") && invocations.get() == 0)
                "local_provider", "unknown_provider" -> {
                    check(failures == listOf("PROVIDER_INVALID_REQUEST") && invocations.get() == 1)
                    check(endpoint.received.isEmpty() && deferrals.isEmpty())
                    val charge = budgets.getValue(handles[0].npcUuid).snapshot()
                    check(charge.settledAttempts == if (name == "local_provider") 0 else 1)
                    check(rates.reservedInCurrentWindow() == if (name == "local_provider") 0 else 1)
                }
                "goal_budget" -> check(deferrals == listOf("GOAL_INPUT_BUDGET_EXHAUSTED") && scheduler.reservedAttempts == 0L)
                "many" -> {
                    check(unlimited && failures.isEmpty() && deferrals.isEmpty())
                    val count = outcomes.size
                    check(count == invocations.get() && count == endpoint.received.size)
                    check(budgets.getValue(handles[0].npcUuid).snapshot().settledAttempts == count)
                    check(rates.reservedInCurrentWindow() == count && rates.retainedExactEntries() == 0)
                    check(rates.retainedMinuteBuckets() <= 61 && rates.trackedNpcs() == 1)
                    if (count < 40) {
                        now += 10000
                        check(scheduler.offer(wake(handles[0].npcUuid)) == null)
                        return false
                    }
                    check(count == 40)
                }
                "cancel", "close" -> check(cancelled && outcomes.isEmpty() && failures.isEmpty() && invocations.get() == 1)
                "cancel_started", "close_started" -> check(outcomes.isEmpty() && failures.isEmpty() && invocations.get() == 0)
            }
            check(budgets.values.all { it.snapshot().inFlight == null })
            if (name in setOf("unverified", "small", "cancel_started", "close_started", "local_provider")) {
                check(budgets.values.all { it.snapshot().settledAttempts == 0 && it.snapshot().chargedInputTokens == 0L }) {
                    "$name charged a model call although no provider was invoked"
                }
                check(rates.reservedInCurrentWindow() == 0) { "$name retained an unsent hourly reservation" }
            }
            return true
        }

        override fun close() {
            if (closed) return
            closed = true
            gateA.countDown(); gateB.countDown()
            scheduler.close()
            endpoint.close()
        }
    }
}
