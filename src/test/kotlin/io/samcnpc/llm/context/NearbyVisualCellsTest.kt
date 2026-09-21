package io.samcnpc.llm.context

import io.samcnpc.core.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NearbyVisualCellsTest {
    @Test fun boundedDiscoveryUsesOnlyVisibleSurfacesAndPrioritizesContainers() {
        val seen = mutableListOf<NpcBlockPosition>()
        val chest = NpcBlockPosition(-1, 64, 0)
        val result = NearbyVisualCells.discover(NpcPosition(-0.2, 64.0, 0.5)) { cell ->
            seen.add(cell)
            NpcVisualBlockRead.Observed(10, NpcBlockObservation(cell,
                if (cell == chest) "minecraft:chest" else "minecraft:oak_log", false, true, cell == chest))
        }
        assertEquals(chest, result.first())
        assertTrue(seen.size <= NearbyVisualCells.MAX_READS)
        assertEquals(seen.size, seen.distinct().size)
        assertEquals(NearbyVisualCells.MAX_RESULTS, result.size)
        assertTrue(seen.all { kotlin.math.abs(it.x + 1) <= 4 && it.y in 64..66 && kotlin.math.abs(it.z) <= 4 })
    }

    @Test fun airAndUninterestingVisibleBlocksDoNotBecomeKnownResources() {
        val result = NearbyVisualCells.discover(NpcPosition(0.5, 64.0, 0.5)) { cell ->
            NpcVisualBlockRead.Observed(10, NpcBlockObservation(cell, "minecraft:stone", false, true, false))
        }
        assertTrue(result.isEmpty())
    }
}
