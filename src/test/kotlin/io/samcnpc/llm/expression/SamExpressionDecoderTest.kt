package io.samcnpc.llm.expression

import com.google.gson.*
import io.samcnpc.behavior.api.*
import io.samcnpc.llm.context.ContextPolicy
import io.samcnpc.llm.decision.*
import io.samcnpc.llm.provider.LlmJson
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class SamExpressionDecoderTest {
    private val id = UUID(1, 2)
    private val navigate = "navigate(dimension_id=\"minecraft:overworld\",destination=pos(-4.5,64,-2.5))"
    private fun accepted(text: String, planner: Boolean = false): LlmDecision {
        val result = SamExpressionDecoder.decode(text, id, planner)
        assertTrue(result is DecisionDecodeResult.Accepted, "$text => $result")
        return (result as DecisionDecodeResult.Accepted).value
    }
    private fun rejected(text: String, planner: Boolean = false) {
        assertTrue(SamExpressionDecoder.decode(text, id, planner) is DecisionDecodeResult.Rejected, text.take(200))
    }

    @Test fun everyFrozenOperationFamilyMapsToExactlyTheSameDocumentAndTypedDefaults() {
        val json = checkNotNull(javaClass.getResourceAsStream("/evaluation/v1/corpus.json")).use { it.readBytes().toString(Charsets.UTF_8) }
        val original=LlmJson.parse(json,131072)["cases"].asJsonArray.map { it.asJsonObject }.filter { !it["expectedOperation"].isJsonNull }
        assertEquals(32,original.size)
        val extension=checkNotNull(javaClass.getResourceAsStream("/evaluation/v16/field-extension.json")).use { LlmJson.parse(it.readBytes().toString(Charsets.UTF_8),131072) }
        val cases=original+extension["cases"].asJsonArray.map { it.asJsonObject }.filter { !it["expectedOperation"].isJsonNull }
        val families = mutableSetOf<String>()
        for (case in cases) {
            val original = case["expectedOperation"].asJsonObject
            val expression = SamExpressionFixtures.order(original)
            val currentDocument = original.deepCopy()
            currentDocument.addProperty("definitionVersion", OperationType.entries.single { it.operationId == original["type"].asString }.definitionVersion)
            assertEquals(currentDocument, SamExpressionCatalog.order(SamExpressionSyntax.parse(expression)), case["id"].asString)
            val actual = (accepted("assign($expression)").action as DecisionAction.Assign).order
            val expected = OperationDocumentApi.decodeOrder(original.toString()) as OperationDocumentResult.Accepted
            assertEquals(Gson().toJsonTree(expected.value), Gson().toJsonTree(actual), case["id"].asString)
            assertEquals(id, accepted("assign($expression)").contextId)
            assertEquals("", accepted("assign($expression)").summary)
            families.add(actual.type.operationId)
        }
        assertEquals(34, cases.size)
        assertEquals(OperationType.entries.map { it.operationId }.toSet(), families)
    }

    @Test fun everyChangeKeepsTheExistingPublicDecoderAndReplacementSemantics() {
        val changes = listOf(
            "change_quantity(amount=32)" to """{"type":"QUANTITY","parameters":{"amount":32}}""",
            "change_recipients(containers=containers(positions=[chest(-3,64,4)]))" to """{"type":"RECIPIENTS","parameters":{"containers":{"positions":[{"x":-3,"y":64,"z":4}]}}}""",
            "change_sources(containers=None)" to """{"type":"SOURCES","parameters":{"containers":null}}""",
            "change_extend_time(ticks=40)" to """{"type":"EXTEND_TIME","parameters":{"ticks":40}}""",
            "change_tactics(tactics=tactics())" to """{"type":"TACTICS","parameters":{"tactics":{}}}""",
            "change_reaction(policy=reaction())" to """{"type":"REACTION","parameters":{"policy":{}}}""",
            "change_logistics(policy=logistics())" to """{"type":"LOGISTICS","parameters":{"policy":{}}}""",
        )
        for ((wire, json) in changes) {
            val original = LlmJson.parse(json, 8192).also { it.addProperty("documentVersion", 1) }
            val expected = OperationDocumentApi.decodeChange(original.toString())
            assertTrue(expected is OperationDocumentResult.Accepted, expected.toString())
            val actual = (accepted("amend($wire)").action as DecisionAction.Amend).change
            assertEquals(Gson().toJsonTree((expected as OperationDocumentResult.Accepted).value), Gson().toJsonTree(actual))
        }
        val replacement = (accepted("amend(change_replace(order=$navigate,objective=\"NEW_OBJECTIVE\"))").action as DecisionAction.Amend).change as OperationChange.Replace
        assertEquals(OperationObjectiveMode.NEW_OBJECTIVE, replacement.objective)
        assertEquals(Gson().toJsonTree((accepted("assign($navigate)").action as DecisionAction.Assign).order), Gson().toJsonTree(replacement.order))
        rejected("amend(change_quantity(amount=0))")
        rejected("amend(change_unknown())")
    }

    @Test fun plannerCannotDropPreconditionsAndOtherModesCannotSupplyAPlan() {
        val plan = "plan(steps=[\"Idź do celu\"],required_items=[record(item_id=\"minecraft:torch\",minimum=1)],minimum_empty_slots=2)"
        val value = accepted("assign($navigate,plan=$plan)", true)
        assertEquals(2, value.schemaVersion)
        assertEquals(2, value.plan?.minimumEmptySlots)
        assertEquals("minecraft:torch", value.plan?.requiredItems?.single()?.itemId)
        rejected("assign($navigate)", true)
        rejected("assign($navigate,plan=$plan)")
        rejected("assign($navigate,plan=plan(steps=[\"x\"]))", true)
        rejected("assign($navigate,plan=$plan)", false)
        assertEquals(2, accepted("ask_user('Brakuje miejsca?')", true).schemaVersion)
    }

    @Test fun controlsQuestionsWaitsAndCoordinatesUseExactClosedForms() {
        assertEquals(DecisionKind.CONTINUE, accepted("continue_task()").kind)
        for (name in listOf("pause", "resume", "cancel")) assertEquals(name.uppercase(), accepted("$name()").kind.name)
        assertEquals(DecisionAction.AskUser("Zażółć 'świat' 😀"), accepted("ask_user(\"Zażółć 'świat' \\ud83d\\ude00\")").action)
        assertEquals(DecisionAction.Wait(WaitTrigger.USER_UPDATE, null), accepted("wait(trigger='USER_UPDATE')").action)
        assertEquals(DecisionAction.Wait(WaitTrigger.DEADLINE, 40), accepted("wait(trigger='DEADLINE',ticks=40,)").action)
        for (wire in listOf("wait(trigger='DEADLINE')", "wait(trigger='TASK_TERMINAL',ticks=40)", "pause(1)", "ask_user('')",
            "ask_user('x',question='y')", "assign($navigate,operation=$navigate)", "ask_user(question='x',bad=1)")) rejected(wire)
        val tuple = accepted("assign(navigate(dimension_id='minecraft:overworld',destination=(-4.5,64,-2.5)))")
        assertEquals(Gson().toJsonTree(accepted("assign($navigate)").action), Gson().toJsonTree(tuple.action))
        rejected("assign(deliver(dimension_id='minecraft:overworld',destination=chest(1.5,64,3),item_id='minecraft:stone',quantity=1,anchor=(0,64,0)))")
        rejected("assign(navigate(dimension_id='minecraft:overworld',destination=chest(1,64,3)))")
        rejected("assign(navigate(dimensionId='minecraft:overworld',destination=pos(0,64,0)))")
    }

    @Test fun discriminatedWorkAndBoxTuplesKeepSemanticValidation() {
        val collect = "inventory_work(dimension_id='minecraft:overworld',anchor=(0,64,0),work=collect(source=chest(-2,64,0),max_items=144))"
        val order = (accepted("assign($collect)").action as DecisionAction.Assign).order as OperationInventoryOrder
        assertEquals(OperationInventoryWork.Collect(io.samcnpc.core.api.NpcBlockPosition(-2, 64, 0), 144), order.work)
        rejected("assign(" + collect.replace("max_items=144", "max_items=2305") + ")")
        rejected("assign(" + collect.replace("max_items=144", "max_items=144,item_id='minecraft:dirt'") + ")")
        val operation = "inventory_work(dimension_id='minecraft:overworld',anchor=(0,64,0),work=supply(needs=[need(item_id='minecraft:cobblestone',minimum=32,target=44)],sources=containers(positions=[chest(-2,64,0)])))"
        assertEquals(OperationType.INVENTORY, (accepted("assign($operation)").action as DecisionAction.Assign).order.type)
        rejected("assign(" + operation.replace("minimum=32,target=44", "minimum=45,target=44") + ")")
        rejected("assign(" + operation.replace("supply(needs", "supply(kind='UNLOAD',needs") + ")")
        val wood = "lumberjack(dimension_id='minecraft:overworld',wood=['samcnpc:oak'],quantity=16,area=area(bounds=box(min=(-8,64,-5),max=(-2,75,-1))),destination=chest(-10,64,-7))"
        assertEquals(OperationType.LUMBERJACK, (accepted("assign($wood)").action as DecisionAction.Assign).order.type)
        rejected("assign(" + wood.replace("max=(-2,75,-1)", "max=(-9,75,-1)") + ")")
    }

    @Test fun visibleContractIsRestrictedToTheSamePolicyAndNoWorldDefaultsAreInvented() {
        val policy = ContextPolicy(1, setOf(OperationType.NAVIGATE), setOf("REPLACE"), emptySet(), 12000, 3, 100)
        val contract = SamExpressionContract.describe(LlmJson.parse(DecisionSchema.forContext(id, policy, hasActiveTask = true), 65536))
        assertFalse(contract.contains("assign(Operation)"))
        assertTrue(contract.contains("change_replace("))
        assertTrue(contract.contains("navigate(dimension_id:"))
        assertTrue(contract.contains("-29999984..29999984"))
        assertFalse(contract.contains("2.9999984E7"))
        assertFalse(contract.contains("lumberjack("))
        assertFalse(contract.contains("cancel()"))
        rejected("assign(navigate(destination=pos(0,64,0)))")
        val order = (accepted("assign($navigate)").action as DecisionAction.Assign).order
        assertEquals("OPERATION_NOT_ALLOWED", DecisionPolicy.orderProblem(order,
            ContextPolicy(1, emptySet(), emptySet(), emptySet(), 12000, 3, 0), "minecraft:overworld"))
    }
}
