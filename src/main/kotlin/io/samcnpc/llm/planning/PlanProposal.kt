package io.samcnpc.llm.planning

import io.samcnpc.core.api.NpcBodyInspection
import io.samcnpc.llm.goal.GoalRecord
import net.minecraft.resources.ResourceLocation

internal data class RequiredItem(val itemId: String, val minimum: Int) {
    init {
        require(itemId.length in 1..256 && ResourceLocation.tryParse(itemId)?.toString() == itemId)
        require(minimum in 1..2304)
    }
}

/** Descriptions are intent; only the separate current operation may be admitted. */
internal class PlanProposal(steps: List<String>, requiredItems: List<RequiredItem>, val minimumEmptySlots: Int) {
    val steps: List<String> = java.util.List.copyOf(steps)
    val requiredItems: List<RequiredItem> = java.util.List.copyOf(requiredItems)
    init {
        require(steps.size in 1..8 && steps.all { GoalRecord.validText(it, 256) })
        require(requiredItems.size <= 8 && requiredItems.map { it.itemId }.distinct().size == requiredItems.size)
        require(minimumEmptySlots in 0..36)
    }

    fun preconditionProblem(body: NpcBodyInspection): String? {
        if (body.inventory.count { it.stack.isEmpty } < minimumEmptySlots) return "PLAN_INVENTORY_SPACE_CHANGED"
        for (required in requiredItems) {
            val count = body.inventory.sumOf { if (it.stack.itemId == required.itemId) it.stack.count.toLong() else 0L }
            if (count < required.minimum) return "PLAN_REQUIRED_ITEM_MISSING"
        }
        return null
    }
}
