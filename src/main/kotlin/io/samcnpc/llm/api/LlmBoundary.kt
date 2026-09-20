package io.samcnpc.llm.api

import io.samcnpc.behavior.api.BehaviorPackValidationApi
import io.samcnpc.behavior.api.ValidationReport

data class BehaviorProposal(
    val json: String,
    val sourceLabel: String,
)

sealed interface ProviderStatus {
    data object Disabled : ProviderStatus
    data class Available(val providerName: String) : ProviderStatus
}

/**
 * This shell has no provider and cannot mutate Minecraft state. Any future candidate is forced
 * through the exact Behavior validator used for JSON loaded from disk.
 */
object LlmBoundary {
    fun status(): ProviderStatus = ProviderStatus.Disabled

    fun validateProposal(proposal: BehaviorProposal): ValidationReport =
        BehaviorPackValidationApi.validateCandidate(proposal.json, "llm:${proposal.sourceLabel.take(64)}")
}
