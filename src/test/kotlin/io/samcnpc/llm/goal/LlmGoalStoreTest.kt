package io.samcnpc.llm.goal

import io.samcnpc.llm.scheduling.*
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.StringTag
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class LlmGoalStoreTest {
    @Test fun versionFourMigrationRetainsPolicyAndOutstandingChargesWhileUnlimitedRoundTripsPastOldCaps() {
        val old = record().copy(phase = GoalPhase.INFERENCING, manualHold = true,
            budget = InferenceBudgetView(3, 32000, 4096, 0, UUID.randomUUID()))
        val legacy = file(old); legacy.putInt("version", 4)
        legacy.getList("goals", 10).getCompound(0).remove("intentMode")
        legacy.getList("goals", 10).getCompound(0).remove("quotaMode")
        val restored = checkNotNull(LlmGoalStore.load(legacy).get(old.npcUuid))
        assertEquals(old.recovered(), restored)
        assertEquals(InferenceQuotaMode.LIMITED, restored.limits.quotaMode)
        val unlimited = old.copy(limits = old.limits.copy(quotaMode = InferenceQuotaMode.UNLIMITED),
            budget = InferenceBudgetView(2000, 4_000_000, 2_000_000, 999, UUID.randomUUID()))
        val first = LlmGoalStore.load(file(unlimited))
        val value = checkNotNull(first.get(unlimited.npcUuid))
        assertEquals(unlimited.recovered(), value); assertEquals(2001, value.budget.settledAttempts)
        assertEquals(value, LlmGoalStore.load(first.save(CompoundTag())).get(value.npcUuid))
        assertTrue(value.manualHold); assertNull(InferenceBudget(value.limits, value.budget).availableCalls)
        for (bad in listOf("", "unlimited", "AUTO", "LIMITED ")) {
            val source = file(unlimited)
            source.getList("goals", 10).getCompound(0).putString("quotaMode", bad)
            assertEquals("INVALID_GOAL_RECORD", LlmGoalStore.load(source).problem)
        }
        assertNull(GoalRecordCodec.decode(GoalRecordCodec.encode(unlimited).also { it.remove("quotaMode") }))
    }

    private fun record() = GoalRecord(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1,
        "Przynieś 32 kłody do wskazanej skrzyni", phase = GoalPhase.WAITING)
    private fun file(vararg records: GoalRecord): CompoundTag {
        val tag = CompoundTag(); tag.putInt("version", LlmGoalStore.VERSION)
        val entries = ListTag(); records.forEach { entries.add(GoalRecordCodec.encode(it)) }
        tag.put("goals", entries); return tag
    }


    @Test fun versionOneMigratesAsTranslatorWithoutResettingBudgetOrKnownTask() {
        val value = record().copy(phase = GoalPhase.EXECUTING,
            task = GoalTask(UUID.randomUUID(), 2, 3, "samcnpc:transport"),
            budget = InferenceBudgetView(2, 32000, 2048, 0, null))
        val legacy = file(value); legacy.putInt("version", 1)
        legacy.getList("goals", 10).getCompound(0).remove("intentMode")
        legacy.getList("goals", 10).getCompound(0).remove("mode")
        legacy.getList("goals", 10).getCompound(0).remove("memory")
        legacy.getList("goals", 10).getCompound(0).remove("planSteps")
        legacy.getList("goals", 10).getCompound(0).remove("quotaMode")
        val restored = LlmGoalStore.load(legacy)
        assertNull(restored.problem); assertEquals(value, restored.get(value.npcUuid))
        assertEquals(6, restored.save(CompoundTag()).getInt("version"))
        assertEquals(value, LlmGoalStore.load(restored.save(CompoundTag())).get(value.npcUuid))
        val disguised = file(value); disguised.putInt("version", 1)
        assertEquals("INVALID_GOAL_RECORD", LlmGoalStore.load(disguised).problem)
    }

    @Test fun strictRoundTripPreservesQuestionTaskIdentityAndConservativeBudget() {
        val value = record().copy(answer = "Dąb", phase = GoalPhase.ASK_USER, question = "Która skrzynia?",
            task = GoalTask(UUID.randomUUID(), 3, 7, "samcnpc:lumberjack"),
            budget = InferenceBudgetView(2, 16000, 2048, 0, null))
        val store = LlmGoalStore.load(file(value))
        assertNull(store.problem); assertEquals(value, store.get(value.npcUuid))
        assertEquals(value, GoalRecordCodec.decode(store.save(CompoundTag()).getList("goals", 10).getCompound(0)))
        assertTrue(value.contextText().contains("Latest user clarification:\nDąb"))
        assertTrue(value.contextText().length <= 2048)
    }

    @Test fun restartNeverReplaysQueuedInferenceOrUncertainAdmissionAndSettlesOnce() {
        for (phase in listOf(GoalPhase.QUEUED, GoalPhase.INFERENCING, GoalPhase.ADMITTING)) {
            val held = if (phase == GoalPhase.QUEUED) null else UUID.randomUUID()
            val value = record().copy(phase = phase, contextId = UUID.randomUUID(),
                budget = InferenceBudgetView(3, 32000, 4096, 0, held))
            val first = LlmGoalStore.load(file(value)); val restored = checkNotNull(first.get(value.npcUuid))
            assertEquals(GoalPhase.REVIEW_REQUIRED, restored.phase); assertTrue(restored.manualHold)
            assertEquals("RESTART_REVIEW_REQUIRED", restored.code); assertNull(restored.contextId)
            assertNull(restored.budget.inFlight)
            assertEquals(if (held == null) 3 else 4, restored.budget.settledAttempts)
            assertEquals(32000, restored.budget.chargedInputTokens)
            val second = LlmGoalStore.load(first.save(CompoundTag()))
            assertEquals(restored, second.get(value.npcUuid))
        }
    }

    @Test fun knownExecutingTaskIsRetainedWithoutResubmittingAndLastHeldAttemptSettles() {
        val value = record().copy(phase = GoalPhase.EXECUTING,
            task = GoalTask(UUID.randomUUID(), 0, 0, "samcnpc:transport"),
            limits = InferenceBudgetLimits(attempts = 1),
            budget = InferenceBudgetView(0, 8192, 1024, 0, UUID.randomUUID()))
        val restored = checkNotNull(LlmGoalStore.load(file(value)).get(value.npcUuid))
        assertEquals(GoalPhase.EXECUTING, restored.phase); assertEquals(value.task, restored.task)
        assertEquals(0, InferenceBudget(restored.limits, restored.budget).availableCalls)
        assertNull(restored.budget.inFlight)
    }

    @Test fun unsupportedAndMalformedFilesRemainReadOnlyAndPreserveOriginalNbt() {
        val value = record()
        val cases = listOf(
            file(value).also { it.putInt("version", 42) },
            file(value).also { it.putString("version", "1") },
            file(value).also { it.put("goals", ListTag().also { list -> list.add(StringTag.valueOf("invalid")) }) },
            file(value, value),
            file(value).also { it.getList("goals", 10).getCompound(0).putString("unknown", "payload") },
            file(value).also { it.getList("goals", 10).getCompound(0).putInt("revision", 1) },
            file(value).also { it.getList("goals", 10).getCompound(0).putByte("hold", 2) },
        )
        for (source in cases) {
            val store = LlmGoalStore.load(source)
            assertNotNull(store.problem); assertEquals(0, store.size)
            assertNotNull(store.put(record())); assertNotNull(store.remove(value.npcUuid))
            assertEquals(source, store.save(CompoundTag()))
        }
    }

    @Test fun capacityRejectsNewNpcButAllowsReplacingAndExplicitlyForgettingKnownNpc() {
        val store = LlmGoalStore.empty()
        val records = List(256) { record() }
        records.forEach { assertNull(store.put(it)) }
        assertEquals("GOAL_STORE_FULL", store.put(record()))
        assertNull(store.put(records[0].copy(revision = 2)))
        assertEquals(256, store.size)
        assertNull(store.remove(records[1].npcUuid)); assertNull(store.put(record()))
        assertEquals(256, store.size)
        val overflow = file(*(records + record()).toTypedArray())
        assertEquals("INVALID_GOAL_ENTRIES", LlmGoalStore.load(overflow).problem)
    }

    @Test fun invalidTextBudgetsAndPhaseCombinationsCannotConstructARecord() {
        val value = record()
        for (text in listOf("", " ", "x".repeat(1025), "a\u0000b", "\uD800", "\uDC00"))
            assertThrows(IllegalArgumentException::class.java) { value.copy(text = text) }
        assertThrows(IllegalArgumentException::class.java) { value.copy(phase = GoalPhase.ASK_USER) }
        assertThrows(IllegalArgumentException::class.java) { value.copy(question = "unused question") }
        assertThrows(IllegalArgumentException::class.java) { value.copy(phase = GoalPhase.EXECUTING) }
        assertThrows(IllegalArgumentException::class.java) { value.copy(phase = GoalPhase.INFERENCING) }
        assertThrows(IllegalArgumentException::class.java) { value.copy(code = "unbounded diagnostic") }
        assertThrows(IllegalArgumentException::class.java) {
            value.copy(budget = InferenceBudgetView(24, 0, 0, 0, UUID.randomUUID()))
        }
    }

    @Test fun serializationBoundCountsEncodedBytesAndAcceptsMaximumValidUnicodeFields() {
        val tag = CompoundTag(); tag.putString("oversized", "界".repeat(6000))
        assertFalse(GoalRecordCodec.fits(tag))
        val value = record().copy(text = "界".repeat(1024), answer = "界".repeat(512),
            phase = GoalPhase.ASK_USER, question = "界".repeat(256))
        assertTrue(GoalRecordCodec.fits(GoalRecordCodec.encode(value)))
        assertEquals(value, GoalRecordCodec.decode(GoalRecordCodec.encode(value)))
    }

    @Test fun rejectedRecordDoesNotExposeEarlierValidEntriesFromTheSameFile() {
        val first = record(); val second = record()
        val source = file(first, second)
        source.getList("goals", 10).getCompound(1).putLong("cost", -1)
        val store = LlmGoalStore.load(source)
        assertEquals("INVALID_GOAL_RECORD", store.problem)
        assertNull(store.get(first.npcUuid)); assertTrue(store.records().isEmpty())
        assertEquals(source, store.save(CompoundTag()))
    }
}
