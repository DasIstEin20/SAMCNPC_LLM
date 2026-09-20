package io.samcnpc.llm.config

import net.minecraftforge.common.ForgeConfigSpec
import net.minecraftforge.fml.ModLoadingContext
import net.minecraftforge.fml.config.ModConfig
import net.minecraftforge.fml.event.config.ModConfigEvent

/** COMMON stays on the physical server; Forge SERVER configs would send it to joining clients. */
internal object LlmConfig {
    private val builder = ForgeConfigSpec.Builder()
    private val enabled = builder.comment("Enable inference only after choosing a model. No startup request.")
        .define("enabled", false)
    private val baseUrl = builder.comment("OpenAI-compatible API base URL, including /v1. No credentials/query/fragment.")
        .define("baseUrl", ProviderSettings.DEFAULT_BASE_URL) { it is String && ProviderSettings.validBaseUrl(it) }
    private val model = builder.comment("Exact model ID loaded by your endpoint. Empty disables requests.")
        .define("model", "") { it is String && ProviderSettings.validModel(it) }
    private val apiKeyEnvironment = builder.comment("Optional environment variable containing the API key. Never put the key here.")
        .define("apiKeyEnvironment", "SAMCNPC_LLM_API_KEY") { it is String && ProviderSettings.validEnvironment(it) }
    private val connectTimeout = builder.defineInRange("connectTimeoutSeconds", 5, 1, 30)
    private val requestTimeout = builder.defineInRange("requestTimeoutSeconds", 45, 1, 120)
    private val temperature = builder.defineInRange("temperature", 0.1, 0.0, 2.0)
    private val maxOutputTokens = builder.defineInRange("maxOutputTokens", 1024, 64, 4096)
    private val maxContextBytes = builder.defineInRange("maxContextBytes", 65_536, 1024, 65_536)
    private val maxResponseBytes = builder.defineInRange("maxResponseBytes", 262_144, 1024, 262_144)
    private val responseFormat = builder.comment("Explicit backend capability. Local validation is mandatory in both modes.")
        .defineEnum("responseFormat", ResponseFormat.JSON_SCHEMA)
    val spec: ForgeConfigSpec = builder.build()
    @Volatile private var current = ConfigSnapshot(0, ProviderSettings())

    fun register() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, spec, "samcnpc-llm-common.toml")
    }

    fun snapshot(): ConfigSnapshot = current

    @Synchronized
    fun changed(event: ModConfigEvent) {
        if (event.config.getSpec<ForgeConfigSpec>() !== spec) return
        val values = if (event is ModConfigEvent.Unloading) ProviderSettings() else read()
        publish(values, event !is ModConfigEvent.Reloading)
    }

    /** CAS prevents a stale GUI draft from replacing externally reloaded server settings. */
    @Synchronized
    fun update(expectedRevision: Long, values: ProviderSettings): Boolean {
        require(values.problem() == null) { "Invalid provider field: ${values.problem()}" }
        check(spec.isLoaded) { "Configuration is not loaded" }
        if (current.revision != expectedRevision) return false
        val previous = current.values
        write(values)
        try {
            spec.save()
        } catch (exception: RuntimeException) {
            write(previous)
            throw exception
        }
        publish(values, false)
        return true
    }

    private fun read(): ProviderSettings {
        if (!spec.isLoaded) return ProviderSettings()
        return ProviderSettings(enabled.get(), baseUrl.get(), model.get(), apiKeyEnvironment.get(),
            connectTimeout.get(), requestTimeout.get(), temperature.get(), maxOutputTokens.get(),
            maxContextBytes.get(), maxResponseBytes.get(), responseFormat.get())
    }

    private fun write(values: ProviderSettings) {
        enabled.set(values.enabled)
        baseUrl.set(values.baseUrl)
        model.set(values.model)
        apiKeyEnvironment.set(values.apiKeyEnvironment)
        connectTimeout.set(values.connectTimeoutSeconds)
        requestTimeout.set(values.requestTimeoutSeconds)
        temperature.set(values.temperature)
        maxOutputTokens.set(values.maxOutputTokens)
        maxContextBytes.set(values.maxContextBytes)
        maxResponseBytes.set(values.maxResponseBytes)
        responseFormat.set(values.responseFormat)
    }

    private fun publish(values: ProviderSettings, lifecycle: Boolean) {
        if (lifecycle || current.values != values) current = ConfigSnapshot(current.revision + 1, values)
    }
}

internal data class ConfigSnapshot(val revision: Long, val values: ProviderSettings)
