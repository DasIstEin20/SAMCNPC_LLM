package io.samcnpc.llm.evaluation

import com.google.gson.*
import io.samcnpc.llm.api.*
import io.samcnpc.llm.config.ResponseFormat
import io.samcnpc.llm.decision.*
import io.samcnpc.llm.expression.*
import io.samcnpc.llm.provider.*
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

internal object RealCorpusAttempt {
    data class Result(val record: JsonObject, val repairCode: String?)
    fun run(row: JsonObject, format: ResponseFormat, feedback: String?, provider: LlmProvider): Result {
        val arm = row.getAsJsonObject("arms").getAsJsonObject(format.name)
        val request = FrozenCorpusArchive.request(arm, feedback)
        val settings = CorpusRequestArchive.settings(format)
        val wire = ChatCompletionCodec.encode(request, settings)
        val upper = checkNotNull(settings.inference.profile(settings).upperBound(settings, wire.bytes.size))
        check(wire.bytes.size <= settings.maxContextBytes && upper <= settings.inference.inputTokens)
        val state = LlmJson.parse(request.contextJson, 65536)
        val schema = JsonParser.parseString(request.responseSchemaJson).asJsonObject
        val record = JsonObject()
        record.addProperty("requestId", request.requestId.toString())
        record.addProperty("wireSha256", CorpusFixtures.sha(wire.bytes))
        record.addProperty("feedback", feedback)
        record.addProperty("stateBytes", wire.stateBytes)
        record.addProperty("systemPromptBytes", wire.systemPromptBytes)
        record.addProperty("contractBytes", wire.contractBytes)
        record.addProperty("responseSchemaBytes", wire.responseSchemaBytes)
        record.addProperty("httpBytes", wire.bytes.size)
        record.addProperty("inputTokenUpperBound", upper)
        val started = System.nanoTime()
        val call = provider.complete(request)
        val response = call.result.toCompletableFuture().get(settings.requestTimeoutSeconds.toLong() + 5, TimeUnit.SECONDS)
        record.addProperty("latencyMillis", (System.nanoTime() - started) / 1_000_000.0)
        record.addProperty("submission", call.submission.name)
        var decision: LlmDecision? = null
        var problem: String? = null
        var repairCode: String? = null
        record.add("syntaxValid", JsonNull.INSTANCE)
        record.add("usage", JsonNull.INSTANCE)
        when (response) {
            is LlmResponse.Failed -> {
                check(response.requestId == request.requestId)
                problem = "PROVIDER_" + response.code.name
                record.addProperty("providerFailure", response.code.name)
                record.addProperty("httpStatus", response.httpStatus)
                if (response.code == LlmFailure.INVALID_OUTPUT) repairCode = problem
            }
            is LlmResponse.Candidate -> {
                check(response.requestId == request.requestId)
                record.addProperty("content", response.decisionJson)
                record.add("usage", Gson().toJsonTree(response.usage))
                record.addProperty("syntaxValid", syntax(response.decisionJson, format))
                val decoded = if (format == ResponseFormat.SAM_EXPRESSION_V1)
                    SamExpressionDecoder.decode(response.decisionJson, UUID.fromString(state["contextId"].asString), false)
                else DecisionDecoder.decode(response.decisionJson)
                when (decoded) {
                    is DecisionDecodeResult.Rejected -> { problem = decoded.code; repairCode = problem }
                    is DecisionDecodeResult.Accepted -> {
                        decision = decoded.value
                        problem = CorpusLanguagePolicy.problem(decoded.value, state, schema)
                        repairCode = problem
                        record.addProperty("decision", decoded.value.kind.name)
                        record.add("typedDecision", Gson().toJsonTree(decoded.value))
                    }
                }
                val usage = response.usage
                if (usage?.promptTokens != null && usage.promptTokens > upper || usage?.completionTokens != null && usage.completionTokens > settings.maxOutputTokens) {
                    problem = "PROFILE_USAGE_BOUND_VIOLATED"
                    repairCode = null
                }
            }
        }
        record.addProperty("problem", problem)
        record.add("score", CorpusSemanticScore.score(row.getAsJsonObject("case"), decision, state, problem))
        return Result(record, repairCode)
    }
    private fun syntax(source: String, format: ResponseFormat): Boolean = try {
        if (format == ResponseFormat.SAM_EXPRESSION_V1) SamExpressionSyntax.parse(source) else LlmJson.parse(source, 16384)
        true
    } catch (_: IllegalArgumentException) { false }
      catch (_: IOException) { false }
}
