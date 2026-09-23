package io.samcnpc.llm.config

import io.samcnpc.llm.scheduling.*

/** Server administrator declaration. Model names never implicitly select a tokenizer/template profile. */
internal data class InferenceSettings(
    val verifiedByteLevel: Boolean = false,
    val verifiedBaseUrl: String = ProviderSettings.DEFAULT_BASE_URL,
    val verifiedModel: String = "",
    val backendVersion: String = "",
    val modelDigest: String = "",
    val tokenizerDigest: String = "",
    val templateDigest: String = "",
    val templateReserve: Int = 2048,
    val contextWindow: Int = 32768,
    val inputTokens: Int = 8192,
    val inputMicrosPerMillion: Long = 0,
    val outputMicrosPerMillion: Long = 0,
    val goalCostMicros: Long = 0,
    val hourlyCostMicros: Long = 0,
    val npcCallsPerHour: Int = 12,
    val serverCallsPerHour: Int = 60,
    val goalQuotaMode: InferenceQuotaMode = InferenceQuotaMode.LIMITED,
    val hourlyQuotaMode: InferenceQuotaMode = InferenceQuotaMode.LIMITED,
) {
    fun problem(): String? = when {
        !ProviderSettings.validBaseUrl(verifiedBaseUrl) -> "verifiedBaseUrl"
        !ProviderSettings.validModel(verifiedModel) -> "verifiedModel"
        listOf(backendVersion, modelDigest, tokenizerDigest, templateDigest).any { !validEvidence(it) } -> "profileEvidence"
        templateReserve !in 0..8192 -> "templateReserve"
        contextWindow !in 1024..262144 -> "contextWindow"
        inputTokens !in 1..131072 -> "inputTokens"
        npcCallsPerHour !in 1..360 -> "npcCallsPerHour"
        serverCallsPerHour !in 1..720 -> "serverCallsPerHour"
        listOf(inputMicrosPerMillion, outputMicrosPerMillion, goalCostMicros, hourlyCostMicros).any { it !in 0..1_000_000_000L } -> "cost"
        else -> null
    }

    fun readiness(settings: ProviderSettings): String? = when {
        problem() != null -> "INVALID_INFERENCE_CONFIGURATION"
        !verifiedByteLevel -> "UNVERIFIED_TOKEN_PROFILE"
        verifiedBaseUrl != settings.baseUrl || verifiedModel != settings.model -> "TOKEN_PROFILE_ENDPOINT_MODEL_MISMATCH"
        listOf(verifiedModel, backendVersion, modelDigest, tokenizerDigest, templateDigest).any(String::isBlank) -> "TOKEN_PROFILE_EVIDENCE_REQUIRED"
        inputTokens.toLong() + settings.maxOutputTokens > contextWindow -> "MODEL_CONTEXT_WINDOW_EXCEEDED"
        goalQuotaMode == InferenceQuotaMode.LIMITED && allocation(settings).charge().costMicros > goalCostMicros -> "GOAL_COST_BUDGET_EXHAUSTED"
        hourlyQuotaMode == InferenceQuotaMode.LIMITED && allocation(settings).charge().costMicros > hourlyCostMicros -> "SERVER_COST_BUDGET"
        else -> null
    }

    fun profile(settings: ProviderSettings): InferenceTokenProfile {
        if (readiness(settings) != null) return InferenceTokenProfile.Unverified
        return InferenceTokenProfile.VerifiedByteLevel(verifiedBaseUrl, verifiedModel,
            backendVersion, modelDigest, tokenizerDigest, templateDigest, templateReserve)
    }
    fun allocation(settings: ProviderSettings) =
        InferenceAllocation(inputTokens, settings.maxOutputTokens, inputMicrosPerMillion, outputMicrosPerMillion)
    // A verified request may reserve much more than the historical 8192-token default.
    // Token quotas must fund the declared call count; cost caps can still stop it earlier.
    fun goalLimits(outputTokens: Int = 1024) = InferenceBudgetLimits(
        inputTokens = inputTokens.toLong() * 24, outputTokens = outputTokens.toLong() * 24, costMicros = goalCostMicros,
        quotaMode = goalQuotaMode)
    fun serverResources(outputTokens: Int = 1024) = ServerInferenceResources(
        inputTokens = inputTokens.toLong() * serverCallsPerHour, outputTokens = outputTokens.toLong() * serverCallsPerHour,
        costMicros = hourlyCostMicros, npcCallsPerHour = npcCallsPerHour, serverCallsPerHour = serverCallsPerHour,
        quotaMode = hourlyQuotaMode)

    companion object {
        fun validEvidence(value: String): Boolean = value.length <= 256 && value == value.trim() && value.none(Char::isISOControl)
    }
}
