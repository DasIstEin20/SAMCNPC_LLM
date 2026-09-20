package io.samcnpc.llm.context

import io.samcnpc.core.api.NpcStockRead
import io.samcnpc.llm.context.ContextJson.obj
import io.samcnpc.llm.context.ContextJson.text
import io.samcnpc.llm.context.ContextJson.number
import io.samcnpc.llm.context.ContextJson.position
import io.samcnpc.llm.context.ContextJson.array
import io.samcnpc.llm.supervision.StockSupervision

/** Worker-side projection of one authorized read, never a persisted stock count. */
internal object StockContext {
    fun encode(state: StockSupervision?, stock: NpcStockRead.Observed?) =
        if (state == null || stock == null) text(null) else obj(
            "dimension" to text(state.target.dimensionId), "position" to position(state.target.position),
            "item" to text(state.target.itemId), "low" to number(state.target.low),
            "target" to number(state.target.target), "count" to number(stock.count),
            "deficit" to number((state.target.target - stock.count).coerceAtLeast(0)),
            "observedTick" to number(stock.observedTick), "source" to text("VISIBLE_REACHABLE_CHEST"),
            "assignmentRule" to text("EXACT_DEFICIT_ITEM_AND_DESTINATION; no returnTo or replant"),
            "recentFailures" to array(state.failures.entries.map {
                obj("decisionFingerprint" to text(it.decision), "stateFingerprint" to text(it.world), "code" to text(it.reason))
            }),
            "consecutiveNonprogress" to number(state.failures.consecutive))
}
