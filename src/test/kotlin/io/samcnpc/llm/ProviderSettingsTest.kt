package io.samcnpc.llm

import io.samcnpc.llm.config.ProviderSettings
import io.samcnpc.llm.config.InferenceSettings
import io.samcnpc.llm.scheduling.InferenceQuotaMode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ProviderSettingsTest {
    @Test fun quotaModesAreExplicitIndependentAndCannotDisablePhysicalRequestBoundsOrProfileVerification() {
        val mode = InferenceQuotaMode.UNLIMITED
        val settings = ProviderSettings(model = "fixture")
        val profile = InferenceSettings(verifiedByteLevel = true, verifiedModel = "fixture", backendVersion = "fixture",
            modelDigest = "fixture", tokenizerDigest = "fixture", templateDigest = "fixture",
            inputMicrosPerMillion = 1_000_000, outputMicrosPerMillion = 1_000_000)
        assertEquals("GOAL_COST_BUDGET_EXHAUSTED", profile.readiness(settings))
        assertEquals("SERVER_COST_BUDGET", profile.copy(goalQuotaMode = mode).readiness(settings))
        assertEquals("GOAL_COST_BUDGET_EXHAUSTED", profile.copy(hourlyQuotaMode = mode).readiness(settings))
        val unlimited = profile.copy(goalQuotaMode = mode, hourlyQuotaMode = mode)
        assertNull(unlimited.readiness(settings))
        assertEquals(mode, unlimited.goalLimits().quotaMode); assertEquals(mode, unlimited.serverResources().quotaMode)
        assertEquals("UNVERIFIED_TOKEN_PROFILE", unlimited.copy(verifiedByteLevel = false).readiness(settings))
        assertEquals("TOKEN_PROFILE_ENDPOINT_MODEL_MISMATCH", unlimited.readiness(settings.copy(baseUrl = "https://example.com/v1")))
        assertEquals("MODEL_CONTEXT_WINDOW_EXCEEDED", unlimited.copy(inputTokens = 32768).readiness(settings))
        assertEquals("inference.contextAllocation", settings.copy(inference = unlimited.copy(inputTokens = 32768)).problem())
        for (url in listOf("http://127.0.0.1:1234/v1", "https://example.com/v1")) {
            val defaults = ProviderSettings(baseUrl = url)
            assertEquals(InferenceQuotaMode.LIMITED, defaults.inference.goalQuotaMode)
            assertEquals(InferenceQuotaMode.LIMITED, defaults.inference.hourlyQuotaMode)
        }
    }

    @Test fun settingsRejectCombinedInputAndOutputBeyondTheDeclaredModelWindow() {
        val settings = ProviderSettings()
        val overflow = settings.copy(inference = settings.inference.copy(inputTokens = 32768))
        assertEquals("inference.contextAllocation", overflow.problem())
        assertEquals("inference.contextAllocation", overflow.copy(enabled = true, model = "fixture").problem())
        assertNull(settings.copy(inference = settings.inference.copy(inputTokens = 31744)).problem())
        assertEquals("inference.contextAllocation", settings.copy(maxOutputTokens = 4096,
            inference = settings.inference.copy(inputTokens = 31744)).problem())
    }

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
