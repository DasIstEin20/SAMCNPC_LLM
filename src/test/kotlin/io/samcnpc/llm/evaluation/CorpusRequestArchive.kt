package io.samcnpc.llm.evaluation

import com.google.gson.*
import io.samcnpc.llm.api.LlmRequest
import io.samcnpc.llm.config.*
import io.samcnpc.llm.context.*
import io.samcnpc.llm.decision.DecisionPrompt
import io.samcnpc.llm.expression.SamExpressionPrompt
import io.samcnpc.llm.provider.*
import io.samcnpc.llm.scheduling.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** Detached preparation/archiving only. Neither this capture phase nor its source fixtures call a provider. */
internal object CorpusRequestArchive {
    val formats = listOf(ResponseFormat.JSON_SCHEMA, ResponseFormat.SAM_EXPRESSION_V1)
    fun settings(format: ResponseFormat) = ProviderSettings(enabled = true, baseUrl = "http://127.0.0.1:18762/v1",
        model = "qwen3.5-4b", apiKeyEnvironment = "", requestTimeoutSeconds = 120, responseFormat = format,
        inference = InferenceSettings(true, "http://127.0.0.1:18762/v1", "qwen3.5-4b",
            "LMStudio-0.4.25+1/llama.cpp-cuda12-2.41.0",
            "25082a7dd3776cc3c741c6347d3bd04523f05796607b3fbc32fa3a25dfa1418c",
            "0a72b5cec6d6e4824ee888d38a4a362b6623653d3b5757fa79e5fe62e2f153f0",
            "e175bc0107b113da24d85536756d0fd6254ba95a1a685ebfeaba25b06fc97155", 2048, 65536, 63488))

    fun save(directory: Path, index: Int, row: JsonObject, setup: JsonObject, captured: CapturedContext): JsonObject {
        val pairs = formats.associateWith { format ->
            val config = settings(format)
            InferenceInput(UUID.randomUUID(), captured, config, config.inference.profile(config), config.inference.allocation(config))
        }
        var selected: Map<ResponseFormat, RequestPreparation.Ready>? = null
        for (level in 0..NpcContextEncoder.MAX_DETAIL_LEVEL) {
            val values = pairs.mapValues { InferenceRequestPreparation.prepare(it.value, level..level) }
            if (values.values.all { it is RequestPreparation.Ready }) {
                selected = values.mapValues { it.value as RequestPreparation.Ready }
                break
            }
        }
        val prepared = checkNotNull(selected) { "Frozen fixture does not fit either protocol: " + row["id"].asString }
        check(prepared.values.map { it.request.contextJson }.distinct().size == 1)
        val record = JsonObject()
        record.add("case", row.deepCopy())
        record.add("fixture", setup.deepCopy())
        record.addProperty("scope", "DETACHED_LANGUAGE_EVALUATION_NO_WORLD_ADMISSION")
        record.addProperty("snapshotSha256", CorpusFixtures.sha(LlmJson.utf8(prepared.values.first().request.contextJson)))
        record.addProperty("textSurfaceLimit", "Public visual API omits chest custom names; name-only injection resistance is not measured by unseen text.")
        val arms = JsonObject()
        for ((format, ready) in prepared) {
            val arm = JsonObject()
            val request = ready.request
            arm.addProperty("requestId", request.requestId.toString())
            arm.addProperty("systemPrompt", request.systemPrompt)
            arm.addProperty("contextJson", request.contextJson)
            arm.addProperty("responseSchemaJson", request.responseSchemaJson)
            arm.add("metrics", Gson().toJsonTree(ready.metrics))
            arm.addProperty("wireSha256", CorpusFixtures.sha(ChatCompletionCodec.request(request, settings(format))))
            arms.add(format.name, arm)
        }
        record.add("arms", arms)
        val filename = "%03d.json".format(index)
        val encoded = GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(record) + "\n"
        check(!Files.exists(directory.resolve(filename)))
        Files.writeString(directory.resolve(filename), encoded)
        return JsonObject().also {
            it.addProperty("file", filename); it.addProperty("id", row["id"].asString)
            it.addProperty("sha256", CorpusFixtures.sha(LlmJson.utf8(encoded)))
        }
    }

    fun manifest(files: List<JsonObject>): JsonObject = JsonObject().also {
        it.addProperty("status", "FROZEN_NATIVE_CAPTURES_NO_INFERENCE")
        it.addProperty("baselineSha256", CorpusFixtures.BASELINE_HASH)
        it.addProperty("expansionSha256", CorpusFixtures.EXPANSION_HASH)
        it.addProperty("jsonPromptSha256", CorpusFixtures.sha(LlmJson.utf8(DecisionPrompt.text)))
        it.addProperty("expressionPromptSha256", CorpusFixtures.sha(LlmJson.utf8(SamExpressionPrompt.text)))
        it.addProperty("cases", files.size)
        it.addProperty("holdoutExposure", "Development regression corpus, not a blind holdout. Authored unblinded; Prompt15 preserves Prompt13 retrieval/Planner rules after Prompt14 regressed physical retrieval. Prompt13 uses error classes from the completed Prompt11 campaign. Frozen goals and scoring oracles are unchanged.")
        it.add("files", JsonArray().also { array -> files.forEach(array::add) })
    }
}
