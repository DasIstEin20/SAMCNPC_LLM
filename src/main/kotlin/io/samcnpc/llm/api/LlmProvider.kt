package io.samcnpc.llm.api

import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/** Only immutable text crosses the transport boundary. No Minecraft objects or action handles. */
class LlmRequest(
    val requestId: UUID,
    val systemPrompt: String,
    val contextJson: String,
    val responseSchemaJson: String,
)

interface LlmProvider : AutoCloseable {
    fun status(): ProviderStatus
    /** Invoke from the inference worker, never the server tick; no world access is permitted. */
    fun complete(request: LlmRequest): LlmCall
    override fun close()
}

class LlmCall internal constructor(
    future: CompletableFuture<LlmResponse>,
    private val cancellation: () -> Boolean,
) {
    val result: CompletionStage<LlmResponse> = future.minimalCompletionStage()
    fun cancel(): Boolean = cancellation()
}

sealed interface LlmResponse {
    /** Untrusted candidate JSON, not a validated decision or a report of any world effect. */
    class Candidate(val requestId: UUID, val decisionJson: String, val usage: LlmUsage?) : LlmResponse
    data class Failed(
        val requestId: UUID,
        val code: LlmFailure,
        val httpStatus: Int? = null,
        val retryAfterSeconds: Int? = null,
    ) : LlmResponse
}

data class LlmUsage(val promptTokens: Int?, val completionTokens: Int?)
enum class LlmFailure {
    DISABLED, INVALID_CONFIGURATION, CONTEXT_TOO_LARGE, INVALID_REQUEST, BUSY,
    TIMEOUT, UNAVAILABLE, UNAUTHORIZED, RATE_LIMITED, INCOMPATIBLE,
    REFUSED, INCOMPLETE, INVALID_OUTPUT, RESPONSE_TOO_LARGE, CANCELLED,
}
