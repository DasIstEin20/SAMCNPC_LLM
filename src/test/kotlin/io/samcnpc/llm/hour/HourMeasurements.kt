package io.samcnpc.llm.hour

import java.lang.management.ManagementFactory
import kotlin.math.ceil

/** Bounded arrays and counters; no per-tick logging or unbounded event history. */
internal class HourMeasurements {
    private val llmNanos = LongArray(108000)
    private val tickNanos = LongArray(108000)
    private var count = 0
    private val captures = mutableListOf<Long>()
    private val admissions = mutableListOf<Long>()
    private val latency = mutableListOf<Long>()
    val heap = mutableListOf<Long>()
    var peakQueue = 0
    var peakWorkers = 0
    fun sample(llm: Long, tick: Long) {
        check(count < llmNanos.size && llm >= 0 && tick >= 0)
        llmNanos[count] = llm; tickNanos[count] = tick; count++
    }
    fun capture(nanos: Long) { check(captures.size < 60); captures.add(nanos) }
    fun admission(nanos: Long) { check(admissions.size < 60); admissions.add(nanos) }
    fun latency(nanos: Long) { check(latency.size < 60); latency.add(nanos) }
    fun heap() {
        check(heap.size < 181)
        heap.add(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
    }
    private fun stats(values: LongArray): Map<String, Any> {
        val a = values.sortedArray()
        fun p(portion: Double) = if (a.isEmpty()) 0.0 else a[(ceil(a.size * portion).toInt()-1).coerceIn(a.indices)] / 1_000_000.0
        return mapOf("samples" to a.size, "p95Ms" to p(0.95), "p99Ms" to p(0.99), "maxMs" to p(1.0))
    }
    fun summary() = linkedMapOf("llmServerWork" to stats(llmNanos.copyOf(count)),
        "wholeServerTick" to stats(tickNanos.copyOf(count)), "snapshot" to stats(captures.toLongArray()),
        "admission" to stats(admissions.toLongArray()), "requestToAdmissionLatency" to stats(latency.toLongArray()),
        "peakQueue" to peakQueue, "peakWorkers" to peakWorkers, "heapUsedBytes" to heap.toList(),
        "gcCollections" to ManagementFactory.getGarbageCollectorMXBeans().sumOf { it.collectionCount.coerceAtLeast(0) },
        "gcMillis" to ManagementFactory.getGarbageCollectorMXBeans().sumOf { it.collectionTime.coerceAtLeast(0) })
    fun verify() {
        check(count >= 600)
        val s = stats(llmNanos.copyOf(count))
        check((s.getValue("p95Ms") as Double) <= 1.0 && (s.getValue("p99Ms") as Double) <= 2.0) { s.toString() }
        check(peakQueue <= 32 && peakWorkers <= 2)
    }
    fun raw() = mapOf("llmServerNanos" to llmNanos.copyOf(count), "wholeTickNanos" to tickNanos.copyOf(count))
}
