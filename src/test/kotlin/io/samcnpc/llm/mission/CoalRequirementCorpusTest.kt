package io.samcnpc.llm.mission

import com.google.gson.*
import io.samcnpc.core.api.NpcPosition
import io.samcnpc.llm.provider.LlmJson
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

internal object CoalRequirementCorpus {
    val bytes: ByteArray get() = checkNotNull(javaClass.getResourceAsStream("/evaluation/v20/coal-requirements.json")).use { it.readBytes() }
    fun rows(): List<JsonObject> = LlmJson.parse(bytes.toString(Charsets.UTF_8), 8192)["cases"].asJsonArray.map { it.asJsonObject }
    fun score(row: JsonObject, contract: MissionContract): Boolean {
        val actual = contract.requirements.map { it.target as? MissionTarget.Items ?: return false }
        if (actual.any { it.chest != null }) return false
        val expected = row["expected"].asJsonArray.map { it.asJsonObject }
        return actual.size == expected.size && expected.all { need -> actual.count {
            it.minimum == need["minimum"].asInt && it.itemIds.toSet() == need["items"].asJsonArray.map { id -> id.asString }.toSet()
        } == 1 }
    }
}

class CoalRequirementCorpusTest {
    @Test fun fourFrozenCoalCasesKeepExactIdentityAlternativesAndEveryQuantity() {
        val rows = CoalRequirementCorpus.rows()
        assertEquals(listOf("A", "B", "C", "D"), rows.map { it["id"].asString })
        for (row in rows) {
            val requirements = row["expected"].asJsonArray.mapIndexed { index, value ->
                val expected = value.asJsonObject
                MissionRequirement("R${index + 1}", row["goal"].asString,
                    MissionTarget.Items(expected["items"].asJsonArray.map { it.asString }, expected["minimum"].asInt))
            }
            val contract = MissionContract(requirements)
            assertTrue(CoalRequirementCorpus.score(row, contract))
            val charcoal = MissionFacts("minecraft:overworld", NpcPosition(0.0, 64.0, 0.0), mapOf("minecraft:charcoal" to 64L))
            val result = MissionEvaluator.evaluate(contract, emptyList(), charcoal)
            assertEquals(row["id"].asString == "C", result.complete)
            assertTrue(CoalRequirementCorpus.score(row, MissionCodec.contract(MissionCodec.encode(contract).toString())))
        }
        val d = rows.last()
        assertFalse(CoalRequirementCorpus.score(d, MissionContract(listOf(
            MissionRequirement("R1", "32 oak logs", MissionTarget.Items(listOf("minecraft:oak_log"), 32))))))
    }
}
