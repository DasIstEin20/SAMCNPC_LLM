package io.samcnpc.llm.provider

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SchemaPromptTest {
    @Test fun sharedNullableReferencesKeepTheirUnionAndTargetConstraints() {
        val schema = LlmJson.parse("""{"type":"object","properties":{"a":{"anyOf":[{"${'$'}ref":"#/${'$'}defs/bounded"},{"type":"null"}]},"b":{"anyOf":[{"${'$'}ref":"#/${'$'}defs/bounded"},{"type":"null"}]}},"required":["a","b"],"additionalProperties":false,"${'$'}defs":{"bounded":{"type":"integer","minimum":1,"maximum":64}}}""", 4096)
        val original = schema.deepCopy()
        val text = SchemaPrompt.describe(schema)
        assertTrue(text.contains("a:@p0,b:@p0"), text)
        assertTrue(text.contains("@p0=anyOf(@d0|null)"), text)
        assertTrue(text.contains("@d0=integer(minimum=1,maximum=64)"), text)
        assertEquals(original, schema)
    }

    @Test fun compactAliasesCannotShadowExistingDefinitions() {
        val schema = LlmJson.parse("""{"type":"object","properties":{"a":{"type":"integer","minimum":1,"maximum":2304,"description":"bounded quantity"},"b":{"type":"integer","minimum":1,"maximum":2304,"description":"bounded quantity"},"c":{"${'$'}ref":"#/${'$'}defs/p0"}},"required":["a","b","c"],"additionalProperties":false,"${'$'}defs":{"p0":{"type":"string","enum":["original"]}}}""", 4096)
        val original = schema.deepCopy()
        val text = SchemaPrompt.describe(schema)
        assertTrue(text.contains("a:@p0,b:@p0,c:@d0"), text)
        assertTrue(text.contains("@d0=string(enum=[\"original\"])"), text)
        assertTrue(text.contains("@p0=integer(minimum=1,maximum=2304,description=\"bounded quantity\")"), text)
        assertEquals(original, schema)
    }

    @Test fun parametersReferencesBoundsOptionalFieldsAndNullAreVisibleToTheModel() {
        val schema = LlmJson.parse("""{"type":"object","properties":{"point":{"${'$'}ref":"#/${'$'}defs/point"},"maybe":{"anyOf":[{"type":"string","enum":["oak","birch"]},{"type":"null"}]},"items":{"type":"array","items":{"type":"integer","minimum":1,"maximum":64},"minItems":1,"maxItems":8,"uniqueItems":true}},"required":["point","items"],"additionalProperties":false,"${'$'}defs":{"point":{"type":"object","properties":{"x":{"type":"number","minimum":-10,"maximum":10}},"required":["x"],"additionalProperties":false}}}""", 4096)
        val original = schema.deepCopy()
        val text = SchemaPrompt.describe(schema)
        for (part in listOf("point:@d0", "maybe?:anyOf(string(enum=[\"oak\",\"birch\"])|null)",
            "items:array<integer(minimum=1,maximum=64)>(minItems=1,maxItems=8,uniqueItems=true)",
            "@d0=!{x:number(minimum=-10,maximum=10)}")) assertTrue(text.contains(part), part)
        assertEquals(original, schema)
    }
}
