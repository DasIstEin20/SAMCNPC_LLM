package io.samcnpc.llm

import io.samcnpc.llm.config.*
import io.samcnpc.llm.scheduling.InferenceTokenProfile
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class InferenceSettingsTest {
    private val settings = ProviderSettings(enabled = true, model = "fixture-model")
    private val verified = InferenceSettings(verifiedByteLevel = true, verifiedModel = settings.model,
        backendVersion = "fixture-1", modelDigest = "model-digest", tokenizerDigest = "byte-tokenizer",
        templateDigest = "bounded-template", inputTokens = 8192)

    @Test fun unknownOrChangedTupleCannotAuthorizeTokenMetering() {
        assertEquals("UNVERIFIED_TOKEN_PROFILE", InferenceSettings().readiness(settings))
        assertEquals("TOKEN_PROFILE_EVIDENCE_REQUIRED", verified.copy(templateDigest = "").readiness(settings))
        assertEquals("TOKEN_PROFILE_ENDPOINT_MODEL_MISMATCH", verified.readiness(settings.copy(model = "different")))
        assertEquals("TOKEN_PROFILE_ENDPOINT_MODEL_MISMATCH",
            verified.readiness(settings.copy(baseUrl = "http://127.0.0.1:11434/v1")))
        assertTrue(verified.profile(settings.copy(model = "different")) is InferenceTokenProfile.Unverified)
        assertNull(verified.readiness(settings))
    }

    @Test fun contextWindowIncludesOutputAndCostRequiresGoalAndServerAllocation() {
        assertEquals("MODEL_CONTEXT_WINDOW_EXCEEDED", verified.copy(contextWindow = 8192).readiness(settings))
        val charged = verified.copy(inputMicrosPerMillion = 1_000_000, outputMicrosPerMillion = 1_000_000)
        assertEquals("GOAL_COST_BUDGET_EXHAUSTED", charged.readiness(settings))
        assertEquals("SERVER_COST_BUDGET", charged.copy(goalCostMicros = 9216).readiness(settings))
        assertNull(charged.copy(goalCostMicros = 9216, hourlyCostMicros = 9216).readiness(settings))
        assertEquals(9216, charged.allocation(settings).charge().costMicros)
    }

    @Test fun malformedEvidenceAndBudgetsAreConfigurationErrorsBeforeNetworking() {
        assertNotNull(verified.copy(tokenizerDigest = "secret\nheader").problem())
        assertNotNull(verified.copy(templateReserve = 8193).problem())
        assertNotNull(verified.copy(inputTokens = 0).problem())
        assertNotNull(verified.copy(inputMicrosPerMillion = -1).problem())
        assertNotNull(settings.copy(inference = verified.copy(inputTokens = 0)).problem())
    }

    @Test fun largeVerifiedReservationsFundEveryAdvertisedGoalCall() {
        val profile = verified.copy(contextWindow = 65536, inputTokens = 63488,
            npcCallsPerHour = 60, serverCallsPerHour = 120)
        val charge = profile.allocation(settings).charge()
        val budget = io.samcnpc.llm.scheduling.InferenceBudget(profile.goalLimits(settings.maxOutputTokens))
        repeat(24) {
            val request = java.util.UUID.randomUUID()
            assertNull(budget.reserve(request, charge))
            assertTrue(budget.settle(request))
        }
        assertEquals("GOAL_CALL_BUDGET_EXHAUSTED", budget.problem(charge))
        val rates = io.samcnpc.llm.scheduling.InferenceRateGate(profile.serverResources(settings.maxOutputTokens))
        val npc = java.util.UUID.randomUUID()
        repeat(60) { assertEquals(io.samcnpc.llm.scheduling.RatePermit.Granted, rates.reserve(npc, it * 10000L, charge)) }
        assertEquals(io.samcnpc.llm.scheduling.RatePermit.Deferred("NPC_HOURLY_BUDGET", 3600000),
            rates.preview(npc, 600000L, charge))
        rates.reconfigure(profile.copy(npcCallsPerHour = 2).serverResources(settings.maxOutputTokens))
        assertEquals(60, rates.reservedInCurrentWindow())
        assertEquals(io.samcnpc.llm.scheduling.RatePermit.Deferred("NPC_HOURLY_BUDGET", 3600000),
            rates.preview(npc, 600000L, charge))
    }

    @Test fun callCapsRejectInvalidValues() {
        assertNotNull(verified.copy(npcCallsPerHour = 0).problem())
        assertNotNull(verified.copy(npcCallsPerHour = 361).problem())
        assertNotNull(verified.copy(serverCallsPerHour = 721).problem())
    }
}
