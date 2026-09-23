package io.samcnpc.llm.intent

import io.samcnpc.behavior.api.OperationType
import io.samcnpc.behavior.api.OperationWorkBox
import io.samcnpc.core.api.NpcBlockPosition
import io.samcnpc.core.api.NpcPosition
import net.minecraft.resources.ResourceLocation

internal enum class GoalQuantityMeaning { NONE, EXACT_ADDITIONAL, TARGET_INVENTORY, MINIMUM_HARVEST }

/** Frozen trusted input. Null area and empty allow-lists authorize no corresponding effect. */
internal class GoalConstraints(
    operations: Set<OperationType>, resourceIds: Set<String>, val dimensionId: String,
    val quantityMeaning: GoalQuantityMeaning, val quantity: Int, val initialStock: Int,
    val workBox: OperationWorkBox?, exclusions: List<OperationWorkBox>,
    sources: List<NpcBlockPosition>, destinations: List<NpcBlockPosition>, navigation: List<NpcPosition>,
    val allowAcquisition: Boolean, val allowDestruction: Boolean, val allowAuxiliaryBlockWork: Boolean,
    val requiredReturnTo: NpcPosition? = null,
    val version: Int = 2,
) {
    val operations: Set<OperationType> = java.util.Set.copyOf(operations)
    val resourceIds: Set<String> = java.util.Set.copyOf(resourceIds)
    val exclusions: List<OperationWorkBox> = java.util.List.copyOf(exclusions)
    val sources: List<NpcBlockPosition> = java.util.List.copyOf(sources)
    val destinations: List<NpcBlockPosition> = java.util.List.copyOf(destinations)
    val navigation: List<NpcPosition> = java.util.List.copyOf(navigation)
    val allowPlayers: Boolean get() = false
    val acquisitionLimit: Int get() = when {
        !allowAcquisition -> 0
        quantityMeaning == GoalQuantityMeaning.TARGET_INVENTORY -> (quantity - initialStock).coerceAtLeast(0)
        else -> quantity
    }
    val deliveryLimit: Int get() = if (quantityMeaning == GoalQuantityMeaning.TARGET_INVENTORY) 0 else quantity

    fun withInitialStock(value: Int) = GoalConstraints(operations, resourceIds, dimensionId, quantityMeaning,
        quantity, value, workBox, exclusions, sources, destinations, navigation,
        allowAcquisition, allowDestruction, allowAuxiliaryBlockWork, requiredReturnTo, version)

    init {
        require(version in 1..2)
        require(version != 1 || requiredReturnTo == null && OperationType.FIELD_PREPARATION !in operations)
        require(operations.isNotEmpty() && operations.all { it in SUPPORTED })
        require(resourceIds.size <= 16 && resourceIds.all(::validId) && validId(dimensionId))
        require(quantity in 0..65536 && initialStock in 0..65536)
        require((quantityMeaning == GoalQuantityMeaning.NONE) == (quantity == 0))
        require(quantityMeaning == GoalQuantityMeaning.NONE || resourceIds.isNotEmpty())
        require(quantityMeaning != GoalQuantityMeaning.TARGET_INVENTORY || resourceIds.size == 1)
        require(exclusions.size <= 8 && sources.size <= 8 && destinations.size <= 8 && navigation.size <= 16)
        require(sources.distinct().size == sources.size && destinations.distinct().size == destinations.size && navigation.distinct().size == navigation.size)
        require(sources.all(::validPosition) && destinations.all(::validPosition))
        require(navigation.all { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() &&
            it.x in -29999984.0..29999984.0 && it.z in -29999984.0..29999984.0 && it.y in -2048.0..2048.0 })
        require(requiredReturnTo == null || requiredReturnTo in navigation) { "required return must be an explicitly permitted navigation point" }
        require(workBox == null || validBox(workBox))
        require(exclusions.all { validBox(it) && workBox != null && contains(workBox, it) })
        require(workBox != null || exclusions.isEmpty())
        require(!allowDestruction || workBox != null)
        require(!allowAuxiliaryBlockWork || allowDestruction)
    }

    // Validate the exact saved representation once, never during goal polling or record copies.
    val persistenceJson: String = GoalConstraintCodec.encode(this).toString().also {
        require(io.samcnpc.llm.provider.LlmJson.utf8(it).size <= GoalConstraintCodec.MAX_BYTES)
    }

    /** Structural identity is independent of collection insertion order and model-supplied text. */
    private fun identity(): List<Any?> = listOf(operations, resourceIds, dimensionId, quantityMeaning, quantity,
        initialStock, workBox, exclusions, sources, destinations, navigation, allowAcquisition,
        allowDestruction, allowAuxiliaryBlockWork, requiredReturnTo, version)
    override fun equals(other: Any?): Boolean = other is GoalConstraints && identity() == other.identity()
    override fun hashCode(): Int = identity().hashCode()

    companion object {
        val SUPPORTED = setOf(OperationType.NAVIGATE, OperationType.DELIVER, OperationType.TRANSPORT,
            OperationType.INVENTORY, OperationType.MINING, OperationType.LUMBERJACK, OperationType.FIELD_PREPARATION)
        fun validId(value: String): Boolean = value.length in 1..256 && ResourceLocation.tryParse(value)?.toString() == value
        fun validPosition(p: NpcBlockPosition): Boolean = p.x in -29999984..29999984 &&
            p.z in -29999984..29999984 && p.y in -2048..2048
        fun validBox(b: OperationWorkBox): Boolean = validPosition(b.min) && validPosition(b.max) &&
            b.min.x <= b.max.x && b.min.y <= b.max.y && b.min.z <= b.max.z
        fun contains(outer: OperationWorkBox, inner: OperationWorkBox): Boolean =
            inner.min.x >= outer.min.x && inner.min.y >= outer.min.y && inner.min.z >= outer.min.z &&
                inner.max.x <= outer.max.x && inner.max.y <= outer.max.y && inner.max.z <= outer.max.z
    }
}

/** Reserved intent, not an assertion of gameplay success. Unknown external effects are never refunded. */
internal data class GoalIntentReservation(val acquired: Int = 0, val delivered: Int = 0) {
    init { require(acquired in 0..65536 && delivered in 0..65536) }
    fun fits(constraints: GoalConstraints): Boolean = acquired <= constraints.acquisitionLimit && delivered <= constraints.deliveryLimit
    fun reserve(charge: GoalIntentReservation, constraints: GoalConstraints): GoalIntentReservation? {
        if (charge.acquired > constraints.acquisitionLimit - acquired || charge.delivered > constraints.deliveryLimit - delivered) return null
        return GoalIntentReservation(acquired + charge.acquired, delivered + charge.delivered)
    }
}
