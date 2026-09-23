package io.samcnpc.llm.provider

import io.samcnpc.llm.api.*
import io.samcnpc.llm.config.ProviderSettings
import java.io.IOException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

/** Two bounded asynchronous exchanges; the later scheduler owns fairness, retries and NPC budgets. */
internal class OpenAiCompatibleProvider(
    private val settings: ProviderSettings,
    private val environment: (String) -> String? = System::getenv,
) : LlmProvider {
    private val active = linkedSetOf<Exchange>()
    private var closed = false
    private var client: HttpClient? = null
    private val io = ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, ArrayBlockingQueue(64),
        daemonFactory("samcnpc-llm-http"), ThreadPoolExecutor.AbortPolicy())
    private val deadlines = ScheduledThreadPoolExecutor(1, daemonFactory("samcnpc-llm-deadline")).apply {
        removeOnCancelPolicy = true
    }

    @Synchronized
    override fun status(): ProviderStatus =
        if (closed || !settings.enabled || settings.problem() != null) ProviderStatus.Disabled
        else ProviderStatus.Available("openai-compatible")

    @Synchronized
    override fun complete(request: LlmRequest): LlmCall {
        if (closed) return failed(request, LlmFailure.CANCELLED)
        if (!settings.enabled) return failed(request, LlmFailure.DISABLED)
        if (settings.problem() != null) return failed(request, LlmFailure.INVALID_CONFIGURATION)
        if (active.size >= 2) return failed(request, LlmFailure.BUSY)
        if (request.systemPrompt.length > settings.maxContextBytes || request.contextJson.length > settings.maxContextBytes ||
            request.responseSchemaJson.length > settings.maxContextBytes) return failed(request, LlmFailure.CONTEXT_TOO_LARGE)
        val inputBytes = try {
            listOf(request.systemPrompt, request.contextJson, request.responseSchemaJson).sumOf { LlmJson.utf8(it).size.toLong() }
        } catch (_: IOException) { return failed(request, LlmFailure.INVALID_REQUEST) }
        if (inputBytes > settings.maxContextBytes) return failed(request, LlmFailure.CONTEXT_TOO_LARGE)
        val payload = try { ChatCompletionCodec.request(request, settings) }
        catch (_: IllegalArgumentException) { return failed(request, LlmFailure.INVALID_REQUEST) }
        catch (_: IOException) { return failed(request, LlmFailure.INVALID_REQUEST) }
        catch (_: com.google.gson.JsonParseException) { return failed(request, LlmFailure.INVALID_REQUEST) }
        if (payload.size > settings.maxContextBytes) return failed(request, LlmFailure.CONTEXT_TOO_LARGE)
        val key = if (settings.apiKeyEnvironment.isEmpty()) null else environment(settings.apiKeyEnvironment)
        if (key != null && (key.length > 4096 || key.any { it <= ' ' || it.code >= 127 }))
            return failed(request, LlmFailure.INVALID_CONFIGURATION)
        val builder = HttpRequest.newBuilder(settings.completionUri())
            .timeout(Duration.ofSeconds(settings.requestTimeoutSeconds.toLong()))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
        if (!key.isNullOrEmpty()) builder.header("Authorization", "Bearer $key")
        val exchange = Exchange(request)
        active.add(exchange)
        var submission = LlmSubmission.NOT_SENT
        try {
            var transport = client
            if (transport == null) {
                transport = HttpClient.newBuilder().executor(io)
                    .connectTimeout(Duration.ofSeconds(settings.connectTimeoutSeconds.toLong()))
                    .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1).build()
                client = transport
            }
            val httpRequest = builder.build()
            submission = LlmSubmission.UNKNOWN
            val network = transport.sendAsync(httpRequest, HttpResponse.BodyHandler {
                BoundedBodySubscriber(settings.maxResponseBytes)
            })
            submission = LlmSubmission.SUBMITTED
            exchange.network = network
            exchange.deadline = deadlines.schedule({
                if (exchange.finish(LlmResponse.Failed(request.requestId, LlmFailure.TIMEOUT))) network.cancel(true)
            }, settings.requestTimeoutSeconds.toLong(), TimeUnit.SECONDS)
            network.whenComplete { response, error ->
                if (error != null) {
                    exchange.finish(LlmResponse.Failed(request.requestId, classify(error)))
                } else {
                    val result = try { ChatCompletionCodec.response(request, response, settings.responseFormat) }
                    catch (_: IllegalArgumentException) { LlmResponse.Failed(request.requestId, LlmFailure.INVALID_OUTPUT) }
                    catch (_: IOException) { LlmResponse.Failed(request.requestId, LlmFailure.INVALID_OUTPUT) }
                    catch (_: com.google.gson.JsonParseException) { LlmResponse.Failed(request.requestId, LlmFailure.INVALID_OUTPUT) }
                    catch (_: ArithmeticException) { LlmResponse.Failed(request.requestId, LlmFailure.INVALID_OUTPUT) }
                    exchange.finish(result)
                }
            }
        } catch (_: RejectedExecutionException) {
            exchange.finish(LlmResponse.Failed(request.requestId, LlmFailure.UNAVAILABLE))
            exchange.network?.cancel(true)
        } catch (_: IllegalArgumentException) {
            exchange.finish(LlmResponse.Failed(request.requestId, LlmFailure.INVALID_CONFIGURATION))
            exchange.network?.cancel(true)
        }
        return LlmCall(exchange.result, submission, exchange::cancel)
    }

    override fun close() {
        val pending: List<Exchange>
        synchronized(this) {
            if (closed) return
            closed = true
            pending = active.toList()
            client = null
        }
        pending.forEach { it.cancel() }
        deadlines.shutdownNow()
        io.shutdownNow()
    }

    @Synchronized
    internal fun activeRequests(): Int = active.size

    private inner class Exchange(val request: LlmRequest) {
        val result = CompletableFuture<LlmResponse>()
        private val finished = AtomicBoolean()
        var network: CompletableFuture<HttpResponse<ByteArray>>? = null
        var deadline: ScheduledFuture<*>? = null

        fun finish(value: LlmResponse): Boolean {
            if (!finished.compareAndSet(false, true)) return false
            deadline?.cancel(false)
            synchronized(this@OpenAiCompatibleProvider) { active.remove(this) }
            result.complete(value)
            return true
        }

        fun cancel(): Boolean {
            if (!finish(LlmResponse.Failed(request.requestId, LlmFailure.CANCELLED))) return false
            network?.cancel(true)
            return true
        }
    }

    private fun failed(request: LlmRequest, code: LlmFailure): LlmCall =
        LlmCall(CompletableFuture.completedFuture(LlmResponse.Failed(request.requestId, code)), LlmSubmission.NOT_SENT) { false }

    private fun classify(error: Throwable): LlmFailure {
        var cause = error
        repeat(4) {
            if (cause is CompletionException || cause is ExecutionException) cause = cause.cause ?: cause
        }
        return when (cause) {
            is ResponseTooLarge -> LlmFailure.RESPONSE_TOO_LARGE
            is HttpTimeoutException, is TimeoutException -> LlmFailure.TIMEOUT
            is CancellationException -> LlmFailure.CANCELLED
            else -> LlmFailure.UNAVAILABLE
        }
    }

    private fun daemonFactory(name: String): ThreadFactory = ThreadFactory { task ->
        Thread(task, name).apply { isDaemon = true }
    }
}
