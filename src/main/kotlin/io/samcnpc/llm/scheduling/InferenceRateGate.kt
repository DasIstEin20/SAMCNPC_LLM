package io.samcnpc.llm.scheduling

import java.util.UUID

internal sealed interface RatePermit {
    data object Granted : RatePermit
    data class Deferred(val code: String, val earliestMillis: Long?) : RatePermit
}
internal data class ServerInferenceResources(val inputTokens: Long = 491520, val outputTokens: Long = 61440,
                                            val costMicros: Long = 0, val npcCallsPerHour: Int = 12,
                                            val serverCallsPerHour: Int = 60,
                                            val quotaMode: InferenceQuotaMode = InferenceQuotaMode.LIMITED) {
    init {
        require(inputTokens >= 0 && outputTokens >= 0 && costMicros >= 0)
        require(npcCallsPerHour in 1..360 && serverCallsPerHour in 1..720)
    }
}

/** Server-thread admission. Only exact, proven-unsent reservations can be refunded. */
internal class InferenceRateGate(private var limits: ServerInferenceResources = ServerInferenceResources()) {
    private val ledger = HourlyInferenceLedger()
    private var lastObservedMillis = 0L

    fun reconfigure(resources: ServerInferenceResources) {
        limits = resources
        if (limits.quotaMode == InferenceQuotaMode.UNLIMITED) ledger.compactSettled()
    }

    fun preview(npcUuid: UUID, nowMillis: Long, charge: InferenceCharge = ZERO): RatePermit =
        evaluate(npcUuid, nowMillis, charge, null)

    fun reserve(npcUuid: UUID, nowMillis: Long, charge: InferenceCharge = ZERO, requestId: UUID = UUID.randomUUID()): RatePermit =
        evaluate(npcUuid, nowMillis, charge, requestId)

    fun releaseUnsent(requestId: UUID): Boolean = ledger.releaseUnsent(requestId)
    fun completeSubmission(requestId: UUID): Boolean = ledger.settle(requestId, limits.quotaMode == InferenceQuotaMode.UNLIMITED)

    private fun evaluate(npcUuid: UUID, nowMillis: Long, charge: InferenceCharge, requestId: UUID?): RatePermit {
        if (nowMillis < lastObservedMillis || nowMillis > Long.MAX_VALUE - WINDOW_MILLIS)
            return RatePermit.Deferred("INVALID_MONOTONIC_CLOCK", null)
        lastObservedMillis = nowMillis
        ledger.expire(nowMillis)
        val last = ledger.latest(npcUuid)
        if (last != null && nowMillis - last < MIN_GAP_MILLIS)
            return RatePermit.Deferred("NPC_COOLDOWN", last + MIN_GAP_MILLIS)
        val spending = ledger.spending()
        if (limits.quotaMode == InferenceQuotaMode.LIMITED) {
            val npcSpending = ledger.spending(npcUuid)
            if (npcSpending.sumOf { it.calls } >= limits.npcCallsPerHour)
                return RatePermit.Deferred("NPC_HOURLY_BUDGET", npcSpending.first().expires)
            if (spending.sumOf { it.calls } >= limits.serverCallsPerHour)
                return RatePermit.Deferred("SERVER_HOURLY_BUDGET", spending.first().expires)
        }
        resources(charge, spending)?.let { return it }
        val identities = ledger.identities()
        if (ledger.exactSize() >= HourlyInferenceLedger.MAX_TRACKED ||
            npcUuid !in identities && identities.size >= HourlyInferenceLedger.MAX_TRACKED)
            return RatePermit.Deferred("SERVER_ACCOUNTING_CAPACITY", spending.firstOrNull()?.expires)
        if (requestId != null) ledger.reserve(requestId, npcUuid, nowMillis, charge)
        return RatePermit.Granted
    }

    private fun resources(charge: InferenceCharge, spending: List<HourlyInferenceLedger.Spending>): RatePermit.Deferred? {
        var used = ZERO
        for (entry in spending) used = HourlyInferenceLedger.add(used, entry.charge)
        if (charge.inputTokens > Long.MAX_VALUE - used.inputTokens || charge.outputTokens > Long.MAX_VALUE - used.outputTokens ||
            charge.costMicros > Long.MAX_VALUE - used.costMicros)
            return RatePermit.Deferred("SERVER_ACCOUNTING_EXHAUSTED", spending.firstOrNull()?.expires)
        if (limits.quotaMode == InferenceQuotaMode.UNLIMITED) return null
        if (charge.inputTokens > limits.inputTokens) return RatePermit.Deferred("SERVER_INPUT_BUDGET", null)
        if (charge.outputTokens > limits.outputTokens) return RatePermit.Deferred("SERVER_OUTPUT_BUDGET", null)
        if (charge.costMicros > limits.costMicros) return RatePermit.Deferred("SERVER_COST_BUDGET", null)
        fun code(): String? = when {
            used.inputTokens > limits.inputTokens - charge.inputTokens -> "SERVER_INPUT_BUDGET"
            used.outputTokens > limits.outputTokens - charge.outputTokens -> "SERVER_OUTPUT_BUDGET"
            used.costMicros > limits.costMicros - charge.costMicros -> "SERVER_COST_BUDGET"
            else -> null
        }
        val problem = code() ?: return null
        for (entry in spending) {
            used = InferenceCharge(used.inputTokens - entry.charge.inputTokens,
                used.outputTokens - entry.charge.outputTokens, used.costMicros - entry.charge.costMicros)
            if (code() == null) return RatePermit.Deferred(problem, entry.expires)
        }
        error("Bounded resource history did not account for all reservations")
    }

    /** Metrics at the latest quota check; querying never advances time. */
    fun reservedInCurrentWindow(): Int = ledger.spending().sumOf { it.calls }
    fun trackedNpcs(): Int = ledger.identities().size
    fun retainedExactEntries(): Int = ledger.exactSize()
    fun retainedMinuteBuckets(): Int = ledger.bucketSize()

    companion object {
        const val MIN_GAP_MILLIS = 10_000L
        const val WINDOW_MILLIS = 3_600_000L
        const val NPC_ATTEMPTS_PER_HOUR = 12
        const val SERVER_ATTEMPTS_PER_HOUR = 60
        private val ZERO = InferenceCharge(0, 0, 0)
    }
}
