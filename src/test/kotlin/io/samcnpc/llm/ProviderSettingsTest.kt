package io.samcnpc.llm

import io.samcnpc.llm.config.ProviderSettings
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ProviderSettingsTest {
    @Test fun defaultIsDisabledAndDoesNotInventAModel() {
        val values = ProviderSettings()
        assertFalse(values.enabled)
        assertEquals("", values.model)
        assertNull(values.problem())
        assertEquals("http://127.0.0.1:1234/v1/chat/completions", values.completionUri().toString())
        assertEquals("model", values.copy(enabled = true).problem())
    }

    @Test fun urlsKeepConfiguredBasePathWithoutCredentialsOrAmbiguousParts() {
        for (url in listOf("http://localhost:11434/v1", "http://[::1]:1234/v1/",
            "https://llm.example/api/v1", "http://192.168.1.50:8080/v1")) {
            val values = ProviderSettings(baseUrl = url)
            assertNull(values.problem(), url)
            assertEquals(url.trimEnd('/') + "/chat/completions", values.completionUri().toString())
        }
        for (url in listOf("file:///tmp/model", "http://", "http://host:0/v1", "http://host:65536",
            "http://user:secret@localhost/v1", "http://localhost/v1?key=secret",
            "http://localhost/v1#secret", "http://localhost/../v1", "http://localhost/%2e/v1",
            " http://localhost/v1", "http://localhost/v1\n", "http://localhost/" + "a".repeat(512))) {
            assertEquals("baseUrl", ProviderSettings(baseUrl = url).problem(), url)
        }
    }

    @Test fun limitsRejectNonFiniteNumbersAndSecretsCannotMasqueradeAsEnvironmentNames() {
        assertEquals("temperature", ProviderSettings(temperature = Double.NaN).problem())
        assertEquals("temperature", ProviderSettings(temperature = Double.POSITIVE_INFINITY).problem())
        assertEquals("connectTimeoutSeconds", ProviderSettings(connectTimeoutSeconds = 0).problem())
        assertEquals("requestTimeoutSeconds", ProviderSettings(requestTimeoutSeconds = 121).problem())
        assertEquals("maxContextBytes", ProviderSettings(maxContextBytes = 65_537).problem())
        assertEquals("maxResponseBytes", ProviderSettings(maxResponseBytes = 262_145).problem())
        assertEquals("maxOutputTokens", ProviderSettings(maxOutputTokens = 4097).problem())
        assertEquals("apiKeyEnvironment", ProviderSettings(apiKeyEnvironment = "secret-key").problem())
        assertEquals("model", ProviderSettings(model = "bad\r\nmodel").problem())
        assertNull(ProviderSettings(apiKeyEnvironment = "", model = "local/model:q4").problem())
    }
}
