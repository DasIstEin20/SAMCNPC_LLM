package io.samcnpc.llm.scheduling

import io.samcnpc.llm.api.LlmFailure
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ProviderCircuitTest {
    private fun acquire(circuit: ProviderCircuit, now: Long): CircuitPermit.Granted {
        val permit = circuit.acquire(now)
        assertTrue(permit is CircuitPermit.Granted, permit.toString())
        return permit as CircuitPermit.Granted
    }
    private fun fail(circuit: ProviderCircuit, now: Long) {
        circuit.complete(acquire(circuit, now), now, LlmFailure.UNAVAILABLE)
    }

    @Test fun thirdFailureOpensOneFiniteCooldownAndAllowsOnlyOneProbe() {
        val circuit = ProviderCircuit()
        fail(circuit, 0); fail(circuit, 1); fail(circuit, 2)
        assertEquals(CircuitPermit.Deferred("PROVIDER_COOLDOWN", 60002), circuit.acquire(60001))
        val probe = acquire(circuit, 60002)
        assertEquals(CircuitPermit.Deferred("PROVIDER_PROBE_IN_FLIGHT", null), circuit.acquire(60002))
        circuit.complete(probe, 60003, null)
        acquire(circuit, 60004); acquire(circuit, 60004)
        assertEquals(CircuitPermit.Deferred("PROVIDER_CAPACITY", null), circuit.acquire(60004))
    }

    @Test fun anOlderSuccessOrDuplicateReplyCannotCloseTheNewCircuit() {
        val circuit = ProviderCircuit()
        fail(circuit, 0); fail(circuit, 1)
        val failure = acquire(circuit, 2); val lateSuccess = acquire(circuit, 2)
        circuit.complete(failure, 3, LlmFailure.TIMEOUT)
        circuit.complete(lateSuccess, 4, null)
        circuit.complete(failure, 5, null)
        assertEquals(CircuitPermit.Deferred("PROVIDER_COOLDOWN", 60003), circuit.acquire(60002))
    }

    @Test fun retryAfterAndFailedProbeExtendTheCooldownWithoutParallelProbes() {
        val circuit = ProviderCircuit()
        fail(circuit, 0); fail(circuit, 1)
        circuit.complete(acquire(circuit, 2), 2, LlmFailure.RATE_LIMITED, 120)
        assertEquals(CircuitPermit.Deferred("PROVIDER_COOLDOWN", 120002), circuit.acquire(60002))
        val probe = acquire(circuit, 120002)
        circuit.complete(probe, 120003, LlmFailure.TIMEOUT)
        assertEquals(CircuitPermit.Deferred("PROVIDER_COOLDOWN", 180003), circuit.acquire(120004))
    }

    @Test fun firstRateLimitThrottlesTheEndpointEvenWhenAnotherOlderRequestSucceeds() {
        val circuit = ProviderCircuit()
        val limited = acquire(circuit, 0); val success = acquire(circuit, 0)
        circuit.complete(limited, 1, LlmFailure.RATE_LIMITED, 120)
        circuit.complete(success, 2, null)
        assertEquals(CircuitPermit.Deferred("PROVIDER_RATE_LIMIT", 120001), circuit.acquire(60000))
        acquire(circuit, 120001)
    }

    @Test fun configurationErrorsRequireANewEndpointLifetime() {
        val circuit = ProviderCircuit()
        val first = acquire(circuit, 0); val second = acquire(circuit, 0)
        circuit.complete(first, 1, LlmFailure.UNAUTHORIZED)
        circuit.complete(second, 2, null)
        assertEquals(CircuitPermit.Deferred("PROVIDER_CONFIGURATION_REQUIRED", null), circuit.acquire(3600000))
        assertTrue(ProviderCircuit().acquire(0) is CircuitPermit.Granted)
    }

    @Test fun invalidOutputIsNotATransportFailureAndCancellationDoesNotPretendSuccess() {
        val circuit = ProviderCircuit()
        repeat(5) { index -> circuit.complete(acquire(circuit, index.toLong()), index.toLong(), LlmFailure.INVALID_OUTPUT) }
        fail(circuit, 5); fail(circuit, 6); fail(circuit, 7)
        val probe = acquire(circuit, 60007)
        circuit.complete(probe, 60008, LlmFailure.CANCELLED)
        val nextProbe = acquire(circuit, 60009)
        circuit.complete(nextProbe, 60010, LlmFailure.INVALID_OUTPUT)
        assertEquals(CircuitPermit.Deferred("PROVIDER_COOLDOWN", 120010), circuit.acquire(60011))
    }
}
