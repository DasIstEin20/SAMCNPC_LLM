package io.samcnpc.llm.config

import java.net.URI
import java.net.URISyntaxException

/** Immutable configuration; contains an environment variable name, never its secret value. */
internal data class ProviderSettings(
    val enabled: Boolean = false,
    val baseUrl: String = DEFAULT_BASE_URL,
    val model: String = "",
    val apiKeyEnvironment: String = "SAMCNPC_LLM_API_KEY",
    val connectTimeoutSeconds: Int = 5,
    val requestTimeoutSeconds: Int = 45,
    val temperature: Double = 0.1,
    val maxOutputTokens: Int = 1024,
    val maxContextBytes: Int = 65_536,
    val maxResponseBytes: Int = 262_144,
    val responseFormat: ResponseFormat = ResponseFormat.JSON_SCHEMA,
    val inference: InferenceSettings = InferenceSettings(),
) {
    fun problem(): String? = when {
        !validBaseUrl(baseUrl) -> "baseUrl"
        !validModel(model) || (enabled && model.isBlank()) -> "model"
        !validEnvironment(apiKeyEnvironment) -> "apiKeyEnvironment"
        connectTimeoutSeconds !in 1..30 -> "connectTimeoutSeconds"
        requestTimeoutSeconds !in 1..120 -> "requestTimeoutSeconds"
        !temperature.isFinite() || temperature !in 0.0..2.0 -> "temperature"
        maxOutputTokens !in 64..4096 -> "maxOutputTokens"
        maxContextBytes !in 1024..65_536 -> "maxContextBytes"
        maxResponseBytes !in 1024..262_144 -> "maxResponseBytes"
        inference.problem() != null -> "inference." + inference.problem()
        else -> null
    }

    fun completionUri(): URI {
        require(validBaseUrl(baseUrl)) { "Invalid baseUrl" }
        return URI(baseUrl.trimEnd('/') + "/chat/completions")
    }

    companion object {
        const val DEFAULT_BASE_URL = "http://127.0.0.1:1234/v1"

        fun validBaseUrl(value: String): Boolean {
            if (value.length !in 1..512 || value.any { it <= ' ' }) return false
            val uri = try { URI(value) } catch (_: URISyntaxException) { return false }
            return uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() &&
                uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
                (uri.port == -1 || uri.port in 1..65535) &&
                !uri.rawPath.orEmpty().contains('%') &&
                uri.path.orEmpty().split('/').none { it == "." || it == ".." }
        }

        fun validModel(value: String): Boolean = value.length <= 256 &&
            value == value.trim() && value.none { it.isISOControl() }

        fun validEnvironment(value: String): Boolean = value.length <= 128 &&
            (value.isEmpty() || (value.first() in 'A'..'Z' || value.first() in 'a'..'z' || value.first() == '_') &&
                value.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_' })
    }
}

internal enum class ResponseFormat { JSON_SCHEMA, JSON_OBJECT }
