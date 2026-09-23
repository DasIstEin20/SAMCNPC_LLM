package io.samcnpc.llm.context

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/** Literal spans only, like GoalCoordinates. Negated/quoted mentions remain untrusted text. */
internal object GoalItemMentions {
    private val mention = Regex("(?<![\\w.+-])([0-9]{1,4})\\s+([a-z0-9_.-]{1,64}:[a-z0-9_./-]{1,128})(?![a-z0-9_./-])")

    fun encode(text: String): JsonArray {
        val rows = JsonArray()
        for (match in mention.findAll(text).take(32)) {
            val literal = match.value.trimEnd('.')
            val row = JsonObject()
            row.addProperty("sourceStart", match.range.first)
            row.addProperty("sourceEndExclusive", match.range.first + literal.length)
            row.addProperty("sourceText", literal)
            row.addProperty("quantityText", match.groupValues[1])
            row.addProperty("itemId", match.groupValues[2].trimEnd('.'))
            rows.add(row)
        }
        return rows
    }
}
