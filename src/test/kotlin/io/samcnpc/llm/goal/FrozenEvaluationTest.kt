package io.samcnpc.llm.goal

import com.google.gson.*
import io.samcnpc.behavior.api.*
import io.samcnpc.llm.api.*
import io.samcnpc.llm.config.ProviderSettings
import io.samcnpc.llm.context.ContextPolicy
import io.samcnpc.llm.decision.*
import io.samcnpc.llm.provider.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Immutable evaluation inputs and scripted transport coverage; never a real language-quality score. */
class FrozenEvaluationTest {
    private val policy = ContextPolicy(1, OperationType.entries.toSet(), emptySet(), emptySet(), 72000, 16, 0)
    private fun bytes(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream((if (name == "profile.json") "/evaluation/v2/" else "/evaluation/v1/") + name)).use { it.readBytes() }
    private fun json(name: String): JsonObject = LlmJson.parse(bytes(name).toString(Charsets.UTF_8), 131072)
    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    @Test fun frozenInputsCoverBothLanguagesEveryFamilyAndDistinctAdversarialClasses() {
        val corpus = json("corpus.json")
        val profile = json("profile.json")
        assertEquals(profile["corpusSha256"].asString, sha(bytes("corpus.json")))
        assertEquals(profile["promptSha256"].asString, sha(DecisionPrompt.text.toByteArray(Charsets.UTF_8)))
        assertEquals(DecisionPrompt.VERSION, profile["promptVersion"].asInt)
        assertEquals("PENDING_FULL_MODEL_EVALUATION", profile["realModelStatus"].asString)
        val oldProfile = checkNotNull(javaClass.getResourceAsStream("/evaluation/v1/profile.json")).use { it.readBytes() }
        assertEquals(profile["previousProfileSha256"].asString, sha(oldProfile))
        assertEquals("SCRIPTED_WIRE_CONTRACT_NOT_MODEL_QUALITY", corpus["scope"].asString)
        val rows = corpus["cases"].asJsonArray.map { it.asJsonObject }
        assertEquals(56, rows.size)
        assertEquals(56, rows.map { it["id"].asString }.toSet().size)
        for (family in OperationType.entries) {
            val group = rows.filter { !it["family"].isJsonNull && it["family"].asString == family.operationId }
            assertEquals(setOf("pl", "en"), group.filter { it["kind"].asString == "complete" }.map { it["language"].asString }.toSet())
            assertEquals(1, group.count { it["kind"].asString == "ambiguous" })
        }
        assertEquals(setOf("out_of_scope", "contradictory", "false_authority", "world_injection", "memory_injection"),
            rows.filter { it["family"].isJsonNull }.map { it["kind"].asString }.toSet())
        for (row in rows) {
            assertTrue(GoalRecord.validText(row["goal"].asString, 1024))
            if (row["expectedDecision"].asString == "ASK_USER") {
                assertTrue(row["expectedOperation"].isJsonNull)
                assertTrue(GoalRecord.validText(row["question"].asString, 512))
            }
        }
    }

    @Test fun allFrozenCandidatesPassRepeatedHttpSchemaPolicyAndNoEffectQuestionContracts() {
        val corpus = json("corpus.json")
        val profile = json("profile.json")
        val repetitions = profile["repetitions"].asInt
        assertEquals(3, repetitions)
        var assignments = 0
        var questions = 0
        FakeOpenAiEndpoint().use { endpoint ->
            val settings = ProviderSettings(enabled = true, baseUrl = endpoint.baseUrl,
                model = profile["model"].asString, apiKeyEnvironment = "", requestTimeoutSeconds = 3)
            OpenAiCompatibleProvider(settings).use { provider ->
                repeat(repetitions) {
                    for (row in corpus["cases"].asJsonArray.map { it.asJsonObject }) {
                        val contextId = UUID.randomUUID()
                        val candidate = JsonObject()
                        candidate.addProperty("schemaVersion", 1)
                        candidate.addProperty("contextId", contextId.toString())
                        candidate.addProperty("decision", row["expectedDecision"].asString)
                        candidate.addProperty("summary", "Scripted fixture; no model-quality claim")
                        for (field in listOf("operation", "change", "question", "wait")) candidate.add(field, JsonNull.INSTANCE)
                        candidate.add("operation", row["expectedOperation"].deepCopy())
                        candidate.add("question", row["question"].deepCopy())
                        endpoint.enqueue(FakeOpenAiEndpoint.Reply(body = LlmJson.utf8(FakeOpenAiEndpoint.success(candidate.toString()))))
                        val state = JsonObject()
                        state.addProperty("fixtureScope", "WIRE_CONTRACT_ONLY_NO_WORLD_ADMISSION")
                        state.addProperty("contextId", contextId.toString())
                        state.addProperty("goal", row["goal"].asString)
                        state.add("untrustedWorldText", row["worldText"].deepCopy())
                        val response = provider.complete(LlmRequest(UUID.randomUUID(), DecisionPrompt.text,
                            state.toString(), DecisionSchema.forContext(contextId, policy)))
                            .result.toCompletableFuture().get(5, TimeUnit.SECONDS)
                        assertTrue(response is LlmResponse.Candidate, row["id"].asString + ": " + response)
                        val decoded = DecisionDecoder.decode((response as LlmResponse.Candidate).decisionJson)
                        assertTrue(decoded is DecisionDecodeResult.Accepted, row["id"].asString + ": " + decoded)
                        val decision = (decoded as DecisionDecodeResult.Accepted).value
                        assertEquals(contextId, decision.contextId)
                        if (row["expectedDecision"].asString == "ASSIGN") {
                            val action = decision.action as DecisionAction.Assign
                            assertEquals(row["family"].asString, action.order.type.operationId)
                            assertNull(DecisionPolicy.orderProblem(action.order, policy, "minecraft:overworld"))
                            assignments++
                        } else {
                            assertTrue(decision.action is DecisionAction.AskUser)
                            val record = GoalRecord(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, row["goal"].asString)
                            val result = TranslatorOutcomes.admitted(record, decision,
                                DecisionOutcome(DecisionOutcomeState.NO_EFFECT, "ASK_USER"))
                            assertEquals(GoalPhase.ASK_USER, result.phase)
                            assertNull(result.task)
                            assertEquals(row["question"].asString, result.question)
                            questions++
                        }
                    }
                }
            }
            assertEquals(168, endpoint.received.size)
        }
        assertEquals(96, assignments)
        assertEquals(72, questions)
        val report = JsonObject()
        report.addProperty("status", "PASS_SCRIPTED_CONTRACT_ONLY")
        report.addProperty("corpusSha256", profile["corpusSha256"].asString)
        report.addProperty("promptSha256", profile["promptSha256"].asString)
        report.addProperty("cases", 56)
        report.addProperty("repetitions", repetitions)
        report.addProperty("httpCandidates", assignments + questions)
        report.addProperty("jsonAccepted", assignments + questions)
        report.addProperty("typedAssignments", assignments)
        report.addProperty("questionsWithoutExecutablePayload", questions)
        report.addProperty("operationSelectionByModel", "NOT_MEASURED")
        report.addProperty("liveUnauthorizedEffects", "NOT_MEASURED_HERE_SEE_NATIVE_ADMISSION_EVIDENCE")
        report.addProperty("physicalSuccess", "NOT_MEASURED_HERE_SEE_NATIVE_RUNTIME_EVIDENCE")
        report.addProperty("realModel", "PENDING_FULL_MODEL_EVALUATION")
        val directory = Path.of("build/evaluation")
        Files.createDirectories(directory)
        Files.writeString(directory.resolve("scripted-v2.json"), GsonBuilder().setPrettyPrinting().create().toJson(report) + "\n")
    }
}

