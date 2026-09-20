package io.samcnpc.llm.context

import io.samcnpc.behavior.api.*
import java.util.UUID

internal enum class LlmMode { TRANSLATOR, SUPERVISOR, PLANNER }

/** Typed server policy, separate from user/model prose. Admission must enforce these same fields. */
internal class ContextPolicy(
    val revision: Long,
    operations: Set<OperationType>,
    changes: Set<String>,
    controls: Set<OperationControl>,
    val maxTaskTicks: Int,
    val maxTaskAttempts: Int,
    val maxExtensionTicks: Int,
) {
    val operations: Set<OperationType> = java.util.Set.copyOf(operations)
    val changes: Set<String> = java.util.Set.copyOf(changes)
    val controls: Set<OperationControl> = java.util.Set.copyOf(controls)
    init {
        require(revision >= 0 && maxTaskTicks > 0 && maxTaskAttempts > 0 && maxExtensionTicks >= 0)
        require(changes.all { it in OperationCatalogApi.snapshot().changes })
    }
}

internal data class ContextPlaceAlias(val name: String, val dimensionId: String,
                                     val position: io.samcnpc.core.api.NpcBlockPosition) {
    init { require(name.length in 1..64 && dimensionId.length in 1..256) }
}

/** Intent/labels and server-confirmed results only; never remembered world state or an unlimited chat. */
internal class ContextMemory(plan: List<String> = emptyList(), aliases: List<ContextPlaceAlias> = emptyList(),
                             confirmedResults: List<String> = emptyList()) {
    val plan: List<String> = java.util.List.copyOf(plan)
    val aliases: List<ContextPlaceAlias> = java.util.List.copyOf(aliases)
    val confirmedResults: List<String> = java.util.List.copyOf(confirmedResults)
    init {
        require(plan.size <= 8 && plan.all { it.length in 1..256 })
        require(aliases.size <= 16 && aliases.map { it.name }.distinct().size == aliases.size)
        require(confirmedResults.size <= 16 && confirmedResults.all { it.length in 1..256 })
    }
}

internal class ContextGoal(val id: UUID, val revision: Long, val text: String, val mode: LlmMode,
                           val deadlineTick: Long?, val remainingCalls: Int, val memory: ContextMemory = ContextMemory(),
                           val supervision: io.samcnpc.llm.supervision.StockSupervision? = null,
                           val planStepsCompleted: Int = 0) {
    init {
        require(revision >= 0 && text.length in 1..2048 && remainingCalls in 0..24)
        require(deadlineTick == null || deadlineTick >= 0)
        require(planStepsCompleted in 0..8 && (mode == LlmMode.PLANNER || planStepsCompleted == 0))
    }
}

internal data class ContextBinding(
    val contextId: UUID,
    val npcUuid: UUID,
    val actorUuid: UUID,
    val goalId: UUID,
    val goalRevision: Long,
    val policyRevision: Long,
    val generations: OperationGenerations,
    val catalogHash: String,
    val issuedTick: Long,
    val expiresTick: Long,
    val priorTaskId: UUID?,
    val definitionRevision: Int?,
    val controlRevision: Long?,
)

internal class CapturedContext(
    val binding: ContextBinding,
    val inspection: OperationInspection,
    val goal: ContextGoal,
    val policy: ContextPolicy,
    val actorIsSummoner: Boolean,
    val actorIsOperator: Boolean,
    val stock: io.samcnpc.core.api.NpcStockRead.Observed? = null,
)

internal class NpcLlmContext(val binding: ContextBinding, val stateJson: String, val utf8Bytes: Int)

internal sealed interface ContextCaptureResult {
    class Captured(val value: CapturedContext) : ContextCaptureResult
    data class Rejected(val code: String) : ContextCaptureResult
}

internal sealed interface ContextEncodingResult {
    class Encoded(val value: NpcLlmContext) : ContextEncodingResult
    data class Rejected(val code: String) : ContextEncodingResult
}
