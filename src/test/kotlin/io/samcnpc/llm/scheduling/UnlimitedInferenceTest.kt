package io.samcnpc.llm.scheduling

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class UnlimitedInferenceTest {
    private val unlimited = ServerInferenceResources(quotaMode = InferenceQuotaMode.UNLIMITED)

    @Test fun unlimitedGoalMetersThousandsOfCallsAndRetainsSingleFlightRefundAndOverflowGuards() {
        val budget = InferenceBudget(InferenceBudgetLimits(quotaMode = InferenceQuotaMode.UNLIMITED))
        repeat(2000) {
            val id = UUID.randomUUID()
            assertNull(budget.reserve(id, InferenceCharge(1, 2, 3)))
            assertNull(budget.availableCalls); assertNull(budget.contextRemainingCalls)
            assertEquals("GOAL_REQUEST_IN_FLIGHT", budget.problem(InferenceCharge(0, 0, 0)))
            assertTrue(budget.settle(id)); assertFalse(budget.releaseUnsent(id))
        }
        assertEquals(InferenceBudgetView(2000, 2000, 4000, 6000, null), budget.snapshot())
        val id = UUID.randomUUID(); val before = budget.snapshot()
        assertNull(budget.reserve(id, InferenceCharge(9, 9, 9))); assertTrue(budget.releaseUnsent(id))
        assertEquals(before, budget.snapshot())
        val restored = InferenceBudget(budget.limits, before)
        assertNull(restored.problem(InferenceCharge(1000, 1000, 1000)))
        for (state in listOf(InferenceBudgetView(Int.MAX_VALUE, 0, 0, 0, null),
            InferenceBudgetView(0, Long.MAX_VALUE, 0, 0, null), InferenceBudgetView(0, 0, Long.MAX_VALUE, 0, null),
            InferenceBudgetView(0, 0, 0, Long.MAX_VALUE, null))) {
            val exhausted = InferenceBudget(budget.limits, state)
            assertEquals("GOAL_ACCOUNTING_EXHAUSTED", exhausted.reserve(id, InferenceCharge(1, 1, 1)))
            assertEquals(state, exhausted.snapshot())
        }
    }

    @Test fun settledUnlimitedWorkCrossesOldCapsWithoutGrowingRequestHistoryAcrossManyHours() {
        val gate = InferenceRateGate(unlimited)
        val npcs = List(32) { UUID.randomUUID() }
        repeat(1500) { round ->
            val now = round * 10_000L
            for (npc in npcs) {
                val id = UUID.randomUUID()
                assertEquals(RatePermit.Granted, gate.reserve(npc, now, InferenceCharge(100, 10, 1), id))
                assertTrue(gate.completeSubmission(id)); assertFalse(gate.completeSubmission(id))
                assertFalse(gate.releaseUnsent(id))
            }
            assertEquals(0, gate.retainedExactEntries()); assertTrue(gate.retainedMinuteBuckets() <= 61)
            assertEquals(32, gate.trackedNpcs())
            assertTrue(gate.reservedInCurrentWindow() <= 32 * 366)
            if (round >= 360) assertTrue(gate.reservedInCurrentWindow() >= 32 * 360)
        }
        assertEquals(RatePermit.Deferred("NPC_COOLDOWN", 15_000_000L), gate.preview(npcs[0], 14_999_999L))
    }

    @Test fun modeChangesNeverRefundSubmittedWorkAndUnsentReleaseRestoresActualCooldown() {
        val gate = InferenceRateGate(ServerInferenceResources(costMicros = 10))
        val npc = UUID.randomUUID(); val sent = UUID.randomUUID(); val unsent = UUID.randomUUID()
        assertEquals(RatePermit.Granted, gate.reserve(npc, 0, InferenceCharge(1, 1, 8), sent))
        assertTrue(gate.completeSubmission(sent)); assertFalse(gate.releaseUnsent(sent))
        gate.reconfigure(unlimited)
        assertEquals(0, gate.retainedExactEntries()); assertEquals(1, gate.retainedMinuteBuckets())
        assertEquals(RatePermit.Granted, gate.reserve(npc, 10_000, InferenceCharge(2, 2, 50), unsent))
        gate.reconfigure(ServerInferenceResources(costMicros = 10))
        assertEquals(RatePermit.Deferred("SERVER_COST_BUDGET", 3_610_000L),
            gate.preview(UUID.randomUUID(), 10_000, InferenceCharge(0, 0, 3)))
        assertTrue(gate.releaseUnsent(unsent)); assertFalse(gate.releaseUnsent(unsent))
        assertEquals(RatePermit.Granted, gate.preview(npc, 10_000, InferenceCharge(0, 0, 2)))
        assertEquals(RatePermit.Deferred("SERVER_COST_BUDGET", 3_600_000L),
            gate.preview(npc, 10_000, InferenceCharge(0, 0, 3)))
        assertEquals(1, gate.reservedInCurrentWindow())
    }

    @Test fun aggregateExpiryNeverUndercountsAfterLimitedPolicyIsEnabled() {
        val gate = InferenceRateGate(unlimited)
        for (time in listOf(0L, 59_999L)) {
            val id = UUID.randomUUID()
            assertEquals(RatePermit.Granted, gate.reserve(UUID.randomUUID(), time, InferenceCharge(0, 0, 5), id))
            assertTrue(gate.completeSubmission(id))
        }
        gate.reconfigure(ServerInferenceResources(costMicros = 5))
        assertEquals(RatePermit.Deferred("SERVER_COST_BUDGET", 3_659_999L), gate.preview(UUID.randomUUID(), 3_600_000))
        assertEquals(2, gate.reservedInCurrentWindow())
        assertEquals(RatePermit.Granted, gate.preview(UUID.randomUUID(), 3_659_999))
        assertEquals(0, gate.reservedInCurrentWindow())
    }

    @Test fun capacityAndArithmeticPressureStayExplicitAndDoNotPartiallyCharge() {
        for (settle in listOf(false, true)) {
            val gate = InferenceRateGate(unlimited)
            repeat(720) {
                val id = UUID.randomUUID()
                assertEquals(RatePermit.Granted, gate.reserve(UUID.randomUUID(), 0, requestId = id))
                if (settle) assertTrue(gate.completeSubmission(id))
            }
            assertEquals(RatePermit.Deferred("SERVER_ACCOUNTING_CAPACITY", 3_600_000L), gate.reserve(UUID.randomUUID(), 0))
            assertEquals(720, gate.reservedInCurrentWindow()); assertEquals(720, gate.trackedNpcs())
            assertEquals(RatePermit.Granted, gate.reserve(UUID.randomUUID(), 3_600_000))
            assertEquals(1, gate.reservedInCurrentWindow())
        }
        for (charge in listOf(InferenceCharge(Long.MAX_VALUE, 0, 0), InferenceCharge(0, Long.MAX_VALUE, 0),
            InferenceCharge(0, 0, Long.MAX_VALUE))) {
            val gate = InferenceRateGate(unlimited); val id = UUID.randomUUID()
            assertEquals(RatePermit.Granted, gate.reserve(UUID.randomUUID(), 0, charge, id))
            assertTrue(gate.completeSubmission(id))
            assertEquals(RatePermit.Deferred("SERVER_ACCOUNTING_EXHAUSTED", 3_600_000L),
                gate.reserve(UUID.randomUUID(), 0, InferenceCharge(1, 1, 1)))
            assertEquals(1, gate.reservedInCurrentWindow())
            gate.reconfigure(ServerInferenceResources())
            assertEquals(RatePermit.Deferred(if (charge.inputTokens > 0) "SERVER_INPUT_BUDGET" else
                if (charge.outputTokens > 0) "SERVER_OUTPUT_BUDGET" else "SERVER_COST_BUDGET", 3_600_000L),
                gate.preview(UUID.randomUUID(), 0))
        }
    }
}
