package io.samcnpc.llm.decision

import com.google.gson.*
import io.samcnpc.behavior.api.*
import io.samcnpc.llm.context.ContextPolicy
import io.samcnpc.llm.provider.ChatCompletionCodec
import io.samcnpc.llm.provider.LlmJson
import io.samcnpc.llm.api.LlmRequest
import io.samcnpc.llm.config.ProviderSettings
import io.samcnpc.llm.config.ResponseFormat
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class DecisionDecoderTest {
    private val id = UUID.fromString("12345678-1234-4abc-8def-123456789abc")
    private val order = """{"documentVersion":1,"type":"samcnpc:navigate","definitionVersion":1,"parameters":{"dimensionId":"minecraft:overworld","destination":{"x":5,"y":64,"z":0}}}"""
    private val change = """{"documentVersion":1,"type":"EXTEND_TIME","parameters":{"ticks":20}}"""
    private fun envelope(kind: DecisionKind): JsonObject {
        val root = JsonObject()
        root.addProperty("schemaVersion", 1); root.addProperty("contextId", id.toString())
        root.addProperty("decision", kind.name); root.addProperty("summary", "bounded decision")
        for (field in listOf("operation", "change", "question", "wait")) root.add(field, JsonNull.INSTANCE)
        when (kind) {
            DecisionKind.ASSIGN -> root.add("operation", JsonParser.parseString(order))
            DecisionKind.AMEND -> root.add("change", JsonParser.parseString(change))
            DecisionKind.ASK_USER -> root.addProperty("question", "Which known storage should I use?")
            DecisionKind.WAIT -> root.add("wait", JsonParser.parseString("""{"trigger":"DEADLINE","ticks":20}"""))
            else -> Unit
        }
        return root
    }
    private fun policy(operations: Set<OperationType> = OperationType.entries.toSet(), changes: Set<String> = OperationCatalogApi.snapshot().changes.keys) =
        ContextPolicy(1, operations, changes, OperationControl.entries.toSet(), 6000, 3, 12000)

    @Test fun allEightDecisionsMapToClosedTypedActions() {
        for (kind in DecisionKind.entries) {
            val decoded = DecisionDecoder.decode(envelope(kind).toString())
            assertTrue(decoded is DecisionDecodeResult.Accepted, decoded.toString())
            val decision = (decoded as DecisionDecodeResult.Accepted).value
            assertEquals(kind, decision.kind); assertEquals(id, decision.contextId)
        }
        val assigned = (DecisionDecoder.decode(envelope(DecisionKind.ASSIGN).toString()) as DecisionDecodeResult.Accepted).value
        assertEquals(OperationType.NAVIGATE, (assigned.action as DecisionAction.Assign).order.type)
        val amended = (DecisionDecoder.decode(envelope(DecisionKind.AMEND).toString()) as DecisionDecodeResult.Accepted).value
        assertEquals(OperationChange.ExtendTime(20), (amended.action as DecisionAction.Amend).change)
    }

    @Test fun missingUnknownAndContradictoryFieldsAreRejected() {
        val invalid = mutableListOf<JsonObject>()
        for (field in envelope(DecisionKind.CONTINUE).keySet()) invalid.add(envelope(DecisionKind.CONTINUE).also { it.remove(field) })
        invalid.add(envelope(DecisionKind.CONTINUE).also { it.addProperty("command", "/give @s minecraft:diamond") })
        invalid.add(envelope(DecisionKind.CONTINUE).also { it.addProperty("decision", "JUMP") })
        invalid.add(envelope(DecisionKind.CONTINUE).also { it.addProperty("schemaVersion", 2) })
        invalid.add(envelope(DecisionKind.CONTINUE).also { it.addProperty("schemaVersion", 1.5) })
        invalid.add(envelope(DecisionKind.CONTINUE).also { it.addProperty("contextId", "1-1-1-1-1") })
        invalid.add(envelope(DecisionKind.CONTINUE).also { it.addProperty("summary", "x".repeat(257)) })
        invalid.add(envelope(DecisionKind.CONTINUE).also { it.add("operation", JsonParser.parseString(order)) })
        invalid.add(envelope(DecisionKind.ASSIGN).also { it.add("change", JsonParser.parseString(change)) })
        invalid.add(envelope(DecisionKind.ASK_USER).also { it.addProperty("question", "   ") })
        for ((index, root) in invalid.withIndex()) assertTrue(DecisionDecoder.decode(root.toString()) is DecisionDecodeResult.Rejected, "case $index")
    }

    @Test fun operationDocumentsUseBehaviorSyntaxAndPolicyRejectsTheWrongDimension() {
        val invalid = listOf(
            order.replace("samcnpc:navigate", "samcnpc:execute_command"),
            order.replace("\"definitionVersion\":1", "\"definitionVersion\":999"),
            order.replace("\"x\":5", "\"x\":1e1000"),
            order.replace("\"dimensionId\":\"minecraft:overworld\"", "\"dimensionId\":\"https://example.invalid\""),
            order.replace("\"destination\":", "\"command\":\"kill\",\"destination\":"))
        for ((index, document) in invalid.withIndex()) {
            val root = envelope(DecisionKind.ASSIGN)
            root.add("operation", JsonParser.parseString(document))
            val decoded = DecisionDecoder.decode(root.toString())
            if (index == 3) {
                // This URL-looking string is syntactically a ResourceLocation; current-dimension policy still rejects it.
                assertTrue(decoded is DecisionDecodeResult.Accepted)
                val assigned = (decoded as DecisionDecodeResult.Accepted).value.action as DecisionAction.Assign
                assertEquals("DIMENSION_MISMATCH", DecisionPolicy.orderProblem(assigned.order, policy(), "minecraft:overworld"))
            } else assertTrue(decoded is DecisionDecodeResult.Rejected, "operation case $index")
        }
    }

    @Test fun waitSourcesAndDelaysAreFiniteAndMutuallyConsistent() {
        val root = envelope(DecisionKind.WAIT)
        for (trigger in listOf("TASK_TERMINAL", "USER_UPDATE")) {
            root.add("wait", JsonParser.parseString("""{"trigger":"$trigger","ticks":null}"""))
            assertTrue(DecisionDecoder.decode(root.toString()) is DecisionDecodeResult.Accepted)
            root["wait"].asJsonObject.addProperty("ticks", 20)
            assertTrue(DecisionDecoder.decode(root.toString()) is DecisionDecodeResult.Rejected)
        }
        for (ticks in listOf("null", "0", "19", "1201", "20.5", "\"20\"")) {
            root.add("wait", JsonParser.parseString("""{"trigger":"DEADLINE","ticks":$ticks}"""))
            assertTrue(DecisionDecoder.decode(root.toString()) is DecisionDecodeResult.Rejected, ticks)
        }
        root.add("wait", JsonParser.parseString("""{"trigger":"DEADLINE","ticks":1200}"""))
        assertTrue(DecisionDecoder.decode(root.toString()) is DecisionDecodeResult.Accepted)
    }

    @Test fun malformedDuplicateUnicodeAndOversizedOutputNeverBecomeCandidates() {
        val valid = envelope(DecisionKind.CONTINUE).toString()
        val invalid = listOf(valid + "{}", "markdown " + valid, valid.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1"),
            valid.replace("bounded decision", "\\uD800"), valid.replace("\"schemaVersion\":1", "\"schemaVersion\":1e10000"))
        for (document in invalid) assertTrue(DecisionDecoder.decode(document) is DecisionDecodeResult.Rejected)
        val huge = envelope(DecisionKind.CONTINUE).also { it.addProperty("summary", "漢".repeat(6000)) }
        assertEquals(DecisionDecodeResult.Rejected("DECISION_TOO_LARGE"), DecisionDecoder.decode(huge.toString()))
    }

    @Test fun schemasReusePublishedConstraintsAndPruneOnlyUnreachableDefinitions() {
        val full = LlmJson.parse(DecisionSchema.forContext(id, policy()), 65_536)
        val key = "$" + "defs"
        assertEquals(16, full[key].asJsonObject["orderDocument"].asJsonObject["oneOf"].asJsonArray.size())
        val narrow = LlmJson.parse(DecisionSchema.forContext(id, policy(setOf(OperationType.NAVIGATE), emptySet())), 65_536)
        assertEquals(1, narrow[key].asJsonObject["orderDocument"].asJsonObject["oneOf"].asJsonArray.size())
        assertFalse(narrow[key].asJsonObject.has("order_LUMBERJACK"))
        assertEquals("null", narrow["properties"].asJsonObject["change"].asJsonObject["type"].asString)
        val controlOnly = LlmJson.parse(DecisionSchema.forContext(id, policy(emptySet(), emptySet())), 65_536)
        assertFalse(controlOnly.has(key))
        assertFalse(full.toString().contains("x-semanticRelations"))
        assertTrue(full.toString().contains("minimum"))
        val folder = Path.of("build", "decision-contract")
        Files.createDirectories(folder)
        Files.writeString(folder.resolve("full-schema.json"), full.toString())
        Files.writeString(folder.resolve("navigate-schema.json"), narrow.toString())
        val corpus = JsonArray()
        for (kind in DecisionKind.entries) corpus.add(envelope(kind))
        Files.writeString(folder.resolve("valid-decisions.json"), corpus.toString())
    }

    @Test fun completeSchemaFitsBothExplicitTransportProfilesAndBothPromptsReceiveParameters() {
        val schema = DecisionSchema.forContext(id, policy())
        val request = LlmRequest(UUID.randomUUID(), DecisionPrompt.text,
            """{"contextId":"$id","goal":{"text":"Gather wood"}}""", schema)
        for (format in ResponseFormat.entries) {
            val settings = ProviderSettings(enabled = true, model = "emulator", responseFormat = format)
            val bytes = ChatCompletionCodec.request(request, settings)
            assertTrue(bytes.size <= settings.maxContextBytes, "format=$format bytes=" + bytes.size)
            val payload = LlmJson.parse(LlmJson.decode(bytes), settings.maxContextBytes)
            val system = payload["messages"].asJsonArray[0].asJsonObject["content"].asString
            assertTrue(system.contains(if (format == ResponseFormat.JSON_OBJECT) "OUTPUT_CONTRACT_JSON_SCHEMA" else "OUTPUT_CONTRACT_TYPES"))
            for (operation in OperationType.entries) assertTrue(system.contains(operation.operationId))
            assertTrue(system.contains("quantity"))
            assertTrue(system.contains("definitionVersion"))
        }
    }
}
