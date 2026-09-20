package io.samcnpc.llm.supervision

import io.samcnpc.behavior.api.*
import io.samcnpc.core.api.NpcStockRead
import io.samcnpc.llm.decision.DecisionAction
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/** Hash typed semantic values, excluding tick, context/task UUIDs and expendable budgets. */
internal object StockFingerprints {
    fun decision(action: DecisionAction): String {
        val values: List<Any?> = when (action) {
            is DecisionAction.Assign -> when (val order = action.order) {
                is OperationOrder.Deliver -> listOf(order.type, order.dimensionId, order.destination, order.itemId,
                    order.quantity, order.anchor, order.keepAtLeast)
                is OperationOrder.Transport -> listOf(order.type, order.dimensionId, order.sources.positions,
                    order.sources.preference, order.destinations.positions, order.destinations.preference,
                    order.itemId, order.quantity, order.anchor, order.travelRadius, order.keepAtLeast,
                    order.sourceKeepAtLeast, order.returnTo)
                is OperationHarvestOrder.Lumberjack -> listOf(order.type, order.dimensionId, order.area.bounds,
                    order.area.exclusions, order.wood.selectors, order.destination, order.quantity,
                    order.tools, order.supplySources?.positions, order.supplySources?.preference)
                else -> error("Unsupported stock operation")
            }
            is DecisionAction.Wait -> listOf("WAIT", action.trigger) // Delay changes cannot disguise repeated waiting.
            is DecisionAction.AskUser -> listOf("ASK_USER")
            DecisionAction.Continue -> listOf("CONTINUE")
            else -> error("Stock policy excludes task controls and amendments")
        }
        return hash(values)
    }

    fun world(state: StockSupervision, stock: NpcStockRead.Observed, inventory: List<Pair<String, Int>>): String {
        val counts = inventory.groupBy({ it.first }, { it.second }).mapValues { (_, values) -> values.sumOf { it.toLong() } }.toSortedMap()
        return hash(listOf(state.target.dimensionId, state.target.position, state.target.itemId,
            state.progressEpoch, stock.count, stock.slots, counts))
    }

    private fun hash(values: List<Any?>): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            for (value in values) {
                // Only the explicit scalar/value collections above enter this length-prefixed encoding.
                val text = (value?.toString() ?: "<absent>").toByteArray(Charsets.UTF_8)
                output.writeInt(text.size); output.write(text)
            }
        }
        return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
