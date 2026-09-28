package io.samcnpc.llm.mission

import io.samcnpc.behavior.api.*
import io.samcnpc.core.api.*
import io.samcnpc.llm.goal.GoalTask
import io.samcnpc.llm.goal.TranslatorOutcomes
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

/** Authorized public API reads, once per decision/operation boundary; no cached stock or hidden scans. */
internal object MissionObservation {
    fun evaluate(server: MinecraftServer, actor: ServerPlayer, npc: UUID, state: MissionState,
                 inspection: OperationInspection, expected: GoalTask? = null): MissionEvaluation {
        check(server.isSameThread)
        val contract = checkNotNull(state.contract)
        val carried = linkedMapOf<String, Long>()
        // mainHand aliases inventory[selectedHotbarSlot]; equipment contains only distinct physical stores.
        for (item in inspection.body.inventory + inspection.body.equipment.values) {
            val id = item.stack.itemId ?: continue
            carried[id] = (carried[id] ?: 0L) + item.stack.count
        }
        val queries = contract.requirements.mapNotNull { it.target as? MissionTarget.Items }
            .filter { it.chest != null }.groupBy { checkNotNull(it.chest) }
        val storage = linkedMapOf<MissionChest, Map<String, Long>?>()
        for ((chest, targets) in queries) {
            val counts = linkedMapOf<String, Long>()
            var observed = true
            for (id in targets.flatMap { it.itemIds }.distinct()) {
                val reply = OperationStockApi.inspect(server, actor, npc, chest.dimension, NpcStockQuery(chest.position, id))
                val stock = reply.stock as? NpcStockRead.Observed
                if (reply.result.status != NpcActionStatus.SUCCEEDED || stock == null ||
                    stock.observedTick != inspection.physical.gameTime) { observed = false; break }
                counts[id] = stock.count.toLong()
            }
            storage[chest] = if (observed) counts else null
        }
        val task = inspection.operation.task
        val completed = expected != null && task != null && task.taskId == expected.id &&
            task.definitionRevision == expected.definitionRevision && task.state == OperationTaskState.COMPLETED &&
            TranslatorOutcomes.controlMatches(expected.controlRevision, task.controlRevision, task.state)
        val frame = if (completed) inspection.frames.firstOrNull() else null
        val facts = MissionFacts(inspection.physical.dimensionId, inspection.physical.position, carried, storage,
            if (completed) task?.taskId else null, frame?.let(::preparedField), frame?.let(::collectedChest))
        return MissionEvaluator.evaluate(contract, state.receipts, facts)
    }

    private fun collectedChest(frame: OperationFrameInspection): MissionChest? {
        val definition = frame.definition
        if (definition.operationId != "samcnpc:inventory_work") return null
        val fields = definition.parameters.fields
        val work = fields["work"] as? OperationValue.Record ?: return null
        if ((work.fields["kind"] as? OperationValue.Text)?.value != "COLLECT") return null
        val dimension = (fields["dimensionId"] as? OperationValue.Text)?.value ?: return null
        val source = block(work.fields["source"]) ?: return null
        return MissionChest(dimension, source)
    }

    private fun preparedField(frame: OperationFrameInspection): MissionTarget.Field? {
        val definition = frame.definition
        if (definition.operationId != "samcnpc:prepare_field") return null
        val progress = frame.progress as? OperationProgress.Observed ?: return null
        if (progress.uncertain == true || progress.reconciliationRequired == true || progress.stopReason != null) return null
        val fields = definition.parameters.fields
        val dimension = (fields["dimensionId"] as? OperationValue.Text)?.value ?: return null
        val area = fields["area"] as? OperationValue.Record ?: return null
        val exclusions = area.fields["exclusions"] as? OperationValue.Sequence ?: return null
        if (exclusions.values.isNotEmpty()) return null
        val bounds = area.fields["bounds"] as? OperationValue.Record ?: return null
        val min = block(bounds.fields["min"]) ?: return null
        val max = block(bounds.fields["max"]) ?: return null
        // A larger valid Behavior field need not fit this experiment's smaller mission bound.
        if (min.y != max.y || min.x > max.x || min.z > max.z ||
            (max.x.toLong() - min.x + 1) * (max.z.toLong() - min.z + 1) !in 1..256) return null
        val field = MissionTarget.Field(dimension, min, max)
        fun count(name: String) = progress.counters.singleOrNull { it.name == name && it.unit == OperationCountUnit.CELLS }?.value
        return field.takeIf { count("preparedCells") == field.cells.toLong() && count("totalCells") == field.cells.toLong() }
    }

    private fun block(value: OperationValue?): NpcBlockPosition? {
        val fields = (value as? OperationValue.Record)?.fields ?: return null
        val x = (fields["x"] as? OperationValue.Whole)?.value ?: return null
        val y = (fields["y"] as? OperationValue.Whole)?.value ?: return null
        val z = (fields["z"] as? OperationValue.Whole)?.value ?: return null
        if (x !in -29999984..29999984 || z !in -29999984..29999984 || y !in -2048..2048) return null
        return NpcBlockPosition(x.toInt(), y.toInt(), z.toInt())
    }
}
