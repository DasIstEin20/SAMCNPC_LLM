package io.samcnpc.llm.scheduling

import java.util.UUID

internal enum class InferenceReason { USER_GOAL, USER_ANSWER, TASK_TERMINAL, WAIT_DEADLINE, TRANSPORT_RETRY, OUTPUT_REPAIR }

/** Only wake intent is queued. The controller captures a fresh authorized context when dispatched. */
internal class InferenceWake(val npcUuid: UUID, val goalId: UUID, val goalRevision: Long,
                             val readyMillis: Long, reasons: Set<InferenceReason>,
                             val transportRetries: Int = 0, val outputRepairs: Int = 0,
                             val feedbackCode: String? = null) {
    val reasons: Set<InferenceReason> = java.util.Set.copyOf(reasons)
    init {
        require(goalRevision >= 0 && readyMillis >= 0 && reasons.isNotEmpty())
        require(transportRetries in 0..1 && outputRepairs in 0..1)
        require(feedbackCode == null || feedbackCode.length in 1..128 &&
            feedbackCode.all { it in 'A'..'Z' || it in '0'..'9' || it == '_' })
    }
}

/** Server-thread FIFO with bounded scans; a busy/cooling NPC cannot block ready NPCs behind it. */
internal class InferenceQueue {
    private val queued = linkedMapOf<UUID, InferenceWake>()
    val size: Int get() = queued.size

    /** The goal controller validates current goal identity before offering an event. */
    fun offer(wake: InferenceWake): String? {
        val prior = queued[wake.npcUuid]
        if (prior != null && prior.goalId == wake.goalId && prior.goalRevision > wake.goalRevision)
            return "STALE_GOAL_EVENT"
        if (prior == null && queued.size >= MAX_QUEUED) return "INFERENCE_QUEUE_FULL"
        val next = if (prior != null && prior.goalId == wake.goalId && prior.goalRevision == wake.goalRevision)
            InferenceWake(wake.npcUuid, wake.goalId, wake.goalRevision, minOf(prior.readyMillis, wake.readyMillis),
                prior.reasons + wake.reasons, maxOf(prior.transportRetries, wake.transportRetries),
                maxOf(prior.outputRepairs, wake.outputRepairs), wake.feedbackCode ?: prior.feedbackCode)
        else wake
        // Replacing an existing key preserves its FIFO position.
        queued[wake.npcUuid] = next
        return null
    }

    fun takeReady(nowMillis: Long, busyNpcs: Set<UUID>): InferenceWake? {
        require(nowMillis >= 0)
        val iterator = queued.values.iterator()
        while (iterator.hasNext()) {
            val wake = iterator.next()
            if (wake.readyMillis > nowMillis || wake.npcUuid in busyNpcs) continue
            iterator.remove()
            return wake
        }
        return null
    }

    fun remove(npcUuid: UUID): Boolean = queued.remove(npcUuid) != null
    fun clear() { queued.clear() }

    companion object { const val MAX_QUEUED = 32 }
}
