package io.samcnpc.llm.provider

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SchemaPromptTest {
    @Test fun parametersReferencesBoundsOptionalFieldsAndNullAreVisibleToTheModel() {
        val schema = LlmJson.parse("""{"type":"object","properties":{"point":{"${'$'}ref":"#/${'$'}defs/point"},"maybe":{"anyOf":[{"type":"string","enum":["oak","birch"]},{"type":"null"}]},"items":{"type":"array","items":{"type":"integer","minimum":1,"maximum":64},"minItems":1,"maxItems":8,"uniqueItems":true}},"required":["point","items"],"additionalProperties":false,"${'$'}defs":{"point":{"type":"object","properties":{"x":{"type":"number","minimum":-10,"maximum":10}},"required":["x"],"additionalProperties":false}}}""", 4096)
        val original = schema.deepCopy()
        val text = SchemaPrompt.describe(schema)
        for (part in listOf("point:@point", "maybe?:anyOf(string(enum=[\"oak\",\"birch\"])|null)",
            "items:array<integer(minimum=1,maximum=64)>(minItems=1,maxItems=8,uniqueItems=true)",
            "@point=!{x:number(minimum=-10,maximum=10)}")) assertTrue(text.contains(part), part)
        assertEquals(original, schema)
    }
}
