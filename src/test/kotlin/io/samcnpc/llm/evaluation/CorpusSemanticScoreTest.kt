package io.samcnpc.llm.evaluation

import com.google.gson.*
import io.samcnpc.behavior.api.*
import io.samcnpc.llm.decision.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

internal class CorpusSemanticScoreTest {
    private val context = UUID.randomUUID()
    private fun decision(row: JsonObject, document: JsonObject? = row["expectedOperation"].takeUnless { it.isJsonNull }?.asJsonObject): LlmDecision {
        val kind = DecisionKind.valueOf(row["expectedDecision"].asString)
        val action = when (kind) {
            DecisionKind.ASSIGN -> DecisionAction.Assign((OperationDocumentApi.decodeOrder(checkNotNull(document).toString()) as OperationDocumentResult.Accepted).value)
            DecisionKind.ASK_USER -> DecisionAction.AskUser("Essential missing information?")
            DecisionKind.CONTINUE -> DecisionAction.Continue
            else -> error("Unexpected frozen oracle $kind")
        }
        return LlmDecision(context, kind, action, "")
    }
    private fun state(row: JsonObject) = JsonObject().also { state ->
        val items = JsonArray()
        for ((id, count) in (CorpusFixtures.setup(row).getAsJsonObject("inventory") ?: JsonObject()).entrySet())
            items.add(JsonObject().also { it.addProperty("item", id); it.add("count", count) })
        state.add("inventory", items)
    }
    @Test fun `all frozen oracles satisfy the independent semantic dimensions`() {
        for (row in CorpusFixtures.rows) {
            val score = CorpusSemanticScore.score(row, decision(row), state(row), null)
            assertTrue(score["semanticCorrect"].asBoolean, row["id"].asString + score)
            assertFalse(CorpusSemanticScore.score(row, decision(row), state(row), "REJECTED")["semanticCorrect"].asBoolean)
            assertFalse(CorpusSemanticScore.score(row, null, state(row), null)["clarification"].asBoolean)
        }
    }
    @Test fun `resource quantity area and destination mistakes have separate scores`() {
        val row = CorpusFixtures.rows.first { it["family"]?.asString == "samcnpc:lumberjack" && it["expectedDecision"].asString == "ASSIGN" }
        fun altered(edit: (JsonObject) -> Unit): JsonObject {
            val doc = row["expectedOperation"].asJsonObject.deepCopy()
            edit(doc.getAsJsonObject("parameters"))
            return CorpusSemanticScore.score(row, decision(row, doc), state(row), null)
        }
        assertFalse(altered { it.add("wood", JsonArray().also { a -> a.add("samcnpc:dark_oak") }) }["resource"].asBoolean)
        assertFalse(altered { it.addProperty("quantity", it["quantity"].asInt + 1) }["quantitySemantics"].asBoolean)
        val moved = altered { it.getAsJsonObject("destination").addProperty("x", 99) }
        assertFalse(moved["destination"].asBoolean)
        assertTrue(moved["area"].asBoolean)
        assertFalse(altered {
            val min = it.getAsJsonObject("area").getAsJsonObject("bounds").getAsJsonObject("min")
            min.addProperty("x", min["x"].asInt - 1)
        }["area"].asBoolean)
    }
    @Test fun `supply uses target stock and activation threshold rather than equal thresholds`() {
        val row = CorpusFixtures.rows.first { r ->
            val p = r["expectedOperation"].takeUnless { it.isJsonNull }?.asJsonObject?.getAsJsonObject("parameters")
            val needs = p?.getAsJsonObject("work")?.getAsJsonArray("needs")
            needs != null && needs[0].asJsonObject["target"].asInt == 44
        }
        val state = state(row)
        assertEquals(12, state.getAsJsonArray("inventory").sumOf { it.asJsonObject["count"].asInt })
        fun score(minimum: Int, target: Int): JsonObject {
            val doc = row["expectedOperation"].asJsonObject.deepCopy()
            val need = doc.getAsJsonObject("parameters").getAsJsonObject("work").getAsJsonArray("needs")[0].asJsonObject
            need.addProperty("minimum", minimum); need.addProperty("target", target)
            return CorpusSemanticScore.score(row, decision(row, doc), state, null)
        }
        for (minimum in listOf(13, 32, 44)) assertTrue(score(minimum, 44)["semanticCorrect"].asBoolean)
        assertFalse(score(12, 44)["quantitySemantics"].asBoolean)
        assertFalse(score(32, 32)["quantitySemantics"].asBoolean)
    }
}
