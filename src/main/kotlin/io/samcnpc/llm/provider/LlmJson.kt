package io.samcnpc.llm.provider

import com.google.gson.*
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Independent HTTP boundary: bounded trees, exact numbers, no duplicate keys or lenient JSON. */
internal object LlmJson {
    private val number = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")

    fun utf8(text: String): ByteArray {
        val encoded = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text))
        return ByteArray(encoded.remaining()).also { encoded.get(it) }
    }

    fun decode(bytes: ByteArray): String = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()

    fun parse(text: String, maxBytes: Int): JsonObject {
        require(text.length <= maxBytes && utf8(text).size <= maxBytes) { "JSON size limit" }
        lexical(text)
        JsonReader(StringReader(text)).use { reader ->
            reader.isLenient = false
            val result = Cursor(reader).value(0)
            require(reader.peek() == JsonToken.END_DOCUMENT && result.isJsonObject) { "Expected one JSON object" }
            return result.asJsonObject
        }
    }

    private fun lexical(text: String) {
        var quoted = false
        var escaped = false
        for ((index, char) in text.withIndex()) {
            if (quoted) {
                require(char.code >= 32) { "Raw string control" }
                if (escaped) {
                    require(char in "\"\\/bfnrtu") { "Invalid escape" }
                    escaped = false
                } else if (char == '\\') escaped = true
                else if (char == '"') quoted = false
            } else if (char == '"') quoted = true
            else if (char == 't') require(text.startsWith("true", index)) { "Invalid literal" }
            else if (char == 'f') require(text.startsWith("false", index)) { "Invalid literal" }
            else if (char == 'n') require(text.startsWith("null", index)) { "Invalid literal" }
            else require(char in "{}[],:-+.0123456789eEtruefalsn \t\r\n") { "Invalid JSON character" }
        }
    }

    private class Cursor(private val reader: JsonReader) {
        private var nodes = 0
        fun value(depth: Int): JsonElement {
            require(depth <= 24 && ++nodes <= 8192) { "JSON complexity limit" }
            return when (reader.peek()) {
                JsonToken.BEGIN_OBJECT -> {
                    reader.beginObject()
                    val result = JsonObject()
                    while (reader.hasNext()) {
                        val name = string(reader.nextName())
                        require(name.length <= 256 && !result.has(name)) { "Invalid or duplicate property" }
                        result.add(name, value(depth + 1))
                    }
                    reader.endObject()
                    result
                }
                JsonToken.BEGIN_ARRAY -> {
                    reader.beginArray()
                    val result = JsonArray()
                    while (reader.hasNext()) result.add(value(depth + 1))
                    reader.endArray()
                    result
                }
                JsonToken.STRING -> JsonPrimitive(string(reader.nextString()))
                JsonToken.NUMBER -> {
                    val token = reader.nextString()
                    require(token.length <= 64 && number.matches(token)) { "Invalid number" }
                    val value = BigDecimal(token)
                    require(value.scale() in -10_000..10_000) { "Number exponent limit" }
                    JsonPrimitive(value)
                }
                JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
                JsonToken.NULL -> { reader.nextNull(); JsonNull.INSTANCE }
                else -> throw IllegalArgumentException("Invalid JSON value")
            }
        }

        private fun string(value: String): String {
            require(value.length <= 65_536) { "String limit" }
            utf8(value) // The reader can produce lone surrogates from otherwise ASCII escape sequences.
            return value
        }
    }
}
