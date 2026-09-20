package io.samcnpc.llm.scheduling

import com.mojang.logging.LogUtils
import io.samcnpc.llm.api.*
import io.samcnpc.llm.config.ProviderSettings
import io.samcnpc.llm.context.*
import io.samcnpc.llm.decision.*
import io.samcnpc.llm.provider.ChatCompletionCodec
import java.io.IOException
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** All inputs are detached values. This worker never accepts a server, player, entity or world callback. */
internal class InferenceInput(val requestId: UUID, val captured: CapturedContext, val settings: ProviderSettings,
                              val profile: InferenceTokenProfile, val allocation: InferenceAllocation,
                              val feedbackCode: String? = null)

internal sealed interface InferenceResult {
    val providerInvoked: Boolean
    data class Decoded(val decision: LlmDecision, val usage: LlmUsage?, val inputTokenUpperBound: Long,
                       val requestBytes: Int) : InferenceResult { override val providerInvoked = true }
    data class Failed(val code: String, val providerFailure: LlmFailure? = null,
                      val retryAfterSeconds: Int? = null, override val providerInvoked: Boolean = false) : InferenceResult
}

/** Cancelling before/after call publication has the same effect; the physical worker is still drained. */
internal class InferenceCancellation {
    private val cancelled = AtomicBoolean()
    private val call = AtomicReference<LlmCall?>()
    fun isCancelled(): Boolean = cancelled.get()
    fun cancel() {
        cancelled.set(true)
        call.get()?.cancel()
    }
    fun attach(value: LlmCall) {
        check(call.compareAndSet(null, value))
        if (cancelled.get()) value.cancel()
    }
}

internal object InferenceWork {
    private val LOGGER = LogUtils.getLogger()

    fun run(input: InferenceInput, provider: LlmProvider, cancellation: InferenceCancellation): InferenceResult {
        var invoked = false
        try {
            if (cancellation.isCancelled()) return InferenceResult.Failed("CANCELLED", LlmFailure.CANCELLED)
            if (input.settings.problem() != null) return InferenceResult.Failed("INVALID_CONFIGURATION", LlmFailure.INVALID_CONFIGURATION)
            if (!input.settings.enabled) return InferenceResult.Failed("DISABLED", LlmFailure.DISABLED)
            if (input.settings.maxOutputTokens > input.allocation.outputTokens)
                return InferenceResult.Failed("OUTPUT_ALLOCATION_TOO_SMALL")
            val encoded = NpcContextEncoder.encode(input.captured)
            if (encoded is ContextEncodingResult.Rejected) return InferenceResult.Failed(encoded.code)
            check(encoded is ContextEncodingResult.Encoded)
            val context = encoded.value
            val prompt = DecisionPrompt.text + if (input.feedbackCode == null) "" else
                "\nThe previous candidate was rejected with code " + input.feedbackCode +
                    ". Produce a corrected decision using the current STATE and output contract."
            val request = LlmRequest(input.requestId, prompt, context.stateJson,
                DecisionSchema.forContext(context.binding.contextId, input.captured.policy,
                    planner = input.captured.goal.mode == LlmMode.PLANNER))
            val bytes = ChatCompletionCodec.request(request, input.settings).size
            if (bytes > input.settings.maxContextBytes) return InferenceResult.Failed("CONTEXT_TOO_LARGE")
            val tokens = input.profile.upperBound(input.settings, bytes)
                ?: return InferenceResult.Failed("UNVERIFIED_TOKEN_PROFILE")
            if (tokens < 0) return InferenceResult.Failed("INVALID_TOKEN_PROFILE")
            if (tokens > input.allocation.inputTokens) return InferenceResult.Failed("INPUT_TOKEN_BOUND_EXCEEDED")
            if (cancellation.isCancelled()) return InferenceResult.Failed("CANCELLED", LlmFailure.CANCELLED)
            invoked = true
            val call = provider.complete(request)
            cancellation.attach(call)
            val response = call.result.toCompletableFuture()
                .get(input.settings.requestTimeoutSeconds.toLong() + 5L, TimeUnit.SECONDS)
            if (cancellation.isCancelled()) return InferenceResult.Failed("CANCELLED", LlmFailure.CANCELLED, providerInvoked = true)
            if (response is LlmResponse.Failed)
                return InferenceResult.Failed("PROVIDER_" + response.code.name, response.code, response.retryAfterSeconds, true)
            check(response is LlmResponse.Candidate)
            val usage = response.usage
            if (usage?.promptTokens != null && usage.promptTokens.toLong() > tokens ||
                usage?.completionTokens != null && usage.completionTokens > input.allocation.outputTokens)
                return InferenceResult.Failed("PROFILE_USAGE_BOUND_VIOLATED", LlmFailure.INCOMPATIBLE, providerInvoked = true)
            return when (val parsed = DecisionDecoder.decode(response.decisionJson)) {
                is DecisionDecodeResult.Rejected -> InferenceResult.Failed(parsed.code, LlmFailure.INVALID_OUTPUT, providerInvoked = true)
                is DecisionDecodeResult.Accepted -> {
                    if (parsed.value.contextId != context.binding.contextId)
                        InferenceResult.Failed("DECISION_CONTEXT_MISMATCH", LlmFailure.INVALID_OUTPUT, providerInvoked = true)
                    else {
                        val problem = DecisionPolicy.problem(parsed.value, input.captured)
                        if (problem != null) InferenceResult.Failed(problem, LlmFailure.INVALID_OUTPUT, providerInvoked = true)
                        else InferenceResult.Decoded(parsed.value, usage, tokens, bytes)
                    }
                }
            }
        } catch (_: IOException) {
            cancellation.cancel()
            return InferenceResult.Failed("INVALID_REQUEST_ENCODING", LlmFailure.INVALID_REQUEST, providerInvoked = invoked)
        } catch (_: InterruptedException) {
            cancellation.cancel()
            Thread.currentThread().interrupt()
            return InferenceResult.Failed("CANCELLED", LlmFailure.CANCELLED, providerInvoked = invoked)
        } catch (_: TimeoutException) {
            cancellation.cancel()
            return InferenceResult.Failed("WORKER_PROVIDER_TIMEOUT", LlmFailure.TIMEOUT, providerInvoked = invoked)
        } catch (error: ExecutionException) {
            cancellation.cancel()
            LOGGER.warn("LLM worker provider future failed request={} exception={}", input.requestId, error.javaClass.simpleName)
            return InferenceResult.Failed("PROVIDER_FUTURE_FAILED", LlmFailure.UNAVAILABLE, providerInvoked = invoked)
        } catch (error: RuntimeException) {
            cancellation.cancel()
            LOGGER.warn("LLM worker failed request={} exception={}", input.requestId, error.javaClass.simpleName)
            return InferenceResult.Failed("INFERENCE_WORKER_FAILED", providerInvoked = invoked)
        }
    }
}
