package io.samcnpc.llm.context

import com.google.gson.JsonElement
import io.samcnpc.behavior.api.*
import io.samcnpc.core.api.NpcActionChannel
import io.samcnpc.core.api.NpcActionCode
import io.samcnpc.llm.context.ContextJson.obj
import io.samcnpc.llm.context.ContextJson.text
import io.samcnpc.llm.context.ContextJson.number
import io.samcnpc.llm.context.ContextJson.flag
import io.samcnpc.llm.context.ContextJson.array

internal object HistoryContext {
    fun journal(state: OperationJournalState, recentLimit: Int = 16): JsonElement {
        require(recentLimit in 0..16)
        if (state == OperationJournalState.NotRecorded) return obj("state" to text("NOT_RECORDED"))
        check(state is OperationJournalState.Recorded)
        val recent = state.events.takeLast(recentLimit)
        val failures = state.events.filter(::isFailure).takeLast(8)
        val counts = com.google.gson.JsonObject()
        for ((code, entries) in failures.groupBy(::category).toSortedMap()) counts.addProperty(code, entries.size)
        return obj("state" to text("RECORDED"), "sampling" to text("END_TICK_COALESCED"),
            "journalId" to text(state.journalId.toString()), "coverageStartTick" to number(state.coverageStartTick),
            "lastSampleTick" to number(state.lastSampleTick), "latestSequence" to number(state.latestSequence),
            "oldestRetainedSequence" to number(state.oldestAvailableSequence),
            "discardedJournalEntries" to number(state.discardedEntries),
            "omittedRecentEntries" to number(state.events.size - recent.size),
            "omittedRetainedFailures" to number(state.events.count(::isFailure) - failures.size),
            "recentEvents" to array(recent.map(::event)), "recentFailures" to array(failures.map(::event)),
            "failureSummaryScope" to text("DISPLAYED_FAILURE_ENTRIES_NOT_ALL_HISTORICAL_ATTEMPTS"),
            "failureSummary" to counts)
    }

    private fun event(event: OperationJournalEvent): JsonElement = obj(
        "sequence" to number(event.sequence), "observedTick" to number(event.observedTick),
        "kind" to text(event.kind.name), "decisionBoundary" to flag(event.kind.decisionBoundary),
        "taskId" to text(event.taskId?.toString()), "objectiveId" to text(event.objectiveId?.toString()),
        "frameId" to text(event.frameId?.toString()), "operationId" to text(event.operationId),
        "taskState" to text(event.taskState?.name), "taskReasonCode" to text(event.taskReason),
        "definitionRevision" to number(event.definitionRevision), "controlRevision" to number(event.controlRevision),
        "totalTaskFailures" to number(event.totalTaskFailures), "frameFailures" to number(event.frameFailures),
        "attemptLimit" to number(event.attemptLimit), "actionId" to text(event.actionId?.toString()),
        "actionCode" to text(event.actionCode?.name), "actionChannel" to text(event.actionChannel?.name),
        "coalesced" to flag(event.coalesced), "failureCategory" to (if (isFailure(event)) text(category(event)) else text(null)),
        "preciseFailedTarget" to text(null), "failurePhase" to text(null), "recoveryStepsAlreadyTried" to text(null),
        "unavailableFieldsReason" to text("NOT_EXPOSED_BY_SOURCE_EVENT"))

    private fun isFailure(event: OperationJournalEvent): Boolean = event.kind in setOf(
        OperationEventKind.ACTION_FAILED, OperationEventKind.TASK_FAILED, OperationEventKind.RETRY_OBSERVED)

    private fun category(event: OperationJournalEvent): String {
        val action = event.actionCode
        if (action != null) return when (action) {
            NpcActionCode.NO_PROGRESS -> if (event.actionChannel == NpcActionChannel.LOCOMOTION) "PATH_FAILED" else "UNKNOWN"
            NpcActionCode.OUT_OF_RANGE -> "OUT_OF_RANGE"
            NpcActionCode.MISSING_RESOURCE -> "MISSING_RESOURCE"
            NpcActionCode.UNSUITABLE_TOOL -> "MISSING_TOOL"
            NpcActionCode.WORLD_REJECTED -> "WORLD_REJECTED"
            NpcActionCode.UNSUPPORTED_MECHANIC -> "UNSUPPORTED"
            NpcActionCode.CONFLICT -> "ACTION_CONFLICT"
            else -> "UNKNOWN"
        }
        return when (event.taskReason) {
            "RETRY_LIMIT", "RECOVERY_EXHAUSTED" -> "TASK_EXHAUSTED"
            "TIME_LIMIT", "COMBAT_TIME_LIMIT" -> "TIMEOUT"
            "MISSING_RESOURCE", "SOURCE_EMPTY" -> "MISSING_RESOURCE"
            "STORAGE_FULL" -> "DESTINATION_FULL"
            "INVENTORY_FULL" -> "INVENTORY_FULL"
            else -> "UNKNOWN"
        }
    }
}
