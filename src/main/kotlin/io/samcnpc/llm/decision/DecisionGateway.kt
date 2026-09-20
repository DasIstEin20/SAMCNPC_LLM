package io.samcnpc.llm.decision

import io.samcnpc.behavior.api.*
import io.samcnpc.llm.context.ContextBinding
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer

/** Internal transport seam for fault injection; never selected by model data. */
internal interface DecisionGateway {
    fun dispatch(server: MinecraftServer, actor: ServerPlayer, binding: ContextBinding,
                 action: DecisionAction, amendment: OperationAmendmentRequest?): OperationReply

    companion object {
        val BEHAVIOR: DecisionGateway = object : DecisionGateway {
            override fun dispatch(server: MinecraftServer, actor: ServerPlayer, binding: ContextBinding,
                                  action: DecisionAction, amendment: OperationAmendmentRequest?): OperationReply =
                when (action) {
                    is DecisionAction.Assign -> OperationSupervisionApi.assign(server, actor, binding.npcUuid,
                        OperationAssignmentRequest(binding.priorTaskId, binding.issuedTick, binding.expiresTick, action.order))
                    is DecisionAction.Amend -> OperationSupervisionApi.amend(server, actor, binding.npcUuid, checkNotNull(amendment))
                    is DecisionAction.Control -> OperationSupervisionApi.control(server, actor, binding.npcUuid,
                        OperationControlRequest(checkNotNull(binding.priorTaskId), checkNotNull(binding.controlRevision),
                            checkNotNull(binding.definitionRevision), binding.issuedTick, binding.expiresTick, action.control))
                    else -> error("non-mutating decision cannot enter the Behavior gateway")
                }
        }
    }
}
