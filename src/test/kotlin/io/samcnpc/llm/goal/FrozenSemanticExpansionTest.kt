package io.samcnpc.llm.goal

import io.samcnpc.behavior.api.OperationDocumentApi
import io.samcnpc.behavior.api.OperationDocumentResult
import io.samcnpc.behavior.api.OperationType
import io.samcnpc.behavior.api.OperationOrder
import io.samcnpc.llm.context.ContextPolicy
import io.samcnpc.llm.decision.DecisionPolicy
import io.samcnpc.llm.provider.LlmJson
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import org.junit.jupiter.api.Assertions.*

/** Validates immutable evaluation inputs; this does not score an actual model. */
class FrozenSemanticExpansionTest {
    private fun resource(name: String) = checkNotNull(javaClass.getResourceAsStream("/evaluation/$name")).use { it.readBytes() }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test fun frozenExpansionHasValidIndependentOraclesAndAnUntunedHoldout() {
        val bytes = resource("v3/corpus.json")
        assertEquals("04dc39485a19ba5b98b36eb6f51ee88f91cb42ec24e87ca2c5f62914065333b5", sha(bytes))
        val manifest = LlmJson.parse(resource("v3/manifest.json").toString(Charsets.UTF_8), 16384)
        assertEquals(sha(bytes), manifest["expansionSha256"].asString)
        assertEquals(sha(resource("v1/corpus.json")), manifest["baselineSha256"].asString)
        assertEquals(3, manifest["repetitions"].asInt)
        assertEquals("JSON_SCHEMA", manifest["defaultProtocol"].asString)
        val corpus = LlmJson.parse(bytes.toString(Charsets.UTF_8), 131072)
        val rows = corpus["cases"].asJsonArray.map { it.asJsonObject }
        assertEquals(36, rows.size)
        assertEquals(36, rows.map { it["id"].asString }.toSet().size)
        assertEquals(24, rows.count { it["partition"].asString == "development" })
        assertEquals(12, rows.count { it["partition"].asString == "holdout" })
        val policy = ContextPolicy(1, OperationType.entries.toSet(), emptySet(), emptySet(), 72000, 16, 0)
        for (row in rows) {
            val id = row["id"].asString
            assertTrue(GoalRecord.validText(row["goal"].asString, 1024), id)
            if (row["expectedDecision"].asString == "ASSIGN") {
                val decoded = OperationDocumentApi.decodeOrder(row["expectedOperation"].toString())
                assertTrue(decoded is OperationDocumentResult.Accepted, "$id: $decoded")
                val order = (decoded as OperationDocumentResult.Accepted<OperationOrder>).value
                assertNull(DecisionPolicy.orderProblem(order, policy, "minecraft:overworld"), id)
            } else {
                assertTrue(row["expectedOperation"].isJsonNull, id)
                assertTrue(row["expectedDecision"].asString in setOf("ASK_USER", "CONTINUE"), id)
            }
        }
        for (group in rows.groupBy { it["id"].asString.substringBeforeLast('-') }.values)
            assertEquals(setOf("pl", "en"), group.map { it["language"].asString }.toSet())
    }
}
