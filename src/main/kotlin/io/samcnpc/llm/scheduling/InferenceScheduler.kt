package io.samcnpc.llm.scheduling

import com.mojang.logging.LogUtils
import io.samcnpc.llm.api.*
import io.samcnpc.llm.config.ProviderSettings
import io.samcnpc.llm.context.CapturedContext
import java.util.UUID
import java.util.concurrent.*

/** One endpoint/config lifetime. The external server controller retains the rate gate across reconfiguration. */
internal class InferenceScheduler(
    private val provider: LlmProvider,
    private val settings: ProviderSettings,
    private val profile: InferenceTokenProfile,
    private val allocation: InferenceAllocation,
    private val host: InferenceHost,
    private val rates: InferenceRateGate,
) : AutoCloseable {
    private class Active(val wake: InferenceWake, val input: InferenceInput, val budget: InferenceBudget,
                         val permit: CircuitPermit.Granted, val cancellation: InferenceCancellation,
                         val future: CompletableFuture<InferenceResult>)
    private val creatingThread = Thread.currentThread().id
    private val queue = InferenceQueue()
    private val circuit = ProviderCircuit()
    private val active = linkedMapOf<UUID, Active>()
    private val workers = ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, ArrayBlockingQueue(2),
        { task -> Thread(task, "samcnpc-llm-inference").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    private var closed = false
    private var polling = false
    private var completing: Active? = null
    private var lastPollMillis = 0L
    var reservedAttempts = 0L
        private set
    var completedProviderInvocations = 0L
        private set

    fun offer(wake: InferenceWake): String? {
        checkThread()
        if (closed) return "INFERENCE_SCHEDULER_CLOSED"
        return queue.offer(wake)
    }

    fun cancel(npcUuid: UUID) {
        checkThread()
        queue.remove(npcUuid)
        active[npcUuid]?.cancellation?.cancel()
        completing?.takeIf { it.wake.npcUuid == npcUuid }?.cancellation?.cancel()
        // Keep a cancelled physical worker occupied until drained; repeated new goals cannot grow the executor queue.
    }

    fun queuedCount(): Int { checkThread(); return queue.size }
    fun activeCount(): Int { checkThread(); return active.size }

    /** Bounded completion/dispatch work only; encoding, schema/JSON parsing and HTTP run on workers. */
    fun poll(nowMillis: Long) {
        checkThread()
        check(!polling) { "Inference host cannot recursively poll the coordinator" }
        if (closed) return
        if (nowMillis < lastPollMillis || nowMillis > Long.MAX_VALUE - InferenceRateGate.WINDOW_MILLIS) {
            LOGGER.warn("LLM inference scheduler closed: invalid monotonic clock")
            close(); return
        }
        lastPollMillis = nowMillis
        polling = true
        try { pollReady(nowMillis) } finally { polling = false }
    }

    private fun pollReady(nowMillis: Long) {
        for (attempt in active.values.toList()) {
            if (!attempt.future.isDone) continue
            active.remove(attempt.wake.npcUuid)
            val completed = try { attempt.future.join() }
            catch (error: CompletionException) {
                LOGGER.warn("LLM inference future failed request={} exception={}", attempt.input.requestId, error.javaClass.simpleName)
                InferenceResult.Failed("INFERENCE_WORKER_FAILED")
            }
            val submission = attempt.cancellation.submission()
            val result = when (completed) {
                is InferenceResult.Failed -> completed.copy(submission = submission)
                is InferenceResult.Decoded -> completed.copy(submission = submission)
            }
            if (result.providerInvoked) completedProviderInvocations++
            val sent = when (submission) { LlmSubmission.NOT_SENT -> "false"; LlmSubmission.SUBMITTED -> "true"; LlmSubmission.UNKNOWN -> "unknown" }
            LOGGER.info("LLM request={} code={} providerInvoked={} requestSent={} {}", attempt.input.requestId,
                (result as? InferenceResult.Failed)?.code ?: "DECODED", result.providerInvoked, sent,
                result.metrics?.describe() ?: "requestMetrics=UNAVAILABLE")
            val failure = (result as? InferenceResult.Failed)?.providerFailure
            circuit.complete(attempt.permit, nowMillis,
                if (result is InferenceResult.Decoded) null else failure ?: LlmFailure.CANCELLED,
                (result as? InferenceResult.Failed)?.retryAfterSeconds)
            completing = attempt
            try {
                if (!attempt.cancellation.isCancelled()) {
                    host.completed(attempt.wake, attempt.input.captured, result)
                    // Local preparation gets one attempt per wake and no automatic retry loop.
                    if (!closed && !attempt.cancellation.isCancelled() && submission != LlmSubmission.NOT_SENT)
                        retry(attempt.wake, result, nowMillis)
                }
            } catch (error: RuntimeException) {
                hostFailed(error)
            } finally {
                completing = null
                settle(attempt)
            }
            if (closed) return
        }
        repeat(2) {
            if (closed || active.size >= 2) return
            val wake = queue.takeReady(nowMillis, active.keys) ?: return
            val permit = circuit.acquire(nowMillis)
            if (permit is CircuitPermit.Deferred) {
                // A probe occupies temporary endpoint capacity; retain the wake until it resolves.
                val retryAt = if (permit.code == "PROVIDER_PROBE_IN_FLIGHT") nowMillis + 1000 else permit.earliestMillis
                defer(wake, permit.code, retryAt)
                return
            }
            check(permit is CircuitPermit.Granted)
            when (val rate = rates.preview(wake.npcUuid, nowMillis, allocation.charge())) {
                is RatePermit.Deferred -> {
                    circuit.complete(permit, nowMillis, LlmFailure.CANCELLED)
                    defer(wake, rate.code, rate.earliestMillis)
                    return@repeat
                }
                RatePermit.Granted -> Unit
            }
            prepare(wake, permit, nowMillis)
        }
    }

    private fun prepare(wake: InferenceWake, permit: CircuitPermit.Granted, now: Long) {
        val prepared = try { host.prepare(wake) } catch (error: RuntimeException) {
            circuit.complete(permit, now, LlmFailure.CANCELLED)
            hostFailed(error); return
        }
        if (prepared is InferencePreparation.Rejected) {
            circuit.complete(permit, now, LlmFailure.CANCELLED)
            defer(wake, prepared.code, null); return
        }
        check(prepared is InferencePreparation.Ready)
        if (closed) return
        val budgetProblem = prepared.budget.problem(allocation.charge())
        if (budgetProblem != null) {
            circuit.complete(permit, now, LlmFailure.CANCELLED)
            defer(wake, budgetProblem, null); return
        }
        val id = UUID.randomUUID()
        when (val rate = rates.reserve(wake.npcUuid, now, allocation.charge(), id)) {
            is RatePermit.Deferred -> {
                circuit.complete(permit, now, LlmFailure.CANCELLED)
                defer(wake, rate.code, rate.earliestMillis); return
            }
            RatePermit.Granted -> reservedAttempts++
        }
        val problem = prepared.budget.reserve(id, allocation.charge())
        if (problem != null) {
            rates.releaseUnsent(id)
            circuit.complete(permit, now, LlmFailure.CANCELLED)
            defer(wake, problem, null); return
        }
        val input = InferenceInput(id, prepared.captured, settings, profile, allocation, wake.feedbackCode)
        val cancellation = InferenceCancellation()
        val future = CompletableFuture<InferenceResult>()
        // Publish the slot before invoking the host so cancellation/close from that callback is effective.
        active[wake.npcUuid] = Active(wake, input, prepared.budget, permit, cancellation, future)
        try {
            host.started(wake, prepared.captured, id)
        } catch (error: RuntimeException) {
            hostFailed(error); return
        }
        if (closed) return
        if (cancellation.isCancelled()) {
            future.complete(InferenceResult.Failed("CANCELLED", LlmFailure.CANCELLED))
            return
        }
        // These closures capture detached input/provider/cancellation/future only, never coordinator/host.
        val transport = provider
        try {
            val submitted = CompletableFuture.supplyAsync({ InferenceWork.run(input, transport, cancellation) }, workers)
            submitted.whenComplete { result, error ->
                if (error == null) future.complete(result) else future.completeExceptionally(error)
            }
        } catch (_: RejectedExecutionException) {
            future.complete(InferenceResult.Failed("INFERENCE_EXECUTOR_CLOSED", LlmFailure.CANCELLED))
        }
    }

    private fun retry(wake: InferenceWake, result: InferenceResult, now: Long) {
        if (result !is InferenceResult.Failed) return
        val failure = result.providerFailure
        val isRetry = failure in setOf(LlmFailure.TIMEOUT, LlmFailure.UNAVAILABLE, LlmFailure.RATE_LIMITED) &&
            wake.transportRetries == 0
        val isRepair = failure in setOf(LlmFailure.INVALID_OUTPUT, LlmFailure.INCOMPLETE) && wake.outputRepairs == 0
        if (!isRetry && !isRepair) return
        val delay = maxOf(InferenceRateGate.MIN_GAP_MILLIS, (result.retryAfterSeconds ?: 0).coerceIn(0, 300) * 1000L)
        val next = InferenceWake(wake.npcUuid, wake.goalId, wake.goalRevision, now + delay,
            wake.reasons + if (isRetry) InferenceReason.TRANSPORT_RETRY else InferenceReason.OUTPUT_REPAIR,
            wake.transportRetries + if (isRetry) 1 else 0, wake.outputRepairs + if (isRepair) 1 else 0,
            if (isRepair) result.code else wake.feedbackCode)
        val problem = queue.offer(next)
        host.deferred(next, problem ?: if (isRetry) "TRANSPORT_RETRY_QUEUED" else "OUTPUT_REPAIR_QUEUED",
            if (problem == null) next.readyMillis else null)
    }

    private fun defer(wake: InferenceWake, code: String, ready: Long?) {
        val next = if (ready == null) null else InferenceWake(wake.npcUuid, wake.goalId, wake.goalRevision,
            ready, wake.reasons, wake.transportRetries, wake.outputRepairs, wake.feedbackCode)
        val problem = if (next == null) null else queue.offer(next)
        try { host.deferred(wake, problem ?: code, if (problem == null) ready else null) }
        catch (error: RuntimeException) { hostFailed(error) }
    }

    private fun settle(attempt: Active) {
        val id = attempt.input.requestId
        if (attempt.cancellation.submission() == LlmSubmission.NOT_SENT) {
            check(attempt.budget.releaseUnsent(id))
            rates.releaseUnsent(id)
        } else {
            check(attempt.budget.settle(id))
            rates.completeSubmission(id)
        }
        try { host.settled(attempt.wake, attempt.input.requestId, attempt.budget.snapshot()) }
        catch (error: RuntimeException) { hostFailed(error) }
    }

    private fun hostFailed(error: RuntimeException) {
        LOGGER.warn("LLM inference host failed; scheduler closed exception={}", error.javaClass.simpleName)
        close()
    }

    override fun close() {
        checkThread()
        if (closed) return
        closed = true
        queue.clear()
        completing?.cancellation?.cancel()
        for (attempt in active.values) {
            attempt.cancellation.cancel()
            settle(attempt)
        }
        active.clear()
        workers.shutdownNow()
        provider.close()
    }

    private fun checkThread() { check(Thread.currentThread().id == creatingThread) { "Inference coordinator requires its creating server thread" } }
    private companion object { val LOGGER = LogUtils.getLogger() }
}
