package io.samcnpc.llm.config

import net.minecraftforge.common.ForgeConfigSpec

/** Advanced server-owned metering declaration in the existing COMMON config; no network/profile lookup. */
internal class InferenceConfigFields(builder: ForgeConfigSpec.Builder) {
    private val defaults = InferenceSettings()
    init { builder.push("inference") }
    private val verified = builder.comment("Enable ONLY after verifying this exact byte-level tokenizer/chat-template tuple. Not a model-quality claim.")
        .define("verifiedByteLevel", false)
    private val endpoint = builder.define("verifiedBaseUrl", defaults.verifiedBaseUrl) {
        it is String && ProviderSettings.validBaseUrl(it)
    }
    private val model = builder.define("verifiedModel", "") { it is String && ProviderSettings.validModel(it) }
    private val backend = evidence(builder, "backendVersion")
    private val modelDigest = evidence(builder, "modelDigest")
    private val tokenizer = evidence(builder, "tokenizerDigest")
    private val template = evidence(builder, "templateDigest")
    private val reserve = builder.defineInRange("templateReserve", defaults.templateReserve, 0, 8192)
    private val window = builder.defineInRange("contextWindow", defaults.contextWindow, 1024, 262144)
    private val input = builder.defineInRange("inputTokens", defaults.inputTokens, 1, 131072)
    private val inputRate = builder.defineInRange("inputMicrosPerMillion", 0L, 0L, 1_000_000_000L)
    private val outputRate = builder.defineInRange("outputMicrosPerMillion", 0L, 0L, 1_000_000_000L)
    private val goalCost = builder.defineInRange("goalCostMicros", 0L, 0L, 1_000_000_000L)
    private val serverCost = builder.defineInRange("hourlyCostMicros", 0L, 0L, 1_000_000_000L)
    private val npcCalls = builder.comment("Maximum requests per NPC in a rolling hour; cooldown still applies.")
        .defineInRange("npcCallsPerHour", defaults.npcCallsPerHour, 1, 360)
    private val serverCalls = builder.comment("Maximum requests across the server in a rolling hour. Token budgets scale with the verified reservation.")
        .defineInRange("serverCallsPerHour", defaults.serverCallsPerHour, 1, 720)
    init { builder.pop() }

    fun read() = InferenceSettings(verified.get(), endpoint.get(), model.get(), backend.get(),
        modelDigest.get(), tokenizer.get(), template.get(), reserve.get(), window.get(), input.get(),
        inputRate.get(), outputRate.get(), goalCost.get(), serverCost.get(), npcCalls.get(), serverCalls.get())

    fun write(values: InferenceSettings) {
        verified.set(values.verifiedByteLevel); endpoint.set(values.verifiedBaseUrl); model.set(values.verifiedModel)
        backend.set(values.backendVersion); modelDigest.set(values.modelDigest)
        tokenizer.set(values.tokenizerDigest); template.set(values.templateDigest)
        reserve.set(values.templateReserve); window.set(values.contextWindow); input.set(values.inputTokens)
        inputRate.set(values.inputMicrosPerMillion); outputRate.set(values.outputMicrosPerMillion)
        goalCost.set(values.goalCostMicros); serverCost.set(values.hourlyCostMicros)
        npcCalls.set(values.npcCallsPerHour); serverCalls.set(values.serverCallsPerHour)
    }

    private fun evidence(builder: ForgeConfigSpec.Builder, name: String) =
        builder.define(name, "") { it is String && InferenceSettings.validEvidence(it) }
}
