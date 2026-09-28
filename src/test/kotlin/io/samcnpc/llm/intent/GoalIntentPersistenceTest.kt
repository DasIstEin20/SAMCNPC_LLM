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
        for (mode in listOf("FREE_TEXT", "BOUNDED_V1", "BOUNDED_V3", "bounded_v1")) {
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
        legacy.getList("goals", 10).getCompound(0).remove("plannerVariant")
        legacy.getList("goals", 10).getCompound(0).remove("intentMode")
        val loaded = checkNotNull(LlmGoalStore.load(legacy).get(value.npcUuid))
        assertEquals(value.recovered(), loaded)
        assertNull(loaded.constraints); assertEquals(GoalIntentReservation(), loaded.intentReservation)
        assertTrue(loaded.manualHold); assertEquals(2, loaded.budget.settledAttempts)
    }

    @Test fun versionSixBoundedMigrationPreservesItsOldMeaningAndDoesNotInventRequiredReturn() {
        val r=record();val legacy=file(r);legacy.putInt("version",6)
        val entry=legacy.getList("goals",10).getCompound(0)
        entry.remove("plannerVariant")
        val constraints=GoalConstraintCodec.encode(checkNotNull(r.constraints))
        constraints.addProperty("version",1);constraints.remove("requiredReturnTo")
        entry.putString("intentMode","BOUNDED_V1");entry.putString("constraints",constraints.toString())
        val store=LlmGoalStore.load(legacy);val restored=checkNotNull(store.get(r.npcUuid))
        assertEquals(1,restored.constraints?.version);assertNull(restored.constraints?.requiredReturnTo)
        assertEquals(r.intentReservation,restored.intentReservation);assertEquals(2,restored.budget.settledAttempts)
        val saved=store.save(CompoundTag());assertEquals(8,saved.getInt("version"))
        assertEquals("BOUNDED_V1",saved.getList("goals",10).getCompound(0).getString("intentMode"))
        val future=file(r);future.putInt("version",6);assertRejected(future)
    }

    @Test fun requiredReturnSurvivesSaveAndCannotBeSilentlyDroppedOrDowngraded() {
        val point=io.samcnpc.core.api.NpcPosition(-4.5,63.0,-2.5)
        val c=GoalConstraints(setOf(OperationType.NAVIGATE),emptySet(),"minecraft:overworld",GoalQuantityMeaning.NONE,
            0,0,null,emptyList(),emptyList(),emptyList(),listOf(point),false,false,false,requiredReturnTo=point)
        val r=record().copy(constraints=c,intentReservation=GoalIntentReservation())
        val saved=file(r);val restored=checkNotNull(LlmGoalStore.load(saved).get(r.npcUuid))
        assertEquals(point,restored.constraints?.requiredReturnTo)
        for(downgrade in listOf(false,true)) {
            val bad=saved.copy();val entry=bad.getList("goals",10).getCompound(0)
            val json=GoalConstraintCodec.encode(c)
            if(downgrade) json.addProperty("version",1) else json.remove("requiredReturnTo")
            entry.putString("constraints",json.toString());assertRejected(bad)
        }
    }

    private fun assertRejected(source: CompoundTag) {
        val loaded = LlmGoalStore.load(source)
        assertEquals("INVALID_GOAL_RECORD", loaded.problem)
        assertEquals(source, loaded.save(CompoundTag())); assertTrue(loaded.records().isEmpty())
    }
}
