package io.samcnpc.llm.supervision

import io.samcnpc.core.api.NpcBlockPosition
import io.samcnpc.core.api.NpcStockQuery
import net.minecraft.resources.ResourceLocation

/** Supplied user policy, not a claim about current contents at this location. */
internal data class StockTarget(val dimensionId: String, val position: NpcBlockPosition,
                                val itemId: String, val low: Int, val target: Int) {
    val query = NpcStockQuery(position, itemId)
    init {
        require(dimensionId.length in 1..256 && ResourceLocation.tryParse(dimensionId)?.toString() == dimensionId)
        require(target in 1..2304 && low in 1..target)
    }
    fun needsRefill(count: Int, armed: Boolean): Boolean {
        require(count >= 0)
        return count < if (armed) low else target
    }
    fun sameStorage(other: StockTarget): Boolean =
        dimensionId == other.dimensionId && position.y == other.position.y && itemId == other.itemId &&
            kotlin.math.abs(position.x.toLong() - other.position.x) + kotlin.math.abs(position.z.toLong() - other.position.z) <= 1
}

/** beforeCount is historical attempt accounting; it must never be used as a fresh sensor value. */
internal data class StockSupervision(
    val target: StockTarget,
    val armed: Boolean = false,
    val pendingDecision: String? = null,
    val pendingWorld: String? = null,
    val beforeCount: Int? = null,
    val failures: FailedDecisions = FailedDecisions(),
    val waitUntilTick: Long? = null,
    val progressEpoch: Long = 0,
) {
    init {
        require((pendingDecision == null) == (pendingWorld == null))
        require(pendingDecision == null || FailedDecisions.validHash(pendingDecision))
        require(pendingWorld == null || FailedDecisions.validHash(pendingWorld))
        require(beforeCount == null || beforeCount >= 0 && pendingDecision != null)
        require(waitUntilTick == null || waitUntilTick >= 0)
        require(progressEpoch >= 0)
    }

    fun userIntervention(): StockSupervision = clearAttempt().copy(failures = failures.progressed(),
        progressEpoch = if (progressEpoch < Long.MAX_VALUE) progressEpoch + 1 else progressEpoch, waitUntilTick = null)

    fun clearAttempt(): StockSupervision = copy(pendingDecision = null, pendingWorld = null, beforeCount = null)
}
