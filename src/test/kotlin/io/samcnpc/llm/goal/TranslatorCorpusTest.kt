package io.samcnpc.llm.goal

import com.google.gson.JsonNull
import com.google.gson.JsonObject
import io.samcnpc.behavior.api.*
import io.samcnpc.llm.api.*
import io.samcnpc.llm.config.ProviderSettings
import io.samcnpc.llm.context.ContextPolicy
import io.samcnpc.llm.decision.*
import io.samcnpc.llm.provider.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Fixed candidate corpus tests wire/schema/policy behavior. It measures no actual model understanding. */
class TranslatorCorpusTest {
    private val policy = ContextPolicy(1, OperationType.entries.toSet(), emptySet(), emptySet(), 72000, 16, 0)
    private fun corpus() = checkNotNull(javaClass.getResourceAsStream("/translator-corpus.json")).use {
        LlmJson.parse(it.readBytes().toString(Charsets.UTF_8), 65536)
    }

    @Test fun allPublishedFamiliesPassScriptedHttpDecodeAndPurePolicyValidation() {
        val corpus = corpus()
        assertEquals("SCRIPTED_WIRE_CONTRACT_NOT_MODEL_QUALITY", corpus["scope"].asString)
        val rows = corpus["cases"].asJsonArray.deepCopy()
        assertEquals(16, rows.size())
        val extension=checkNotNull(javaClass.getResourceAsStream("/evaluation/v16/field-extension.json")).use { LlmJson.parse(it.readBytes().toString(Charsets.UTF_8),131072) }
        val field=extension["cases"].asJsonArray[0].asJsonObject
        rows.add(JsonObject().also { it.add("operation",field["expectedOperation"]);it.add("goal",field["goal"]) })
        assertEquals(17,rows.size())
        val families = mutableSetOf<OperationType>()
        FakeOpenAiEndpoint().use { endpoint ->
            val settings = ProviderSettings(enabled = true, baseUrl = endpoint.baseUrl, model = "corpus-emulator",
                apiKeyEnvironment = "", requestTimeoutSeconds = 3)
            OpenAiCompatibleProvider(settings).use { provider ->
                for (row in rows.map { it.asJsonObject }) {
                    val contextId = UUID.randomUUID()
                    val candidate = envelope(contextId, "ASSIGN")
                    candidate.add("operation", row["operation"])
                    val decision = exchange(provider, endpoint, contextId, row["goal"].asString, candidate)
                    assertTrue(decision.action is DecisionAction.Assign)
                    val order = (decision.action as DecisionAction.Assign).order
                    assertEquals(row["operation"].asJsonObject["type"].asString, order.type.operationId)
                    assertNull(DecisionPolicy.orderProblem(order, policy, "minecraft:overworld"), order.type.name)
                    assertEquals("DIMENSION_MISMATCH", DecisionPolicy.orderProblem(order, policy, "minecraft:the_nether"))
                    families.add(order.type)
                }
            }
            assertEquals(17, endpoint.received.size)
        }
        assertEquals(OperationType.entries.toSet(), families)
    }

    @Test fun everyAmbiguousCorpusGoalHasOneExplicitQuestionAndNoExecutablePayload() {
        FakeOpenAiEndpoint().use { endpoint ->
            OpenAiCompatibleProvider(ProviderSettings(enabled = true, baseUrl = endpoint.baseUrl,
                model = "corpus-emulator", apiKeyEnvironment = "", requestTimeoutSeconds = 3)).use { provider ->
                for (row in corpus()["cases"].asJsonArray.map { it.asJsonObject }) {
                    val contextId = UUID.randomUUID()
                    val candidate = envelope(contextId, "ASK_USER")
                    candidate.addProperty("question", row["question"].asString)
                    val decision = exchange(provider, endpoint, contextId, row["ambiguousGoal"].asString, candidate)
                    assertTrue(decision.action is DecisionAction.AskUser)
                    val record = GoalRecord(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1,
                        row["ambiguousGoal"].asString)
                    val outcome = TranslatorOutcomes.admitted(record, decision, DecisionOutcome(DecisionOutcomeState.NO_EFFECT, "ASK_USER"))
                    assertEquals(GoalPhase.ASK_USER, outcome.phase)
                    assertNull(outcome.task)
                    assertEquals(row["question"].asString, outcome.question)
                }
            }
            assertEquals(16, endpoint.received.size)
        }
    }

    private fun exchange(provider: LlmProvider, endpoint: FakeOpenAiEndpoint, contextId: UUID,
                         goal: String, candidate: JsonObject): LlmDecision {
        assertTrue(GoalRecord.validText(goal, 1024))
        endpoint.enqueue(FakeOpenAiEndpoint.Reply(body = LlmJson.utf8(FakeOpenAiEndpoint.success(candidate.toString()))))
        val fixture = JsonObject()
        fixture.addProperty("fixtureScope", "WIRE_CONTRACT_ONLY_NO_WORLD_ADMISSION")
        fixture.addProperty("contextId", contextId.toString()); fixture.addProperty("goal", goal)
        val response = provider.complete(LlmRequest(UUID.randomUUID(), DecisionPrompt.text,
            fixture.toString(), DecisionSchema.forContext(contextId, policy))).result.toCompletableFuture().get(5, TimeUnit.SECONDS)
        assertTrue(response is LlmResponse.Candidate, response.toString())
        val decoded = DecisionDecoder.decode((response as LlmResponse.Candidate).decisionJson)
        assertTrue(decoded is DecisionDecodeResult.Accepted, decoded.toString())
        val value = (decoded as DecisionDecodeResult.Accepted).value
        assertEquals(contextId, value.contextId)
        return value
    }

    private fun envelope(id: UUID, kind: String): JsonObject = JsonObject().also { value ->
        value.addProperty("schemaVersion", 1); value.addProperty("contextId", id.toString())
        value.addProperty("decision", kind); value.addProperty("summary", "Fixed protocol fixture, not a model evaluation")
        for (name in listOf("operation", "change", "question", "wait")) value.add(name, JsonNull.INSTANCE)
    }
}
