package io.samcnpc.llm.goal

import io.samcnpc.core.api.NpcHandle
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class LlmNpcSelectorTest {
    private val sam = NpcHandle(UUID.fromString("7d8afd3c-0605-44fa-8b6d-217ccc2ca552"), "Sam")
    private val samuel = NpcHandle(UUID.fromString("7d8afd3c-0605-44fa-8b6d-217ccc2ca553"), "Samuel")
    private val alex = NpcHandle(UUID.fromString("8d8afd3c-0605-44fa-8b6d-217ccc2ca554"), "Alex")
    private val nearby = listOf(samuel, alex, sam)

    @Test fun exactNameWinsOverLongerNamesIgnoringCase() {
        assertEquals(listOf(sam), LlmNpcSelector.matches("sAM", nearby))
    }
    @Test fun uniqueNameAndUuidPrefixesResolve() {
        assertEquals(listOf(samuel), LlmNpcSelector.matches("samu", nearby))
        assertEquals(listOf(alex), LlmNpcSelector.matches("8D8AFD3C", nearby))
    }
    @Test fun ambiguousNamesAndShortIdsNeverSelectByIterationOrder() {
        assertEquals(setOf(sam, samuel), LlmNpcSelector.matches("Sa", nearby).toSet())
        assertEquals(setOf(sam, samuel), LlmNpcSelector.matches("7d8afd3c", nearby).toSet())
        assertEquals(2, LlmNpcSelector.matches("Sam", listOf(sam, samuel.copy(displayName = "SAM"))).size)
    }
    @Test fun fullUuidWinsEvenWhenAnotherNpcHasThatName() {
        assertEquals(listOf(sam), LlmNpcSelector.matches(sam.npcUuid.toString().uppercase(),
            nearby + alex.copy(displayName = sam.npcUuid.toString())))
    }
    @Test fun namePrefixWinsOverUuidPrefixLikeCore() {
        val named = alex.copy(displayName = "7d8afd3c-worker")
        assertEquals(listOf(named), LlmNpcSelector.matches("7d8afd3c", nearby + named))
    }
    @Test fun missingEmptyAndMalformedSelectorsDoNotChooseAnNpc() {
        for (selector in listOf("", " ", "unknown", "1-2-3-4-5"))
            assertTrue(LlmNpcSelector.matches(selector, nearby).isEmpty())
        assertTrue(LlmNpcSelector.matches("Sam", emptyList()).isEmpty())
    }
}
