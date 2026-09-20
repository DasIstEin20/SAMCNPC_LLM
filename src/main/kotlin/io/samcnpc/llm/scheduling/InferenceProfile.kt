package io.samcnpc.llm.scheduling

import io.samcnpc.llm.config.ProviderSettings

/** Tokenizer/template evidence is server configuration, never model text or a guessed model-name heuristic. */
internal sealed interface InferenceTokenProfile {
    fun upperBound(settings: ProviderSettings, serializedRequestBytes: Int): Long?

    data object Unverified : InferenceTokenProfile {
        override fun upperBound(settings: ProviderSettings, serializedRequestBytes: Int): Long? = null
    }

    /**
     * For an externally verified byte-level tokenizer and bounded chat template only. Counting the
     * entire escaped HTTP JSON over-reserves message/schema bytes; template tokens are added.
     * Changing endpoint/model invalidates this declaration. Actual backend tuples require L3.5 proof.
     */
    class VerifiedByteLevel(
        private val baseUrl: String,
        private val model: String,
        val backendVersion: String,
        val modelDigest: String,
        val tokenizerDigest: String,
        val templateDigest: String,
        val templateTokenReserve: Int,
    ) : InferenceTokenProfile {
        init {
            require(ProviderSettings.validBaseUrl(baseUrl) && model.isNotBlank() && ProviderSettings.validModel(model))
            require(listOf(backendVersion, modelDigest, tokenizerDigest, templateDigest).all { it.length in 1..256 })
            require(templateTokenReserve in 0..8192)
        }
        override fun upperBound(settings: ProviderSettings, serializedRequestBytes: Int): Long? {
            require(serializedRequestBytes >= 0)
            if (settings.baseUrl != baseUrl || settings.model != model) return null
            return serializedRequestBytes.toLong() + templateTokenReserve
        }
    }
}

internal data class InferenceAllocation(val inputTokens: Int = 8192, val outputTokens: Int = 1024,
                                       val inputMicrosPerMillion: Long = 0, val outputMicrosPerMillion: Long = 0) {
    init {
        require(inputTokens in 1..131072 && outputTokens in 64..4096)
        require(inputMicrosPerMillion in 0..1_000_000_000L && outputMicrosPerMillion in 0..1_000_000_000L)
    }
    /** Rounded up independently. A reservation uses caps, not an optimistic estimate/refund. */
    fun charge(): InferenceCharge {
        fun price(tokens: Int, rate: Long): Long = (tokens * rate + 999999L) / 1000000L
        return InferenceCharge(inputTokens.toLong(), outputTokens.toLong(),
            price(inputTokens, inputMicrosPerMillion) + price(outputTokens, outputMicrosPerMillion))
    }
}
