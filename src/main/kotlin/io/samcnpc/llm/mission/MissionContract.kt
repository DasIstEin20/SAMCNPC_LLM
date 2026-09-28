package io.samcnpc.llm.mission

import io.samcnpc.core.api.NpcBlockPosition
import io.samcnpc.core.api.NpcPosition
import io.samcnpc.llm.goal.GoalRecord
import net.minecraft.resources.ResourceLocation

internal enum class PlannerVariant { V1, MISSION_V2 }
internal enum class MissionStage { REQUIREMENTS, PLAN, OPERATION }

internal data class MissionChest(val dimension: String, val position: NpcBlockPosition) {
    init { require(validIdentity(dimension) && validPosition(position)) }
}

internal sealed interface MissionTarget {
    /** Whole-container collection keeps Behavior's captured quota semantics without guessing contents. */
    data class Collect(val chest: MissionChest) : MissionTarget
    /** Null chest means the NPC's own carried inventory and equipment, without aliases. */
    class Items(itemIds: List<String>, val minimum: Int, val chest: MissionChest? = null) : MissionTarget {
        val itemIds: List<String> = java.util.List.copyOf(itemIds)
        init {
            require(itemIds.size in 1..4 && itemIds.distinct().size == itemIds.size)
            require(itemIds.all(::validIdentity) && minimum in 1..2304)
        }
        override fun equals(other: Any?): Boolean = other is Items &&
            itemIds == other.itemIds && minimum == other.minimum && chest == other.chest
        override fun hashCode(): Int = 31 * (31 * itemIds.hashCode() + minimum) + (chest?.hashCode() ?: 0)
    }

    data class Visit(val dimension: String, val position: NpcPosition) : MissionTarget {
        init {
            require(validIdentity(dimension))
            require(position.x.isFinite() && position.y.isFinite() && position.z.isFinite())
            require(position.x in -29999984.0..29999984.0 && position.z in -29999984.0..29999984.0)
            require(position.y in -2048.0..2048.0)
        }
    }

    data class Field(val dimension: String, val min: NpcBlockPosition, val max: NpcBlockPosition) : MissionTarget {
        init {
            require(validIdentity(dimension) && validPosition(min) && validPosition(max))
            require(min.y == max.y && min.x <= max.x && min.z <= max.z)
            require((max.x.toLong() - min.x + 1) * (max.z.toLong() - min.z + 1) in 1..256)
        }
        val cells: Int get() = (max.x - min.x + 1) * (max.z - min.z + 1)
    }
}

internal class MissionRequirement(val id: String, val source: String, val target: MissionTarget,
                                  after: List<String> = emptyList()) {
    val after: List<String> = java.util.List.copyOf(after)
    init {
        require(validRequirementId(id) && GoalRecord.validText(source, 256))
        require(after.size <= 23 && after.distinct().size == after.size && after.all(::validRequirementId))
        require(id !in after)
    }
    override fun equals(other: Any?): Boolean = other is MissionRequirement &&
        id == other.id && source == other.source && target == other.target && after == other.after
    override fun hashCode(): Int = listOf(id, source, target, after).hashCode()
}

/** Extraction intent only. GoalConstraints alone establish authority. */
internal class MissionContract(requirements: List<MissionRequirement>) {
    val requirements: List<MissionRequirement> = java.util.List.copyOf(requirements)
    val ids: Set<String> = java.util.Set.copyOf(requirements.map { it.id })
    init {
        require(requirements.size in 1..24 && ids.size == requirements.size)
        val preceding = mutableSetOf<String>()
        for (requirement in requirements) {
            require(requirement.after.all { it in preceding }) { "Dependencies must name earlier requirements" }
            preceding.add(requirement.id)
        }
    }
    fun groundingProblem(goalText: String): String? =
        if (requirements.any { !goalText.contains(it.source) }) "MISSION_SOURCE_NOT_IN_GOAL" else null

    override fun equals(other: Any?): Boolean = other is MissionContract && requirements == other.requirements
    override fun hashCode(): Int = requirements.hashCode()
}

internal class MissionStep(val description: String, covers: List<String>) {
    val covers: List<String> = java.util.List.copyOf(covers)
    init {
        require(GoalRecord.validText(description, 256))
        require(covers.size in 1..24 && covers.distinct().size == covers.size && covers.all(::validRequirementId))
    }
    override fun equals(other: Any?): Boolean = other is MissionStep && description == other.description && covers == other.covers
    override fun hashCode(): Int = 31 * description.hashCode() + covers.hashCode()
}

internal class MissionPlan(steps: List<MissionStep>) {
    val steps: List<MissionStep> = java.util.List.copyOf(steps)
    init { require(steps.size in 1..8) }
    fun problem(contract: MissionContract): String? {
        val covered = steps.flatMap { it.covers }.toSet()
        if (!contract.ids.containsAll(covered)) return "MISSION_UNKNOWN_REQUIREMENT"
        if (!covered.containsAll(contract.ids)) return "MISSION_INCOMPLETE_COVERAGE"
        val firstStep = contract.ids.associateWith { id -> steps.indexOfFirst { id in it.covers } }
        if (contract.requirements.any { requirement -> requirement.after.any {
                firstStep.getValue(it) > firstStep.getValue(requirement.id) } }) return "MISSION_DEPENDENCY_ORDER"
        return null
    }
    override fun equals(other: Any?): Boolean = other is MissionPlan && steps == other.steps
    override fun hashCode(): Int = steps.hashCode()
}

internal fun validRequirementId(value: String): Boolean = value.length in 2..3 && value[0] == 'R' &&
    value.substring(1).toIntOrNull()?.let { it in 1..24 && value == "R$it" } == true

private fun validIdentity(value: String): Boolean = value.length in 1..128 &&
    ResourceLocation.tryParse(value)?.toString() == value

private fun validPosition(value: NpcBlockPosition): Boolean = value.x in -29999984..29999984 &&
    value.z in -29999984..29999984 && value.y in -2048..2048
