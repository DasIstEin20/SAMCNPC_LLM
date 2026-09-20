package io.samcnpc.llm.scheduling

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class InferenceRateGateTest {
    @Test fun attemptsKeepTheirReservationsAcrossFailuresAndGoalChanges() {
        val gate = InferenceRateGate(); val npc = UUID.randomUUID()
        assertEquals(RatePermit.Granted, gate.reserve(npc, 0))
        assertEquals(RatePermit.Deferred("NPC_COOLDOWN", 10000), gate.reserve(npc, 9999))
        for (index in 1..11) assertEquals(RatePermit.Granted, gate.reserve(npc, index * 10000L))
        assertEquals(RatePermit.Deferred("NPC_HOURLY_BUDGET", 3600000), gate.reserve(npc, 120000))
        assertEquals(12, gate.reservedInCurrentWindow())
        assertEquals(RatePermit.Granted, gate.reserve(npc, 3600000))
        assertEquals(12, gate.reservedInCurrentWindow())
    }

    @Test fun serverBudgetAndNpcMemoryStayBoundedUnderManyDistinctNpcs() {
        val gate = InferenceRateGate()
        repeat(60) { assertEquals(RatePermit.Granted, gate.reserve(UUID.randomUUID(), 0)) }
        assertEquals(RatePermit.Deferred("SERVER_HOURLY_BUDGET", 3600000), gate.reserve(UUID.randomUUID(), 0))
        assertEquals(60, gate.trackedNpcs())
        assertEquals(RatePermit.Granted, gate.reserve(UUID.randomUUID(), 3600000))
        assertEquals(1, gate.trackedNpcs())
    }

    @Test fun rollingExpiryDoesNotResetAWholeHourBucketEarly() {
        val gate = InferenceRateGate()
        repeat(30) { assertEquals(RatePermit.Granted, gate.reserve(UUID.randomUUID(), 0)) }
        repeat(30) { assertEquals(RatePermit.Granted, gate.reserve(UUID.randomUUID(), 1800000)) }
        repeat(30) { assertEquals(RatePermit.Granted, gate.reserve(UUID.randomUUID(), 3600000)) }
        assertEquals(RatePermit.Deferred("SERVER_HOURLY_BUDGET", 5400000), gate.reserve(UUID.randomUUID(), 3600000))
    }

    @Test fun clockRollbackAndOverflowNeverRefundReservations() {
        val gate = InferenceRateGate(); val npc = UUID.randomUUID()
        assertEquals(RatePermit.Granted, gate.reserve(npc, 100))
        for (time in listOf(-1L, 99L, Long.MAX_VALUE)) {
            assertEquals(RatePermit.Deferred("INVALID_MONOTONIC_CLOCK", null), gate.reserve(npc, time))
            assertEquals(1, gate.reservedInCurrentWindow())
        }
    }
    @Test fun serverTokenAndCostReservationsWaitUntilEnoughSpecificEntriesExpire() {
        val gate = InferenceRateGate(ServerInferenceResources(10, 10, 10))
        assertEquals(RatePermit.Granted, gate.reserve(UUID.randomUUID(), 0, InferenceCharge(1, 1, 1)))
        assertEquals(RatePermit.Granted, gate.reserve(UUID.randomUUID(), 10000, InferenceCharge(9, 9, 9)))
        val candidate = UUID.randomUUID()
        assertEquals(RatePermit.Deferred("SERVER_INPUT_BUDGET", 3610000),
            gate.reserve(candidate, 20000, InferenceCharge(9, 9, 9)))
        assertEquals(2, gate.reservedInCurrentWindow())
        assertEquals(RatePermit.Granted, gate.preview(candidate, 3610000, InferenceCharge(9, 9, 9)))
        assertEquals(0, gate.reservedInCurrentWindow())
        assertEquals(RatePermit.Granted, gate.reserve(candidate, 3610000, InferenceCharge(9, 9, 9)))
        assertEquals(1, gate.reservedInCurrentWindow())
    }

    @Test fun impossibleChargesAndPreflightDoNotConsumeCallBudgets() {
        val gate = InferenceRateGate(ServerInferenceResources(10, 10, 10)); val id = UUID.randomUUID()
        assertEquals(RatePermit.Deferred("SERVER_COST_BUDGET", null), gate.preview(id, 0, InferenceCharge(0, 0, 11)))
        repeat(5) { assertEquals(RatePermit.Granted, gate.preview(id, 0, InferenceCharge(10, 10, 10))) }
        assertEquals(0, gate.reservedInCurrentWindow()); assertEquals(0, gate.trackedNpcs())
        assertEquals(RatePermit.Granted, gate.reserve(id, 0, InferenceCharge(10, 10, 10)))
        assertEquals(1, gate.reservedInCurrentWindow())
    }

}
