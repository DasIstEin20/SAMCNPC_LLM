package io.samcnpc.llm.planning

import com.google.gson.JsonElement
import com.google.gson.JsonObject

/** Structural parsing only; current-step policy and fresh inventory are checked at admission. */
internal object PlanProposalCodec {
    fun decode(element: JsonElement): PlanProposal {
        require(element.isJsonObject)
        val root = element.asJsonObject
        require(root.keySet() == setOf("steps", "requiredItems", "minimumEmptySlots"))
        require(root["steps"].isJsonArray && root["steps"].asJsonArray.size() in 1..8)
        val steps = root["steps"].asJsonArray.map { text(it, 256) }
        require(root["requiredItems"].isJsonArray && root["requiredItems"].asJsonArray.size() <= 8)
        val items = root["requiredItems"].asJsonArray.map {
            require(it.isJsonObject)
            val item = it.asJsonObject
            require(item.keySet() == setOf("itemId", "minimum"))
            RequiredItem(text(item["itemId"], 256), integer(item["minimum"]))
        }
        return PlanProposal(steps, items, integer(root["minimumEmptySlots"]))
    }

    private fun text(value: JsonElement, maximum: Int): String {
        require(value.isJsonPrimitive && value.asJsonPrimitive.isString)
        return value.asString.also { require(it.length in 1..maximum) }
    }
    private fun integer(value: JsonElement): Int {
        require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber)
        return value.asBigDecimal.intValueExact()
    }
}
