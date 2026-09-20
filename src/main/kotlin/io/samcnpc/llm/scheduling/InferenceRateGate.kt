package io.samcnpc.llm.scheduling

import java.util.ArrayDeque
import java.util.UUID

internal sealed interface RatePermit {
    data object Granted : RatePermit
    data class Deferred(val code: String, val earliestMillis: Long?) : RatePermit
}
internal data class ServerInferenceResources(val inputTokens: Long = 491520, val outputTokens: Long = 61440,
                                            val costMicros: Long = 0) {
    init { require(inputTokens >= 0 && outputTokens >= 0 && costMicros >= 0) }
}

/** Server-thread attempt reservations, independent of wall clock/game ticks. No automatic refund. */
internal class InferenceRateGate(private val limits: ServerInferenceResources = ServerInferenceResources()) {
    private data class Entry(val atMillis: Long, val charge: InferenceCharge)
    private val global = ArrayDeque<Entry>()
    private val byNpc = linkedMapOf<UUID, ArrayDeque<Long>>()
    private var lastObservedMillis = 0L

    /** Cheap bounded preflight before capturing another context; expiry is the only mutation. */
    fun preview(npcUuid: UUID, nowMillis: Long, charge: InferenceCharge = ZERO): RatePermit =
        evaluate(npcUuid, nowMillis, charge, false)

    fun reserve(npcUuid: UUID, nowMillis: Long, charge: InferenceCharge = ZERO): RatePermit =
        evaluate(npcUuid, nowMillis, charge, true)

    private fun evaluate(npcUuid: UUID, nowMillis: Long, charge: InferenceCharge, commit: Boolean): RatePermit {
        if (nowMillis < lastObservedMillis || nowMillis > Long.MAX_VALUE - WINDOW_MILLIS)
            return RatePermit.Deferred("INVALID_MONOTONIC_CLOCK", null)
        lastObservedMillis = nowMillis
        while (global.isNotEmpty() && nowMillis - global.peekFirst().atMillis >= WINDOW_MILLIS) global.removeFirst()
        val iterator = byNpc.values.iterator()
        while (iterator.hasNext()) {
            val history = iterator.next()
            while (history.isNotEmpty() && nowMillis - history.peekFirst() >= WINDOW_MILLIS) history.removeFirst()
            if (history.isEmpty()) iterator.remove()
        }
        val history = byNpc[npcUuid]
        val last = history?.peekLast()
        if (last != null && nowMillis - last < MIN_GAP_MILLIS)
            return RatePermit.Deferred("NPC_COOLDOWN", last + MIN_GAP_MILLIS)
        if (history != null && history.size >= NPC_ATTEMPTS_PER_HOUR)
            return RatePermit.Deferred("NPC_HOURLY_BUDGET", history.peekFirst() + WINDOW_MILLIS)
        if (global.size >= SERVER_ATTEMPTS_PER_HOUR)
            return RatePermit.Deferred("SERVER_HOURLY_BUDGET", global.peekFirst().atMillis + WINDOW_MILLIS)
        resources(charge)?.let { return it }
        if (!commit) return RatePermit.Granted
        // Global reservations bound the number of unexpired NPC histories to <=60.
        check(byNpc.size <= SERVER_ATTEMPTS_PER_HOUR)
        val next = history ?: ArrayDeque<Long>().also { byNpc[npcUuid] = it }
        next.addLast(nowMillis)
        global.addLast(Entry(nowMillis, charge))
        return RatePermit.Granted
    }

    private fun resources(charge: InferenceCharge): RatePermit.Deferred? {
        if (charge.inputTokens > limits.inputTokens) return RatePermit.Deferred("SERVER_INPUT_BUDGET", null)
        if (charge.outputTokens > limits.outputTokens) return RatePermit.Deferred("SERVER_OUTPUT_BUDGET", null)
        if (charge.costMicros > limits.costMicros) return RatePermit.Deferred("SERVER_COST_BUDGET", null)
        var input = limits.inputTokens; var output = limits.outputTokens; var cost = limits.costMicros
        for (entry in global) {
            input -= entry.charge.inputTokens; output -= entry.charge.outputTokens; cost -= entry.charge.costMicros
        }
        val code = when {
            charge.inputTokens > input -> "SERVER_INPUT_BUDGET"
            charge.outputTokens > output -> "SERVER_OUTPUT_BUDGET"
            charge.costMicros > cost -> "SERVER_COST_BUDGET"
            else -> return null
        }
        for (entry in global) {
            input += entry.charge.inputTokens; output += entry.charge.outputTokens; cost += entry.charge.costMicros
            if (charge.inputTokens <= input && charge.outputTokens <= output && charge.costMicros <= cost)
                return RatePermit.Deferred(code, entry.atMillis + WINDOW_MILLIS)
        }
        error("Bounded resource history did not account for all reservations")
    }

    /** Metrics at the latest quota check; querying never advances time. */
    fun reservedInCurrentWindow(): Int = global.size
    fun trackedNpcs(): Int = byNpc.size

    companion object {
        const val MIN_GAP_MILLIS = 10_000L
        const val WINDOW_MILLIS = 3_600_000L
        const val NPC_ATTEMPTS_PER_HOUR = 12
        const val SERVER_ATTEMPTS_PER_HOUR = 60
        private val ZERO = InferenceCharge(0, 0, 0)
    }
}
