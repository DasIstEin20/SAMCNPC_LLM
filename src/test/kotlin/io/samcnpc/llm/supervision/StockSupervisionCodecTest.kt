package io.samcnpc.llm.supervision

import io.samcnpc.core.api.NpcBlockPosition
import io.samcnpc.llm.context.LlmMode
import io.samcnpc.llm.goal.*
import io.samcnpc.llm.scheduling.InferenceBudgetView
import net.minecraft.nbt.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class StockSupervisionCodecTest {
    private fun state() = StockSupervision(
        StockTarget("minecraft:overworld", NpcBlockPosition(2, 64, 3), "minecraft:oak_log", 192, 256),
        armed = true, pendingDecision = "a".repeat(64), pendingWorld = "b".repeat(64), beforeCount = 176,
        failures = FailedDecisions().failed("c".repeat(64), "d".repeat(64), "SOURCE_EMPTY"), progressEpoch = 2)

    @Test fun pendingKnownTaskAndWaitingWatchSurviveWithoutReplayingInferenceOrResettingCharges() {
        val value = GoalRecord(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 3, "Maintain logs",
            mode = LlmMode.SUPERVISOR, supervision = state(), phase = GoalPhase.EXECUTING,
            task = GoalTask(UUID.randomUUID(), 2, 3, "samcnpc:transport"),
            budget = InferenceBudgetView(2, 64000, 2048, 0, UUID.randomUUID()))
        val store = LlmGoalStore.empty(); assertNull(store.put(value))
        val restored = checkNotNull(LlmGoalStore.load(store.save(CompoundTag())).get(value.npcUuid))
        assertEquals(value.task, restored.task); assertEquals(state(), restored.supervision)
        assertEquals(3, restored.budget.settledAttempts); assertEquals(64000, restored.budget.chargedInputTokens)
        assertNull(restored.budget.inFlight)
        val waiting = restored.copy(phase = GoalPhase.WAITING, task = null, supervision = state().clearAttempt().copy(waitUntilTick = 400))
        assertNull(store.put(waiting))
        assertEquals(waiting, LlmGoalStore.load(store.save(CompoundTag())).get(value.npcUuid))
        val uncertain = value.copy(phase = GoalPhase.ADMITTING, contextId = UUID.randomUUID())
        assertNull(store.put(uncertain))
        val recovered = checkNotNull(LlmGoalStore.load(store.save(CompoundTag())).get(value.npcUuid))
        assertEquals(GoalPhase.REVIEW_REQUIRED, recovered.phase); assertTrue(recovered.manualHold)
    }

    @Test fun malformedPartialAndOversizedFailureStateIsRejectedWithoutPartialLoad() {
        val encoded = StockSupervisionCodec.encode(state())
        assertEquals(state(), StockSupervisionCodec.decode(encoded))
        val invalid = listOf(
            encoded.copy().also { it.putString("unexpected", "payload") },
            encoded.copy().also { it.putInt("epoch", 2) },
            encoded.copy().also { it.putByte("armed", 2) },
            encoded.copy().also { it.remove("world") },
            encoded.copy().also { it.putInt("consecutive", 4) },
            encoded.copy().also { it.putInt("before", -1) },
            encoded.copy().also { it.putInt("target", 100) },
            encoded.copy().also { it.putLong("waitUntil", -1) },
            encoded.copy().also { it.put("failures", ListTag().also { list -> repeat(9) { list.add(encoded.getList("failures", 10).getCompound(0).copy()) } }) })
        for (tag in invalid) assertNull(StockSupervisionCodec.decode(tag))
        assertTrue(state().target.sameStorage(state().target.copy(position = NpcBlockPosition(3, 64, 3))))
        assertFalse(state().target.sameStorage(state().target.copy(position = NpcBlockPosition(4, 64, 3))))
    }
}
