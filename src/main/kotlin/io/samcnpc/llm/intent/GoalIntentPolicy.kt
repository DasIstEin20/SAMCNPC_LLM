package io.samcnpc.llm.intent

import io.samcnpc.behavior.api.*
import io.samcnpc.core.api.NpcBodyInspection
import io.samcnpc.core.api.NpcPosition
import io.samcnpc.llm.context.ContextGoal
import io.samcnpc.llm.decision.DecisionAction

internal data class GoalIntentCheck(val problem: String? = null, val charge: GoalIntentReservation = GoalIntentReservation())

/** A closed subset of typed Behavior intent. Prose, summaries and future plan steps grant nothing. */
internal object GoalIntentPolicy {
    fun check(action: DecisionAction, goal: ContextGoal, body: NpcBodyInspection, position: NpcPosition): GoalIntentCheck {
        val constraints = goal.constraints ?: return GoalIntentCheck()
        val checked = when (action) {
            is DecisionAction.Assign -> order(action.order, constraints, body, position)
            // Public task snapshots do not expose every side policy. Do not reconstruct missing permissions.
            is DecisionAction.Amend -> if (action.change is OperationChange.ExtendTime) GoalIntentCheck()
                else rejected("INTENT_AMENDMENT_REQUIRES_NEW_GOAL")
            else -> GoalIntentCheck()
        }
        if (checked.problem != null) return checked
        return if (goal.intentReservation.reserve(checked.charge, constraints) == null)
            rejected("INTENT_QUANTITY_ALREADY_RESERVED") else checked
    }

    fun stock(body: NpcBodyInspection, resources: Set<String>): Int =
        body.inventory.filter { it.stack.itemId != null && it.stack.itemId in resources }.sumOf { it.stack.count }

    private fun order(order: OperationOrder, c: GoalConstraints, body: NpcBodyInspection, position: NpcPosition): GoalIntentCheck {
        if (order.type !in c.operations) return rejected("INTENT_OPERATION_NOT_ALLOWED")
        if (order.dimensionId != c.dimensionId) return rejected("INTENT_DIMENSION_NOT_ALLOWED")
        if(c.requiredReturnTo != null && returnTarget(order) != c.requiredReturnTo)
            return rejected("INTENT_REQUIRED_RETURN_NOT_SATISFIED")
        return when (order) {
            is OperationOrder.Navigate -> if (order.destination in c.navigation) GoalIntentCheck()
                else rejected("INTENT_DESTINATION_NOT_ALLOWED")
            is OperationOrder.Deliver -> when {
                order.itemId !in c.resourceIds -> rejected("INTENT_RESOURCE_NOT_ALLOWED")
                order.destination !in c.destinations -> rejected("INTENT_DESTINATION_NOT_ALLOWED")
                !exact(order.quantity, c) -> rejected("INTENT_QUANTITY_MEANING")
                stock(body, setOf(order.itemId)) - order.keepAtLeast < order.quantity -> rejected("INTENT_CARRIED_STOCK_INSUFFICIENT")
                else -> GoalIntentCheck(charge = GoalIntentReservation(delivered = order.quantity))
            }
            is OperationOrder.Transport -> when {
                !c.allowAcquisition -> rejected("INTENT_ACQUISITION_NOT_ALLOWED")
                order.itemId !in c.resourceIds -> rejected("INTENT_RESOURCE_NOT_ALLOWED")
                !c.sources.containsAll(order.sources.positions) -> rejected("INTENT_SOURCE_NOT_ALLOWED")
                !c.destinations.containsAll(order.destinations.positions) || !returnAllowed(order.returnTo, c, position) -> rejected("INTENT_DESTINATION_NOT_ALLOWED")
                !exact(order.quantity, c) -> rejected("INTENT_QUANTITY_MEANING")
                else -> GoalIntentCheck(charge = GoalIntentReservation(order.quantity, order.quantity))
            }
            is OperationInventoryOrder -> supply(order, c, body, position)
            is OperationPrepareFieldOrder -> when {
                !c.allowAuxiliaryBlockWork || !c.allowDestruction -> rejected("INTENT_SOIL_CHANGE_NOT_ALLOWED")
                c.quantityMeaning != GoalQuantityMeaning.NONE -> rejected("INTENT_QUANTITY_MEANING")
                !areaAllowed(order.area,c) -> rejected("INTENT_AREA_NOT_ALLOWED")
                !returnAllowed(order.returnTo,c,position) -> rejected("INTENT_DESTINATION_NOT_ALLOWED")
                else -> GoalIntentCheck()
            }
            is OperationHarvestOrder.Lumberjack -> when {
                !c.allowAcquisition || !c.allowDestruction -> rejected("INTENT_HARVEST_NOT_ALLOWED")
                !c.allowAuxiliaryBlockWork -> rejected("INTENT_AUXILIARY_WORK_REQUIRED")
                order.supplySources != null || order.replant != null -> rejected("INTENT_SIDE_WORK_NOT_SUPPORTED")
                !c.resourceIds.containsAll(order.wood.selectors) -> rejected("INTENT_RESOURCE_NOT_ALLOWED")
                !areaAllowed(order.area, c) -> rejected("INTENT_AREA_NOT_ALLOWED")
                order.destination !in c.destinations -> rejected("INTENT_DESTINATION_NOT_ALLOWED")
                !minimum(order.quantity, c) -> rejected("INTENT_QUANTITY_MEANING")
                else -> GoalIntentCheck(charge = GoalIntentReservation(order.quantity, order.quantity))
            }
            is OperationHarvestOrder.Mining -> when {
                !c.allowAcquisition || !c.allowDestruction -> rejected("INTENT_HARVEST_NOT_ALLOWED")
                order.work.method !in setOf(OperationMiningMethod.EXPOSED, OperationMiningMethod.VEIN) ||
                    order.work.access != null || order.work.tunnel != null -> rejected("INTENT_MINING_VARIANT_NOT_SUPPORTED")
                !c.resourceIds.containsAll(order.work.resources.values) || !c.resourceIds.containsAll(order.outputs.values) -> rejected("INTENT_RESOURCE_NOT_ALLOWED")
                !areaAllowed(order.work.area, c) -> rejected("INTENT_AREA_NOT_ALLOWED")
                !c.destinations.containsAll(order.destinations.positions) || !returnAllowed(order.returnTo, c, position) -> rejected("INTENT_DESTINATION_NOT_ALLOWED")
                order.counting != OperationMiningCounting.DELIVERED_ITEMS || !minimum(order.quantity, c) -> rejected("INTENT_QUANTITY_MEANING")
                else -> GoalIntentCheck(charge = GoalIntentReservation(order.quantity, order.quantity))
            }
            else -> rejected("INTENT_OPERATION_NOT_SUPPORTED")
        }
    }

    private fun supply(order: OperationInventoryOrder, c: GoalConstraints, body: NpcBodyInspection, position: NpcPosition): GoalIntentCheck {
        val work = order.work as? OperationInventoryWork.Supply ?: return rejected("INTENT_INVENTORY_VARIANT_NOT_SUPPORTED")
        if (!c.allowAcquisition) return rejected("INTENT_ACQUISITION_NOT_ALLOWED")
        if (work.needs.size != 1 || c.resourceIds.size != 1 || work.needs.single().itemId !in c.resourceIds)
            return rejected("INTENT_RESOURCE_NOT_ALLOWED")
        if (!c.sources.containsAll(work.sources.positions)) return rejected("INTENT_SOURCE_NOT_ALLOWED")
        if (!returnAllowed(order.returnTo, c, position)) return rejected("INTENT_DESTINATION_NOT_ALLOWED")
        val target = when (c.quantityMeaning) {
            GoalQuantityMeaning.TARGET_INVENTORY -> c.quantity
            GoalQuantityMeaning.EXACT_ADDITIONAL -> c.initialStock + c.quantity
            else -> return rejected("INTENT_QUANTITY_MEANING")
        }
        val need = work.needs.single()
        // Behavior starts SUPPLY below minimum, then fills to target. Equivalent activating
        // thresholds authorize the same acquisition; a threshold at/below initial stock does not.
        if (need.target != target || need.minimum !in 0..target ||
            (c.initialStock < target && need.minimum <= c.initialStock)) return rejected("INTENT_QUANTITY_MEANING")
        // A stale initial-stock interpretation cannot turn '32 more' into a different acquisition.
        if (stock(body, c.resourceIds) != c.initialStock) return rejected("INTENT_INITIAL_STOCK_CHANGED")
        return GoalIntentCheck(charge = GoalIntentReservation(acquired = (target - c.initialStock).coerceAtLeast(0)))
    }

    private fun exact(quantity: Int, c: GoalConstraints) = c.quantityMeaning == GoalQuantityMeaning.EXACT_ADDITIONAL && quantity == c.quantity
    private fun returnTarget(order: OperationOrder): NpcPosition? = when(order) {
        is OperationOrder.Navigate -> order.destination
        is OperationOrder.Transport -> order.returnTo
        is OperationInventoryOrder -> order.returnTo
        is OperationHarvestOrder.Mining -> order.returnTo
        is OperationPrepareFieldOrder -> order.returnTo
        else -> null
    }
    private fun minimum(quantity: Int, c: GoalConstraints) = c.quantityMeaning == GoalQuantityMeaning.MINIMUM_HARVEST && quantity == c.quantity
    private fun returnAllowed(point: NpcPosition?, c: GoalConstraints, current: NpcPosition) = point == null || point == current || point in c.navigation
    internal fun areaAllowed(area: OperationWorkArea, c: GoalConstraints): Boolean {
        val permitted = c.workBox ?: return false
        if (!GoalConstraints.contains(permitted, area.bounds)) return false
        return c.exclusions.all { exclusion ->
            val min = io.samcnpc.core.api.NpcBlockPosition(maxOf(exclusion.min.x, area.bounds.min.x), maxOf(exclusion.min.y, area.bounds.min.y), maxOf(exclusion.min.z, area.bounds.min.z))
            val max = io.samcnpc.core.api.NpcBlockPosition(minOf(exclusion.max.x, area.bounds.max.x), minOf(exclusion.max.y, area.bounds.max.y), minOf(exclusion.max.z, area.bounds.max.z))
            val overlap = OperationWorkBox(min, max)
            !GoalConstraints.validBox(overlap) || area.exclusions.any { GoalConstraints.contains(it, overlap) }
        }
    }
    private fun rejected(code: String) = GoalIntentCheck(problem = code)
}
