package io.samcnpc.llm.scheduling

import java.util.UUID

/** Exact refundable entries plus minute aggregates of settled, non-refundable work. */
internal class HourlyInferenceLedger {
    private data class Entry(val id: UUID, val npc: UUID, val at: Long, val charge: InferenceCharge, var settled: Boolean = false)
    private data class NpcCount(var calls: Int, var latest: Long)
    private class Bucket {
        var latest = 0L
        var charge = InferenceCharge(0, 0, 0)
        val npcs = linkedMapOf<UUID, NpcCount>()
    }
    data class Spending(val expires: Long, val calls: Int, val charge: InferenceCharge)
    private val exact = linkedMapOf<UUID, Entry>()
    private val buckets = sortedMapOf<Long, Bucket>()

    fun expire(now: Long) {
        exact.values.removeIf { now - it.at >= InferenceRateGate.WINDOW_MILLIS }
        buckets.values.removeIf { now - it.latest >= InferenceRateGate.WINDOW_MILLIS }
    }

    fun reserve(id: UUID, npc: UUID, now: Long, charge: InferenceCharge) {
        check(id !in exact && exact.size < MAX_TRACKED)
        exact[id] = Entry(id, npc, now, charge)
    }

    fun releaseUnsent(id: UUID): Boolean {
        val entry = exact[id] ?: return false
        if (entry.settled) return false
        exact.remove(id)
        return true
    }

    fun settle(id: UUID, compact: Boolean): Boolean {
        val entry = exact[id] ?: return false
        if (entry.settled) return false
        entry.settled = true
        if (compact) { aggregate(entry); exact.remove(id) }
        return true
    }

    fun compactSettled() {
        val iterator = exact.values.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.settled) { aggregate(entry); iterator.remove() }
        }
    }

    private fun aggregate(entry: Entry) {
        val bucket = buckets.getOrPut(entry.at / 60_000) { Bucket() }
        bucket.latest = maxOf(bucket.latest, entry.at)
        bucket.charge = add(bucket.charge, entry.charge)
        val npc = bucket.npcs[entry.npc]
        if (npc == null) bucket.npcs[entry.npc] = NpcCount(1, entry.at)
        else { npc.calls++; npc.latest = maxOf(npc.latest, entry.at) }
        check(buckets.size <= 61)
    }

    fun spending(npc: UUID? = null): List<Spending> {
        val result = ArrayList<Spending>(exact.size + buckets.size)
        for (entry in exact.values) if (npc == null || npc == entry.npc)
            result.add(Spending(entry.at + InferenceRateGate.WINDOW_MILLIS, 1, entry.charge))
        for (bucket in buckets.values) {
            val calls = if (npc == null) bucket.npcs.values.sumOf { it.calls } else bucket.npcs[npc]?.calls ?: 0
            if (calls > 0) result.add(Spending(bucket.latest + InferenceRateGate.WINDOW_MILLIS, calls, bucket.charge))
        }
        return result.sortedBy { it.expires }
    }

    fun latest(npc: UUID): Long? {
        val exactLatest = exact.values.filter { it.npc == npc }.maxOfOrNull { it.at }
        val bucketLatest = buckets.values.mapNotNull { it.npcs[npc]?.latest }.maxOrNull()
        return listOfNotNull(exactLatest, bucketLatest).maxOrNull()
    }

    fun identities(): Set<UUID> = buildSet {
        exact.values.forEach { add(it.npc) }
        buckets.values.forEach { addAll(it.npcs.keys) }
    }
    fun exactSize(): Int = exact.size
    fun bucketSize(): Int = buckets.size

    companion object {
        const val MAX_TRACKED = 720
        // Admission verifies the aggregate cannot overflow before a reservation is inserted.
        fun add(a: InferenceCharge, b: InferenceCharge) = InferenceCharge(
            Math.addExact(a.inputTokens, b.inputTokens), Math.addExact(a.outputTokens, b.outputTokens),
            Math.addExact(a.costMicros, b.costMicros))
    }
}
