package io.samcnpc.llm.scheduling

import io.samcnpc.llm.context.CapturedContext
import java.util.UUID

internal sealed interface InferencePreparation {
    data class Ready(val captured: CapturedContext, val budget: InferenceBudget) : InferencePreparation
    data class Rejected(val code: String) : InferencePreparation
}

/** Only the scheduler's creating/server thread invokes these callbacks. Workers never receive this host. */
internal interface InferenceHost {
    fun prepare(wake: InferenceWake): InferencePreparation
    /** Persist/mark the goal's reservation before provider work is submitted. */
    fun started(wake: InferenceWake, captured: CapturedContext, requestId: UUID)
    /** Admit or record failure before the held attempt is settled. Never retry an uncertain world effect. */
    fun completed(wake: InferenceWake, captured: CapturedContext, result: InferenceResult)
    fun deferred(wake: InferenceWake, code: String, retryAtMillis: Long?)
}
