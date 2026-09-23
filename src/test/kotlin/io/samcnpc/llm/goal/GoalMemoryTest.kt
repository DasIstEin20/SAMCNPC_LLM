package io.samcnpc.llm.goal

import io.samcnpc.core.api.NpcBlockPosition
import io.samcnpc.llm.context.ContextPlaceAlias
import io.samcnpc.llm.context.LlmMode
import io.samcnpc.llm.scheduling.InferenceBudgetView
import io.samcnpc.llm.supervision.StockSupervision
import io.samcnpc.llm.supervision.StockTarget
import net.minecraft.nbt.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class GoalMemoryTest {
    private fun place(name: String = "main_storage") = ContextPlaceAlias(name, "minecraft:overworld", NpcBlockPosition(1, 64, 3))
    private fun record() = GoalRecord(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, "Prepare supplies",
        phase = GoalPhase.WAITING)

    @Test fun memoryCopiesInputsAndRejectsOversizedOrInvalidIntent() {
        val steps = mutableListOf("Gather food"); val aliases = mutableListOf(place())
        val memory = GoalMemory(steps, aliases)
        steps.clear(); aliases.clear()
        assertEquals(listOf("Gather food"), memory.plan); assertEquals(listOf(place()), memory.aliases)
        assertThrows(UnsupportedOperationException::class.java) { (memory.plan as MutableList).add("mutate") }
        for (plan in listOf(List(9) { "step" }, listOf(" "), listOf("\uD800"), List(8) { "界".repeat(256) }))
            assertThrows(IllegalArgumentException::class.java) { GoalMemory(plan) }
        for (alias in listOf(place("UPPER"), place("unsafe.name"), place().copy(dimensionId = "OverWorld"),
            place().copy(position = NpcBlockPosition(30000000, 64, 0)))) {
            assertThrows(IllegalArgumentException::class.java) { GoalMemory(aliases = listOf(alias)) }
        }
        assertThrows(IllegalArgumentException::class.java) { GoalMemory(aliases = List(17) { place("p$it") }) }
        assertThrows(IllegalArgumentException::class.java) { GoalMemory(aliases = listOf(place(), place())) }
        assertEquals(emptyList<String>(), memory.placesOnly().plan)
        assertEquals(memory.aliases, memory.placesOnly().aliases)
    }

    @Test fun confirmedResultsAreIdempotentBoundedAndCannotBeModelSummaries() {
        var memory = GoalMemory(aliases = listOf(place()))
        val task = GoalTask(UUID.randomUUID(), 0, 0, "samcnpc:transport")
        memory = memory.withOutcome(task, "TASK_COMPLETED")
        assertSame(memory, memory.withOutcome(task, "TASK_FAILED"))
        assertThrows(IllegalArgumentException::class.java) { memory.withOutcome(task, "Model says success") }
        repeat(40) { memory = memory.withOutcome(GoalTask(UUID.randomUUID(), 0, 0, "samcnpc:lumberjack"), "TASK_FAILED") }
        assertEquals(16, memory.results.size)
        assertTrue(memory.results.none { it.startsWith(task.id.toString()) })
        assertEquals(listOf(place()), memory.aliases)
        assertEquals(memory, GoalMemoryCodec.decode(GoalMemoryCodec.encode(memory)))
        assertEquals(emptyList<String>(), memory.placesOnly().results)
    }

    @Test fun malformedMemoryDoesNotPartiallyLoadOrOverwriteTheOriginalSave() {
        val memory = GoalMemory(listOf("Gather food"), listOf(place()), listOf("server historical result"))
        val encoded = GoalMemoryCodec.encode(memory)
        assertEquals(memory, GoalMemoryCodec.decode(encoded))
        val invalid = listOf(
            encoded.copy().also { it.putString("unknown", "payload") },
            encoded.copy().also { it.remove("results") },
            encoded.copy().also { it.putString("aliases", "wrong type") },
            encoded.copy().also { it.getList("aliases", 10).getCompound(0).putLong("x", 2) },
            encoded.copy().also { it.getList("aliases", 10).getCompound(0).putString("dimension", "MALFORMED") },
            encoded.copy().also { it.getList("aliases", 10).add(it.getList("aliases", 10).getCompound(0).copy()) },
            encoded.copy().also { it.put("plan", ListTag().also { list -> repeat(9) { list.add(StringTag.valueOf("step")) } }) })
        for (bad in invalid) {
            assertNull(GoalMemoryCodec.decode(bad))
            val store = LlmGoalStore.empty(); val value = record()
            assertNull(store.put(value))
            val file = store.save(CompoundTag()); file.getList("goals", 10).getCompound(0).put("memory", bad)
            val rejected = LlmGoalStore.load(file)
            assertEquals("INVALID_GOAL_RECORD", rejected.problem); assertEquals(0, rejected.size)
            assertEquals(file, rejected.save(CompoundTag()))
        }
    }

    @Test fun versionTwoSupervisorMigratesWithoutChangingTargetTaskOrBudgets() {
        val value = record().copy(mode = LlmMode.SUPERVISOR,
            supervision = StockSupervision(StockTarget("minecraft:overworld", NpcBlockPosition(1, 64, 3),
                "minecraft:oak_log", 192, 256), armed = true),
            budget = InferenceBudgetView(2, 65536, 2048, 0, null))
        val store = LlmGoalStore.empty(); assertNull(store.put(value))
        val file = store.save(CompoundTag()); file.putInt("version", 2)
        file.getList("goals", 10).getCompound(0).remove("memory")
        file.getList("goals", 10).getCompound(0).remove("planSteps")
        file.getList("goals", 10).getCompound(0).remove("quotaMode")
        file.getList("goals", 10).getCompound(0).remove("intentMode")
        val loaded = LlmGoalStore.load(file)
        assertNull(loaded.problem); assertEquals(value, loaded.get(value.npcUuid))
        assertEquals(6, loaded.save(CompoundTag()).getInt("version"))
        val disguised = file.copy(); disguised.getList("goals", 10).getCompound(0).put("memory", CompoundTag())
        assertEquals("INVALID_GOAL_RECORD", LlmGoalStore.load(disguised).problem)
    }

    @Test fun boundedUnicodeMemoryAndMaximumGoalFieldsFitExistingRecordBudget() {
        var memory = GoalMemory(List(4) { "界".repeat(256) }, listOf(place()))
        repeat(30) { memory = memory.withOutcome(GoalTask(UUID.randomUUID(), 0, 0, "samcnpc:transport"), "TASK_COMPLETED") }
        val value = record().copy(text = "界".repeat(1024), answer = "界".repeat(512),
            phase = GoalPhase.ASK_USER, question = "界".repeat(256), memory = memory)
        val tag = GoalRecordCodec.encode(value)
        assertTrue(GoalRecordCodec.fits(tag)); assertEquals(value, GoalRecordCodec.decode(tag))
        assertEquals(memory, value.recovered().memory)
    }
}
