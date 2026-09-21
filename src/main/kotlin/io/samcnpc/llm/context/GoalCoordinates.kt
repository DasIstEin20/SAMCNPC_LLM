package io.samcnpc.llm.context

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/** User text hints only, never observations, authority or an executable target. Worker-side only. */
internal object GoalCoordinates {
    private const val NUMBER = "[+-]?[0-9]{1,8}(?:\\.[0-9]{1,6})?"
    private val tuple = Regex("(?<![0-9.,+-])($NUMBER)\\s*,\\s*($NUMBER)\\s*,\\s*($NUMBER)(?![0-9.,])")

    fun encode(text: String): JsonArray {
        val values = JsonArray()
        for (match in tuple.findAll(text).take(8)) {
            val point = JsonObject()
            for ((index, axis) in listOf("x", "y", "z").withIndex())
                point.addProperty(axis, match.groupValues[index + 1].toBigDecimal())
            values.add(point)
        }
        return values
    }
}
