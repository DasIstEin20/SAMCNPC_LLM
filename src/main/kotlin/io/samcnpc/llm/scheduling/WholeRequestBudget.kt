package io.samcnpc.llm.scheduling

import io.samcnpc.llm.api.LlmRequest
import io.samcnpc.llm.config.ProviderSettings
import io.samcnpc.llm.context.ContextEncodingResult
import io.samcnpc.llm.context.NpcContextEncoder
import io.samcnpc.llm.provider.ChatCompletionCodec
import java.util.UUID

/** Raw UTF-8 section bytes; total additionally includes JSON escaping and HTTP JSON wrappers. */
internal data class RequestMetrics(val stateBytes: Int, val systemPromptBytes: Int, val contractBytes: Int,
    val responseSchemaBytes: Int, val totalHttpRequestBytes: Int, val calculatedTokenUpperBound: Long?,
    val configuredInputAllocation: Int, val configuredOutputAllocation: Int, val detailLevel: Int,
    val configuredContextWindow: Int, val templateTokenReserve: Int?, val tokenProfile: String) {
    fun describe(): String = "stateBytes=$stateBytes systemPromptBytes=$systemPromptBytes contractBytes=$contractBytes " +
        "responseSchemaBytes=$responseSchemaBytes totalHttpRequestBytes=$totalHttpRequestBytes " +
        "calculatedTokenUpperBound=$calculatedTokenUpperBound configuredInputAllocation=$configuredInputAllocation " +
        "configuredOutputAllocation=$configuredOutputAllocation configuredContextWindow=$configuredContextWindow " +
        "templateTokenReserve=$templateTokenReserve tokenProfile=$tokenProfile " +
        "detailLevel=$detailLevel sections=RAW_UTF8 total=ESCAPED_JSON"
}

internal sealed interface RequestPreparation {
    val metrics: RequestMetrics?
    data class Ready(val request: LlmRequest, override val metrics: RequestMetrics) : RequestPreparation
    data class Rejected(val code: String, override val metrics: RequestMetrics? = null) : RequestPreparation
}

internal object WholeRequestBudget {
    /** The projection closes over detached snapshot values only. Four bounded encodings, no inference or world reads. */
    fun prepare(requestId: UUID, prompt: String, schema: String, settings: ProviderSettings,
                profile: InferenceTokenProfile, allocation: InferenceAllocation,
                levels: IntRange = 0..NpcContextEncoder.MAX_DETAIL_LEVEL,
                maxStateBytes: Int = NpcContextEncoder.MAX_STATE_BYTES,
                projection: (Int) -> ContextEncodingResult): RequestPreparation {
        require(!levels.isEmpty() && levels.first >= 0 && levels.last <= NpcContextEncoder.MAX_DETAIL_LEVEL)
        require(maxStateBytes in 1..NpcContextEncoder.MAX_MISSION_STATE_BYTES)
        var last: RequestPreparation.Rejected? = null
        for (level in levels) {
            val state = projection(level)
            if (state is ContextEncodingResult.Rejected) return RequestPreparation.Rejected(state.code)
            check(state is ContextEncodingResult.Encoded)
            val request = LlmRequest(requestId, prompt, state.value.stateJson, schema)
            val wire = ChatCompletionCodec.encode(request, settings)
            val tokens = profile.upperBound(settings, wire.bytes.size)
            val verified = profile as? InferenceTokenProfile.VerifiedByteLevel
            val metrics = RequestMetrics(wire.stateBytes, wire.systemPromptBytes, wire.contractBytes,
                wire.responseSchemaBytes, wire.bytes.size, tokens, allocation.inputTokens, allocation.outputTokens, level,
                settings.inference.contextWindow, verified?.templateTokenReserve,
                if (verified == null) "UNVERIFIED" else "VERIFIED_BYTE_LEVEL")
            val problem = when {
                tokens == null -> "UNVERIFIED_TOKEN_PROFILE"
                tokens < 0 -> "INVALID_TOKEN_PROFILE"
                state.value.utf8Bytes > maxStateBytes -> "CONTEXT_TOO_LARGE"
                wire.bytes.size > settings.maxContextBytes -> "CONTEXT_TOO_LARGE"
                tokens > allocation.inputTokens -> "INPUT_TOKEN_BOUND_EXCEEDED"
                else -> null
            }
            if (problem == null) return RequestPreparation.Ready(request, metrics)
            last = RequestPreparation.Rejected(problem, metrics)
            if (tokens == null || tokens < 0) return last
        }
        return checkNotNull(last)
    }
}
