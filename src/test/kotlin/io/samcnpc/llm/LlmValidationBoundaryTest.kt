package io.samcnpc.llm

import io.samcnpc.behavior.api.BehaviorPackValidationApi
import io.samcnpc.behavior.api.BehaviorCatalogApi
import io.samcnpc.behavior.api.BehaviorParameter
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LlmValidationBoundaryTest {
    @Test fun harvestingUsesOnlyPublishedSpatialResourceAndQuotaTypes() {
        val at = io.samcnpc.core.api.NpcPosition(0.5, 64.0, 0.5)
        val cell = io.samcnpc.core.api.NpcBlockPosition(8, 64, 0)
        val area = io.samcnpc.behavior.api.OperationWorkArea(io.samcnpc.behavior.api.OperationWorkBox(cell, cell))
        val order = io.samcnpc.behavior.api.OperationHarvestOrder.Mining("minecraft:overworld",
            io.samcnpc.behavior.api.OperationMiningWork(area, io.samcnpc.behavior.api.OperationMiningMethod.VEIN,
                io.samcnpc.behavior.api.OperationResourceIds(listOf("minecraft:iron_ore"))),
            io.samcnpc.behavior.api.OperationResourceIds(listOf("minecraft:raw_iron")),
            io.samcnpc.behavior.api.OperationContainers(listOf(io.samcnpc.core.api.NpcBlockPosition(4, 64, 0))),
            75, io.samcnpc.behavior.api.OperationMiningCounting.DELIVERED_ITEMS, at)
        assertTrue(io.samcnpc.behavior.api.OperationSupervisionApi.validateOrder(order).status ==
            io.samcnpc.core.api.NpcActionStatus.SUCCEEDED)
        assertTrue(order.quantity == 75 && order.type.operationId == "samcnpc:mine")
    }

    @Test fun combatAndInventoryOrdersNeedOnlyPublishedImmutableTypes() {
        val anchor = io.samcnpc.core.api.NpcPosition(0.5, 64.0, 0.5)
        val attack = io.samcnpc.behavior.api.OperationCombatOrder.Attack("minecraft:overworld", java.util.UUID(2, 3), anchor)
        val pickup = io.samcnpc.behavior.api.OperationInventoryOrder("minecraft:overworld",
            io.samcnpc.behavior.api.OperationInventoryWork.Pickup(listOf("minecraft:raw_iron"), maxItems = 75), anchor)
        for (order in listOf(attack, pickup)) {
            assertTrue(io.samcnpc.behavior.api.OperationSupervisionApi.validateOrder(order).status ==
                io.samcnpc.core.api.NpcActionStatus.SUCCEEDED)
        }
        assertTrue(attack.type.definitionVersion == 2 && pickup.type.operationId == "samcnpc:inventory_work")
    }

    @Test fun typedOrdersValidateThroughThePublicBoundaryWithoutAWorld() {
        val order = io.samcnpc.behavior.api.OperationOrder.Navigate("minecraft:overworld",
            io.samcnpc.core.api.NpcPosition(4.0, 64.0, 2.0))
        val result = io.samcnpc.behavior.api.OperationSupervisionApi.validateOrder(order)
        assertTrue(result.status == io.samcnpc.core.api.NpcActionStatus.SUCCEEDED)
        assertTrue(order.type.operationId == "samcnpc:navigate" && order.type.definitionVersion == 1)
        val rejected = io.samcnpc.behavior.api.OperationSupervisionApi.validateOrder(order.copy(speed = 9.0F))
        assertTrue(rejected.status == io.samcnpc.core.api.NpcActionStatus.REJECTED)
    }

    @Test fun amendmentInputsUseOnlyPublicBoundedValueTypes() {
        val positions = mutableListOf(io.samcnpc.core.api.NpcBlockPosition(1, 64, 2))
        val containers = io.samcnpc.behavior.api.OperationContainers(positions)
        positions.clear()
        assertTrue(containers.positions.size == 1)
        val input = io.samcnpc.behavior.api.OperationAmendmentRequest(java.util.UUID.randomUUID(),
            java.util.UUID.randomUUID(), 0, 100, 200,
            io.samcnpc.behavior.api.OperationChange.Recipients(containers))
        assertTrue(input.change is io.samcnpc.behavior.api.OperationChange.Recipients)
        val policies = listOf(
            io.samcnpc.behavior.api.OperationChange.Replace(io.samcnpc.behavior.api.OperationOrder.Navigate("minecraft:overworld", io.samcnpc.core.api.NpcPosition(0.0,64.0,0.0))),
            io.samcnpc.behavior.api.OperationChange.Tactics(io.samcnpc.behavior.api.OperationCombatTactics()),
            io.samcnpc.behavior.api.OperationChange.Reaction(io.samcnpc.behavior.api.OperationReactionPolicy()),
            io.samcnpc.behavior.api.OperationChange.Logistics(io.samcnpc.behavior.api.OperationLogisticsPolicy()))
        assertTrue(policies.all { input.copy(change = it).taskId == input.taskId })
        val schema = io.samcnpc.behavior.api.BehaviorSchemaApi.registeredSchema()
        assertTrue(schema.contains("samcnpc:move_to_summoner") && schema.contains("startDistance"))
    }

    @Test
    fun componentCatalogUsesOnlyPublishedValueTypes() {
        val catalog = BehaviorCatalogApi.snapshot()
        assertTrue(catalog.conditions.size == 25 && catalog.actions.size == 25)
        val move = catalog.actions.single { it.id == "samcnpc:move_to_target" }
        val speed = move.parameters.single { it.name == "speed" } as BehaviorParameter.Numeric
        assertTrue(speed.required && speed.minimum == 0.1 && speed.maximum == 1.5)
        assertTrue(move.channels == listOf("look", "movement"))
    }


    @Test
    fun candidateDocumentsUseThePublishedBehaviorAllowList() {
        val candidate = """{"schemaVersion":1,"id":"samcnpc:candidate","description":"boundary test",
            "priority":0,"channels":["movement"],"rules":[{"id":"idle","priority":0,
            "when":{"test":{"condition":"samcnpc:always"}},"actions":[{"action":"samcnpc:stop_movement"}]}]}"""
        assertTrue(BehaviorPackValidationApi.validateCandidate(candidate).accepted)
        val unsafe = candidate.replace("samcnpc:stop_movement", "samcnpc:execute_command")
        val rejected = BehaviorPackValidationApi.validateCandidate(unsafe, "llm-boundary-test")
        assertFalse(rejected.accepted)
        assertTrue(rejected.messages.any { it.contains("unknown action") && it.contains("llm-boundary-test") })
    }
}
