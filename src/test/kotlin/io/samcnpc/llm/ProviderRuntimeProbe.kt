package io.samcnpc.llm

import io.samcnpc.llm.api.*
import io.samcnpc.llm.config.ProviderSettings
import io.samcnpc.llm.provider.FakeOpenAiEndpoint
import io.samcnpc.llm.provider.OpenAiCompatibleProvider
import java.util.UUID
import java.util.concurrent.*

/** Test-only I/O worker: verifies actual HTTP in Forge without capturing server/client objects. */
internal object ProviderRuntimeProbe {
    private var worker: ExecutorService? = null
    private var result: CompletableFuture<String>? = null

    fun start() {
        check(result == null)
        val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "samcnpc-llm-runtime-probe").apply { isDaemon = true } }
        worker = executor
        result = CompletableFuture.supplyAsync({
            FakeOpenAiEndpoint().use { endpoint ->
                val settings = ProviderSettings(enabled = true, baseUrl = endpoint.baseUrl, model = "test-only-emulator",
                    requestTimeoutSeconds = 1, apiKeyEnvironment = "")
                OpenAiCompatibleProvider(settings).use { provider ->
                    val request = LlmRequest(UUID.randomUUID(), "Return JSON only.", """{"state":{},"goal":"wait"}""",
                        """{"type":"object","properties":{"decision":{"type":"string"}},"required":["decision"],"additionalProperties":false}""")
                    endpoint.enqueue()
                    check(provider.complete(request).result.toCompletableFuture().get(3, TimeUnit.SECONDS) is LlmResponse.Candidate)
                    val stalledBody = CountDownLatch(1)
                    endpoint.enqueue(FakeOpenAiEndpoint.Reply(waitDuringBody = stalledBody))
                    val timedOut = provider.complete(request).result.toCompletableFuture().get(3, TimeUnit.SECONDS)
                    check(timedOut is LlmResponse.Failed && timedOut.code == LlmFailure.TIMEOUT)
                    stalledBody.countDown()
                    endpoint.enqueue(FakeOpenAiEndpoint.Reply(status = 503))
                    val offline = provider.complete(request).result.toCompletableFuture().get(3, TimeUnit.SECONDS)
                    check(offline is LlmResponse.Failed && offline.code == LlmFailure.UNAVAILABLE)
                    endpoint.enqueue()
                    check(provider.complete(request).result.toCompletableFuture().get(3, TimeUnit.SECONDS) is LlmResponse.Candidate)
                    check(provider.activeRequests() == 0 && endpoint.received.size == 4)
                    "emulatedHttp=true boundedBodyTimeout=true http503=true recovery=true calls=4"
                }
            }
        }, executor)
    }

    fun poll(): String? {
        val future = result ?: return null
        if (!future.isDone) return null
        val value = future.join()
        worker?.shutdown()
        return value
    }
}
