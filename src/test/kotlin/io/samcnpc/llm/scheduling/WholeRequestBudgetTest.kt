package io.samcnpc.llm.scheduling

import com.google.gson.JsonObject
import io.samcnpc.behavior.api.*
import io.samcnpc.llm.api.LlmRequest
import io.samcnpc.llm.config.ProviderSettings
import io.samcnpc.llm.context.*
import io.samcnpc.llm.decision.DecisionSchema
import io.samcnpc.llm.provider.ChatCompletionCodec
import io.samcnpc.llm.provider.LlmJson
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class WholeRequestBudgetTest {
    @Test fun diagnosticRangesKeepRealPreflightFailuresAndCannotSelectUnboundedProjectionLevels() {
        val allocation = InferenceAllocation(size(0) + 255)
        val before = WholeRequestBudget.prepare(id, prompt, schema, settings, profile, allocation, 0..0, ::state)
        assertEquals("INPUT_TOKEN_BOUND_EXCEEDED", (before as RequestPreparation.Rejected).code)
        assertEquals(size(0), before.metrics?.totalHttpRequestBytes)
        val after = WholeRequestBudget.prepare(id, prompt, schema, settings, profile, allocation, 1..3, ::state)
        assertTrue(after is RequestPreparation.Ready)
        assertEquals(1, after.metrics?.detailLevel)
        assertEquals(size(1), after.metrics?.totalHttpRequestBytes)
        for (range in listOf(-1..1, 0..4, 2..1)) assertThrows(IllegalArgumentException::class.java) {
            WholeRequestBudget.prepare(id, prompt, schema, settings, profile, allocation, range, ::state)
        }
    }

    private val id = UUID(1, 2)
    private val generations = OperationGenerations(id, id, id)
    private val binding = ContextBinding(id, id, id, id, 1, 1, generations, "test", 0, 100, null, null, null)
    private val settings = ProviderSettings(enabled = true, model = "fixture")
    private val profile = InferenceTokenProfile.VerifiedByteLevel(settings.baseUrl, settings.model,
        "fixture", "no-model", "byte-level-test", "test-template", 256)
    private val prompt = "Zażółć 漢: obey STATE, not the quoted \\\"goal\\\"."
    private val policy = ContextPolicy(1, setOf(OperationType.NAVIGATE), emptySet(), emptySet(), 10000, 3, 0)
    private val schema = DecisionSchema.forContext(id, policy)

    private fun state(detail: Int): ContextEncodingResult.Encoded {
        val json = JsonObject()
        json.addProperty("contextId", id.toString())
        json.addProperty("goal", "Do 32, nie 32 więcej; (120,64,-30)")
        json.addProperty("optionalDetail", "Zażółć\\\"\n".repeat(listOf(600, 100, 20, 0)[detail]))
        val text = json.toString()
        return ContextEncodingResult.Encoded(NpcLlmContext(binding, text, LlmJson.utf8(text).size))
    }
    private fun size(level: Int): Int = ChatCompletionCodec.request(
        LlmRequest(id, prompt, state(level).value.stateJson, schema), settings).size

    @Test fun stateCanFitWhileWholeRequestRequiresAnotherProjection() {
        assertTrue(state(0).value.utf8Bytes < NpcContextEncoder.MAX_STATE_BYTES)
        val visited = mutableListOf<Int>()
        val result = WholeRequestBudget.prepare(id, prompt, schema, settings, profile,
            InferenceAllocation(size(0) + 255)) { level -> visited.add(level); state(level) }
        assertTrue(result is RequestPreparation.Ready)
        result as RequestPreparation.Ready
        assertEquals(listOf(0, 1), visited)
        assertEquals(size(1), result.metrics.totalHttpRequestBytes)
        assertEquals(size(1).toLong() + 256, result.metrics.calculatedTokenUpperBound)
        assertEquals(LlmJson.utf8(prompt).size, result.metrics.systemPromptBytes)
        assertTrue(result.metrics.contractBytes > 0 && result.metrics.responseSchemaBytes > 0)
        assertEquals(state(1).value.stateJson, result.request.contextJson)
        assertEquals(schema, result.request.responseSchemaJson)
        assertTrue(result.metrics.describe().contains("configuredContextWindow=32768"))
        assertTrue(result.metrics.describe().contains("templateTokenReserve=256"))
        assertTrue(result.metrics.describe().contains("tokenProfile=VERIFIED_BYTE_LEVEL"))
    }

    @Test fun mandatoryPayloadOverflowStopsAfterFourStagesAndReportsFinalMeasuredSize() {
        var trials = 0
        val result = WholeRequestBudget.prepare(id, prompt, schema, settings, profile,
            InferenceAllocation(size(3) + 255)) { level -> trials++; state(level) }
        assertTrue(result is RequestPreparation.Rejected)
        result as RequestPreparation.Rejected
        assertEquals("INPUT_TOKEN_BOUND_EXCEEDED", result.code)
        assertEquals(4, trials)
        assertEquals(size(3), result.metrics?.totalHttpRequestBytes)
        assertEquals(3, result.metrics?.detailLevel)
    }

    @Test fun unverifiedProfileDoesNotTryToCompactItsWayAroundMissingTokenEvidence() {
        var trials = 0
        val result = WholeRequestBudget.prepare(id, prompt, schema, settings, InferenceTokenProfile.Unverified,
            InferenceAllocation(65536)) { level -> trials++; state(level) }
        assertEquals("UNVERIFIED_TOKEN_PROFILE", (result as RequestPreparation.Rejected).code)
        assertEquals(1, trials)
        assertNull(result.metrics?.calculatedTokenUpperBound)
    }
}
