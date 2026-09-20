package io.samcnpc.llm.scheduling

import io.samcnpc.llm.api.*
import io.samcnpc.llm.config.ProviderSettings
import io.samcnpc.llm.context.CapturedContext
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class InferenceSchedulerTest {
    private class Transport : LlmProvider {
        var closed = 0
        override fun status(): ProviderStatus = error("unused")
        override fun complete(request: LlmRequest): LlmCall = error("rejected preparation must not invoke provider")
        override fun close() { closed++ }
    }
    private class Host(private val throwOnPrepare: Boolean = false) : InferenceHost {
        val prepared = mutableListOf<UUID>()
        val deferred = mutableListOf<String>()
        override fun prepare(wake: InferenceWake): InferencePreparation {
            if (throwOnPrepare) error("test-only host failure")
            prepared.add(wake.npcUuid)
            return InferencePreparation.Rejected("GOAL_NO_LONGER_CURRENT")
        }
        override fun started(wake: InferenceWake, captured: CapturedContext, requestId: UUID) = error("unexpected")
        override fun completed(wake: InferenceWake, captured: CapturedContext, result: InferenceResult) = error("unexpected")
        override fun settled(wake: InferenceWake, requestId: UUID, budget: InferenceBudgetView) = error("unexpected")
        override fun deferred(wake: InferenceWake, code: String, retryAtMillis: Long?) { deferred.add(code) }
    }
    private fun wake(id: UUID = UUID.randomUUID()) =
        InferenceWake(id, UUID.randomUUID(), 1, 0, setOf(InferenceReason.USER_GOAL))
    private fun scheduler(provider: Transport, host: Host) = InferenceScheduler(provider,
        ProviderSettings(enabled = true, model = "test"), InferenceTokenProfile.Unverified,
        InferenceAllocation(), host, InferenceRateGate())

    @Test fun boundedDispatchRejectsObsoleteGoalsWithoutCallingTransportOrChargingResources() {
        val transport = Transport(); val host = Host()
        scheduler(transport, host).use { scheduler ->
            val events = List(32) { wake() }
            events.forEach { assertNull(scheduler.offer(it)) }
            assertEquals("INFERENCE_QUEUE_FULL", scheduler.offer(wake()))
            scheduler.poll(0)
            assertEquals(events.take(2).map { it.npcUuid }, host.prepared)
            assertEquals(30, scheduler.queuedCount())
            assertEquals(0L, scheduler.reservedAttempts)
            assertEquals(0, scheduler.activeCount())
            scheduler.cancel(events[2].npcUuid)
            assertEquals(29, scheduler.queuedCount())
            scheduler.poll(0)
            assertEquals(events.take(2).plus(events.subList(3, 5)).map { it.npcUuid }, host.prepared)
            assertEquals(List(4) { "GOAL_NO_LONGER_CURRENT" }, host.deferred)
        }
        assertEquals(1, transport.closed)
    }

    @Test fun wrongThreadCannotOfferCancelPollOrClose() {
        val transport = Transport(); val host = Host()
        scheduler(transport, host).use { scheduler ->
            CompletableFuture.runAsync {
                assertThrows(IllegalStateException::class.java) { scheduler.offer(wake()) }
                assertThrows(IllegalStateException::class.java) { scheduler.cancel(UUID.randomUUID()) }
                assertThrows(IllegalStateException::class.java) { scheduler.poll(0) }
                assertThrows(IllegalStateException::class.java) { scheduler.close() }
            }.get(2, TimeUnit.SECONDS)
            assertEquals(0, transport.closed)
        }
    }

    @Test fun monotonicClockRollbackClosesWithoutConsumingAnotherGoal() {
        val transport = Transport(); val host = Host()
        scheduler(transport, host).use { scheduler ->
            scheduler.poll(100)
            scheduler.offer(wake()); scheduler.poll(99)
            assertEquals(1, transport.closed)
            assertEquals(0, scheduler.queuedCount())
            assertTrue(host.prepared.isEmpty())
            assertEquals("INFERENCE_SCHEDULER_CLOSED", scheduler.offer(wake()))
        }
        assertEquals(1, transport.closed)
    }

    @Test fun hostFailureClosesCoordinatorAndClearsOtherQueuedGoals() {
        val transport = Transport(); val host = Host(true)
        scheduler(transport, host).use { scheduler ->
            scheduler.offer(wake()); scheduler.offer(wake()); scheduler.poll(0)
            assertEquals(1, transport.closed)
            assertEquals(0, scheduler.queuedCount())
            assertEquals(0L, scheduler.reservedAttempts)
            assertEquals("INFERENCE_SCHEDULER_CLOSED", scheduler.offer(wake()))
        }
    }
}
