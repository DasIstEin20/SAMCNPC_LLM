package io.samcnpc.llm.context

import io.samcnpc.core.api.*
import kotlin.math.floor

/** Decision-boundary surface discovery through the real-eye sensor, never raw world/container reads. */
internal object NearbyVisualCells {
    const val MAX_READS = 128
    const val MAX_RESULTS = 8
    private val offsets = buildList {
        for (x in -4..4) for (z in -4..4) for (y in 0..2)
            if (x * x + y * y + z * z <= 16) add(NpcBlockPosition(x, y, z))
    }.sortedWith(compareBy<NpcBlockPosition> { it.x * it.x + it.y * it.y + it.z * it.z }
        .thenBy { it.x }.thenBy { it.y }.thenBy { it.z }).take(MAX_READS)

    fun discover(feet: NpcPosition, readVisible: (NpcBlockPosition) -> NpcVisualBlockRead): List<NpcBlockPosition> {
        val origin = NpcBlockPosition(floor(feet.x).toInt(), floor(feet.y).toInt(), floor(feet.z).toInt())
        val containers = mutableListOf<NpcBlockPosition>()
        val wood = mutableListOf<NpcBlockPosition>()
        for (offset in offsets) {
            val cell = NpcBlockPosition(origin.x + offset.x, origin.y + offset.y, origin.z + offset.z)
            val read = readVisible(cell)
            if (read !is NpcVisualBlockRead.Observed) continue
            val block = read.block
            if (block.hasContainer) containers.add(cell)
            else if (block.blockId.endsWith("_log") || block.blockId.endsWith("_wood")) wood.add(cell)
        }
        return (containers + wood).take(MAX_RESULTS)
    }
}
