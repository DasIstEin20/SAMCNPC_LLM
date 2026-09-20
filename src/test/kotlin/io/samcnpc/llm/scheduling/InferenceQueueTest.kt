package io.samcnpc.llm.scheduling

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class InferenceQueueTest {
    private fun wake(npc: UUID = UUID.randomUUID(), goal: UUID = UUID.randomUUID(), revision: Long = 0,
                     ready: Long = 0, reason: InferenceReason = InferenceReason.USER_GOAL) =
        InferenceWake(npc, goal, revision, ready, setOf(reason))

    @Test fun coalescingRetainsFairOrderAndRejectsOlderGoalRevisions() {
        val queue = InferenceQueue(); val a = wake(); val b = wake()
        assertNull(queue.offer(a)); assertNull(queue.offer(b))
        assertNull(queue.offer(wake(a.npcUuid, a.goalId, reason = InferenceReason.TASK_TERMINAL)))
        assertEquals(2, queue.size)
        val first = checkNotNull(queue.takeReady(0, emptySet()))
        assertEquals(a.npcUuid, first.npcUuid)
        assertEquals(setOf(InferenceReason.USER_GOAL, InferenceReason.TASK_TERMINAL), first.reasons)
        val next = wake(a.npcUuid, a.goalId, 2); assertNull(queue.offer(next))
        assertEquals("STALE_GOAL_EVENT", queue.offer(a))
        assertEquals(b.npcUuid, queue.takeReady(0, emptySet())?.npcUuid)
        assertEquals(2, queue.takeReady(0, emptySet())?.goalRevision)
    }

    @Test fun busyAndDelayedHeadsDoNotStarveReadyNpcs() {
        val queue = InferenceQueue(); val busy = wake(); val delayed = wake(ready = 10000); val ready = wake()
        for (value in listOf(busy, delayed, ready)) assertNull(queue.offer(value))
        assertEquals(ready.npcUuid, queue.takeReady(0, setOf(busy.npcUuid))?.npcUuid)
        assertNull(queue.takeReady(9999, setOf(busy.npcUuid)))
        assertEquals(delayed.npcUuid, queue.takeReady(10000, setOf(busy.npcUuid))?.npcUuid)
        assertEquals(busy.npcUuid, queue.takeReady(10000, emptySet())?.npcUuid)
    }

    @Test fun globalCapacityStillAllowsReplacementAndLifecycleRemoval() {
        val queue = InferenceQueue(); val first = wake(); assertNull(queue.offer(first))
        repeat(31) { assertNull(queue.offer(wake())) }
        assertEquals("INFERENCE_QUEUE_FULL", queue.offer(wake()))
        assertNull(queue.offer(wake(first.npcUuid, UUID.randomUUID(), 1)))
        assertEquals(32, queue.size); assertTrue(queue.remove(first.npcUuid))
        assertNull(queue.offer(wake())); queue.clear(); assertEquals(0, queue.size)
    }
}
