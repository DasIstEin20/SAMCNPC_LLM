package io.samcnpc.llm.context

import com.google.gson.JsonElement
import io.samcnpc.core.api.*
import io.samcnpc.llm.context.ContextJson.obj
import io.samcnpc.llm.context.ContextJson.text
import io.samcnpc.llm.context.ContextJson.number
import io.samcnpc.llm.context.ContextJson.flag
import io.samcnpc.llm.context.ContextJson.array
import io.samcnpc.llm.context.ContextJson.position
import io.samcnpc.llm.context.ContextJson.vector

internal object BodyContext {
    fun body(physical: NpcSnapshot, body: NpcBodyInspection): JsonElement = obj(
        "observedTick" to number(body.observedTick),
        "position" to position(physical.position), "eyePosition" to position(physical.eyePosition),
        "velocity" to vector(physical.velocity), "yaw" to number(physical.yaw), "pitch" to number(physical.pitch),
        "grounded" to flag(physical.onGround), "inWater" to flag(physical.inWater), "inLava" to flag(physical.inLava),
        "climbing" to flag(physical.climbing), "riding" to flag(physical.riding),
        "sprinting" to flag(physical.sprinting), "sneaking" to flag(physical.sneaking),
        "health" to number(body.health), "maxHealth" to number(body.maxHealth), "absorption" to number(body.absorption),
        "effectsTruncated" to flag(body.effectsTruncated),
        "effects" to array(body.effects.map {
            obj("id" to text(it.effectId), "remainingTicks" to number(it.durationTicks), "amplifier" to number(it.amplifier),
                "ambient" to flag(it.ambient), "visible" to flag(it.visible))
        }),
    )

    fun inventory(body: NpcBodyInspection, enchantmentLimit: Int): JsonElement =
        array(body.inventory.mapIndexed { slot, item ->
            val value = itemDetails(item, enchantmentLimit)
            value.addProperty("slot", slot)
            value
        })

    fun equipment(body: NpcBodyInspection, enchantmentLimit: Int): JsonElement {
        val value = obj("mainHand" to obj("inventorySlotAlias" to number(body.selectedHotbarSlot)),
            "carriedArrowCount" to number(body.carriedArrowCount),
            "readinessScope" to text("RESOURCE_ONLY; target, range and permission are checked by Behavior/Core"))
        for (slot in NpcInspectionSlot.entries) value.add(slot.name, itemDetails(body.equipment.getValue(slot), enchantmentLimit))
        return value
    }

    private fun itemDetails(item: NpcItemInspection, enchantmentLimit: Int): com.google.gson.JsonObject {
        val stack = item.stack
        val value = obj("item" to text(stack.itemId), "count" to number(stack.count))
        if (stack.isEmpty) return value
        val knowledge = item.knowledge
        value.add("maxStackSize", number(stack.maxStackSize))
        value.add("durability", if (stack.maxDamage > 0)
            obj("remaining" to number((stack.maxDamage - stack.damage).coerceAtLeast(0)), "max" to number(stack.maxDamage))
            else text(null))
        value.add("roles", array(knowledge.roles.sortedBy { it.name }.map { text(it.name) }))
        value.add("toolKind", text(knowledge.toolKind?.name))
        value.add("armorDestination", text(knowledge.armorDestination?.name))
        value.add("edible", flag(knowledge.edible))
        value.add("healingConsumable", flag(knowledge.combat.healingConsumable))
        value.add("healingStrength", number(knowledge.combat.healingStrength))
        value.add("useTicks", number(knowledge.combat.useTicks))
        value.add("rangedReadiness", text(item.rangedReadiness.name))
        value.add("enchantments", array(item.enchantments.take(enchantmentLimit).map {
            obj("id" to text(it.enchantmentId), "level" to number(it.level))
        }))
        value.add("enchantmentsTruncated", flag(item.enchantmentsTruncated || item.enchantments.size > enchantmentLimit))
        value.add("omittedKnownEnchantments", number((item.enchantments.size - enchantmentLimit).coerceAtLeast(0)))
        value.add("unreadableEnchantmentEntries", number(item.unreadableEnchantmentEntries))
        val block = knowledge.placeableBlock
        value.add("placeableBlock", if (block == null) text(null) else obj(
            "pillarClass" to text(block.pillarMaterialClass.name), "fullCollision" to flag(block.fullCollision),
            "gravityAffected" to flag(block.gravityAffected), "hazardous" to flag(block.hazardous),
            "functional" to flag(block.functional), "hasBlockEntity" to flag(block.hasBlockEntity)))
        return value
    }
}
