package io.samcnpc.llm.goal

import io.samcnpc.llm.context.ContextMemory
import io.samcnpc.llm.context.ContextPlaceAlias
import net.minecraft.resources.ResourceLocation

/** Bounded intent and historical results. Named places never assert current world contents. */
internal class GoalMemory(
    plan: List<String> = emptyList(),
    aliases: List<ContextPlaceAlias> = emptyList(),
    results: List<String> = emptyList(),
) {
    val plan: List<String> = java.util.List.copyOf(plan)
    val aliases: List<ContextPlaceAlias> = java.util.List.copyOf(aliases)
    val results: List<String> = java.util.List.copyOf(results)

    init {
        require(plan.size <= 8 && plan.all { GoalRecord.validText(it, 256) })
        require(aliases.size <= 16 && aliases.map { it.name }.distinct().size == aliases.size)
        require(aliases.all(::validPlace))
        require(results.size <= 16 && results.all { GoalRecord.validText(it, 256) })
        require(intentBytes() <= MAX_INTENT_BYTES)
        require(results.sumOf(::textBytes) <= MAX_RESULT_BYTES)
    }

    fun context() = ContextMemory(plan, aliases, results)
    fun placesOnly() = GoalMemory(aliases = aliases)
    fun withPlace(alias: ContextPlaceAlias) = GoalMemory(plan,
        (aliases.filter { it.name != alias.name } + alias).sortedBy { it.name }, results)
    fun withoutPlace(name: String) = GoalMemory(plan, aliases.filter { it.name != name }, results)

    /** Called only after observing the exact bound Behavior task, never from model summaries. */
    fun withOutcome(task: GoalTask, code: String): GoalMemory {
        require(code in setOf("TASK_COMPLETED", "TASK_FAILED", "TASK_CANCELLED"))
        val prefix = task.id.toString() + " "
        if (results.any { it.startsWith(prefix) }) return this
        val entry = prefix + task.operationId + " " + code
        val bounded = (results + entry).takeLast(16).toMutableList()
        while (bounded.sumOf(::textBytes) > MAX_RESULT_BYTES) bounded.removeAt(0)
        return GoalMemory(plan, aliases, bounded)
    }

    private fun intentBytes(): Int = plan.sumOf(::textBytes) +
        aliases.sumOf { textBytes(it.name) + textBytes(it.dimensionId) + 32 }

    override fun equals(other: Any?): Boolean = other is GoalMemory &&
        plan == other.plan && aliases == other.aliases && results == other.results
    override fun hashCode(): Int = 31 * (31 * plan.hashCode() + aliases.hashCode()) + results.hashCode()

    companion object {
        const val MAX_INTENT_BYTES = 4096
        const val MAX_RESULT_BYTES = 2048
        private fun textBytes(value: String): Int = value.toByteArray(Charsets.UTF_8).size + 8
        fun validName(value: String): Boolean = value.length in 1..64 &&
            value.all { it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-' }
        fun validPlace(place: ContextPlaceAlias): Boolean =
            validName(place.name) && ResourceLocation.tryParse(place.dimensionId)?.toString() == place.dimensionId &&
                place.position.x in -29999984..29999984 && place.position.z in -29999984..29999984 &&
                place.position.y in -2048..2048
    }
}
