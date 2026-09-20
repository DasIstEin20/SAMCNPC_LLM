package io.samcnpc.llm.provider

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LlmJsonTest {
    @Test fun rejectsAmbiguousOrUnboundedEnvelopeSyntax() {
        val documents = listOf(
            """{"a":1,"a":2}""", """{"a":TRUE}""", """{"a":Null}""",
            """{"a":+1}""", """{"a":01}""", """{"a":NaN}""", """{"a":Infinity}""",
            """{"a":1.}""", """{"a":.1}""", """{"a":1e}""", """{"a":1e10001}""",
            """{"a":1,}""", """{'a':1}""", """{a:1}""", """{"a":1}// comment""",
            "{}{}", "[]", """{"a":"raw
control"}""", """{"a":"\uD800"}""", """{"a":"\uDC00"}""",
            """{"a":"\x00"}""", "{\"a\":" + "[".repeat(25) + "0" + "]".repeat(25) + "}",
        )
        for ((index, document) in documents.withIndex()) {
            assertThrows(Exception::class.java, { LlmJson.parse(document, 65_536) }, "case $index")
        }
    }

    @Test fun acceptsLegalUnicodeAndPreservesExactIntegers() {
        val root = LlmJson.parse("""{"name":"Zażółć gęślą jaźń 😄","n":9007199254740993,"v":-0.25e2,"ok":true,"none":null}""", 1024)
        assertEquals("Zażółć gęślą jaźń 😄", root["name"].asString)
        assertEquals("9007199254740993", root["n"].asBigDecimal.toPlainString())
        assertEquals("-25", root["v"].asBigDecimal.toPlainString())
        assertThrows(Exception::class.java) { LlmJson.parse("""{"x":"żżżż"}""", 12) }
    }
}
