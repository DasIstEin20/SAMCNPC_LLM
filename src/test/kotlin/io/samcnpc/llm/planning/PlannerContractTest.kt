package io.samcnpc.llm.planning

import com.google.gson.*
import io.samcnpc.behavior.api.*
import io.samcnpc.core.api.*
import io.samcnpc.llm.context.*
import io.samcnpc.llm.decision.*
import io.samcnpc.llm.goal.*
import io.samcnpc.llm.scheduling.InferenceBudgetView
import net.minecraft.nbt.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class PlannerContractTest {
    private val contextId = UUID(1, 2)
    private fun proposal() = PlanProposal(listOf("Acquire supplies", "Return"), listOf(RequiredItem("minecraft:apple", 3)), 1)
    private fun decision() = LlmDecision(contextId, DecisionKind.ASSIGN,
        DecisionAction.Assign(OperationOrder.Navigate("minecraft:overworld", NpcPosition(3.0, 64.0, 0.0))),
        "Untrusted completion claim", 2, proposal())
    private fun envelope(): JsonObject = JsonParser.parseString("""
        {"schemaVersion":2,"contextId":"00000000-0000-0001-0000-000000000002",
         "decision":"ASSIGN","summary":"intent only","change":null,"question":null,"wait":null,
         "operation":{"documentVersion":1,"type":"samcnpc:navigate","definitionVersion":1,
           "parameters":{"dimensionId":"minecraft:overworld","destination":{"x":3,"y":64,"z":0}}},
         "plan":{"steps":["Acquire supplies","Return"],"requiredItems":[{"itemId":"minecraft:apple","minimum":3}],"minimumEmptySlots":1}}
    """).asJsonObject
    private fun record() = GoalRecord(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, "Prepare supplies",
        mode = LlmMode.PLANNER, phase = GoalPhase.WAITING)
    private fun applied() = DecisionOutcome(DecisionOutcomeState.APPLIED, "ASSIGNED", UUID.randomUUID(), 0, 0)

    @Test fun plannerAdmitsPublishedMiningAndFarmOrdersWithTheSameBehaviorValidation() {
        val policy = GoalPolicies.forRecord(record())
        val corpus = checkNotNull(javaClass.getResourceAsStream("/translator-corpus.json")).use {
            JsonParser.parseString(it.readBytes().toString(Charsets.UTF_8)).asJsonObject
        }
        val orders = corpus["cases"].asJsonArray.map { it.asJsonObject["operation"].asJsonObject }
        for (type in listOf(OperationType.MINING, OperationType.FARM)) {
            val document = orders.single { it["type"].asString == type.operationId }
            val decoded = OperationDocumentApi.decodeOrder(document.toString()) as OperationDocumentResult.Accepted
            assertNull(DecisionPolicy.orderProblem(decoded.value, policy, "minecraft:overworld"))
            assertEquals("DIMENSION_MISMATCH", DecisionPolicy.orderProblem(decoded.value, policy, "minecraft:the_nether"))
        }
        assertFalse(OperationType.ATTACK in policy.operations)
        assertTrue(policy.controls.isEmpty() && policy.changes.isEmpty())
    }

    @Test fun plannerEnvelopeIsClosedAndCannotInjectAccomplishmentsOrASequenceOfOrders() {
        val accepted = DecisionDecoder.decode(envelope().toString()) as DecisionDecodeResult.Accepted
        assertEquals(2, accepted.value.schemaVersion)
        assertEquals(proposal().steps, accepted.value.plan?.steps)
        val invalid = listOf(
            envelope().also { it.remove("plan") },
            envelope().also { it.addProperty("schemaVersion", 1) },
            envelope().also { it.add("plan", JsonNull.INSTANCE) },
            envelope().also { it["plan"].asJsonObject.addProperty("completed", true) },
            envelope().also { it["plan"].asJsonObject.add("steps", JsonArray().also { a -> repeat(9) { a.add("step") } }) },
            envelope().also { it["plan"].asJsonObject.add("steps", JsonArray().also { a -> a.add(" ") }) },
            envelope().also { it["plan"].asJsonObject["requiredItems"].asJsonArray.add(it["plan"].asJsonObject["requiredItems"].asJsonArray[0].deepCopy()) },
            envelope().also { it["plan"].asJsonObject.addProperty("minimumEmptySlots", 37) },
            envelope().also { it["plan"].asJsonObject["requiredItems"].asJsonArray[0].asJsonObject.addProperty("minimum", 1.5) },
            envelope().also { it["plan"].asJsonObject["steps"].asJsonArray.add(JsonObject()) },
            envelope().also { it.addProperty("decision", "CONTINUE"); it.add("operation", JsonNull.INSTANCE) })
        for (bad in invalid) assertTrue(DecisionDecoder.decode(bad.toString()) is DecisionDecodeResult.Rejected, bad.toString())
        val ask = envelope().also {
            it.addProperty("decision", "ASK_USER"); it.add("operation", JsonNull.INSTANCE)
            it.add("plan", JsonNull.INSTANCE); it.addProperty("question", "Where is storage?")
        }
        assertTrue(DecisionDecoder.decode(ask.toString()) is DecisionDecodeResult.Accepted)
        val policy = ContextPolicy(3, PlannerPolicy.operations, emptySet(), emptySet(), 72000, 16, 0)
        val schema = JsonParser.parseString(DecisionSchema.forContext(contextId, policy, true)).asJsonObject
        assertEquals(2, schema["properties"].asJsonObject["schemaVersion"].asJsonObject["const"].asInt)
        assertFalse(schema["additionalProperties"].asBoolean)
        assertTrue(schema["required"].asJsonArray.any { it.asString == "plan" })
        assertFalse(JsonParser.parseString(DecisionSchema.forContext(contextId, policy)).asJsonObject["properties"].asJsonObject.has("plan"))
        val lastStep = JsonParser.parseString(DecisionSchema.forContext(contextId, policy, planner = true,
            hasActiveTask = false, remainingPlanSteps = 1)).asJsonObject["properties"].asJsonObject
        assertEquals(1, lastStep["plan"].asJsonObject["anyOf"].asJsonArray[0].asJsonObject["properties"].asJsonObject
            ["steps"].asJsonObject["maxItems"].asInt)
        val exhausted = JsonParser.parseString(DecisionSchema.forContext(contextId, policy, planner = true,
            hasActiveTask = false, remainingPlanSteps = 0)).asJsonObject["properties"].asJsonObject
        assertEquals("null", exhausted["plan"].asJsonObject["type"].asString)
        assertFalse(exhausted["decision"].asJsonObject["enum"].asJsonArray.any { it.asString == "ASSIGN" })
    }

    @Test fun preconditionsCountOnlyRealInventoryAndRejectFreshResourceLossOrSpaceChanges() {
        val empty = NpcItemInspection(NpcItemStackSnapshot.EMPTY, NpcItemKnowledge.EMPTY, emptyList(), false, 0, NpcRangedResourceReadiness.NOT_RANGED)
        val apples = NpcItemInspection(NpcItemStackSnapshot("minecraft:apple", 3, 64, 0, 0),
            NpcItemKnowledge("minecraft:apple", setOf(NpcItemRole.OTHER)), emptyList(), false, 0, NpcRangedResourceReadiness.NOT_RANGED)
        fun body(hasApples: Boolean) = NpcBodyInspection(10, "Sam", 20F, 20F, 0F, 4, emptyList(), false,
            List(36) { if (hasApples && it == 4) apples else empty }, NpcInspectionSlot.entries.associateWith { apples }, 0)
        assertNull(proposal().preconditionProblem(body(true)))
        assertEquals("PLAN_REQUIRED_ITEM_MISSING", proposal().preconditionProblem(body(false)))
        fun planSchema(hasApples: Boolean) = JsonParser.parseString(DecisionSchema.forContext(contextId,
            GoalPolicies.forRecord(record()), planner = true, hasActiveTask = false, plannerInventory = body(hasApples)))
            .asJsonObject["properties"].asJsonObject["plan"].asJsonObject["anyOf"].asJsonArray[0]
            .asJsonObject["properties"].asJsonObject
        val emptySchema = planSchema(false)
        assertEquals(0, emptySchema["requiredItems"].asJsonObject["maxItems"].asInt)
        assertEquals(36, emptySchema["minimumEmptySlots"].asJsonObject["maximum"].asInt)
        val suppliedSchema = planSchema(true)
        assertEquals(35, suppliedSchema["minimumEmptySlots"].asJsonObject["maximum"].asInt)
        val allowedItem = suppliedSchema["requiredItems"].asJsonObject["items"].asJsonObject["oneOf"].asJsonArray.single()
            .asJsonObject["properties"].asJsonObject
        assertEquals("minecraft:apple", allowedItem["itemId"].asJsonObject["enum"].asJsonArray.single().asString)
        assertEquals(3, allowedItem["minimum"].asJsonObject["maximum"].asInt)
        assertEquals("PLAN_INVENTORY_SPACE_CHANGED", PlanProposal(listOf("step"), emptyList(), 36).preconditionProblem(body(true)))
        assertEquals("PLAN_REQUIRED_ITEM_MISSING", PlanProposal(listOf("step"),
            listOf(RequiredItem("minecraft:apple", 4)), 0).preconditionProblem(body(true)))
        val steps = mutableListOf("step"); val input = PlanProposal(steps, emptyList(), 0); steps.clear()
        assertEquals(listOf("step"), input.steps)
    }

    @Test fun onlyAppliedReceiptAndObservedCompletionAdvanceOneStepAndLastStepRequiresPlayerConfirmation() {
        val started = PlannerOutcomes.admitted(record(), decision(), applied())
        assertEquals(GoalPhase.EXECUTING, started.phase); assertEquals(0, started.planStepsCompleted)
        assertEquals(2, started.memory.plan.size); assertTrue(started.memory.results.isEmpty())
        val observed = started.copy(phase = GoalPhase.COMPLETED, code = "TASK_COMPLETED",
            memory = started.memory.withOutcome(checkNotNull(started.task), "TASK_COMPLETED"))
        val next = PlannerOutcomes.terminal(observed)
        assertEquals(1, next.planStepsCompleted); assertEquals(listOf("Return"), next.memory.plan)
        assertNull(next.task); assertTrue(PlannerOutcomes.boundary(next)); assertEquals(1, next.memory.results.size)
        val finalDecision = decision().copy(plan = PlanProposal(listOf("Return"), emptyList(), 0))
        val finalTask = PlannerOutcomes.admitted(next, finalDecision, applied())
        val last = PlannerOutcomes.terminal(finalTask.copy(phase = GoalPhase.COMPLETED, code = "TASK_COMPLETED",
            memory = finalTask.memory.withOutcome(checkNotNull(finalTask.task), "TASK_COMPLETED")))
        assertEquals(GoalPhase.ASK_USER, last.phase); assertEquals("PLAN_CONFIRMATION_REQUIRED", last.code)
        assertEquals(2, last.planStepsCompleted); assertTrue(last.memory.plan.isEmpty())
        assertEquals(2, last.memory.results.size); assertFalse(PlannerOutcomes.boundary(last))
        val continuing = PlannerOutcomes.admitted(record(),
            LlmDecision(contextId, DecisionKind.CONTINUE, DecisionAction.Continue, "all done", 2),
            DecisionOutcome(DecisionOutcomeState.NO_EFFECT, "CONTINUE"))
        assertEquals(GoalPhase.WAITING, continuing.phase); assertTrue(continuing.manualHold)
        assertTrue(continuing.memory.results.isEmpty())
    }

    @Test fun failureOrUncertainAdmissionCannotAdvanceResetBudgetsOrBlindlyReplayThePlan() {
        val original = record().copy(budget = InferenceBudgetView(2, 64000, 2048, 0, null))
        val rejected = PlannerOutcomes.admitted(original, decision(), DecisionOutcome.rejected("PLAN_REQUIRED_ITEM_MISSING"))
        assertEquals(GoalPhase.ASK_USER, rejected.phase); assertEquals(original.budget, rejected.budget)
        assertTrue(rejected.memory.plan.isEmpty()); assertEquals(0, rejected.planStepsCompleted)
        val uncertain = PlannerOutcomes.admitted(original, decision(), DecisionOutcome(DecisionOutcomeState.UNCERTAIN, "LOST_REPLY"))
        assertEquals(GoalPhase.REVIEW_REQUIRED, uncertain.phase); assertTrue(uncertain.manualHold)
        assertEquals(original.budget, uncertain.recovered().budget)
        val executing = PlannerOutcomes.admitted(original, decision(), applied())
        val failed = PlannerOutcomes.terminal(executing.copy(phase = GoalPhase.FAILED, code = "TASK_FAILED"))
        assertEquals(GoalPhase.ASK_USER, failed.phase); assertEquals(0, failed.planStepsCompleted)
        assertFalse(PlannerOutcomes.boundary(failed)); assertEquals(executing.memory.plan, failed.memory.plan)
        assertThrows(IllegalArgumentException::class.java) { executing.copy(planStepsCompleted = 7) }
    }

    @Test fun versionThreeMemoryMigratesAndVersionFourRetainsPlanProgressWithoutDuplicateCompletion() {
        val previous = record().copy(mode = LlmMode.TRANSLATOR, memory = GoalMemory(aliases =
            listOf(ContextPlaceAlias("home", "minecraft:overworld", NpcBlockPosition(0, 64, 0)))))
        val oldStore = LlmGoalStore.empty(); assertNull(oldStore.put(previous))
        val oldFile = oldStore.save(CompoundTag()); oldFile.putInt("version", 3)
        oldFile.getList("goals", 10).getCompound(0).remove("planSteps")
        oldFile.getList("goals", 10).getCompound(0).remove("quotaMode")
        oldFile.getList("goals", 10).getCompound(0).remove("intentMode")
        assertEquals(previous, LlmGoalStore.load(oldFile).get(previous.npcUuid))
        oldFile.getList("goals", 10).getCompound(0).putString("mode", "PLANNER")
        assertEquals("INVALID_GOAL_RECORD", LlmGoalStore.load(oldFile).problem)
        val task = PlannerOutcomes.admitted(record().copy(planStepsCompleted = 2), decision(), applied())
        val store = LlmGoalStore.empty(); assertNull(store.put(task))
        val saved = store.save(CompoundTag())
        val restored = checkNotNull(LlmGoalStore.load(saved).get(task.npcUuid))
        assertEquals(task, restored); assertEquals(task, LlmGoalStore.load(LlmGoalStore.load(saved).save(CompoundTag())).get(task.npcUuid))
        for (value in listOf(-1, 9)) {
            val bad = saved.copy(); bad.getList("goals", 10).getCompound(0).putInt("planSteps", value)
            assertEquals("INVALID_GOAL_RECORD", LlmGoalStore.load(bad).problem)
        }
    }
}
