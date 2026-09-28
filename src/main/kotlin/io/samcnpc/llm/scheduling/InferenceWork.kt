package io.samcnpc.llm.scheduling

import com.mojang.logging.LogUtils
import io.samcnpc.llm.api.*
import io.samcnpc.llm.config.ProviderSettings
import io.samcnpc.llm.context.*
import io.samcnpc.llm.decision.*
import java.io.IOException
import java.util.UUID
import java.util.concurrent.*

/** All inputs are detached values. This worker never accepts a server, player, entity or world callback. */
internal class InferenceInput(val requestId: UUID, val captured: CapturedContext, val settings: ProviderSettings,
                              val profile: InferenceTokenProfile, val allocation: InferenceAllocation,
                              val feedbackCode: String? = null)

internal sealed interface InferenceResult {
    val providerInvoked: Boolean
    val metrics: RequestMetrics?
    val submission: LlmSubmission
    data class Decoded(val decision: LlmDecision, val usage: LlmUsage?, val inputTokenUpperBound: Long,
                       val requestBytes: Int, override val metrics: RequestMetrics? = null,
                       override val submission: LlmSubmission = LlmSubmission.UNKNOWN) : InferenceResult { override val providerInvoked = true }
    data class Mission(val reply: io.samcnpc.llm.mission.MissionReply, val usage: LlmUsage?,
                       override val metrics: RequestMetrics?,
                       override val submission: LlmSubmission = LlmSubmission.UNKNOWN) : InferenceResult { override val providerInvoked = true }
    data class Failed(val code: String, val providerFailure: LlmFailure? = null,
                      val retryAfterSeconds: Int? = null, override val providerInvoked: Boolean = false,
                      override val metrics: RequestMetrics? = null,
                      override val submission: LlmSubmission = LlmSubmission.UNKNOWN) : InferenceResult
}

/** Cancelling before/after call publication has the same effect; the physical worker is still drained. */
internal class InferenceCancellation {
    private var cancelled = false
    private var call: LlmCall? = null
    private var submission = LlmSubmission.NOT_SENT
    @Synchronized fun isCancelled(): Boolean = cancelled
    @Synchronized fun submission(): LlmSubmission = submission
    @Synchronized fun beginInvocation(): Boolean {
        if (cancelled) return false
        check(submission == LlmSubmission.NOT_SENT && call == null)
        submission = LlmSubmission.UNKNOWN
        return true
    }
    fun cancel() {
        val active = synchronized(this) { cancelled = true; call }
        active?.cancel()
    }
    fun attach(value: LlmCall) {
        val cancel = synchronized(this) {
            check(call == null && submission == LlmSubmission.UNKNOWN)
            call = value
            submission = value.submission
            cancelled
        }
        if (cancel) value.cancel()
    }
}

internal object InferenceWork {
    private val LOGGER = LogUtils.getLogger()

    fun run(input: InferenceInput, provider: LlmProvider, cancellation: InferenceCancellation): InferenceResult {
        var invoked = false
        var metrics: RequestMetrics? = null
        try {
            if (cancellation.isCancelled()) return InferenceResult.Failed("CANCELLED", LlmFailure.CANCELLED)
            if (input.settings.problem() != null) return InferenceResult.Failed("INVALID_CONFIGURATION", LlmFailure.INVALID_CONFIGURATION)
            if (!input.settings.enabled) return InferenceResult.Failed("DISABLED", LlmFailure.DISABLED)
            if (input.settings.maxOutputTokens > input.allocation.outputTokens)
                return InferenceResult.Failed("OUTPUT_ALLOCATION_TOO_SMALL")
            val prepared = InferenceRequestPreparation.prepare(input)
            metrics = prepared.metrics
            if (prepared is RequestPreparation.Rejected) return InferenceResult.Failed(prepared.code, metrics = metrics)
            check(prepared is RequestPreparation.Ready)
            val request = prepared.request
            val bytes = prepared.metrics.totalHttpRequestBytes
            val tokens = checkNotNull(prepared.metrics.calculatedTokenUpperBound)
            if (!cancellation.beginInvocation()) return InferenceResult.Failed("CANCELLED", LlmFailure.CANCELLED, metrics = metrics)
            invoked = true
            val call = provider.complete(request)
            cancellation.attach(call)
            val response = call.result.toCompletableFuture()
                .get(input.settings.requestTimeoutSeconds.toLong() + 5L, TimeUnit.SECONDS)
            if (cancellation.isCancelled()) return InferenceResult.Failed("CANCELLED", LlmFailure.CANCELLED, providerInvoked = true, metrics = metrics)
            val responseRequestId = when (response) {
                is LlmResponse.Candidate -> response.requestId
                is LlmResponse.Failed -> response.requestId
            }
            if (responseRequestId != input.requestId)
                return InferenceResult.Failed("RESPONSE_REQUEST_MISMATCH", LlmFailure.INVALID_OUTPUT, providerInvoked = true, metrics = metrics)
            if (response is LlmResponse.Failed)
                return InferenceResult.Failed("PROVIDER_" + response.code.name, response.code, response.retryAfterSeconds, true, metrics)
            check(response is LlmResponse.Candidate)
            val usage = response.usage
            if (usage?.promptTokens != null && usage.promptTokens.toLong() > tokens ||
                usage?.completionTokens != null && usage.completionTokens > input.allocation.outputTokens)
                return InferenceResult.Failed("PROFILE_USAGE_BOUND_VIOLATED", LlmFailure.INCOMPATIBLE, providerInvoked = true, metrics = metrics)
            val mission = input.captured.goal.mission
            if (mission != null && mission.stage != io.samcnpc.llm.mission.MissionStage.OPERATION) {
                return when (val parsed = io.samcnpc.llm.mission.MissionProtocol.decode(response.decisionJson, input.captured)) {
                    is io.samcnpc.llm.mission.MissionDecodeResult.Accepted -> InferenceResult.Mission(parsed.reply, usage, metrics)
                    is io.samcnpc.llm.mission.MissionDecodeResult.Rejected ->
                        InferenceResult.Failed(parsed.code, LlmFailure.INVALID_OUTPUT, providerInvoked = true, metrics = metrics)
                }
            }
            val decoded = if (input.settings.responseFormat == io.samcnpc.llm.config.ResponseFormat.SAM_EXPRESSION_V1)
                io.samcnpc.llm.expression.SamExpressionDecoder.decode(response.decisionJson,
                    input.captured.binding.contextId, input.captured.goal.mode == LlmMode.PLANNER)
            else DecisionDecoder.decode(response.decisionJson)
            return when (val parsed = decoded) {
                is DecisionDecodeResult.Rejected -> InferenceResult.Failed(parsed.code, LlmFailure.INVALID_OUTPUT, providerInvoked = true, metrics = metrics)
                is DecisionDecodeResult.Accepted -> {
                    if (parsed.value.contextId != input.captured.binding.contextId)
                        InferenceResult.Failed("DECISION_CONTEXT_MISMATCH", LlmFailure.INVALID_OUTPUT, providerInvoked = true, metrics = metrics)
                    else {
                        val problem = DecisionPolicy.problem(parsed.value, input.captured)
                        if (problem != null) InferenceResult.Failed(problem, LlmFailure.INVALID_OUTPUT, providerInvoked = true, metrics = metrics)
                        else InferenceResult.Decoded(parsed.value, usage, tokens, bytes, metrics)
                    }
                }
            }
        } catch (_: IOException) {
            cancellation.cancel()
            return InferenceResult.Failed("INVALID_REQUEST_ENCODING", LlmFailure.INVALID_REQUEST, providerInvoked = invoked, metrics = metrics)
        } catch (_: InterruptedException) {
            cancellation.cancel()
            Thread.currentThread().interrupt()
            return InferenceResult.Failed("CANCELLED", LlmFailure.CANCELLED, providerInvoked = invoked, metrics = metrics)
        } catch (_: TimeoutException) {
            cancellation.cancel()
            return InferenceResult.Failed("WORKER_PROVIDER_TIMEOUT", LlmFailure.TIMEOUT, providerInvoked = invoked, metrics = metrics)
        } catch (error: ExecutionException) {
            cancellation.cancel()
            LOGGER.warn("LLM worker provider future failed request={} exception={}", input.requestId, error.javaClass.simpleName)
            return InferenceResult.Failed("PROVIDER_FUTURE_FAILED", LlmFailure.UNAVAILABLE, providerInvoked = invoked, metrics = metrics)
        } catch (error: RuntimeException) {
            cancellation.cancel()
            LOGGER.warn("LLM worker failed request={} exception={}", input.requestId, error.javaClass.simpleName)
            return InferenceResult.Failed("INFERENCE_WORKER_FAILED", providerInvoked = invoked, metrics = metrics)
        }
    }
}
