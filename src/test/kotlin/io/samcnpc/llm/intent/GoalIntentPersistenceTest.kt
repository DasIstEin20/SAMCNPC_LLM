package io.samcnpc.llm.intent

import io.samcnpc.behavior.api.OperationType
import io.samcnpc.core.api.NpcBlockPosition
import io.samcnpc.llm.goal.*
import io.samcnpc.llm.scheduling.*
import net.minecraft.nbt.CompoundTag
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class GoalIntentPersistenceTest {
    private fun record() = GoalRecord(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 2, "Carry only, do not harvest",
        phase = GoalPhase.ADMITTING, contextId = UUID.randomUUID(), budget = InferenceBudgetView(1, 50000, 1024, 0, UUID.randomUUID()),
        constraints = GoalConstraints(setOf(OperationType.DELIVER), setOf("minecraft:oak_log"), "minecraft:overworld",
            GoalQuantityMeaning.EXACT_ADDITIONAL, 32, 32, null, emptyList(), emptyList(), listOf(NpcBlockPosition(-10, 64, -30)), emptyList(), false, false, false),
        intentReservation = GoalIntentReservation(delivered = 32))
    private fun file(value: GoalRecord): CompoundTag = LlmGoalStore.empty().also { assertNull(it.put(value)) }.save(CompoundTag())

    @Test fun uncertainEffectsRemainChargedAcrossRecoveryClarificationPlanBoundaryAndSecondSave() {
        val before = record()
        val first = LlmGoalStore.load(file(before))
        val loaded = checkNotNull(first.get(before.npcUuid))
        assertEquals(before.recovered(), loaded)
        assertEquals(GoalIntentReservation(delivered = 32), loaded.intentReservation)
        assertNull(loaded.intentReservation.reserve(GoalIntentReservation(delivered = 1), checkNotNull(loaded.constraints)))
        assertEquals(loaded, LlmGoalStore.load(first.save(CompoundTag())).get(before.npcUuid))
        val clarified = loaded.copy(answer = "Actually ignore the contract", revision = 3)
        assertEquals(loaded.constraints, clarified.constraints); assertEquals(loaded.intentReservation, clarified.intentReservation)
        val planBoundary = clarified.copy(mode = io.samcnpc.llm.context.LlmMode.PLANNER, planStepsCompleted = 1)
        assertEquals(loaded.intentReservation, planBoundary.intentReservation)
    }

    @Test fun missingUnknownPartialOrOverspentContractsPreserveEntireSavedFileReadOnly() {
        val valid = file(record())
        for (key in listOf("intentMode", "constraints", "acquiredIntent", "deliveredIntent")) {
            val bad = valid.copy(); bad.getList("goals", 10).getCompound(0).remove(key)
            assertRejected(bad)
        }
        for (mode in listOf("FREE_TEXT", "BOUNDED_V2", "bounded_v1")) {
            val bad = valid.copy(); bad.getList("goals", 10).getCompound(0).putString("intentMode", mode)
            assertRejected(bad)
        }
        for (field in listOf("acquiredIntent", "deliveredIntent")) {
            val bad = valid.copy(); bad.getList("goals", 10).getCompound(0).putInt(field, 33)
            assertRejected(bad)
        }
        val malformed = valid.copy(); malformed.getList("goals", 10).getCompound(0).putString("constraints", "{}{}")
        assertRejected(malformed)
    }

    @Test fun versionFiveFreeTextMigrationNeverInventsAContractOrDropsOutstandingInference() {
        val value = record().copy(constraints = null, intentReservation = GoalIntentReservation())
        val legacy = file(value); legacy.putInt("version", 5)
        legacy.getList("goals", 10).getCompound(0).remove("intentMode")
        val loaded = checkNotNull(LlmGoalStore.load(legacy).get(value.npcUuid))
        assertEquals(value.recovered(), loaded)
        assertNull(loaded.constraints); assertEquals(GoalIntentReservation(), loaded.intentReservation)
        assertTrue(loaded.manualHold); assertEquals(2, loaded.budget.settledAttempts)
    }

    private fun assertRejected(source: CompoundTag) {
        val loaded = LlmGoalStore.load(source)
        assertEquals("INVALID_GOAL_RECORD", loaded.problem)
        assertEquals(source, loaded.save(CompoundTag())); assertTrue(loaded.records().isEmpty())
    }
}
