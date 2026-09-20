package io.samcnpc.llm.supervision

import io.samcnpc.behavior.api.*
import io.samcnpc.core.api.*
import io.samcnpc.llm.decision.*
import io.samcnpc.llm.goal.*
import io.samcnpc.llm.context.LlmMode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class StockDecisionPolicyTest {
    private val pos = NpcBlockPosition(0, 64, 0)
    private val target = StockTarget("minecraft:overworld", pos, "minecraft:oak_log", 192, 256)
    private val stock = NpcStockRead.Observed(100, pos, target.itemId, 176, 27)
    private fun order() = OperationOrder.Transport(target.dimensionId, OperationContainers(listOf(pos.copy(x = 8))),
        OperationContainers(listOf(pos)), target.itemId, 80, NpcPosition(0.5, 64.0, 0.5))
    private fun problem(order: OperationOrder, read: NpcStockRead.Observed = stock) =
        StockDecisionPolicy.problem(DecisionAction.Assign(order), target, read)

    @Test fun exactDeficitAndRecipientAreEnforcedEvenForSyntacticallyValidOperations() {
        assertNull(problem(order()))
        assertEquals("STOCK_QUANTITY_MISMATCH", problem(order().copy(quantity = 81)))
        assertEquals("STOCK_TARGET_MISMATCH", problem(order().copy(itemId = "minecraft:diamond")))
        assertEquals("STOCK_TARGET_MISMATCH", problem(order().copy(destinations = OperationContainers(listOf(pos, pos.copy(x = 4))))))
        assertEquals("STOCK_SOURCE_IS_DESTINATION", problem(order().copy(sources = OperationContainers(listOf(pos)))))
        assertEquals("STOCK_RETURN_NOT_ALLOWED", problem(order().copy(returnTo = NpcPosition(100.0, 64.0, 0.0))))
        assertEquals("STOCK_ALREADY_SUFFICIENT", problem(order(), stock.copy(count = 256)))
        assertEquals("STOCK_QUANTITY_MISMATCH", problem(order(), stock.copy(count = 177)))
        assertEquals("STOCK_OPERATION_NOT_ALLOWED", problem(OperationOrder.Navigate(target.dimensionId, NpcPosition(1.0, 64.0, 1.0))))
    }

    @Test fun deliveryAndLumberjackKeepExactItemRatherThanBroadWoodPreset() {
        val deliver = OperationOrder.Deliver(target.dimensionId, pos, target.itemId, 80, NpcPosition(0.5, 64.0, 0.5))
        val wood = OperationHarvestOrder.Lumberjack(target.dimensionId,
            OperationWorkArea(OperationWorkBox(pos.copy(x = -3, z = -3), pos.copy(x = 3, y = 68, z = 3))),
            OperationWoodSelection(listOf(target.itemId)), pos, 80)
        assertEquals(NpcActionStatus.SUCCEEDED, OperationSupervisionApi.validateOrder(deliver).status)
        assertEquals(NpcActionStatus.SUCCEEDED, OperationSupervisionApi.validateOrder(wood).status)
        assertNull(problem(deliver)); assertNull(problem(wood))
        assertEquals("STOCK_QUANTITY_MISMATCH", problem(deliver.copy(quantity = 81)))
        assertEquals("STOCK_TARGET_MISMATCH", problem(wood.copy(wood = OperationWoodSelection(listOf("samcnpc:oak")))))
    }

    @Test fun semanticFingerprintIgnoresRetryBudgetsButSeparatesRealSources() {
        val first = StockFingerprints.decision(DecisionAction.Assign(order()))
        val reconstructed = order().copy(sources = OperationContainers(listOf(pos.copy(x = 8))),
            destinations = OperationContainers(listOf(pos)), budget = OperationBudget(12000, 8, 100))
        assertEquals(first, StockFingerprints.decision(DecisionAction.Assign(reconstructed)))
        assertNotEquals(first, StockFingerprints.decision(DecisionAction.Assign(order().copy(sources = OperationContainers(listOf(pos.copy(x = 9)))))))
        val state = StockSupervision(target)
        val a = StockFingerprints.world(state, stock, listOf("minecraft:stone" to 2, "minecraft:stone" to 3))
        val b = StockFingerprints.world(state, stock.copy(observedTick = 900), listOf("minecraft:stone" to 5))
        assertEquals(a, b)
        assertNotEquals(a, StockFingerprints.world(state.copy(progressEpoch = 1), stock, listOf("minecraft:stone" to 5)))
        assertEquals(StockFingerprints.decision(DecisionAction.Wait(WaitTrigger.DEADLINE, 20)),
            StockFingerprints.decision(DecisionAction.Wait(WaitTrigger.DEADLINE, 1200)))
    }

    @Test fun CompletionWithoutPhysicalStockIncreaseIsNotGoalProgress() {
        val state = StockSupervision(target, pendingDecision = "a".repeat(64), pendingWorld = "b".repeat(64), beforeCount = 176)
        val record = GoalRecord(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, "Maintain wood",
            mode = LlmMode.SUPERVISOR, supervision = state, phase = GoalPhase.COMPLETED, code = "TASK_COMPLETED",
            task = GoalTask(UUID.randomUUID(), 0, 0, "samcnpc:transport"))
        val unchanged = StockSupervisor.terminal(record, stock)
        assertEquals(1, unchanged.supervision?.failures?.consecutive)
        assertEquals(GoalPhase.WAITING, unchanged.phase)
        assertNull(unchanged.task)
        val progress = StockSupervisor.terminal(record, stock.copy(count = 256))
        assertEquals(0, progress.supervision?.failures?.consecutive)
        assertEquals(1L, progress.supervision?.progressEpoch)
        assertTrue(checkNotNull(progress.supervision).armed)
        assertEquals("STOCK_TARGET_REACHED", progress.code)
        assertFalse(checkNotNull(progress.supervision).target.needsRefill(220, true))
    }

    @Test fun DeadlineWaitsAreBoundedAndUserInterventionRetainsHistoricalFailures() {
        val failures = FailedDecisions().failed("a".repeat(64), "b".repeat(64), "WAIT").failed("a".repeat(64), "b".repeat(64), "WAIT")
        val state = StockSupervision(target, failures = failures, pendingDecision = "a".repeat(64), pendingWorld = "b".repeat(64), beforeCount = 176)
        val record = GoalRecord(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, "Maintain wood",
            mode = LlmMode.SUPERVISOR, supervision = state, phase = GoalPhase.WAITING)
        val decision = LlmDecision(UUID.randomUUID(), DecisionKind.WAIT, DecisionAction.Wait(WaitTrigger.DEADLINE, 20), "")
        val fresh = record.copy(supervision = state.copy(failures = FailedDecisions()))
        val waiting = StockSupervisor.admitted(fresh, decision, DecisionOutcome(DecisionOutcomeState.NO_EFFECT, "WAIT"), 100)
        assertEquals("WAIT_DEADLINE", waiting.code); assertFalse(waiting.manualHold)
        assertEquals(120L, waiting.supervision?.waitUntilTick)
        val rejected = StockSupervisor.admitted(fresh, decision, DecisionOutcome.rejected("STOCK_CHANGED_DURING_INFERENCE"), 100)
        assertEquals("STOCK_CHANGED_DURING_INFERENCE", rejected.code); assertNull(rejected.supervision?.waitUntilTick)
        val overflow = StockSupervisor.admitted(fresh, decision, DecisionOutcome(DecisionOutcomeState.NO_EFFECT, "WAIT"), Long.MAX_VALUE)
        assertEquals(GoalPhase.ASK_USER, overflow.phase); assertEquals("GAME_CLOCK_EXHAUSTED", overflow.code)
        val next = StockSupervisor.admitted(record, decision, DecisionOutcome(DecisionOutcomeState.NO_EFFECT, "WAIT"), 100)
        assertEquals(GoalPhase.ASK_USER, next.phase)
        assertEquals("NONPROGRESS_DECISION_LIMIT", next.code)
        val reset = checkNotNull(next.supervision).userIntervention()
        assertEquals(0, reset.failures.consecutive)
        assertEquals(3, reset.failures.entries.size)
        assertEquals(1L, reset.progressEpoch)
    }
}
