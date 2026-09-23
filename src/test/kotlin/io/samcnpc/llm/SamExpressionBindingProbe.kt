package io.samcnpc.llm

import io.samcnpc.behavior.api.*
import io.samcnpc.core.api.*
import io.samcnpc.llm.api.*
import io.samcnpc.llm.config.*
import io.samcnpc.llm.context.*
import io.samcnpc.llm.decision.*
import io.samcnpc.llm.scheduling.*
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real independent NPC captures/admission; detached immediate provider stub isolates response binding. */
internal object SamExpressionBindingProbe {
    fun verify(server: MinecraftServer, actor: ServerPlayer, origin: NpcPosition): String {
        val service = CoreNpcApi.service(server)
        val dimension = actor.serverLevel().dimension().location().toString()
        val handles = (0..1).map { index ->
            val summoned = service.summon(NpcSummonRequest(actor.uuid, "ExpressionBinding$index", dimension,
                origin.copy(x = origin.x + index * 2, z = origin.z - 6), 0F))
            check(summoned.result.status == NpcActionStatus.SUCCEEDED)
            checkNotNull(summoned.handle)
        }
        val policy = ContextPolicy(1, setOf(OperationType.NAVIGATE), emptySet(), emptySet(), 12000, 3, 0)
        val goal = ContextGoal(UUID.randomUUID(), 1, "Wait for the requested destination", LlmMode.TRANSLATOR, null, 24)
        val settings = ProviderSettings(enabled = true, model = "binding-fixture", responseFormat = ResponseFormat.SAM_EXPRESSION_V1)
        val profile = InferenceTokenProfile.VerifiedByteLevel(settings.baseUrl, settings.model,
            "stub", "no-model", "test-byte-bound", "test-template", 256)
        val serverThread = Thread.currentThread().id
        fun capture(handle: NpcHandle): CapturedContext {
            val result = NpcContextBuilder.capture(server, actor, handle.npcUuid, goal, policy)
            check(result is ContextCaptureResult.Captured)
            return result.value
        }
        fun worker(input: InferenceInput, replyId: UUID = input.requestId,
                   cancellation: InferenceCancellation = InferenceCancellation(), cancelDuringCall: Boolean = false): Pair<InferenceResult, Int> {
            val calls = AtomicInteger()
            val provider = object : LlmProvider {
                override fun status(): ProviderStatus = error("Stub status is unused")
                override fun complete(request: LlmRequest): LlmCall {
                    check(Thread.currentThread().id != serverThread && request.requestId == input.requestId)
                    calls.incrementAndGet()
                    if (cancelDuringCall) cancellation.cancel()
                    val response: LlmResponse = LlmResponse.Candidate(replyId, "ask_user('Which destination?')", null)
                    return LlmCall(CompletableFuture.completedFuture(response), LlmSubmission.UNKNOWN) { true }
                }
                override fun close() = Unit
            }
            val result = CompletableFuture.supplyAsync { InferenceWork.run(input, provider, cancellation) }.get(5, TimeUnit.SECONDS)
            return result to calls.get()
        }
        try {
            val a = capture(handles[0])
            val b = capture(handles[1])
            val inputA = InferenceInput(UUID.randomUUID(), a, settings, profile, InferenceAllocation(16384))
            val inputB = InferenceInput(UUID.randomUUID(), b, settings, profile, InferenceAllocation(16384))
            val decoded = worker(inputA)
            check(decoded.first is InferenceResult.Decoded && decoded.second == 1)
            val decision = (decoded.first as InferenceResult.Decoded).decision
            check(decision.contextId == a.binding.contextId && decision.contextId != b.binding.contextId)
            val second = worker(inputB)
            check((second.first as InferenceResult.Decoded).decision.contextId == b.binding.contextId)
            for ((input, exchangedId) in listOf(inputA to inputB.requestId, inputB to inputA.requestId)) {
                val swapped = worker(input, exchangedId)
                check((swapped.first as InferenceResult.Failed).code == "RESPONSE_REQUEST_MISMATCH" && swapped.second == 1)
            }
            val wrongNpc = DecisionAdmission().also { check(it.bind(b)) }
            check(wrongNpc.admit(server, actor, decision, goal, policy, false).code == "CONTEXT_MISMATCH")
            val revised = ContextGoal(goal.id, 2, goal.text, goal.mode, null, 24)
            val stale = DecisionAdmission().also { check(it.bind(a)) }
            check(stale.admit(server, actor, decision, revised, policy, false).code == "GOAL_CHANGED")
            val cancelled = DecisionAdmission().also { check(it.bind(a)); it.invalidate() }
            check(cancelled.admit(server, actor, decision, goal, policy, false).code == "CONTEXT_INVALIDATED")
            val replay = DecisionAdmission().also { check(it.bind(a)) }
            check(replay.admit(server, actor, decision, goal, policy, false).state == DecisionOutcomeState.NO_EFFECT)
            check(replay.admit(server, actor, decision, goal, policy, false).code == "DECISION_ALREADY_CONSUMED")
            val before = worker(inputA, cancellation = InferenceCancellation().also { it.cancel() })
            check((before.first as InferenceResult.Failed).code == "CANCELLED" && before.second == 0)
            val during = worker(inputA, cancelDuringCall = true)
            check((during.first as InferenceResult.Failed).code == "CANCELLED" && during.second == 1)
            for (handle in handles) check(OperationSupervisionApi.observe(server, actor, handle.npcUuid).observation?.task == null)
            return "expressionBindingChecks=11 expressionOriginalContext=true expressionCrossNpcSwapRejected=true expressionReplayRejected=true expressionNoAssignments=true"
        } finally {
            for (handle in handles) check(service.dismiss(handle, NpcDismissMode.ONLY_IF_EMPTY).status == NpcActionStatus.SUCCEEDED)
        }
    }
}
