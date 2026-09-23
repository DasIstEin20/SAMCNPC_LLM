package io.samcnpc.llm.context

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.samcnpc.core.api.NpcInspectionSlot
import io.samcnpc.llm.provider.LlmJson

/** Shares equal observed details, never a guess that two stacks of an item have equal properties. */
internal object ItemFactsProjection {
    private val inlineFields = setOf("slot", "item", "count")

    fun compact(source: JsonObject): JsonObject {
        val result = source.deepCopy()
        val rows = result["inventory"].asJsonArray.map { it.asJsonObject } +
            result["equipment"].asJsonObject.entrySet().mapNotNull { (_, value) ->
                if (value.isJsonObject && value.asJsonObject.has("item")) value.asJsonObject else null
            }
        require(rows.size <= 36 + NpcInspectionSlot.entries.size)
        val groups = linkedMapOf<String, MutableList<JsonObject>>()
        val details = linkedMapOf<String, JsonObject>()
        for (row in rows) {
            if (row["item"].isJsonNull) continue
            val facts = JsonObject()
            for ((name, value) in row.entrySet()) if (name !in inlineFields) facts.add(name, value.deepCopy())
            val key = facts.toString()
            if (key.length < 64) continue
            groups.getOrPut(key) { mutableListOf() }.add(row)
            details[key] = facts
        }
        val dictionary = JsonArray()
        for ((key, members) in groups) {
            if (members.size < 2) continue
            val index = dictionary.size()
            dictionary.add(details.getValue(key))
            for (row in members) {
                for (field in row.keySet().toList()) if (field !in inlineFields) row.remove(field)
                row.addProperty("itemFactsRef", index)
            }
        }
        if (dictionary.isEmpty) return source
        result.add("itemFacts", dictionary)
        result["projection"].asJsonObject.addProperty("itemFactsSemantics",
            "ZERO_BASED_SHARED_OBSERVED_DETAILS; combine itemFacts[itemFactsRef] with each explicit slot/item/count")
        return if (LlmJson.utf8(result.toString()).size < LlmJson.utf8(source.toString()).size) result else source
    }
}
