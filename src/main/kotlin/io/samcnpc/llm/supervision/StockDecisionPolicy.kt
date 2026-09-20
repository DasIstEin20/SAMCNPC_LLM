package io.samcnpc.llm.supervision

import io.samcnpc.behavior.api.*
import io.samcnpc.core.api.NpcStockRead
import io.samcnpc.llm.decision.DecisionAction

/** A model can select logistics, but cannot widen the user's stock contract. */
internal object StockDecisionPolicy {
    val operations = setOf(OperationType.TRANSPORT, OperationType.DELIVER, OperationType.LUMBERJACK)

    fun problem(action: DecisionAction, target: StockTarget, stock: NpcStockRead.Observed): String? {
        if (stock.position != target.position || stock.itemId != target.itemId) return "STOCK_IDENTITY_CHANGED"
        if (action !is DecisionAction.Assign) return null
        val order = action.order
        if (order.dimensionId != target.dimensionId) return "STOCK_DIMENSION_MISMATCH"
        val deficit = target.target - stock.count
        if (deficit <= 0) return "STOCK_ALREADY_SUFFICIENT"
        return when (order) {
            is OperationOrder.Deliver -> when {
                order.destination != target.position || order.itemId != target.itemId -> "STOCK_TARGET_MISMATCH"
                order.quantity != deficit -> "STOCK_QUANTITY_MISMATCH"
                else -> null
            }
            is OperationOrder.Transport -> when {
                order.destinations.positions != listOf(target.position) || order.itemId != target.itemId -> "STOCK_TARGET_MISMATCH"
                target.position in order.sources.positions -> "STOCK_SOURCE_IS_DESTINATION"
                order.quantity != deficit -> "STOCK_QUANTITY_MISMATCH"
                order.returnTo != null -> "STOCK_RETURN_NOT_ALLOWED"
                else -> null
            }
            is OperationHarvestOrder.Lumberjack -> when {
                order.destination != target.position || order.wood.selectors != listOf(target.itemId) -> "STOCK_TARGET_MISMATCH"
                order.quantity != deficit -> "STOCK_QUANTITY_MISMATCH"
                order.replant != null -> "STOCK_REPLANT_NOT_ALLOWED"
                else -> null
            }
            else -> "STOCK_OPERATION_NOT_ALLOWED"
        }
    }
}
