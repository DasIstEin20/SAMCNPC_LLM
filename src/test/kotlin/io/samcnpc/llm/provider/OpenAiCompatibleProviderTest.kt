package io.samcnpc.llm.provider

import io.samcnpc.llm.api.*
import io.samcnpc.llm.config.ProviderSettings
import io.samcnpc.llm.config.ResponseFormat
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@Timeout(20)
class OpenAiCompatibleProviderTest {
    private fun request(context: String = """{"state":{},"goal":"Przynieś drewno"}""") =
        LlmRequest(UUID.randomUUID(), "Return one JSON decision.", context,
            """{"type":"object","properties":{"decision":{"type":"string"}},"required":["decision"],"additionalProperties":false}""")

    private fun result(call: LlmCall): LlmResponse = call.result.toCompletableFuture().get(4, TimeUnit.SECONDS)
    private fun failure(call: LlmCall, code: LlmFailure): LlmResponse.Failed {
        val value = result(call)
        assertTrue(value is LlmResponse.Failed, value.javaClass.name)
        value as LlmResponse.Failed
        assertEquals(code, value.code)
        return value
    }

    @Test fun completeRequestAndCandidateRoundTripOverRealHttpWithoutWorldAccess() {
        FakeOpenAiEndpoint().use { endpoint ->
            endpoint.enqueue()
            val settings = ProviderSettings(enabled = true, baseUrl = endpoint.baseUrl, model = "test-model")
            OpenAiCompatibleProvider(settings) { "test-secret" }.use { provider ->
                assertTrue(provider.status() is ProviderStatus.Available)
                assertEquals(0, endpoint.received.size)
                val input = request()
                val response = result(provider.complete(input)) as LlmResponse.Candidate
                assertEquals(input.requestId, response.requestId)
                assertEquals("""{"decision":"CONTINUE"}""", response.decisionJson)
                assertEquals(LlmUsage(12, 5), response.usage)
                val received = endpoint.received.single()
                assertEquals("/v1/chat/completions", received.path)
                assertEquals("POST", received.method)
                assertEquals("Bearer test-secret", received.authorization)
                val sent = LlmJson.parse(received.body, 65_536)
                assertEquals("test-model", sent["model"].asString)
                assertFalse(sent["stream"].asBoolean)
                assertEquals(1, sent["n"].asInt)
                assertEquals(1024, sent["max_tokens"].asInt)
                assertEquals("Przynieś drewno", LlmJson.parse(sent["messages"].asJsonArray[1].asJsonObject["content"].asString, 65_536)["goal"].asString)
                val format = sent["response_format"].asJsonObject
                assertEquals("json_schema", format["type"].asString)
                assertTrue(format["json_schema"].asJsonObject["strict"].asBoolean)
                val system = sent["messages"].asJsonArray[0].asJsonObject["content"].asString
                assertEquals(input.systemPrompt + "\n" + SchemaPrompt.describe(format["json_schema"].asJsonObject["schema"].asJsonObject), system)
                assertTrue(system.contains("decision:string"))
                assertEquals(0, provider.activeRequests())
            }
        }
    }

    @Test fun disabledMalformedOversizedAndBadCredentialsNeverReachNetwork() {
        FakeOpenAiEndpoint().use { endpoint ->
            val settings = ProviderSettings(baseUrl = endpoint.baseUrl, model = "test-model")
            OpenAiCompatibleProvider(settings) { null }.use { provider ->
                failure(provider.complete(request()), LlmFailure.DISABLED)
            }
            OpenAiCompatibleProvider(settings.copy(enabled = true)) { null }.use { provider ->
                failure(provider.complete(request("""{"x":1,"x":2}""")), LlmFailure.INVALID_REQUEST)
                failure(provider.complete(request("x".repeat(65_537))), LlmFailure.CONTEXT_TOO_LARGE)
                failure(provider.complete(request("""{"goal":"${"x".repeat(65_500)}"}""")), LlmFailure.CONTEXT_TOO_LARGE)
            }
            OpenAiCompatibleProvider(settings.copy(enabled = true)) { "secret\r\ninjected" }.use { provider ->
                failure(provider.complete(request()), LlmFailure.INVALID_CONFIGURATION)
            }
            assertTrue(endpoint.received.isEmpty())
        }
    }

    @Test fun callSubmissionDistinguishesProviderEntryFromHttpAndKeepsSentFailuresCharged() {
        FakeOpenAiEndpoint().use { endpoint ->
            val settings = ProviderSettings(enabled = true, baseUrl = endpoint.baseUrl, model = "fixture", apiKeyEnvironment = "")
            OpenAiCompatibleProvider(settings).use { provider ->
                val local = provider.complete(request("{broken"))
                assertEquals(LlmSubmission.NOT_SENT, local.submission)
                failure(local, LlmFailure.INVALID_REQUEST)
                assertTrue(endpoint.received.isEmpty())
                endpoint.enqueue(FakeOpenAiEndpoint.Reply("{}".toByteArray(), 503))
                val sent = provider.complete(request())
                assertEquals(LlmSubmission.SUBMITTED, sent.submission)
                failure(sent, LlmFailure.UNAVAILABLE)
                assertEquals(1, endpoint.received.size)
            }
        }
    }

    @Test fun explicitJsonObjectProfileDoesNotSilentlyRetryWithAnotherFormat() {
        FakeOpenAiEndpoint().use { endpoint ->
            endpoint.enqueue()
            val settings = ProviderSettings(enabled = true, baseUrl = endpoint.baseUrl, model = "test-model",
                responseFormat = ResponseFormat.JSON_OBJECT, apiKeyEnvironment = "")
            OpenAiCompatibleProvider(settings) { error("Empty key variable must not be read") }.use { provider ->
                assertTrue(result(provider.complete(request())) is LlmResponse.Candidate)
                val received = endpoint.received.single()
                assertNull(received.authorization)
                val payload = LlmJson.parse(received.body, 65_536)
                val system = payload["messages"].asJsonArray[0].asJsonObject["content"].asString
                assertTrue(system.contains("OUTPUT_CONTRACT_JSON_SCHEMA"))
                assertEquals(LlmJson.parse(request().responseSchemaJson, 65_536),
                    LlmJson.parse(system.substringAfter("OUTPUT_CONTRACT_JSON_SCHEMA\n"), 65_536))
                assertEquals("""{"type":"json_object"}""", LlmJson.parse(received.body, 65_536)["response_format"].toString())
            }
        }
    }

    @Test fun httpFailuresAndRedirectsAreTypedWithoutRetriesOrSecretEchoes() {
        FakeOpenAiEndpoint().use { endpoint ->
            OpenAiCompatibleProvider(ProviderSettings(enabled = true, baseUrl = endpoint.baseUrl, model = "test-model")) { "private-key" }.use { provider ->
                val cases = mapOf(401 to LlmFailure.UNAUTHORIZED, 403 to LlmFailure.UNAUTHORIZED,
                    400 to LlmFailure.INCOMPATIBLE, 404 to LlmFailure.INCOMPATIBLE,
                    429 to LlmFailure.RATE_LIMITED, 500 to LlmFailure.UNAVAILABLE, 503 to LlmFailure.UNAVAILABLE,
                    302 to LlmFailure.INCOMPATIBLE)
                for ((status, code) in cases) {
                    endpoint.enqueue(FakeOpenAiEndpoint.Reply("""{"error":"private-key"}""".toByteArray(), status,
                        mapOf("Location" to endpoint.baseUrl + "/redirect", "Retry-After" to "999999")))
                    val failed = failure(provider.complete(request()), code)
                    assertEquals(status, failed.httpStatus)
                    assertFalse(failed.toString().contains("private-key"))
                    if (status == 429) assertEquals(300, failed.retryAfterSeconds)
                }
                assertEquals(cases.size, endpoint.received.size)
                assertTrue(endpoint.received.all { it.path == "/v1/chat/completions" })
            }
        }
    }

    @Test fun refusalIncompleteToolCallsAndInvalidJsonRemainDataFailures() {
        FakeOpenAiEndpoint().use { endpoint ->
            OpenAiCompatibleProvider(ProviderSettings(enabled = true, baseUrl = endpoint.baseUrl, model = "test-model")) { null }.use { provider ->
                val valid = FakeOpenAiEndpoint.success()
                val cases = listOf(
                    FakeOpenAiEndpoint.success(finish = "length") to LlmFailure.INCOMPLETE,
                    FakeOpenAiEndpoint.success(finish = "content_filter") to LlmFailure.REFUSED,
                    FakeOpenAiEndpoint.success(finish = "tool_calls") to LlmFailure.INCOMPATIBLE,
                    valid.replace("\"role\":\"assistant\"", "\"role\":\"assistant\",\"refusal\":\"no\"") to LlmFailure.REFUSED,
                    valid.replace("\"role\":\"assistant\"", "\"role\":\"assistant\",\"tool_calls\":[{}]") to LlmFailure.INCOMPATIBLE,
                    valid.dropLast(2) to LlmFailure.INVALID_OUTPUT,
                    valid.replace("\"index\":0", "\"index\":0,\"index\":1") to LlmFailure.INVALID_OUTPUT,
                    valid.replace("\"prompt_tokens\":12", "\"prompt_tokens\":12.5") to LlmFailure.INVALID_OUTPUT,
                    FakeOpenAiEndpoint.success("not JSON") to LlmFailure.INVALID_OUTPUT,
                    FakeOpenAiEndpoint.success("""{"decision":"WAIT","decision":"CANCEL"}""") to LlmFailure.INVALID_OUTPUT,
                    FakeOpenAiEndpoint.success("""{"summary":"${"x".repeat(16_385)}"}""") to LlmFailure.RESPONSE_TOO_LARGE,
                )
                for ((body, code) in cases) {
                    endpoint.enqueue(FakeOpenAiEndpoint.Reply(body.toByteArray()))
                    failure(provider.complete(request()), code)
                    assertEquals(0, provider.activeRequests())
                }
                assertEquals(cases.size, endpoint.received.size)
            }
        }
    }

    @Test fun byteOverflowInvalidUtf8AndTruncatedHttpReleaseTheSlot() {
        FakeOpenAiEndpoint().use { endpoint ->
            val settings = ProviderSettings(enabled = true, baseUrl = endpoint.baseUrl, model = "test-model", maxResponseBytes = 1024)
            OpenAiCompatibleProvider(settings) { null }.use { provider ->
                endpoint.enqueue(FakeOpenAiEndpoint.Reply(ByteArray(1025) { 'x'.code.toByte() }))
                failure(provider.complete(request()), LlmFailure.RESPONSE_TOO_LARGE)
                endpoint.enqueue(FakeOpenAiEndpoint.Reply(byteArrayOf(0xC3.toByte(), 0x28)))
                failure(provider.complete(request()), LlmFailure.INVALID_OUTPUT)
                endpoint.enqueue(FakeOpenAiEndpoint.Reply(truncated = true))
                failure(provider.complete(request()), LlmFailure.UNAVAILABLE)
                endpoint.enqueue()
                assertTrue(result(provider.complete(request())) is LlmResponse.Candidate)
                assertEquals(0, provider.activeRequests())
            }
        }
    }

    @Test fun deadlinesCoverHeadersAndNeverEndingChunkedBody() {
        FakeOpenAiEndpoint().use { endpoint ->
            val settings = ProviderSettings(enabled = true, baseUrl = endpoint.baseUrl, model = "test-model", requestTimeoutSeconds = 1)
            OpenAiCompatibleProvider(settings) { null }.use { provider ->
                for (duringBody in listOf(false, true)) {
                    val gate = CountDownLatch(1)
                    endpoint.enqueue(if (duringBody) FakeOpenAiEndpoint.Reply(waitDuringBody = gate)
                        else FakeOpenAiEndpoint.Reply(waitBeforeHeaders = gate))
                    val start = System.nanoTime()
                    failure(provider.complete(request()), LlmFailure.TIMEOUT)
                    assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(3))
                    assertEquals(0, provider.activeRequests())
                    gate.countDown()
                }
                endpoint.enqueue()
                assertTrue(result(provider.complete(request())) is LlmResponse.Candidate)
            }
        }
    }

    @Test fun cancellationCloseAndCapacityAreBoundedAndLateResponsesCannotReplaceResults() {
        FakeOpenAiEndpoint().use { endpoint ->
            val gate = CountDownLatch(1)
            repeat(2) { endpoint.enqueue(FakeOpenAiEndpoint.Reply(waitBeforeHeaders = gate)) }
            val provider = OpenAiCompatibleProvider(ProviderSettings(enabled = true, baseUrl = endpoint.baseUrl, model = "test-model")) { null }
            provider.use {
                val first = provider.complete(request())
                val second = provider.complete(request())
                assertTrue(endpoint.arrivals.tryAcquire(2, 3, TimeUnit.SECONDS))
                failure(provider.complete(request()), LlmFailure.BUSY)
                assertTrue(first.cancel())
                assertFalse(first.cancel())
                failure(first, LlmFailure.CANCELLED)
                provider.close()
                failure(second, LlmFailure.CANCELLED)
                failure(provider.complete(request()), LlmFailure.CANCELLED)
                gate.countDown()
                assertEquals(0, provider.activeRequests())
                failure(first, LlmFailure.CANCELLED)
                assertEquals(2, endpoint.received.size)
            }
        }
    }
}
