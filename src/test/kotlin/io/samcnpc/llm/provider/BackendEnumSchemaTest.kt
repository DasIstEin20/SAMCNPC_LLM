package io.samcnpc.llm.provider

import com.google.gson.*
import io.samcnpc.llm.api.LlmRequest
import io.samcnpc.llm.config.ProviderSettings
import org.junit.jupiter.api.Test
import java.util.UUID
import org.junit.jupiter.api.Assertions.*

class BackendEnumSchemaTest {
    private fun backend(source: String): JsonObject {
        val encoded=ChatCompletionCodec.encode(LlmRequest(UUID(0,1),"fixture","{}",source),ProviderSettings(enabled=true,model="emulator"))
        val request=LlmJson.parse(LlmJson.decode(encoded.bytes),131072)
        return request["response_format"].asJsonObject["json_schema"].asJsonObject["schema"].asJsonObject
    }
    @Test fun stringMembershipAlonePreservesExactlyThePermittedValuesAndOtherConstraints() {
        val original=LlmJson.parse("""{"type":"string","enum":["oak","birch"],"minLength":4}""",4096)
        val compact=backend(original.toString())
        assertFalse(compact.has("type"));assertEquals(original["enum"],compact["enum"])
        assertEquals(original["minLength"],compact["minLength"])
        for(text in listOf("\"oak\"","\"birch\"","\"pine\"","null","true","1","[]","{}")) {
            val candidate=JsonParser.parseString(text)
            val originalMember=candidate.isJsonPrimitive && candidate.asJsonPrimitive.isString &&
                original["enum"].asJsonArray.any { it==candidate }
            assertEquals(originalMember,compact["enum"].asJsonArray.any { it==candidate },text)
        }
        assertEquals(SchemaEquivalence.expanded(original),SchemaEquivalence.expanded(compact))
    }
    @Test fun mixedEmptyAndNonStringEnumsRetainTheirIndependentTypeConstraint() {
        for(source in listOf("""{"type":"string","enum":["oak",null]}""",
            """{"type":"string","enum":[]}""","""{"type":"integer","enum":[1,2]}""",
            """{"type":"string","minLength":1}""")) {
            val original=LlmJson.parse(source,4096)
            assertEquals(original,backend(source))
        }
    }
}
