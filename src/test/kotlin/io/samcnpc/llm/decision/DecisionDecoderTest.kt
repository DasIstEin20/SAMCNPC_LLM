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
        assertEquals(17, full[key].asJsonObject["orderDocument"].asJsonObject["oneOf"].asJsonArray.size())
        val narrow = LlmJson.parse(DecisionSchema.forContext(id, policy(setOf(OperationType.NAVIGATE), emptySet())), 65_536)
        assertEquals(1, narrow[key].asJsonObject["orderDocument"].asJsonObject["oneOf"].asJsonArray.size())
        assertFalse(narrow[key].asJsonObject.has("order_LUMBERJACK"))
        val navigateParameters = narrow[key].asJsonObject["order_NAVIGATE"].asJsonObject["properties"]
            .asJsonObject["parameters"].asJsonObject["properties"].asJsonObject
        assertEquals(1.0, navigateParameters["speed"].asJsonObject["default"].asDouble)
        assertEquals(0.75, navigateParameters["arrivalDistance"].asJsonObject["default"].asDouble)
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

    @Test fun completeSchemaFitsEveryExplicitTransportProfileAndEveryPromptReceivesParameters() {
        val schema = DecisionSchema.forContext(id, policy())
        val request = LlmRequest(UUID.randomUUID(), DecisionPrompt.text,
            """{"contextId":"$id","goal":{"text":"Gather wood"}}""", schema)
        for (format in ResponseFormat.entries) {
            val settings = ProviderSettings(enabled = true, model = "emulator", responseFormat = format)
            val bytes = ChatCompletionCodec.request(request, settings)
            assertTrue(bytes.size <= settings.maxContextBytes, "format=$format bytes=" + bytes.size)
            val payload = LlmJson.parse(LlmJson.decode(bytes), settings.maxContextBytes)
            val system = payload["messages"].asJsonArray[0].asJsonObject["content"].asString
            val marker = when (format) {
                ResponseFormat.JSON_SCHEMA -> "OUTPUT_CONTRACT_TYPES"
                ResponseFormat.JSON_OBJECT -> "OUTPUT_CONTRACT_JSON_SCHEMA"
                ResponseFormat.SAM_EXPRESSION_V1 -> "SAM_EXPRESSION_V1_UNCONSTRAINED"
            }
            assertTrue(system.contains(marker))
            if (format == ResponseFormat.SAM_EXPRESSION_V1) assertFalse(payload.has("response_format"))
            for (operation in OperationType.entries) assertTrue(system.contains(operation.operationId))
            assertTrue(system.contains("quantity"))
            assertTrue(system.contains("definitionVersion"))
            assertTrue(system.contains("default"), "Behavior defaults must be visible to the model")
            if (format == ResponseFormat.JSON_SCHEMA) {
                val wireSchema = payload["response_format"].asJsonObject["json_schema"].asJsonObject["schema"].toString()
                assertEquals(io.samcnpc.llm.provider.SchemaEquivalence.expanded(LlmJson.parse(schema, 65536)),
                    io.samcnpc.llm.provider.SchemaEquivalence.expanded(LlmJson.parse(wireSchema, 65536)))
                assertFalse(wireSchema.contains("\"default\":"))
                assertTrue(wireSchema.contains("\"minimum\":"))
                assertTrue(wireSchema.contains("\"required\":"))
            }
        }
    }

    @Test fun idleTranslatorContractContainsOnlyApplicableDecisions() {
        val idle = LlmJson.parse(DecisionSchema.forContext(id, policy(), hasActiveTask = false), 65536)
        val choices = idle["properties"].asJsonObject["decision"].asJsonObject["enum"].asJsonArray.map { it.asString }.toSet()
        assertEquals(setOf("ASSIGN", "WAIT", "ASK_USER"), choices)
        val active = LlmJson.parse(DecisionSchema.forContext(id, policy(), hasActiveTask = true), 65536)
        val activeChoices = active["properties"].asJsonObject["decision"].asJsonObject["enum"].asJsonArray.map { it.asString }.toSet()
        assertFalse("ASSIGN" in activeChoices)
        assertTrue("CONTINUE" in activeChoices)
        val planner = LlmJson.parse(DecisionSchema.forContext(id, policy(), planner = true, hasActiveTask = false), 65536)
        assertFalse(planner["properties"].asJsonObject["decision"].asJsonObject["enum"].asJsonArray.any { it.asString == "CONTINUE" })
    }

    @Test fun constrainedWaitSchemaCannotOfferATimerForAUserOrTaskEvent() {
        for (active in listOf(false, true)) {
            val schema = JsonParser.parseString(DecisionSchema.forContext(id, policy(), hasActiveTask = active)).asJsonObject
            val variants = schema["properties"].asJsonObject["wait"].asJsonObject["anyOf"].asJsonArray[0]
                .asJsonObject["oneOf"].asJsonArray.map { it.asJsonObject["properties"].asJsonObject }
            val event = variants.single { it["ticks"].asJsonObject["type"].asString == "null" }
            assertEquals(if (active) setOf("USER_UPDATE", "TASK_TERMINAL") else setOf("USER_UPDATE"),
                event["trigger"].asJsonObject["enum"].asJsonArray.map { it.asString }.toSet())
            val timer = variants.single { it["ticks"].asJsonObject["type"].asString == "integer" }
            assertEquals(listOf("DEADLINE"), timer["trigger"].asJsonObject["enum"].asJsonArray.map { it.asString })
            assertEquals(20, timer["ticks"].asJsonObject["minimum"].asInt)
            assertEquals(1200, timer["ticks"].asJsonObject["maximum"].asInt)
        }
    }

    @Test fun sharedBackendShapesReduceCatalogWithoutChangingAnyEffectiveConstraint() {
        val sizes = JsonArray()
        for (planner in listOf(false, true)) for (operations in listOf(OperationType.entries.toSet()) +
            OperationType.entries.map { setOf(it) }) {
            val schema = DecisionSchema.forContext(id, policy(operations, setOf("REPLACE", "EXTEND_TIME")), planner)
            val encoded = ChatCompletionCodec.encode(LlmRequest(id, DecisionPrompt.text, "{}", schema),
                ProviderSettings(enabled = true, model = "emulator"))
            val root = LlmJson.parse(LlmJson.decode(encoded.bytes), 131072)
            if(operations.size == OperationType.entries.size) {
                Files.createDirectories(Path.of("build/decision-contract"))
                Files.writeString(Path.of("build/decision-contract/source-$planner.json"),schema)
            }
            val backend = root["response_format"].asJsonObject["json_schema"].asJsonObject["schema"].asJsonObject
            assertEquals(io.samcnpc.llm.provider.SchemaEquivalence.expanded(LlmJson.parse(schema, 65536)),
                io.samcnpc.llm.provider.SchemaEquivalence.expanded(backend), "planner=$planner operations=$operations")
            sizes.add(JsonObject().also {
                it.addProperty("planner", planner); it.addProperty("operationCount", operations.size)
                it.addProperty("sourceSchemaBytes", LlmJson.utf8(schema).size)
                it.addProperty("backendSchemaBytes", encoded.responseSchemaBytes)
                it.addProperty("contractBytes", encoded.contractBytes); it.addProperty("requestBytes", encoded.bytes.size)
            })
        }
        val directory = Path.of("build", "decision-contract")
        Files.createDirectories(directory)
        Files.writeString(directory.resolve("catalog-sizes.json"), sizes.toString())
        for (value in sizes.filter { it.asJsonObject["operationCount"].asInt == OperationType.entries.size })
            assertTrue(value.asJsonObject["backendSchemaBytes"].asInt < 24000, value.toString())
    }
}
