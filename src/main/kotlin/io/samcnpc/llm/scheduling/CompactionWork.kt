package io.samcnpc.llm.scheduling

import com.mojang.logging.LogUtils
import io.samcnpc.llm.context.ContextBinding
import io.samcnpc.llm.context.NpcContextEncoder
import java.util.concurrent.*

internal data class CompactionReport(val binding: ContextBinding, val before: RequestMetrics?,
    val after: RequestMetrics?, val code: String) {
    fun describe(): String = "compact=$code observedTick=${binding.issuedTick} requestSent=false " +
        "inferenceCalls=0 canonicalMemoryPreserved=true\nbefore: " + (before?.describe() ?: "metrics=UNAVAILABLE") +
        "\nafter: " + (after?.describe() ?: "metrics=UNAVAILABLE")
}

/** One physical worker slot, no queued work or provider. Only detached captures enter the closure. */
internal class CompactionWork : AutoCloseable {
    private val executor = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, SynchronousQueue(),
        { task -> Thread(task, "samcnpc-llm-compact").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    private var pending: CompletableFuture<CompactionReport>? = null
    private var closed = false
    val available: Boolean get() = !closed && pending == null

    fun submit(input: InferenceInput): Boolean {
        if (!available) return false
        return try {
            pending = CompletableFuture.supplyAsync({ calculate(input) }, executor)
            true
        } catch (_: RejectedExecutionException) { false }
    }

    fun poll(): CompactionReport? {
        val future = pending ?: return null
        if (!future.isDone) return null
        pending = null
        return future.join()
    }

    override fun close() {
        closed = true
        pending?.cancel(true); pending = null
        executor.shutdownNow()
    }

    companion object {
        private val LOGGER = LogUtils.getLogger()
        private fun calculate(input: InferenceInput): CompactionReport {
            var before: RequestMetrics? = null
            return try {
                before = InferenceRequestPreparation.prepare(input, 0..0).metrics
                val compacted = InferenceRequestPreparation.prepare(input, 1..NpcContextEncoder.MAX_DETAIL_LEVEL)
                CompactionReport(input.captured.binding, before, compacted.metrics,
                    (compacted as? RequestPreparation.Rejected)?.code ?: "CONTEXT_COMPACTED")
            } catch (error: Exception) {
                LOGGER.warn("LLM manual projection failed exception={}", error.javaClass.simpleName)
                CompactionReport(input.captured.binding, before, null, "COMPACTION_PREPARATION_FAILED")
            }
        }
    }
}
