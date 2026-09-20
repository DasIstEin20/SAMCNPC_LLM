package io.samcnpc.llm.context

import com.google.gson.JsonElement
import io.samcnpc.behavior.api.*
import io.samcnpc.core.api.*
import io.samcnpc.llm.context.ContextJson.obj
import io.samcnpc.llm.context.ContextJson.text
import io.samcnpc.llm.context.ContextJson.number
import io.samcnpc.llm.context.ContextJson.flag
import io.samcnpc.llm.context.ContextJson.array
import io.samcnpc.llm.context.ContextJson.position
import io.samcnpc.llm.context.ContextJson.vector

internal object WorldContext {
    fun world(world: OperationWorldInspection?, capturedTick: Long, staleAfterTicks: Int): JsonElement {
        if (world == null) return obj("state" to text("NOT_REQUESTED"))
        return obj("source" to text(world.source.name), "dimension" to text(world.dimensionId),
            "capturedTick" to number(world.observedTick),
            "entities" to entities(world.entities, capturedTick, staleAfterTicks),
            "blocks" to array(world.blocks.map { entry ->
                val observed = entry.observation
                obj("requestedCell" to position(entry.requested), "observation" to when (observed) {
                    is NpcVisualBlockRead.Unavailable -> obj("state" to text("UNAVAILABLE"), "reason" to text(observed.reason.name))
                    is NpcVisualBlockRead.Observed -> obj("state" to text("VISUALLY_OBSERVED"),
                        "age" to age(observed.observedTick, capturedTick, staleAfterTicks),
                        "block" to text(observed.block.blockId), "position" to position(observed.block.position),
                        "solid" to flag(observed.block.isSolid), "containerSurface" to flag(observed.block.hasContainer),
                        "containerContents" to text("UNKNOWN"))
                })
            }))
    }

    private fun entities(scan: NpcVisualEntityScan?, capturedTick: Long, staleAfterTicks: Int): JsonElement = when (scan) {
        null -> obj("state" to text("NOT_REQUESTED"))
        is NpcVisualEntityScan.Unavailable -> obj("state" to text("UNAVAILABLE"), "reason" to text(scan.reason.name))
        is NpcVisualEntityScan.Observed -> obj("state" to text("VISUALLY_OBSERVED"),
            "truncated" to flag(scan.truncated), "age" to age(scan.observedTick, capturedTick, staleAfterTicks),
            "values" to array(scan.entities.map {
                obj("uuid" to text(it.uuid.toString()), "type" to text(it.typeId), "position" to position(it.position),
                    "velocity" to vector(it.velocity), "alive" to flag(it.alive), "player" to flag(it.isPlayer),
                    "droppedItem" to (it.droppedItem?.let { item ->
                        obj("item" to text(item.itemId), "count" to number(item.count))
                    } ?: text(null)))
            }))
    }

    fun reservations(values: List<OperationWorkReservation>): JsonElement = obj(
        "semantics" to text("OWN_COORDINATION_NOT_AUTHORITY_OR_STOCK"),
        "values" to array(values.map {
            obj("kind" to text(it.kind.name), "scopeId" to text(it.scopeId.toString()),
                "dimension" to text(it.dimensionId), "anchorIntent" to position(it.anchor),
                "timing" to when (val timing = it.timing) {
                    is OperationReservationTiming.Held -> obj("state" to text("HELD"), "expiresTick" to number(timing.expiresAtTick))
                    is OperationReservationTiming.Requested -> obj("state" to text("REQUESTED"),
                        "firstRequestTick" to number(timing.firstRequestTick), "lastRequestTick" to number(timing.lastRequestTick))
                })
        }))

    fun age(observedTick: Long, capturedTick: Long, staleAfterTicks: Int): JsonElement {
        require(observedTick in 0..capturedTick && staleAfterTicks >= 0)
        return obj("observedTick" to number(observedTick), "ageTicksAtCapture" to number(capturedTick - observedTick),
            "staleAtCapture" to flag(capturedTick - observedTick > staleAfterTicks),
            "staleAfterTicks" to number(staleAfterTicks))
    }
}
