package io.samcnpc.llm.provider

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Flow

/** Cancel on overflow before allocating an unbounded response body. */
internal class BoundedBodySubscriber(private val limit: Int) : HttpResponse.BodySubscriber<ByteArray> {
    private val result = CompletableFuture<ByteArray>()
    private val bytes = ByteArrayOutputStream(minOf(limit, 8192))
    private var subscription: Flow.Subscription? = null
    override fun getBody(): CompletionStage<ByteArray> = result

    override fun onSubscribe(value: Flow.Subscription) {
        if (subscription != null) { value.cancel(); return }
        subscription = value
        value.request(1)
    }

    override fun onNext(buffers: List<ByteBuffer>) {
        if (result.isDone) return
        for (buffer in buffers) {
            if (buffer.remaining() > limit - bytes.size()) {
                subscription?.cancel()
                result.completeExceptionally(ResponseTooLarge())
                return
            }
            val chunk = ByteArray(buffer.remaining())
            buffer.get(chunk)
            bytes.write(chunk)
        }
        subscription?.request(1)
    }

    override fun onError(error: Throwable) { result.completeExceptionally(error) }
    override fun onComplete() { result.complete(bytes.toByteArray()) }
}

internal class ResponseTooLarge : IOException("Response byte limit")
