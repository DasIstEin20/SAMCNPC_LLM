package io.samcnpc.llm.scheduling

import io.samcnpc.llm.api.LlmFailure
import java.util.UUID

internal sealed interface CircuitPermit {
    data class Granted(val id: UUID) : CircuitPermit
    data class Deferred(val code: String, val earliestMillis: Long?) : CircuitPermit
}

/** One endpoint/config lifetime. Obsolete or duplicate completions cannot close a newer circuit. */
internal class ProviderCircuit {
    private data class Attempt(val epoch: Long, val probe: Boolean)
    private val pending = linkedMapOf<UUID, Attempt>()
    private var epoch = 0L
    private var failures = 0
    private var rateLimitedUntil = 0L
    private var openUntil: Long? = null
    private var blocked: String? = null
    private var lastObservedMillis = 0L

    fun acquire(nowMillis: Long): CircuitPermit {
        if (!clockValid(nowMillis)) return CircuitPermit.Deferred("INVALID_MONOTONIC_CLOCK", null)
        blocked?.let { return CircuitPermit.Deferred(it, null) }
        val until = openUntil
        if (until != null && nowMillis < until) return CircuitPermit.Deferred("PROVIDER_COOLDOWN", until)
        if (nowMillis < rateLimitedUntil) return CircuitPermit.Deferred("PROVIDER_RATE_LIMIT", rateLimitedUntil)
        if (until != null && pending.values.any { it.probe })
            return CircuitPermit.Deferred("PROVIDER_PROBE_IN_FLIGHT", null)
        if (pending.size >= 2) return CircuitPermit.Deferred("PROVIDER_CAPACITY", null)
        val id = UUID.randomUUID()
        pending[id] = Attempt(epoch, until != null)
        return CircuitPermit.Granted(id)
    }

    /** null failure means a successful provider response, not a successful NPC operation. */
    fun complete(permit: CircuitPermit.Granted, nowMillis: Long, failure: LlmFailure?,
                 retryAfterSeconds: Int? = null) {
        val attempt = pending.remove(permit.id) ?: return
        if (!clockValid(nowMillis)) {
            blocked = "INVALID_MONOTONIC_CLOCK"
            pending.clear()
            return
        }
        if (attempt.epoch != epoch) return
        if (failure == null) {
            failures = 0
            if (attempt.probe) openUntil = null
            return
        }
        if (failure == LlmFailure.RATE_LIMITED) {
            val delay = maxOf(10_000L, (retryAfterSeconds ?: 0).coerceIn(0, 300) * 1000L)
            rateLimitedUntil = maxOf(rateLimitedUntil, nowMillis + delay)
        }
        if (failure in permanentFailures) {
            blocked = "PROVIDER_CONFIGURATION_REQUIRED"
            invalidatePending()
            return
        }
        if (failure == LlmFailure.CANCELLED) {
            // Cancelling a probe leaves one later probe possible; it is not a success or failure.
            return
        }
        if (failure !in transportFailures) {
            // Invalid output belongs to bounded repair; it does not indicate a healthy transport probe.
            if (attempt.probe) open(nowMillis, retryAfterSeconds)
            return
        }
        failures++
        if (attempt.probe || failures >= FAILURE_LIMIT) open(nowMillis, retryAfterSeconds)
    }

    private fun open(now: Long, retryAfterSeconds: Int?) {
        val retry = (retryAfterSeconds ?: 0).coerceIn(0, 300) * 1000L
        openUntil = now + maxOf(COOLDOWN_MILLIS, retry)
        invalidatePending()
    }

    private fun invalidatePending() {
        epoch++
        pending.clear()
    }

    private fun clockValid(now: Long): Boolean {
        if (now < lastObservedMillis || now > Long.MAX_VALUE - 300_000L) return false
        lastObservedMillis = now
        return true
    }

    private companion object {
        const val FAILURE_LIMIT = 3
        const val COOLDOWN_MILLIS = 60_000L
        val transportFailures = setOf(LlmFailure.TIMEOUT, LlmFailure.UNAVAILABLE, LlmFailure.RATE_LIMITED)
        val permanentFailures = setOf(LlmFailure.UNAUTHORIZED, LlmFailure.INCOMPATIBLE, LlmFailure.INVALID_CONFIGURATION,
            LlmFailure.DISABLED)
    }
}
