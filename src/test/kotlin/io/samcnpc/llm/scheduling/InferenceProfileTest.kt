package io.samcnpc.llm.scheduling

import io.samcnpc.llm.config.ProviderSettings
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class InferenceProfileTest {
    @Test fun unverifiedAndChangedEndpointModelProfilesDoNotInventTokenCounts() {
        val settings = ProviderSettings(enabled = true, model = "pinned-model")
        assertNull(InferenceTokenProfile.Unverified.upperBound(settings, 1000))
        val profile = InferenceTokenProfile.VerifiedByteLevel(settings.baseUrl, settings.model,
            "backend-test", "model-test", "tokenizer-test", "template-test", 100)
        assertEquals(1100L, profile.upperBound(settings, 1000))
        assertNull(profile.upperBound(settings.copy(model = "different-model"), 1000))
        assertNull(profile.upperBound(settings.copy(baseUrl = "http://127.0.0.1:11434/v1"), 1000))
    }

    @Test fun capsAndCostReservationsRoundUpWithoutOverflow() {
        assertEquals(InferenceCharge(8192, 1024, 0), InferenceAllocation().charge())
        assertEquals(InferenceCharge(1, 64, 2), InferenceAllocation(1, 64, 1, 1).charge())
        val large = InferenceAllocation(131072, 4096, 1000000000, 1000000000).charge()
        assertEquals(135168000L, large.costMicros)
        assertThrows(IllegalArgumentException::class.java) { InferenceAllocation(inputTokens = 0) }
        assertThrows(IllegalArgumentException::class.java) { InferenceAllocation(inputMicrosPerMillion = Long.MAX_VALUE) }
    }
}
