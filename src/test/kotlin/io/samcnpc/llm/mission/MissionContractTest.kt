package io.samcnpc.llm.mission

import com.google.gson.JsonParser
import io.samcnpc.core.api.NpcBlockPosition
import io.samcnpc.core.api.NpcPosition
import net.minecraft.nbt.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class MissionContractTest {
    private val dimension = "minecraft:overworld"
    private val home = NpcPosition(0.5, 64.0, 0.5)
    private val chest = MissionChest(dimension, NpcBlockPosition(1, 64, 0))
    private val field = MissionTarget.Field(dimension, NpcBlockPosition(2, 63, 2), NpcBlockPosition(4, 63, 4))
    private fun items(id: String, item: String, quantity: Int, storage: MissionChest? = null) =
        MissionRequirement(id, "Get $quantity $item", MissionTarget.Items(listOf(item), quantity, storage))
    private fun contract() = MissionContract(listOf(
        items("R1", "minecraft:iron_pickaxe", 1), items("R2", "minecraft:oak_log", 32),
        items("R3", "minecraft:cobblestone", 30), items("R4", "minecraft:coal", 2),
        MissionRequirement("R5", "Return to surface", MissionTarget.Visit(dimension, home), listOf("R3", "R4")),
        MissionRequirement("R6", "Prepare field", field, listOf("R5"))))
    private fun plan() = MissionPlan(listOf(MissionStep("Acquire equipment", listOf("R1")),
        MissionStep("Cut oak", listOf("R2")), MissionStep("Mine cobble and coal", listOf("R3", "R4")),
        MissionStep("Return", listOf("R5")), MissionStep("Hoe", listOf("R6"))))
    private fun facts(carried: Map<String, Long>, task: UUID? = null, prepared: MissionTarget.Field? = null) =
        MissionFacts(dimension, home, carried, completedTask = task, preparedField = prepared)

    @Test fun missingCoalCoverageAndUnknownIdsAreRejectedBeforeExecution() {
        assertNull(plan().problem(contract()))
        val truncated = MissionPlan(plan().steps.map { if ("R4" in it.covers) MissionStep(it.description, listOf("R3")) else it })
        assertEquals("MISSION_INCOMPLETE_COVERAGE", truncated.problem(contract()))
        assertEquals("MISSION_UNKNOWN_REQUIREMENT", MissionPlan(plan().steps + MissionStep("Invented", listOf("R24"))).problem(contract()))
        val reversed = MissionPlan(plan().steps.reversed())
        assertEquals("MISSION_DEPENDENCY_ORDER", reversed.problem(contract()))
    }

    @Test fun exactCoalNeverAcceptsCharcoalWhileExplicitAlternativeDoes() {
        val exact = MissionContract(listOf(items("R1", "minecraft:coal", 2)))
        val alternative = MissionContract(listOf(MissionRequirement("R1", "coal or charcoal", MissionTarget.Items(
            listOf("minecraft:coal", "minecraft:charcoal"), 2))))
        val charcoal = facts(mapOf("minecraft:charcoal" to 64))
        assertFalse(MissionEvaluator.evaluate(exact, emptyList(), charcoal).complete)
        assertEquals(0L, MissionEvaluator.evaluate(exact, emptyList(), charcoal).progress.single().count)
        assertTrue(MissionEvaluator.evaluate(alternative, emptyList(), charcoal).complete)
        assertTrue(MissionEvaluator.evaluate(alternative, emptyList(), facts(mapOf("minecraft:coal" to 1, "minecraft:charcoal" to 1))).complete)
    }

    @Test fun everyRequirementIsRecomputedAfterIncidentalMiningAndResourceLoss() {
        val mission = MissionContract(contract().requirements.take(4))
        val before = facts(mapOf("minecraft:iron_pickaxe" to 1, "minecraft:oak_log" to 32, "minecraft:cobblestone" to 4))
        val first = MissionEvaluator.evaluate(mission, emptyList(), before)
        assertEquals(listOf(RequirementState.SATISFIED, RequirementState.SATISFIED, RequirementState.UNSATISFIED,
            RequirementState.UNSATISFIED), first.progress.map { it.state })
        val after = facts(before.carried + mapOf("minecraft:cobblestone" to 33, "minecraft:coal" to 5), UUID.randomUUID())
        val second = MissionEvaluator.evaluate(mission, first.receipts, after)
        assertTrue(second.complete)
        assertTrue(second.receipts.isEmpty())
        val lost = MissionEvaluator.evaluate(mission, second.receipts, facts(after.carried - "minecraft:coal"))
        assertFalse(lost.complete)
        assertEquals(RequirementState.UNSATISFIED, lost.progress.last().state)
    }

    @Test fun returnRequiresPhysicalBoundaryAfterResourcesAndFieldNeedsExactEvidence() {
        val mission = contract()
        val stock = mapOf("minecraft:iron_pickaxe" to 1L, "minecraft:oak_log" to 32L,
            "minecraft:cobblestone" to 30L, "minecraft:coal" to 2L)
        assertEquals(RequirementState.UNSATISFIED, MissionEvaluator.evaluate(mission, emptyList(), facts(stock)).progress[4].state)
        val task = UUID.randomUUID()
        val early = MissionEvaluator.evaluate(mission, emptyList(), facts(stock - "minecraft:coal", task))
        assertTrue(early.receipts.isEmpty())
        val returned = MissionEvaluator.evaluate(mission, emptyList(), facts(stock, task))
        assertEquals(listOf(MissionReceipt("R5", task)), returned.receipts)
        assertFalse(returned.complete)
        val wrongField = MissionTarget.Field(dimension, NpcBlockPosition(3, 63, 2), field.max)
        assertFalse(MissionEvaluator.evaluate(mission, returned.receipts, facts(stock, UUID.randomUUID(), wrongField)).complete)
        val done = MissionEvaluator.evaluate(mission, returned.receipts, facts(stock, UUID.randomUUID(), field))
        assertTrue(done.complete)
        assertEquals(setOf("R5", "R6"), done.receipts.map { it.requirementId }.toSet())
        assertNull(done.currentStep(plan()))
    }

    @Test fun inaccessibleChestIsUnknownAndCarriedStockDoesNotCountAsDelivered() {
        val mission = MissionContract(listOf(items("R1", "minecraft:coal", 2, chest)))
        val carried = mapOf("minecraft:coal" to 64L)
        val unseen = MissionEvaluator.evaluate(mission, emptyList(), facts(carried))
        assertEquals(RequirementState.UNKNOWN, unseen.progress.single().state)
        assertNull(unseen.progress.single().count)
        val empty = MissionFacts(dimension, home, carried, mapOf(chest to emptyMap()))
        assertFalse(MissionEvaluator.evaluate(mission, emptyList(), empty).complete)
        val delivered = MissionFacts(dimension, home, emptyMap(), mapOf(chest to mapOf("minecraft:coal" to 2L)))
        assertTrue(MissionEvaluator.evaluate(mission, emptyList(), delivered).complete)
    }

    @Test fun dependencyCyclesDuplicatesAndUnboundedInputsCannotCreateContracts() {
        val first = items("R1", "minecraft:coal", 2)
        assertThrows(IllegalArgumentException::class.java) { MissionContract(listOf(first, first)) }
        assertThrows(IllegalArgumentException::class.java) {
            MissionContract(listOf(MissionRequirement("R1", "invalid dependency", first.target, listOf("R2")),
                MissionRequirement("R2", "cycle", first.target, listOf("R1"))))
        }
        for (id in listOf("r1", "R01", "R0", "R25", "R-1", "command"))
            assertThrows(IllegalArgumentException::class.java) { MissionRequirement(id, "bad id", first.target) }
        assertThrows(IllegalArgumentException::class.java) { MissionTarget.Items(listOf("minecraft:coal"), 2305) }
        assertThrows(IllegalArgumentException::class.java) { MissionTarget.Items(listOf("minecraft:coal", "minecraft:coal"), 2) }
        assertThrows(IllegalArgumentException::class.java) { MissionTarget.Visit(dimension, NpcPosition(Double.NaN, 0.0, 0.0)) }
        assertThrows(IllegalArgumentException::class.java) { MissionTarget.Field(dimension, field.min, NpcBlockPosition(100, 63, 100)) }
    }

    @Test fun immutableContractsAndPlansCannotBeEditedThroughInputCollections() {
        val ids = mutableListOf("minecraft:coal")
        val target = MissionTarget.Items(ids, 2)
        ids.add("minecraft:charcoal")
        val rows = mutableListOf(MissionRequirement("R1", "coal", target))
        val contract = MissionContract(rows)
        rows.clear()
        assertEquals(listOf("minecraft:coal"), (contract.requirements.single().target as MissionTarget.Items).itemIds)
        assertThrows(UnsupportedOperationException::class.java) { (contract.requirements as MutableList).clear() }
    }

    @Test fun strictJsonRetainsExactIdentityScopeAndGrounding() {
        val encoded = MissionCodec.encode(contract()).toString()
        assertEquals(contract(), MissionCodec.contract(encoded))
        assertEquals(plan(), MissionCodec.plan(MissionCodec.encode(plan()).toString()))
        assertNull(contract().groundingProblem(contract().requirements.joinToString("; ") { it.source }))
        assertEquals("MISSION_SOURCE_NOT_IN_GOAL", contract().groundingProblem("only get coal"))
        val cases = listOf(
            encoded.replace("\"version\":1", "\"version\":2"),
            encoded.replace("\"version\":1", "\"version\":1,\"version\":1"),
            encoded.replace("\"minimum\":32", "\"minimum\":32.5"),
            encoded.replace("\"kind\":\"ITEMS\"", "\"kind\":\"EXECUTE\""),
            encoded.replace("\"version\":1", "\"version\":1,\"completed\":true"))
        for (bad in cases) assertThrows(IllegalArgumentException::class.java) { MissionCodec.contract(bad) }
        val chestContract = MissionContract(listOf(items("R1", "minecraft:coal", 2, chest)))
        assertEquals(chestContract, MissionCodec.contract(MissionCodec.encode(chestContract).toString()))
    }

    @Test fun persistenceRejectsForgedItemReceiptsUnknownVersionsAndPartialPlans() {
        val state = MissionState(contract(), plan(), listOf(MissionReceipt("R5", UUID.randomUUID())))
        assertEquals(state, MissionStateCodec.decode(MissionStateCodec.encode(state)))
        assertEquals(MissionState(), MissionStateCodec.decode(MissionStateCodec.encode(MissionState())))
        assertThrows(IllegalArgumentException::class.java) { MissionState(null, plan()) }
        assertThrows(IllegalArgumentException::class.java) { MissionState(contract(), plan(), listOf(MissionReceipt("R4", UUID.randomUUID()))) }
        val unknown = MissionStateCodec.encode(state).also { it.putInt("version", 2) }
        assertNull(MissionStateCodec.decode(unknown))
        val malformed = MissionStateCodec.encode(state).also { it.putString("receipts", "completed") }
        assertNull(MissionStateCodec.decode(malformed))
        val extra = MissionStateCodec.encode(state).also { it.putBoolean("success", true) }
        assertNull(MissionStateCodec.decode(extra))
        val missing = MissionStateCodec.encode(state).also { it.remove("contract") }
        assertNull(MissionStateCodec.decode(missing))
    }
}
