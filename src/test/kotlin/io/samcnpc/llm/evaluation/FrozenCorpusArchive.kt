package io.samcnpc.llm.evaluation

import com.google.gson.*
import io.samcnpc.llm.api.LlmRequest
import io.samcnpc.llm.config.ResponseFormat
import io.samcnpc.llm.decision.DecisionPrompt
import io.samcnpc.llm.expression.SamExpressionPrompt
import io.samcnpc.llm.provider.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

internal class FrozenCorpusArchive(directory: Path) {
    val manifest: JsonObject = read(directory.resolve("manifest.json"))
    val rows: List<JsonObject>
    init {
        check(manifest["cases"].asInt == 92)
        check(manifest["baselineSha256"].asString == CorpusFixtures.BASELINE_HASH)
        check(manifest["expansionSha256"].asString == CorpusFixtures.EXPANSION_HASH)
        check(manifest["jsonPromptSha256"].asString == CorpusFixtures.sha(LlmJson.utf8(DecisionPrompt.text)))
        check(manifest["expressionPromptSha256"].asString == CorpusFixtures.sha(LlmJson.utf8(SamExpressionPrompt.text)))
        rows = manifest.getAsJsonArray("files").mapIndexed { index, element ->
            val file = element.asJsonObject
            check(file["file"].asString == "%03d.json".format(index))
            val path = directory.resolve(file["file"].asString)
            check(Files.size(path) <= 262144)
            val bytes = Files.readAllBytes(path)
            check(CorpusFixtures.sha(bytes) == file["sha256"].asString)
            val row = JsonParser.parseString(LlmJson.decode(bytes)).asJsonObject
            check(row.getAsJsonObject("case") == CorpusFixtures.rows[index]) { "Archived oracle differs from frozen source at index $index" }
            val states = CorpusRequestArchive.formats.map { format ->
                val arm = row.getAsJsonObject("arms").getAsJsonObject(format.name)
                val request = request(arm)
                check(CorpusFixtures.sha(ChatCompletionCodec.request(request, CorpusRequestArchive.settings(format))) == arm["wireSha256"].asString)
                val state = LlmJson.parse(request.contextJson, 65536)
                check(state.getAsJsonObject("goal")["text"].asString == row.getAsJsonObject("case")["goal"].asString)
                check(CorpusFixtures.sha(LlmJson.utf8(request.contextJson)) == row["snapshotSha256"].asString)
                request.contextJson
            }
            check(states.distinct().size == 1)
            row
        }
        check(rows.size == 92)
    }
    companion object {
        fun request(arm: JsonObject, feedback: String? = null): LlmRequest {
            val prompt = arm["systemPrompt"].asString + if (feedback == null) "" else
                "\nThe previous candidate was rejected with code $feedback. Produce a corrected decision using the current STATE and output contract."
            return LlmRequest(UUID.randomUUID(), prompt, arm["contextJson"].asString, arm["responseSchemaJson"].asString)
        }
        private fun read(path: Path): JsonObject {
            check(Files.size(path) <= 262144)
            return JsonParser.parseString(Files.readString(path)).asJsonObject
        }
    }
}
