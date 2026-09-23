package io.samcnpc.llm.intent

import io.samcnpc.behavior.api.*
import io.samcnpc.core.api.*
import io.samcnpc.llm.context.*
import io.samcnpc.llm.decision.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class GoalIntentPolicyTest {
    private val dimension = "minecraft:overworld"
    private val origin = NpcPosition(-4.5, 64.0, -2.5)
    private val source = NpcBlockPosition(-10, 64, -20)
    private val destination = NpcBlockPosition(-30, 64, -40)
    private val box = OperationWorkBox(NpcBlockPosition(-10, 60, -10), NpcBlockPosition(10, 80, 10))
    private val exclusion = OperationWorkBox(NpcBlockPosition(0, 60, 0), NpcBlockPosition(1, 80, 1))
    private val empty = NpcItemInspection(NpcItemStackSnapshot.EMPTY, NpcItemKnowledge.EMPTY, emptyList(), false, 0, NpcRangedResourceReadiness.NOT_RANGED)
    private fun body(count: Int = 12) = NpcBodyInspection(10, "Sam", 20F, 20F, 0F, 0, emptyList(), false,
        List(36) { if (it != 0 || count == 0) empty else NpcItemInspection(NpcItemStackSnapshot("minecraft:cobblestone", count, 64, 0, 0),
            NpcItemKnowledge("minecraft:cobblestone", setOf(NpcItemRole.OTHER)), emptyList(), false, 0, NpcRangedResourceReadiness.NOT_RANGED) },
        NpcInspectionSlot.entries.associateWith { empty }, 0)
    private fun contract(meaning: GoalQuantityMeaning = GoalQuantityMeaning.EXACT_ADDITIONAL,
                         acquire: Boolean = true, harvest: Boolean = false) = GoalConstraints(
        GoalConstraints.SUPPORTED, setOf("minecraft:cobblestone", "minecraft:stone", "samcnpc:oak"), dimension,
        meaning, 32, 12, box, listOf(exclusion), listOf(source), listOf(destination), listOf(origin), acquire, harvest, harvest)
    private fun goal(c: GoalConstraints, spent: GoalIntentReservation = GoalIntentReservation()) = ContextGoal(
        UUID.randomUUID(), 1, "Arbitrary untrusted text", LlmMode.PLANNER, null, 24, constraints = c, intentReservation = spent)
    private fun check(order: OperationOrder, c: GoalConstraints = contract(), count: Int = 12, spent: GoalIntentReservation = GoalIntentReservation()) =
        GoalIntentPolicy.check(DecisionAction.Assign(order), goal(c, spent), body(count), origin)
    private fun transport() = OperationOrder.Transport(dimension, OperationContainers(listOf(source)),
        OperationContainers(listOf(destination)), "minecraft:cobblestone", 32, origin)

    @Test fun validJsonDoesNotAuthorizeDifferentResourcesChestsDimensionOrQuantity() {
        assertNull(check(transport()).problem)
        assertEquals(GoalIntentReservation(32, 32), check(transport()).charge)
        assertEquals("INTENT_RESOURCE_NOT_ALLOWED", check(transport().copy(itemId = "minecraft:oak_log")).problem)
        assertEquals("INTENT_SOURCE_NOT_ALLOWED", check(transport().copy(sources = OperationContainers(listOf(destination)))).problem)
        assertEquals("INTENT_DESTINATION_NOT_ALLOWED", check(transport().copy(destinations = OperationContainers(listOf(source)))).problem)
        assertEquals("INTENT_DIMENSION_NOT_ALLOWED", check(transport().copy(dimensionId = "minecraft:the_nether")).problem)
        for (amount in listOf(1, 31, 33, 64)) assertEquals("INTENT_QUANTITY_MEANING", check(transport().copy(quantity = amount)).problem)
        assertEquals("INTENT_QUANTITY_ALREADY_RESERVED", check(transport(), spent = GoalIntentReservation(32, 32)).problem)
    }

    @Test fun carriedDeliveryCannotBecomeAcquisitionAndInsufficientCarriedStockIsExplicit() {
        val c = contract(acquire = false)
        val deliver = OperationOrder.Deliver(dimension, destination, "minecraft:cobblestone", 32, origin)
        assertEquals("INTENT_ACQUISITION_NOT_ALLOWED", check(transport(), c).problem)
        assertEquals("INTENT_CARRIED_STOCK_INSUFFICIENT", check(deliver, c, 31).problem)
        assertEquals(GoalIntentReservation(delivered = 32), check(deliver, c, 32).charge)
        assertEquals("INTENT_CARRIED_STOCK_INSUFFICIENT", check(deliver.copy(keepAtLeast = 1), c, 32).problem)
    }

    @Test fun twelveToThirtyTwoAndThirtyTwoMoreHaveDifferentTargetsAndPersistentAcquisitionGrants() {
        fun c(mode: GoalQuantityMeaning) = GoalConstraints(setOf(OperationType.INVENTORY), setOf("minecraft:cobblestone"),
            dimension, mode, 32, 12, null, emptyList(), listOf(source), emptyList(), listOf(origin), true, false, false)
        fun supply(target: Int, minimum: Int = target) = OperationInventoryOrder(dimension,
            OperationInventoryWork.Supply(listOf(OperationStockNeed("minecraft:cobblestone", minimum, target)), OperationContainers(listOf(source))), origin)
        val total = c(GoalQuantityMeaning.TARGET_INVENTORY)
        val more = c(GoalQuantityMeaning.EXACT_ADDITIONAL)
        assertEquals(20, check(supply(32), total).charge.acquired)
        assertEquals(32, check(supply(44), more).charge.acquired)
        for (activation in listOf(13, 32, 44)) {
            assertNull(check(supply(44, activation), more).problem)
            assertEquals(32, check(supply(44, activation), more).charge.acquired)
        }
        for (activation in listOf(0, 1, 12, 45))
            assertEquals("INTENT_QUANTITY_MEANING", check(supply(44, activation), more).problem)
        assertEquals("INTENT_QUANTITY_MEANING", check(supply(32), more).problem)
        assertEquals("INTENT_QUANTITY_MEANING", check(supply(44), total).problem)
        assertEquals("INTENT_QUANTITY_MEANING", check(supply(32, 1), total).problem)
        assertEquals("INTENT_INITIAL_STOCK_CHANGED", check(supply(32), total, count = 11).problem)
        assertEquals("INTENT_QUANTITY_ALREADY_RESERVED", check(supply(32), total, spent = GoalIntentReservation(acquired = 20)).problem)
    }

    @Test fun oakAreaExclusionsAndMinimumHarvestCannotBeWidenedOrMisrepresentedAsExactOutput() {
        val c = contract(GoalQuantityMeaning.MINIMUM_HARVEST, harvest = true)
        val order = OperationHarvestOrder.Lumberjack(dimension, OperationWorkArea(box, listOf(exclusion)),
            OperationWoodSelection(listOf("samcnpc:oak")), destination, 32)
        assertNull(check(order, c).problem)
        assertEquals("INTENT_RESOURCE_NOT_ALLOWED", check(order.copy(wood = OperationWoodSelection(listOf("samcnpc:oak", "samcnpc:spruce"))), c).problem)
        assertEquals("INTENT_AREA_NOT_ALLOWED", check(order.copy(area = OperationWorkArea(box)), c).problem)
        assertEquals("INTENT_AREA_NOT_ALLOWED", check(order.copy(area = OperationWorkArea(box.copy(max = box.max.copy(x = 11)), listOf(exclusion))), c).problem)
        assertEquals("INTENT_SIDE_WORK_NOT_SUPPORTED", check(order.copy(supplySources = OperationContainers(listOf(source))), c).problem)
        assertEquals("INTENT_QUANTITY_MEANING", check(order, contract(harvest = true)).problem)
        val outsideExclusion = OperationWorkArea(OperationWorkBox(box.min, NpcBlockPosition(-1, 80, -1)))
        assertTrue(GoalIntentPolicy.areaAllowed(outsideExclusion, c))
        val partial = OperationWorkArea(box, listOf(exclusion.copy(max = exclusion.max.copy(x = 0))))
        assertFalse(GoalIntentPolicy.areaAllowed(partial, c))
    }

    @Test fun amendmentsAndSidePoliciesFailClosedWhileTimeControlsAndQuestionsGrantNoNewQuantity() {
        val g = goal(contract())
        val changes = listOf(OperationChange.Quantity(33), OperationChange.Quantity(1, OperationQuantityMode.ADD),
            OperationChange.Recipients(OperationContainers(listOf(source))), OperationChange.Sources(OperationContainers(listOf(destination))),
            OperationChange.Replace(transport()), OperationChange.Replace(transport(), OperationObjectiveMode.NEW_OBJECTIVE),
            OperationChange.Reaction(OperationReactionPolicy(allowPlayers = true)),
            OperationChange.Logistics(OperationLogisticsPolicy(supply = OperationInventoryWork.Supply(listOf(OperationStockNeed("minecraft:stone", 32, 32)), OperationContainers(listOf(source))))))
        for (change in changes) assertEquals("INTENT_AMENDMENT_REQUIRES_NEW_GOAL",
            GoalIntentPolicy.check(DecisionAction.Amend(change), g, body(), origin).problem)
        for (action in listOf(DecisionAction.Amend(OperationChange.ExtendTime(20)), DecisionAction.Control(OperationControl.PAUSE),
            DecisionAction.Control(OperationControl.RESUME), DecisionAction.AskUser("Doprecyzuj cel"), DecisionAction.Continue)) {
            assertEquals(GoalIntentCheck(), GoalIntentPolicy.check(action, g, body(), origin))
        }
    }

    @Test fun miningChecksBothRemovedBlocksAndOutputIdsAndRejectsAccessExcavationAndDifferentCounters() {
        val c = contract(GoalQuantityMeaning.MINIMUM_HARVEST, harvest = true)
        val order = OperationHarvestOrder.Mining(dimension,
            OperationMiningWork(OperationWorkArea(box, listOf(exclusion)), OperationMiningMethod.EXPOSED,
                OperationResourceIds(listOf("minecraft:stone"))), OperationResourceIds(listOf("minecraft:cobblestone")),
            OperationContainers(listOf(destination)), 32, OperationMiningCounting.DELIVERED_ITEMS, origin)
        assertEquals(GoalIntentReservation(32, 32), check(order, c).charge)
        assertNull(check(order.copy(work = order.work.copy(method = OperationMiningMethod.VEIN)), c).problem)
        assertEquals("INTENT_RESOURCE_NOT_ALLOWED", check(order.copy(work = order.work.copy(resources = OperationResourceIds(listOf("minecraft:diamond_ore")))), c).problem)
        assertEquals("INTENT_RESOURCE_NOT_ALLOWED", check(order.copy(outputs = OperationResourceIds(listOf("minecraft:diamond"))), c).problem)
        assertEquals("INTENT_MINING_VARIANT_NOT_SUPPORTED", check(order.copy(work = order.work.copy(access = OperationResourceIds(listOf("minecraft:stone")))), c).problem)
        assertEquals("INTENT_MINING_VARIANT_NOT_SUPPORTED", check(order.copy(work = order.work.copy(method = OperationMiningMethod.EXCAVATION)), c).problem)
        assertEquals("INTENT_QUANTITY_MEANING", check(order.copy(counting = OperationMiningCounting.REMOVED_RESOURCE_BLOCKS), c).problem)
        assertEquals("INTENT_AREA_NOT_ALLOWED", check(order.copy(work = order.work.copy(area = OperationWorkArea(box))), c).problem)
        assertEquals("INTENT_DESTINATION_NOT_ALLOWED", check(order.copy(returnTo = origin.copy(x = 100.0)), c).problem)
    }
}
