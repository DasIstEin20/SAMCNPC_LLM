package io.samcnpc.llm.context

import com.google.gson.JsonObject
import io.samcnpc.core.api.*
import io.samcnpc.llm.provider.LlmJson
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Independent reconstruction checks the observation contract, including null/false and unknown flags. */
internal object ItemFactsEvidence {
    fun expand(source: JsonObject): JsonObject {
        val result = source.deepCopy()
        val dictionary = result.remove("itemFacts")?.asJsonArray ?: return result
        check(dictionary.size() <= 36 + NpcInspectionSlot.entries.size)
        fun row(value: JsonObject) {
            val reference = value.remove("itemFactsRef") ?: return
            check(reference.asInt in 0 until dictionary.size())
            for ((key, detail) in dictionary[reference.asInt].asJsonObject.entrySet()) {
                check(!value.has(key))
                value.add(key, detail.deepCopy())
            }
        }
        result["inventory"].asJsonArray.forEach { row(it.asJsonObject) }
        result["equipment"].asJsonObject.entrySet().forEach { if (it.value.isJsonObject) row(it.value.asJsonObject) }
        result["projection"].asJsonObject.remove("itemFactsSemantics")
        return result
    }
}

class ItemFactsProjectionTest {
    private fun item(damage: Int, omitted: Int = 0) = NpcItemInspection(
        NpcItemStackSnapshot("minecraft:bow", 1, 1, damage, 384),
        NpcItemKnowledge("minecraft:bow", setOf(NpcItemRole.RANGED_WEAPON)),
        listOf(NpcEnchantmentInspection("minecraft:power", 3)), false, omitted,
        NpcRangedResourceReadiness.MISSING_AMMUNITION)
    private fun state(items: List<NpcItemInspection>): JsonObject {
        val empty = NpcItemInspection(NpcItemStackSnapshot.EMPTY, NpcItemKnowledge.EMPTY,
            emptyList(), false, 0, NpcRangedResourceReadiness.NOT_RANGED)
        val body = NpcBodyInspection(10, "Fixture", 20F, 20F, 0F, 0, emptyList(), false,
            items, NpcInspectionSlot.entries.associateWith { empty }, 0)
        return ContextJson.obj("inventory" to BodyContext.inventory(body, 16),
            "equipment" to BodyContext.equipment(body, 16), "projection" to JsonObject())
    }

    @Test fun sharingPreservesAllSlotsCountsDurabilityReadinessAndUnknownFieldsWithoutMutatingInput() {
        val source = state(List(36) { if (it == 35) item(11, 2) else item(10) })
        val before = source.deepCopy()
        val compact = ItemFactsProjection.compact(source)
        assertEquals(before, source)
        assertEquals(source, ItemFactsEvidence.expand(compact))
        assertTrue(LlmJson.utf8(compact.toString()).size < LlmJson.utf8(source.toString()).size / 2)
        assertEquals(36, compact["inventory"].asJsonArray.size())
        assertFalse(compact["inventory"].asJsonArray[35].asJsonObject.has("itemFactsRef"))
    }

    @Test fun differentDurabilityForEveryStackDoesNotBecomeAnItemIdBasedGuess() {
        val source = state(List(36) { item(it) })
        val compact = ItemFactsProjection.compact(source)
        assertEquals(source, compact)
        assertFalse(compact.has("itemFacts"))
    }
}
