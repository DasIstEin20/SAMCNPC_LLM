package io.samcnpc.llm

import com.google.gson.*
import io.samcnpc.behavior.api.*
import io.samcnpc.core.api.*
import io.samcnpc.llm.config.*
import io.samcnpc.llm.mission.*
import io.samcnpc.llm.provider.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

/** Test-only fixed replies through real HTTP. No live-world callback and no executor bypass. */
internal class SequenceMissionEmulator : AutoCloseable {
    private val original = LlmConfig.snapshot().values
    private val calls = AtomicInteger()
    private val orders = operations()
    private val endpoint = FakeOpenAiEndpoint(::reply)
    val settings = ProviderSettings(enabled = true, baseUrl = endpoint.baseUrl, model = "mission-emulator",
        apiKeyEnvironment = "", requestTimeoutSeconds = 5, maxOutputTokens = 4096,
        inference = InferenceSettings(true, endpoint.baseUrl, "mission-emulator", "emulator-v1",
            "scripted-fixture", "test-byte-bound", "test-template", 2048, 65536, 61440,
            npcCallsPerHour = 60, serverCallsPerHour = 120))
    init {
        check(LlmConfig.update(LlmConfig.snapshot().revision, settings))
        Files.createDirectories(Path.of("mission-emulator"))
    }

    private fun reply(received: FakeOpenAiEndpoint.Received): FakeOpenAiEndpoint.Reply {
        val number = calls.incrementAndGet()
        val http = LlmJson.parse(received.body, 65536)
        val state = LlmJson.parse(http["messages"].asJsonArray[1].asJsonObject["content"].asString,
            io.samcnpc.llm.context.NpcContextEncoder.MAX_MISSION_STATE_BYTES)
        val root = JsonObject()
        root.addProperty("schemaVersion", 1); root.add("contextId", state["contextId"])
        val stage = if (state.has("MISSION")) state["MISSION"].asJsonObject["stage"].asString else "OPERATION"
        when (stage) {
            "REQUIREMENTS" -> {
                check(number == 1)
                root.add("contract", MissionCodec.encode(contract())); root.add("question", JsonNull.INSTANCE)
            }
            "PLAN" -> {
                check(number == 2)
                check(MissionCodec.contract(state["MISSION"].asJsonObject["contract"].toString()) == contract())
                root.add("plan", MissionCodec.encode(plan())); root.add("question", JsonNull.INSTANCE)
            }
            "OPERATION" -> {
                val mission = state["mission"].asJsonObject
                check(MissionCodec.contract(mission["contract"].toString()) == contract())
                val step = mission["currentStep"].asInt - 1
                check(step == number - 3) { "step=$step call=$number progress=${mission["progress"]}" }
                root.addProperty("decision", "ASSIGN"); root.addProperty("summary", "This is intent, not completion")
                root.add("operation", JsonParser.parseString(orders[step]))
                for (field in listOf("change", "question", "wait")) root.add(field, JsonNull.INSTANCE)
            }
            else -> error("Unexpected mission stage")
        }
        Files.writeString(Path.of("mission-emulator/$number-request.json"), received.body)
        Files.writeString(Path.of("mission-emulator/$number-decision.json"), root.toString())
        return FakeOpenAiEndpoint.Reply(body = LlmJson.utf8(FakeOpenAiEndpoint.success(root.toString())))
    }

    override fun close() {
        endpoint.close()
        if (LlmConfig.snapshot().values != original) check(LlmConfig.update(LlmConfig.snapshot().revision, original))
    }

    companion object {
        private const val DIMENSION = "minecraft:overworld"
        fun contract(): MissionContract {
            val chest = MissionChest(DIMENSION, NpcBlockPosition(-38, 63, 78))
            val home = MissionTarget.Visit(DIMENSION, NpcPosition(-39.5, 63.0, 78.5))
            return MissionContract(listOf(
                MissionRequirement("R1", "Take everything from chest (-38,63,78)", MissionTarget.Collect(chest)),
                MissionRequirement("R2", "cut 32 oak logs", MissionTarget.Items(listOf("minecraft:oak_log"), 32, chest), listOf("R1")),
                MissionRequirement("R3", "at least30 cobblestone", MissionTarget.Items(listOf("minecraft:cobblestone"), 30, chest), listOf("R2")),
                MissionRequirement("R4", "and2 coal", MissionTarget.Items(listOf("minecraft:coal"), 2, chest), listOf("R2")),
                MissionRequirement("R5", "Return to surface (-39.5,63,78.5)", home, listOf("R3", "R4")),
                MissionRequirement("R6", "hoe soil (-37,62,80)..(-35,62,82)", MissionTarget.Field(DIMENSION,
                    NpcBlockPosition(-37, 62, 80), NpcBlockPosition(-35, 62, 82)), listOf("R5")),
                MissionRequirement("R7", "then return (-39.5,63,78.5)", home, listOf("R6"))))
        }

        fun plan() = MissionPlan(listOf(MissionStep("Collect equipment", listOf("R1")),
            MissionStep("Cut and deliver oak", listOf("R2")),
            MissionStep("Descend, mine coal and cobble, deliver and return", listOf("R3", "R4", "R5")),
            MissionStep("Prepare field and return", listOf("R6", "R7"))))

        private fun operations(): List<String> {
            val home = """{"x":-39.5,"y":63,"z":78.5}"""
            val chest = """{"x":-38,"y":63,"z":78}"""
            fun order(type: String, version: Int, parameters: String): String {
                val result = """{"documentVersion":1,"type":"$type","definitionVersion":$version,"parameters":$parameters}"""
                val decoded = OperationDocumentApi.decodeOrder(result)
                check(decoded is OperationDocumentResult.Accepted) { decoded.toString() }
                return result
            }
            return listOf(
                order("samcnpc:inventory_work", 2, """{"dimensionId":"$DIMENSION","work":{"kind":"COLLECT","source":$chest},"anchor":$home,"returnTo":$home}"""),
                order("samcnpc:lumberjack", 2, """{"dimensionId":"$DIMENSION","area":{"bounds":{"min":{"x":-34,"y":63,"z":83},"max":{"x":-23,"y":68,"z":89}}},"wood":["samcnpc:oak"],"destination":$chest,"quantity":32}"""),
                order("samcnpc:mine", 2, """{"dimensionId":"$DIMENSION","work":{"area":{"bounds":{"min":{"x":-33,"y":51,"z":76},"max":{"x":-21,"y":65,"z":76}}},"method":"TUNNEL","resources":["minecraft:stone","minecraft:coal_ore"],"tunnel":{"origin":{"x":-33,"y":63,"z":76},"direction":"EAST","width":1,"height":3,"length":13,"stepDown":1}},"outputs":["minecraft:cobblestone","minecraft:coal"],"destinations":{"positions":[$chest]},"quantity":1,"counting":"CLEARED_VOLUME","anchor":$home,"returnTo":$home}"""),
                order("samcnpc:prepare_field", 1, """{"dimensionId":"$DIMENSION","area":{"bounds":{"min":{"x":-37,"y":62,"z":80},"max":{"x":-35,"y":62,"z":82}}},"anchor":$home,"returnTo":$home}"""))
        }
    }
}
