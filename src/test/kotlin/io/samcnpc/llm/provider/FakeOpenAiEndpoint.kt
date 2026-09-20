package io.samcnpc.llm.provider

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** CPU-only OpenAI-compatible protocol emulator; never loads or impersonates a real model. */
internal class FakeOpenAiEndpoint : AutoCloseable {
    data class Reply(
        val body: ByteArray = success().toByteArray(),
        val status: Int = 200,
        val headers: Map<String, String> = emptyMap(),
        val waitBeforeHeaders: CountDownLatch? = null,
        val waitDuringBody: CountDownLatch? = null,
        val truncated: Boolean = false,
    )
    data class Received(val path: String, val method: String, val authorization: String?, val body: String)
    private val replies = ConcurrentLinkedQueue<Reply>()
    val received = ConcurrentLinkedQueue<Received>()
    val arrivals = java.util.concurrent.Semaphore(0)
    val disconnects = AtomicInteger()
    private val executor = Executors.newFixedThreadPool(4) { task -> Thread(task, "samcnpc-http-emulator").apply { isDaemon = true } }
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}/v1"

    init {
        server.executor = executor
        server.createContext("/") { exchange ->
            try {
                val requestBytes = exchange.requestBody.readNBytes(65_537)
                received.add(Received(exchange.requestURI.path, exchange.requestMethod,
                    exchange.requestHeaders.getFirst("Authorization"), requestBytes.toString(Charsets.UTF_8)))
                arrivals.release()
                val reply = checkNotNull(replies.poll()) { "Unexpected emulator request" }
                reply.waitBeforeHeaders?.await(5, TimeUnit.SECONDS)
                exchange.responseHeaders.set("Content-Type", "application/json")
                reply.headers.forEach { (key, value) -> exchange.responseHeaders.set(key, value) }
                val length = when {
                    reply.waitDuringBody != null -> 0L
                    reply.truncated -> reply.body.size.toLong() + 64
                    else -> reply.body.size.toLong()
                }
                exchange.sendResponseHeaders(reply.status, length)
                if (reply.waitDuringBody != null) {
                    exchange.responseBody.write('{'.code)
                    exchange.responseBody.flush()
                    reply.waitDuringBody.await(5, TimeUnit.SECONDS)
                } else exchange.responseBody.write(reply.body)
            } catch (_: IOException) {
                disconnects.incrementAndGet() // Expected when a client cancels a scripted failure.
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally { exchange.close() }
        }
        server.start()
    }

    fun enqueue(reply: Reply = Reply()) { replies.add(reply) }
    override fun close() {
        server.stop(0)
        executor.shutdownNow()
        check(executor.awaitTermination(3, TimeUnit.SECONDS))
    }

    companion object {
        fun success(content: String = """{"decision":"CONTINUE"}""", finish: String = "stop"): String {
            val escaped = com.google.gson.JsonPrimitive(content).toString()
            return """{"choices":[{"index":0,"message":{"role":"assistant","content":$escaped},"finish_reason":"$finish"}],"usage":{"prompt_tokens":12,"completion_tokens":5}}"""
        }
    }
}
