package io.samcnpc.llm.supervision

internal data class FailedDecision(val decision: String, val world: String, val reason: String) {
    init {
        require(FailedDecisions.validHash(decision) && FailedDecisions.validHash(world))
        require(reason.length in 1..128 && reason.all { it in 'A'..'Z' || it in '0'..'9' || it == '_' })
    }
}

/** Immutable bounded ledger. Budgets/timestamps/task UUIDs belong outside semantic fingerprints. */
internal class FailedDecisions(entries: List<FailedDecision> = emptyList(), val consecutive: Int = 0) {
    val entries: List<FailedDecision> = java.util.List.copyOf(entries)
    init { require(entries.size <= 8 && consecutive in 0..3) }

    fun problem(decision: String? = null, world: String? = null): String? = when {
        consecutive >= 3 -> "NONPROGRESS_DECISION_LIMIT"
        decision != null && world != null && entries.count { it.decision == decision && it.world == world } >= 2 ->
            "REPEATED_FAILED_DECISION"
        else -> null
    }

    fun failed(decision: String, world: String, reason: String): FailedDecisions =
        FailedDecisions((entries + FailedDecision(decision, world, reason)).takeLast(8), minOf(3, consecutive + 1))

    /** Historical failures remain visible, while confirmed physical progress ends the nonprogress streak. */
    fun progressed(): FailedDecisions = FailedDecisions(entries, 0)

    override fun equals(other: Any?): Boolean =
        other is FailedDecisions && consecutive == other.consecutive && entries == other.entries
    override fun hashCode(): Int = 31 * entries.hashCode() + consecutive

    companion object {
        fun validHash(value: String): Boolean = value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }
    }
}
