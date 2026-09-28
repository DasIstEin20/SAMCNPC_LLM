package io.samcnpc.llm.mission

import com.google.gson.*
import io.samcnpc.llm.context.LlmMode
import io.samcnpc.llm.goal.*
import io.samcnpc.llm.scheduling.InferenceBudgetView
import net.minecraft.nbt.CompoundTag
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class MissionProtocolTest {
    private val context = UUID.randomUUID()
    private val text = "Get 32 oak logs, 30 cobblestone and 2 minecraft:coal. Do not substitute charcoal for coal."
    private val contract = MissionContract(listOf(
        MissionRequirement("R1", "32 oak logs", MissionTarget.Items(listOf("minecraft:oak_log"), 32)),
        MissionRequirement("R2", "30 cobblestone", MissionTarget.Items(listOf("minecraft:cobblestone"), 30)),
        MissionRequirement("R3", "2 minecraft:coal", MissionTarget.Items(listOf("minecraft:coal"), 2))))
    private val plan = MissionPlan(listOf(MissionStep("Get wood", listOf("R1")), MissionStep("Mine both resources", listOf("R2", "R3"))))
    private fun envelope(field: String, value: JsonElement, question: String? = null) = JsonObject().also {
        it.addProperty("schemaVersion", 1); it.addProperty("contextId", context.toString())
        it.add(field, value); it.add("question", question?.let(::JsonPrimitive) ?: JsonNull.INSTANCE)
    }

    @Test fun independentStageSchemasNeverExposeOperationsAndHaveClosedPayloads() {
        val requirements = JsonParser.parseString(MissionProtocol.schema(context, MissionState(), "minecraft:overworld")).asJsonObject
        assertEquals(setOf("schemaVersion", "contextId", "contract", "question"), requirements["properties"].asJsonObject.keySet())
        assertFalse(requirements["additionalProperties"].asBoolean)
        val planned = JsonParser.parseString(MissionProtocol.schema(context, MissionState(contract), "minecraft:overworld")).asJsonObject
        assertEquals(setOf("schemaVersion", "contextId", "plan", "question"), planned["properties"].asJsonObject.keySet())
        assertFalse(planned.toString().contains("operation"))
    }

    @Test fun extractionKeepsAllExactItemRequirementsAndRejectsCommandsOrFabricatedGrounding() {
        val valid = envelope("contract", MissionCodec.encode(contract))
        val decoded = MissionProtocol.decode(valid.toString(), context, MissionState(), text) as MissionDecodeResult.Accepted
        assertEquals(contract, (decoded.reply as MissionReply.Requirements).contract)
        assertEquals(listOf("minecraft:coal"), (contract.requirements.last().target as MissionTarget.Items).itemIds)
        val bad = listOf(valid.deepCopy().also { it.addProperty("command", "/give") },
            valid.deepCopy().also { it.addProperty("contextId", UUID.randomUUID().toString()) },
            valid.deepCopy().also { it.addProperty("question", "also mutate") },
            valid.deepCopy().also { it.add("operation", JsonObject()) })
        for (row in bad) assertTrue(MissionProtocol.decode(row.toString(), context, MissionState(), text) is MissionDecodeResult.Rejected)
        val ungrounded = MissionProtocol.decode(valid.toString(), context, MissionState(), "Get only oak")
        assertEquals("MISSION_SOURCE_NOT_IN_GOAL", (ungrounded as MissionDecodeResult.Rejected).code)
    }

    @Test fun planCannotOmitCoalRewriteContractOrAssertSuccess() {
        val state = MissionState(contract)
        val valid = envelope("plan", MissionCodec.encode(plan))
        assertTrue(MissionProtocol.decode(valid.toString(), context, state, text) is MissionDecodeResult.Accepted)
        val missing = MissionPlan(listOf(MissionStep("Get wood", listOf("R1")), MissionStep("Mine", listOf("R2"))))
        val rejected = MissionProtocol.decode(envelope("plan", MissionCodec.encode(missing)).toString(), context, state, text)
        assertEquals("MISSION_INCOMPLETE_COVERAGE", (rejected as MissionDecodeResult.Rejected).code)
        for (name in listOf("contract", "completed", "operation")) {
            val forged = valid.deepCopy().also { it.addProperty(name, true) }
            assertTrue(MissionProtocol.decode(forged.toString(), context, state, text) is MissionDecodeResult.Rejected)
        }
        val ask = MissionProtocol.decode(envelope("plan", JsonNull.INSTANCE, "Where should I work?").toString(), context, state, text)
        assertEquals("Where should I work?", ((ask as MissionDecodeResult.Accepted).reply as MissionReply.Question).text)
    }

    @Test fun miningVolumeCannotMasqueradeAsPreparedFieldAndProducesActionableFeedback() {
        val contract = JsonParser.parseString("""{"version":1,"requirements":[{"id":"R1","source":"Mine","after":[],"target":{"kind":"FIELD","dimension":"minecraft:overworld","min":{"x":0,"y":50,"z":0},"max":{"x":10,"y":64,"z":0}}}]}""")
        val reply = MissionProtocol.decode(envelope("contract", contract).toString(), context, MissionState(), "Mine")
        assertEquals("MISSION_FIELD_REQUIRES_ONE_SOIL_PLANE", (reply as MissionDecodeResult.Rejected).code)
    }

    @Test fun requirementsAndPlanAdvanceSeparatelyWithoutAWorldTaskOrBudgetReset() {
        val record = record().copy(phase = GoalPhase.INFERENCING,
            budget = InferenceBudgetView(1, 20000, 1024, 0, UUID.randomUUID()))
        val extracted = MissionOutcomes.adopt(record, MissionReply.Requirements(contract))
        assertEquals(MissionStage.PLAN, extracted.mission?.stage)
        assertNull(extracted.task); assertEquals(record.budget, extracted.budget)
        assertTrue(MissionOutcomes.boundary(extracted)); assertEquals(0, extracted.planStepsCompleted)
        val planned = MissionOutcomes.adopt(extracted, MissionReply.Plan(plan))
        assertEquals(MissionStage.OPERATION, planned.mission?.stage)
        assertNull(planned.task); assertEquals(record.budget, planned.budget)
        assertEquals(contract, planned.mission?.contract)
        assertThrows(IllegalArgumentException::class.java) { MissionOutcomes.adopt(planned, MissionReply.Requirements(contract)) }
    }

    @Test fun storeEightRoundTripsEveryStageAndUncertainInferenceRequiresReview() {
        for (state in listOf(MissionState(), MissionState(contract), MissionState(contract, plan))) {
            val record = record().copy(mission = state, phase = GoalPhase.INFERENCING,
                budget = InferenceBudgetView(2, 40000, 2048, 0, UUID.randomUUID()))
            val store = LlmGoalStore.empty(); assertNull(store.put(record))
            val restored = LlmGoalStore.load(store.save(CompoundTag()))
            assertNull(restored.problem)
            val recovered = checkNotNull(restored.get(record.npcUuid))
            assertEquals(state, recovered.mission)
            assertEquals(GoalPhase.REVIEW_REQUIRED, recovered.phase); assertTrue(recovered.manualHold)
            assertEquals(3, recovered.budget.settledAttempts); assertNull(recovered.budget.inFlight)
            assertEquals(recovered, LlmGoalStore.load(restored.save(CompoundTag())).get(record.npcUuid))
        }
    }

    @Test fun legacyVersionSevenRemainsV1AndUnknownOrDisguisedVariantsPreserveSaveReadOnly() {
        val v1 = record().copy(mission = null)
        val store = LlmGoalStore.empty(); assertNull(store.put(v1))
        val old = store.save(CompoundTag()); old.putInt("version", 7)
        old.getList("goals", 10).getCompound(0).remove("plannerVariant")
        val loaded = LlmGoalStore.load(old)
        assertNull(loaded.problem); assertNull(checkNotNull(loaded.get(v1.npcUuid)).mission)
        assertEquals(v1, loaded.get(v1.npcUuid))
        for (variant in listOf("MISSION_V3", "MISSION_V2", "auto", "")) {
            val bad = store.save(CompoundTag())
            bad.getList("goals", 10).getCompound(0).putString("plannerVariant", variant)
            val rejected = LlmGoalStore.load(bad)
            assertNotNull(rejected.problem); assertEquals(bad, rejected.save(CompoundTag()))
        }
    }

    private fun record() = GoalRecord(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, text,
        phase = GoalPhase.WAITING, mode = LlmMode.PLANNER, mission = MissionState())
}
