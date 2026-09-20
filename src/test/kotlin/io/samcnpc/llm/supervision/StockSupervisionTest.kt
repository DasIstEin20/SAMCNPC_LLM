package io.samcnpc.llm.supervision

import io.samcnpc.core.api.NpcBlockPosition
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class StockSupervisionTest {
    private val target = StockTarget("minecraft:overworld", NpcBlockPosition(0, 64, 0), "minecraft:oak_log", 192, 256)
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)
    private val world = "0".repeat(64)

    @Test fun initialFillAndArmedLowWatermarkHaveDistinctThresholds() {
        assertTrue(target.needsRefill(220, false))
        assertFalse(target.needsRefill(256, false))
        assertFalse(target.needsRefill(220, true))
        assertFalse(target.needsRefill(192, true))
        assertTrue(target.needsRefill(191, true))
        assertTrue(target.sameStorage(target.copy(low = 128, target = 192)))
        assertFalse(target.sameStorage(target.copy(dimensionId = "minecraft:the_nether")))
        assertThrows(IllegalArgumentException::class.java) { target.copy(low = 0) }
        assertThrows(IllegalArgumentException::class.java) { target.copy(low = 257) }
    }

    @Test fun identicalAndAlternatingFailuresAreBoundedWithoutDependingOnTaskIds() {
        val first = FailedDecisions().failed(a, world, "PATH_FAILED")
        assertNull(first.problem(a, world))
        val second = first.failed(a, world, "PATH_FAILED")
        assertEquals("REPEATED_FAILED_DECISION", second.problem(a, world))
        assertNull(second.problem(b, world))
        val alternating = first.failed(b, world, "DESTINATION_FULL").failed(a, "1".repeat(64), "SOURCE_EMPTY")
        assertEquals("NONPROGRESS_DECISION_LIMIT", alternating.problem())
        assertNull(alternating.progressed().problem(b, "2".repeat(64)))
        assertEquals(3, alternating.progressed().entries.size)
    }

    @Test fun ledgerCopiesCallerListAndKeepsOnlyEightHistoricalFailures() {
        val original = mutableListOf(FailedDecision(a, world, "PATH_FAILED"))
        val captured = FailedDecisions(original, 1)
        original.clear()
        assertEquals(1, captured.entries.size)
        assertThrows(UnsupportedOperationException::class.java) { (captured.entries as MutableList).clear() }
        var value = FailedDecisions()
        repeat(20) { value = value.failed(a, world, "PATH_FAILED").progressed() }
        assertEquals(8, value.entries.size)
        assertThrows(IllegalArgumentException::class.java) { FailedDecision("raw prompt", world, "PATH_FAILED") }
        assertThrows(IllegalArgumentException::class.java) { StockSupervision(target, pendingDecision = a) }
    }
}
