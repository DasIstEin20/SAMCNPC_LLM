package io.samcnpc.llm.mission

import com.google.gson.*
import io.samcnpc.llm.api.*
import io.samcnpc.llm.config.*
import io.samcnpc.llm.provider.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Opt-in language measurement using the production schema, transport and strict mission decoder. */
@EnabledIfSystemProperty(named = "samcnpc.realMissionCorpus", matches = "true")
@Timeout(value = 30, unit = TimeUnit.MINUTES)
class RealMissionCorpusTest {
    @Test fun measureFrozenCoalRequirementsWithoutWorldAdmissionOrOracleFeedback() {
        val output = Path.of(System.getProperty("samcnpc.realMissionOutput"))
        check(!Files.exists(output)); Files.createDirectories(output)
        val settings = ProviderSettings(enabled = true, baseUrl = "http://127.0.0.1:18762/v1", model = "qwen3.5-4b",
            apiKeyEnvironment = "", requestTimeoutSeconds = 120, temperature = 0.1, maxOutputTokens = 4096,
            inference = InferenceSettings(contextWindow = 65536, inputTokens = 61440))
        val report = JsonObject()
        report.addProperty("scope", "DETACHED_REQUIREMENTS_ONLY_NO_WORLD_ADMISSION_OR_REPAIR")
        report.addProperty("startedUtc", Instant.now().toString())
        fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        report.addProperty("corpusSha256", sha(CoalRequirementCorpus.bytes))
        report.addProperty("promptSha256", sha(LlmJson.utf8(MissionProtocol.requirementsPrompt)))
        report.addProperty("seed", "UNSPECIFIED_BACKEND_DEFAULT")
        report.add("settings", Gson().toJsonTree(settings))
        val rows = JsonArray()
        OpenAiCompatibleProvider(settings).use { provider ->
            for (repetition in 1..3) for (row in CoalRequirementCorpus.rows()) {
                check(!Files.exists(output.resolve("stop")))
                val contextId = UUID.nameUUIDFromBytes(LlmJson.utf8("mission-coal-${row["id"].asString}-$repetition"))
                val state = JsonObject()
                state.addProperty("contextId", contextId.toString()); state.addProperty("USER_GOAL", row["goal"].asString)
                state.addProperty("dimension", "minecraft:overworld")
                state.add("npcFeetPosition", JsonParser.parseString("[0.5,64,0.5]"))
                state.add("MISSION", MissionProjection.encode(MissionState(), null))
                state.add("availableOperations", JsonArray().also { array ->
                    io.samcnpc.behavior.api.OperationType.entries.sortedBy { it.operationId }.forEach { array.add(it.operationId) }
                })
                state.add("constraints", JsonNull.INSTANCE); state.add("aliases", JsonArray())
                val request = LlmRequest(contextId, MissionProtocol.requirementsPrompt, state.toString(),
                    MissionProtocol.schema(contextId, MissionState(), "minecraft:overworld"))
                val wire = ChatCompletionCodec.request(request, settings)
                check(wire.size <= 65536 && wire.size + 2048 <= 61440)
                val name = "r$repetition-${row["id"].asString}"
                Files.write(output.resolve("$name-request.json"), wire)
                val started = System.nanoTime()
                val response = provider.complete(request).result.toCompletableFuture().get(125, TimeUnit.SECONDS)
                val result = JsonObject()
                result.addProperty("id", row["id"].asString); result.addProperty("repetition", repetition)
                result.addProperty("seconds", (System.nanoTime() - started) / 1e9)
                result.addProperty("requestSha256", sha(wire)); result.addProperty("requestBytes", wire.size)
                result.addProperty("requirementsPreserved", false)
                when (response) {
                    is LlmResponse.Failed -> result.addProperty("transportFailure", response.code.name)
                    is LlmResponse.Candidate -> {
                        result.addProperty("candidate", response.decisionJson); result.add("usage", Gson().toJsonTree(response.usage))
                        when (val decoded = MissionProtocol.decode(response.decisionJson, contextId, MissionState(), row["goal"].asString)) {
                            is MissionDecodeResult.Rejected -> result.addProperty("decodeFailure", decoded.code)
                            is MissionDecodeResult.Accepted -> {
                                result.addProperty("decoded", true)
                                val reply = decoded.reply
                                result.addProperty("unnecessaryQuestion", reply is MissionReply.Question)
                                if (reply is MissionReply.Requirements)
                                    result.addProperty("requirementsPreserved", CoalRequirementCorpus.score(row, reply.contract))
                            }
                        }
                    }
                }
                rows.add(result)
                Files.writeString(output.resolve("$name-result.json"), GsonBuilder().setPrettyPrinting().create().toJson(result))
                Files.writeString(output.resolve("progress.txt"), "${Instant.now()} measured=${rows.size()}/12\n")
            }
        }
        report.addProperty("endedUtc", Instant.now().toString()); report.add("rows", rows)
        Files.writeString(output.resolve("campaign.json"), GsonBuilder().setPrettyPrinting().create().toJson(report))
    }
}
