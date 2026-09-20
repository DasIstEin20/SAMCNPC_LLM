package io.samcnpc.llm.context

import com.google.gson.JsonElement
import io.samcnpc.behavior.api.*
import io.samcnpc.llm.context.ContextJson.obj
import io.samcnpc.llm.context.ContextJson.text
import io.samcnpc.llm.context.ContextJson.number
import io.samcnpc.llm.context.ContextJson.flag
import io.samcnpc.llm.context.ContextJson.array

internal object TaskContext {
    fun task(inspection: OperationInspection): JsonElement {
        val task = inspection.operation.task ?: return obj("state" to text("NO_TASK"))
        return obj("taskId" to text(task.taskId.toString()), "objectiveId" to text(task.objectiveId.toString()),
            "definitionRevision" to number(task.definitionRevision), "controlRevision" to number(task.controlRevision),
            "state" to text(task.state.name), "reasonCode" to text(task.reason),
            "totalFailures" to number(task.totalFailures), "completedInterruptions" to number(task.completedInterruptions),
            "pendingAmendmentId" to text(task.pendingAmendmentId?.toString()),
            "stackOrder" to text("PRIMARY_FIRST_ACTIVE_LAST"),
            "frames" to array(task.frames.map { frame ->
                val details = inspection.frames.single { it.frameId == frame.frameId }
                obj("frameId" to text(frame.frameId.toString()), "operationId" to text(frame.operationId),
                    "definitionVersion" to number(details.definition.definitionVersion),
                    "parameters" to encodeValue(details.definition.parameters), "parameterProvenance" to text("TASK_INTENT"),
                    "remainingTicks" to number(frame.remainingTicks), "durationTicks" to number(frame.durationTicks),
                    "failureAttempts" to number(frame.failures), "attemptLimit" to number(frame.attemptLimit),
                    "waitTicks" to number(frame.waitTicks), "reasonCode" to text(frame.reason),
                    "progress" to progress(details.progress), "resources" to resources(details.resources))
            }))
    }

    private fun progress(progress: OperationProgress): JsonElement = when (progress) {
        OperationProgress.NotInitialized -> obj("state" to text("NOT_INITIALIZED"))
        is OperationProgress.Observed -> obj("state" to text("OBSERVED_TASK_CHECKPOINT"),
            "phase" to text(progress.phase), "counters" to counts(progress.counters),
            "uncertain" to flag(progress.uncertain), "reconciliationRequired" to flag(progress.reconciliationRequired),
            "stopReasonCode" to text(progress.stopReason))
    }

    private fun resources(resources: OperationResourceInspection): JsonElement = when (resources) {
        OperationResourceInspection.NotTracked -> obj("state" to text("NOT_TRACKED"))
        OperationResourceInspection.NotInitialized -> obj("state" to text("NOT_INITIALIZED"))
        is OperationResourceInspection.Checkpoint -> obj("state" to text("TASK_ACCOUNTING_CHECKPOINT"),
            "kind" to text(resources.kind.name), "uncertain" to flag(resources.uncertain),
            "reconciliationRequired" to flag(resources.reconciliationRequired),
            "currentContainerStockKnown" to flag(false), "checkpointAgeTicks" to text(null),
            "items" to array(resources.items.map {
                obj("item" to text(it.itemId), "counters" to counts(it.counters))
            }))
    }

    private fun counts(values: List<OperationMeasuredCount>): JsonElement = array(values.map {
        obj("name" to text(it.name), "value" to number(it.value), "unit" to text(it.unit.name))
    })

    private fun encodeValue(value: OperationValue): JsonElement = when (value) {
        OperationValue.Absent -> text(null)
        is OperationValue.Text -> text(value.value)
        is OperationValue.Whole -> number(value.value)
        is OperationValue.Decimal -> number(value.value)
        is OperationValue.Flag -> flag(value.value)
        is OperationValue.Sequence -> array(value.values.map(::encodeValue))
        is OperationValue.Record -> {
            val result = com.google.gson.JsonObject()
            for ((name, field) in value.fields) result.add(name, encodeValue(field))
            result
        }
    }
}
