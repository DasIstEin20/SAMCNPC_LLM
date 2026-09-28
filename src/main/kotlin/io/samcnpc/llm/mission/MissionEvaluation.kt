package io.samcnpc.llm.mission

import io.samcnpc.core.api.NpcPosition
import java.util.UUID

internal enum class RequirementState { SATISFIED, UNSATISFIED, UNKNOWN }
internal data class RequirementProgress(val id: String, val state: RequirementState, val count: Long?, val minimum: Int?)

/** Only server-observed completed milestones are durable; item counts are deliberately absent. */
internal data class MissionReceipt(val requirementId: String, val taskId: UUID) {
    init { require(validRequirementId(requirementId)) }
}

/** Detached authoritative values from a single decision boundary, never model output. */
internal class MissionFacts(
    val dimension: String,
    val position: NpcPosition,
    carried: Map<String, Long>,
    storage: Map<MissionChest, Map<String, Long>?> = emptyMap(),
    val completedTask: UUID? = null,
    val preparedField: MissionTarget.Field? = null,
    val collectedChest: MissionChest? = null,
) {
    val carried: Map<String, Long> = java.util.Map.copyOf(carried)
    val storage: Map<MissionChest, Map<String, Long>?> = java.util.Collections.unmodifiableMap(
        storage.mapValues { (_, counts) -> counts?.let { java.util.Map.copyOf(it) } })
    init {
        require(carried.values.all { it >= 0 } && storage.values.all { rows -> rows == null || rows.values.all { it >= 0 } })
        require(preparedField == null || completedTask != null)
        require(collectedChest == null || completedTask != null)
    }
}

internal class MissionEvaluation(progress: List<RequirementProgress>, receipts: List<MissionReceipt>) {
    val progress: List<RequirementProgress> = java.util.List.copyOf(progress)
    val receipts: List<MissionReceipt> = java.util.List.copyOf(receipts)
    val complete: Boolean get() = progress.isNotEmpty() && progress.all { it.state == RequirementState.SATISFIED }
    fun currentStep(plan: MissionPlan): Int? {
        val satisfied = progress.filter { it.state == RequirementState.SATISFIED }.map { it.id }.toSet()
        return plan.steps.indexOfFirst { step -> step.covers.any { it !in satisfied } }.takeIf { it >= 0 }
    }
}

/** Re-evaluates every requirement, never just the current step or a model's covers claim. */
internal object MissionEvaluator {
    fun evaluate(contract: MissionContract, prior: List<MissionReceipt>, facts: MissionFacts): MissionEvaluation {
        require(prior.size <= 24 && prior.map { it.requirementId }.distinct().size == prior.size)
        val milestoneIds = contract.requirements.filter { it.target !is MissionTarget.Items }.map { it.id }.toSet()
        require(prior.all { it.requirementId in milestoneIds })
        val receipts = prior.toMutableList()
        val confirmed = prior.map { it.requirementId }.toMutableSet()
        val progress = mutableListOf<RequirementProgress>()
        for (requirement in contract.requirements) {
            val target = requirement.target
            var count: Long? = null
            val minimum = (target as? MissionTarget.Items)?.minimum
            val state = when {
                requirement.id in confirmed -> RequirementState.SATISFIED
                requirement.after.any { it !in confirmed } -> RequirementState.UNSATISFIED
                target is MissionTarget.Items -> {
                    val counts = if (target.chest == null) facts.carried else facts.storage[target.chest]
                    if (counts == null) RequirementState.UNKNOWN else {
                        // Inventory and chest bounds prevent overflow; do not accept arbitrary unbounded counts.
                        count = target.itemIds.sumOf { (counts[it] ?: 0L).coerceAtMost(1_000_000) }
                        if (count >= target.minimum) RequirementState.SATISFIED else RequirementState.UNSATISFIED
                    }
                }
                target is MissionTarget.Visit -> if (facts.completedTask != null && facts.dimension == target.dimension &&
                    distanceSquared(facts.position, target.position) <= 0.75 * 0.75) RequirementState.SATISFIED
                    else RequirementState.UNSATISFIED
                target is MissionTarget.Field -> if (facts.preparedField == target) RequirementState.SATISFIED
                    else RequirementState.UNSATISFIED
                target is MissionTarget.Collect -> if (facts.collectedChest == target.chest) RequirementState.SATISFIED
                    else RequirementState.UNSATISFIED
                else -> error("Unhandled mission target")
            }
            if (state == RequirementState.SATISFIED) {
                if (target !is MissionTarget.Items && requirement.id !in confirmed)
                    receipts.add(MissionReceipt(requirement.id, checkNotNull(facts.completedTask)))
                confirmed.add(requirement.id)
            }
            progress.add(RequirementProgress(requirement.id, state, count, minimum))
        }
        return MissionEvaluation(progress, receipts)
    }

    private fun distanceSquared(a: NpcPosition, b: NpcPosition): Double =
        (a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y) + (a.z - b.z) * (a.z - b.z)
}
