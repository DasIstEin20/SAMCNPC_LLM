package io.samcnpc.llm.scheduling

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class InferenceBudgetTest {
    @Test fun provenUnsentReservationReturnsExactResourcesAndCannotBeReleasedTwiceOrAfterRestore() {
        val budget = InferenceBudget(); val id = UUID.randomUUID()
        val before = budget.snapshot()
        assertNull(budget.reserve(id, InferenceCharge(100, 10, 0)))
        val restored = InferenceBudget(budget.limits, budget.snapshot())
        assertFalse(restored.releaseUnsent(id))
        assertFalse(budget.releaseUnsent(UUID.randomUUID()))
        assertTrue(budget.releaseUnsent(id))
        assertEquals(before, budget.snapshot())
        assertFalse(budget.releaseUnsent(id))
        assertTrue(restored.settle(id))
        assertEquals(1, restored.snapshot().settledAttempts)
    }

    @Test fun lastReservedCallCanBeAdmittedBeforeSettlementButCannotStartAnotherRequest() {
        val budget = InferenceBudget(InferenceBudgetLimits(attempts = 1)); val id = UUID.randomUUID()
        assertNull(budget.reserve(id, InferenceCharge(100, 10, 0)))
        assertEquals(0, budget.availableCalls); assertEquals(1, budget.contextRemainingCalls)
        assertEquals("GOAL_REQUEST_IN_FLIGHT", budget.reserve(UUID.randomUUID(), InferenceCharge(0, 0, 0)))
        assertTrue(budget.settle(id)); assertFalse(budget.settle(id))
        assertEquals(0, budget.contextRemainingCalls)
        assertEquals("GOAL_CALL_BUDGET_EXHAUSTED", budget.reserve(UUID.randomUUID(), InferenceCharge(0, 0, 0)))
    }

    @Test fun tokenAndCostLimitsRejectWithoutPartialChargesOrArithmeticOverflow() {
        val limits = InferenceBudgetLimits(inputTokens = Long.MAX_VALUE, outputTokens = 10, costMicros = 20)
        val budget = InferenceBudget(limits); val id = UUID.randomUUID()
        assertNull(budget.reserve(id, InferenceCharge(Long.MAX_VALUE - 1, 9, 19))); assertTrue(budget.settle(id))
        val before = budget.snapshot()
        for ((charge, code) in listOf(InferenceCharge(2, 0, 0) to "GOAL_INPUT_BUDGET_EXHAUSTED",
            InferenceCharge(0, 2, 0) to "GOAL_OUTPUT_BUDGET_EXHAUSTED",
            InferenceCharge(0, 0, 2) to "GOAL_COST_BUDGET_EXHAUSTED")) {
            assertEquals(code, budget.reserve(UUID.randomUUID(), charge))
            assertEquals(before, budget.snapshot())
        }
        assertNull(budget.reserve(UUID.randomUUID(), InferenceCharge(1, 1, 1)))
        assertEquals(Long.MAX_VALUE, budget.snapshot().chargedInputTokens)
    }

    @Test fun restoringABudgetPreservesOutstandingReservationAndConservativeCharges() {
        val budget = InferenceBudget(); val id = UUID.randomUUID()
        assertNull(budget.reserve(id, InferenceCharge(8192, 1024, 0)))
        val restored = InferenceBudget(budget.limits, budget.snapshot())
        assertEquals(23, restored.availableCalls)
        assertEquals("GOAL_REQUEST_IN_FLIGHT", restored.reserve(UUID.randomUUID(), InferenceCharge(1, 1, 0)))
        assertTrue(restored.settle(id))
        assertEquals(23, restored.availableCalls)
        assertEquals(8192, restored.snapshot().chargedInputTokens)
        assertEquals(1024, restored.snapshot().chargedOutputTokens)
        assertFalse(restored.settle(id))
    }
}
