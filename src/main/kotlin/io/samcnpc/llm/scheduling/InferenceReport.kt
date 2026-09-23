package io.samcnpc.llm.scheduling

import io.samcnpc.llm.api.LlmSubmission
import io.samcnpc.llm.api.LlmUsage
import java.util.UUID

/** Transient, bounded diagnostics contain counts and binding IDs, never prompt or credential text. */
internal data class InferenceReport(val goalId: UUID, val goalRevision: Long, val contextId: UUID,
    val providerInvoked: Boolean, val submission: LlmSubmission, val metrics: RequestMetrics?, val usage: LlmUsage?) {
    fun describe(): String {
        val sent = when (submission) {
            LlmSubmission.NOT_SENT -> "false"
            LlmSubmission.SUBMITTED -> "true"
            LlmSubmission.UNKNOWN -> "unknown"
        }
        return "lastRequestContext=$contextId goalRevision=$goalRevision providerInvoked=$providerInvoked requestSent=$sent " +
            "providerReportedInputTokens=${usage?.promptTokens ?: "unknown"} " +
            "providerReportedOutputTokens=${usage?.completionTokens ?: "unknown"}\n" +
            (metrics?.describe() ?: "requestMetrics=unavailable")
    }
}
