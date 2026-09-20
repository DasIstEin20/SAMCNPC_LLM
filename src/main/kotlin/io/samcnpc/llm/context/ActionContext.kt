package io.samcnpc.llm.context

import com.google.gson.JsonElement
import io.samcnpc.core.api.NpcSnapshot
import io.samcnpc.llm.context.ContextJson.obj
import io.samcnpc.llm.context.ContextJson.text
import io.samcnpc.llm.context.ContextJson.number
import io.samcnpc.llm.context.ContextJson.flag
import io.samcnpc.llm.context.ContextJson.array
import io.samcnpc.llm.context.ContextJson.position
import io.samcnpc.llm.context.ContextJson.vector

internal object ActionContext {
    /** Simultaneous channels remain simultaneous; intent targets are not fresh observations of their existence. */
    fun capture(body: NpcSnapshot): JsonElement {
        val actions = mutableListOf<JsonElement>()
        body.navigation?.let {
            actions.add(obj("kind" to text("NAVIGATE"), "actionId" to text(it.actionId.toString()),
                "intentDestination" to position(it.request.position), "remainingDistance" to number(it.remainingDistance),
                "stalledTicks" to number(it.stalledTicks), "leaseExpiresTick" to number(it.expiresAt)))
        }
        body.blockBreak?.let {
            actions.add(obj("kind" to text("BREAK_BLOCK"), "actionId" to text(it.actionId?.toString()),
                "intentTarget" to position(it.position), "progress" to number(it.progress),
                "toolItem" to text(it.toolItemId), "leaseExpiresTick" to number(it.leaseExpiresAt)))
        }
        body.itemUse?.let {
            actions.add(obj("kind" to text("USE_ITEM"), "actionId" to text(it.actionId?.toString()),
                "hand" to text(it.hand.name), "item" to text(it.itemId),
                "elapsedTicks" to number(it.elapsedTicks), "remainingTicks" to number(it.remainingTicks)))
        }
        body.rangedAttack?.let {
            actions.add(obj("kind" to text("RANGED_ATTACK"), "actionId" to text(it.actionId?.toString()),
                "intentTargetUuid" to text(it.targetUuid.toString()), "hand" to text(it.hand.name),
                "weapon" to text(it.weapon.name), "phase" to text(it.phase.name),
                "elapsedTicks" to number(it.elapsedTicks), "requiredChargeTicks" to number(it.requiredChargeTicks)))
        }
        body.fishing?.let {
            actions.add(obj("kind" to text("FISH"), "actionId" to text(it.actionId.toString()),
                "phase" to text(it.phase.name), "hookPosition" to position(it.position),
                "elapsedTicks" to number(it.elapsedTicks), "leaseRemainingTicks" to number(it.leaseRemainingTicks),
                "openWater" to flag(it.openWater)))
        }
        body.control?.let {
            actions.add(obj("kind" to text("DIRECT_BODY_CONTROL"), "actionId" to text(it.actionId.toString()),
                "leaseExpiresTick" to number(it.expiresAt)))
        }
        return obj("observedTick" to number(body.gameTime), "actions" to array(actions),
            "targetSemantics" to text("INTENT_NOT_FRESH_WORLD_FACT"))
    }
}
