package io.samcnpc.llm.expression

import io.samcnpc.behavior.api.*
import io.samcnpc.llm.api.*
import io.samcnpc.llm.config.*
import io.samcnpc.llm.context.ContextPolicy
import io.samcnpc.llm.decision.*
import io.samcnpc.llm.provider.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.UUID
import java.util.concurrent.TimeUnit

@Timeout(20)
class SamExpressionTransportTest {
    private val id = UUID(5, 6)
    private val policy = ContextPolicy(1, OperationType.entries.toSet(), emptySet(), emptySet(), 72000, 16, 0)
    private fun request() = LlmRequest(id, SamExpressionPrompt.text, """{"contextId":"$id","goal":"Idź do (-1,64,2)"}""",
        DecisionSchema.forContext(id, policy))

    @Test fun unconstrainedWireOmitsResponseFormatPreservesHttpChecksAndNeverFallsBack() {
        FakeOpenAiEndpoint().use { endpoint ->
            val settings = ProviderSettings(enabled = true, baseUrl = endpoint.baseUrl, model = "expression-fixture",
                apiKeyEnvironment = "", requestTimeoutSeconds = 3, responseFormat = ResponseFormat.SAM_EXPRESSION_V1)
            OpenAiCompatibleProvider(settings).use { provider ->
                val content = "assign(navigate(dimension_id='minecraft:overworld',destination=(-1,64,2)))"
                endpoint.enqueue(FakeOpenAiEndpoint.Reply(body = LlmJson.utf8(FakeOpenAiEndpoint.success(content))))
                val reply = provider.complete(request()).result.toCompletableFuture().get(4, TimeUnit.SECONDS)
                assertTrue(reply is LlmResponse.Candidate, reply.toString())
                reply as LlmResponse.Candidate
                assertEquals(content, reply.decisionJson)
                assertEquals(id, reply.requestId)
                assertTrue(SamExpressionDecoder.decode(reply.decisionJson, id, false) is DecisionDecodeResult.Accepted)
                val wire = LlmJson.parse(endpoint.received.single().body, 65536)
                assertFalse(wire.has("response_format"))
                assertFalse(wire.has("tools"))
                val system = wire["messages"].asJsonArray[0].asJsonObject["content"].asString
                assertTrue(system.contains("SAM_EXPRESSION_V1_UNCONSTRAINED"))
                assertTrue(system.contains("inventory_work("))
                assertFalse(system.contains("OUTPUT_CONTRACT_JSON_SCHEMA"))
                endpoint.enqueue(FakeOpenAiEndpoint.Reply(body = LlmJson.utf8(FakeOpenAiEndpoint.success("import os"))))
                val invalid = provider.complete(request()).result.toCompletableFuture().get(4, TimeUnit.SECONDS) as LlmResponse.Candidate
                assertTrue(SamExpressionDecoder.decode(invalid.decisionJson, id, false) is DecisionDecodeResult.Rejected)
                endpoint.enqueue(FakeOpenAiEndpoint.Reply(body = LlmJson.utf8(FakeOpenAiEndpoint.success("ask_user('x')", finish = "length"))))
                val truncated = provider.complete(request()).result.toCompletableFuture().get(4, TimeUnit.SECONDS)
                assertEquals(LlmFailure.INCOMPLETE, (truncated as LlmResponse.Failed).code)
                assertEquals(3, endpoint.received.size)
            }
        }
    }

    @Test fun wholeWireMetricsIncludeExpressionContractWhileJsonDefaultIsUnchanged() {
        assertEquals(ResponseFormat.JSON_SCHEMA, ProviderSettings().responseFormat)
        val expression = ChatCompletionCodec.encode(request(), ProviderSettings(model = "test", responseFormat = ResponseFormat.SAM_EXPRESSION_V1))
        val json = ChatCompletionCodec.encode(request(), ProviderSettings(model = "test"))
        assertTrue(expression.contractBytes > 0)
        assertEquals(0, expression.responseSchemaBytes)
        assertTrue(json.responseSchemaBytes > 0)
        assertTrue(expression.bytes.size < json.bytes.size)
        assertEquals(LlmJson.utf8(SamExpressionPrompt.text).size, expression.systemPromptBytes)
        java.nio.file.Files.createDirectories(java.nio.file.Path.of("build/evaluation"))
        java.nio.file.Files.writeString(java.nio.file.Path.of("build/evaluation/expression-contract.txt"),
            SamExpressionContract.describe(LlmJson.parse(request().responseSchemaJson, 65536)))
    }
}
