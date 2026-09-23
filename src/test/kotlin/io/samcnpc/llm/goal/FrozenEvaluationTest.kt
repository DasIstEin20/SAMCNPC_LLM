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
        checkNotNull(javaClass.getResourceAsStream((if (name == "profile.json") "/evaluation/v19/" else "/evaluation/v1/") + name)).use { it.readBytes() }
    private fun json(name: String): JsonObject = LlmJson.parse(bytes(name).toString(Charsets.UTF_8), 131072)
    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun extension(): List<JsonObject> {
        val data=checkNotNull(javaClass.getResourceAsStream("/evaluation/v16/field-extension.json")).use { it.readBytes() }
        assertEquals(json("profile.json")["extensionSha256"].asString,sha(data))
        return LlmJson.parse(data.toString(Charsets.UTF_8),131072)["cases"].asJsonArray.map { it.asJsonObject }
    }

    @Test fun frozenInputsCoverBothLanguagesEveryFamilyAndDistinctAdversarialClasses() {
        val corpus = json("corpus.json")
        val profile = json("profile.json")
        assertEquals(profile["corpusSha256"].asString, sha(bytes("corpus.json")))
        assertEquals(profile["promptSha256"].asString, sha(DecisionPrompt.text.toByteArray(Charsets.UTF_8)))
        assertEquals(DecisionPrompt.VERSION, profile["promptVersion"].asInt)
        assertEquals(io.samcnpc.llm.context.NpcContextEncoder.VERSION, profile["contextVersion"].asInt)
        assertEquals(LlmGoalStore.VERSION, profile["goalStoreVersion"].asInt)
        assertEquals("PENDING_FULL_MODEL_EVALUATION", profile["realModelStatus"].asString)
        val intentPromptProfile = checkNotNull(javaClass.getResourceAsStream("/evaluation/v8/profile.json")).use { it.readBytes() }
        val itemSpansProfile = checkNotNull(javaClass.getResourceAsStream("/evaluation/v9/profile.json")).use { it.readBytes() }
        val retrievalProfile = checkNotNull(javaClass.getResourceAsStream("/evaluation/v10/profile.json")).use { it.readBytes() }
        val collectionProfile = checkNotNull(javaClass.getResourceAsStream("/evaluation/v11/profile.json")).use { it.readBytes() }
        val quantityProfile = checkNotNull(javaClass.getResourceAsStream("/evaluation/v12/profile.json")).use { it.readBytes() }
        val feetProfile = checkNotNull(javaClass.getResourceAsStream("/evaluation/v13/profile.json")).use { it.readBytes() }
        val compactProfile = checkNotNull(javaClass.getResourceAsStream("/evaluation/v14/profile.json")).use { it.readBytes() }
        val priorProfile=checkNotNull(javaClass.getResourceAsStream("/evaluation/v15/profile.json")).use { it.readBytes() }
        val fieldProfile=checkNotNull(javaClass.getResourceAsStream("/evaluation/v16/profile.json")).use { it.readBytes() }
        val returnProfile=checkNotNull(javaClass.getResourceAsStream("/evaluation/v17/profile.json")).use { it.readBytes() }
        val nullableProfile=checkNotNull(javaClass.getResourceAsStream("/evaluation/v18/profile.json")).use { it.readBytes() }
        assertEquals(profile["previousProfileSha256"].asString,sha(nullableProfile))
        assertEquals(LlmJson.parse(nullableProfile.toString(Charsets.UTF_8),131072)["previousProfileSha256"].asString,sha(returnProfile))
        assertEquals(LlmJson.parse(returnProfile.toString(Charsets.UTF_8),131072)["previousProfileSha256"].asString,sha(fieldProfile))
        assertEquals(LlmJson.parse(fieldProfile.toString(Charsets.UTF_8),131072)["previousProfileSha256"].asString,sha(priorProfile))
        assertEquals(LlmJson.parse(priorProfile.toString(Charsets.UTF_8),131072)["previousProfileSha256"].asString, sha(compactProfile))
        assertEquals(LlmJson.parse(compactProfile.toString(Charsets.UTF_8), 131072)["previousProfileSha256"].asString, sha(feetProfile))
        assertEquals(LlmJson.parse(feetProfile.toString(Charsets.UTF_8), 131072)["previousProfileSha256"].asString, sha(quantityProfile))
        assertEquals(LlmJson.parse(quantityProfile.toString(Charsets.UTF_8), 131072)["previousProfileSha256"].asString, sha(collectionProfile))
        assertEquals(LlmJson.parse(collectionProfile.toString(Charsets.UTF_8), 131072)["previousProfileSha256"].asString, sha(retrievalProfile))
        assertEquals(LlmJson.parse(retrievalProfile.toString(Charsets.UTF_8), 131072)["previousProfileSha256"].asString, sha(itemSpansProfile))
        assertEquals(LlmJson.parse(itemSpansProfile.toString(Charsets.UTF_8), 131072)["previousProfileSha256"].asString, sha(intentPromptProfile))
        val historyProfile = checkNotNull(javaClass.getResourceAsStream("/evaluation/v7/profile.json")).use { it.readBytes() }
        assertEquals(LlmJson.parse(intentPromptProfile.toString(Charsets.UTF_8), 131072)["previousProfileSha256"].asString, sha(historyProfile))
        val projectionProfile = checkNotNull(javaClass.getResourceAsStream("/evaluation/v6/profile.json")).use { it.readBytes() }
        assertEquals(LlmJson.parse(historyProfile.toString(Charsets.UTF_8), 131072)["previousProfileSha256"].asString, sha(projectionProfile))
        val intentProfile = checkNotNull(javaClass.getResourceAsStream("/evaluation/v5/profile.json")).use { it.readBytes() }
        assertEquals(LlmJson.parse(projectionProfile.toString(Charsets.UTF_8), 131072)["previousProfileSha256"].asString, sha(intentProfile))
        val quotaProfile = checkNotNull(javaClass.getResourceAsStream("/evaluation/v4/profile.json")).use { it.readBytes() }
        assertEquals(LlmJson.parse(intentProfile.toString(Charsets.UTF_8), 131072)["previousProfileSha256"].asString, sha(quotaProfile))
        val oldProfile = checkNotNull(javaClass.getResourceAsStream("/evaluation/v3/profile.json")).use { it.readBytes() }
        assertEquals(LlmJson.parse(quotaProfile.toString(Charsets.UTF_8), 131072)["previousProfileSha256"].asString, sha(oldProfile))
        val secondProfile = checkNotNull(javaClass.getResourceAsStream("/evaluation/v2/profile.json")).use { it.readBytes() }
        assertEquals(LlmJson.parse(oldProfile.toString(Charsets.UTF_8), 131072)["previousProfileSha256"].asString, sha(secondProfile))
        val firstProfile = checkNotNull(javaClass.getResourceAsStream("/evaluation/v1/profile.json")).use { it.readBytes() }
        assertEquals(LlmJson.parse(secondProfile.toString(Charsets.UTF_8), 131072)["previousProfileSha256"].asString, sha(firstProfile))
        assertEquals("SCRIPTED_WIRE_CONTRACT_NOT_MODEL_QUALITY", corpus["scope"].asString)
        val original=corpus["cases"].asJsonArray.map { it.asJsonObject }
        assertEquals(56,original.size)
        val rows=original+extension()
        assertEquals(59,rows.size)
        assertEquals(59,rows.map { it["id"].asString }.toSet().size)
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
                    for (row in corpus["cases"].asJsonArray.map { it.asJsonObject }+extension()) {
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
            assertEquals(177, endpoint.received.size)
        }
        assertEquals(102, assignments)
        assertEquals(75, questions)
        val report = JsonObject()
        report.addProperty("status", "PASS_SCRIPTED_CONTRACT_ONLY")
        report.addProperty("corpusSha256", profile["corpusSha256"].asString)
        report.addProperty("promptSha256", profile["promptSha256"].asString)
        report.addProperty("cases", 59)
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

