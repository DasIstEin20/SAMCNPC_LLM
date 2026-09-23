package io.samcnpc.llm.evaluation

import com.google.gson.JsonObject
import io.samcnpc.core.api.*
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.ChestBlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.UUID

/** Disposable, server-thread test fixture. Never consumes model output. */
internal class CorpusArena(private val server: MinecraftServer, private val actor: ServerPlayer,
                           val setup: JsonObject) : AutoCloseable {
    private val level = actor.serverLevel()
    private val service = CoreNpcApi.service(server)
    private val saved = linkedMapOf<BlockPos, BlockState>()
    private val point = setup.getAsJsonObject("npcPosition")
    val position = NpcPosition(point["x"].asDouble, point["y"].asDouble, point["z"].asDouble)
    val base = BlockPos.containing(position.x, position.y, position.z)
    private var target: net.minecraft.world.entity.Entity? = null
    private var current: NpcHandle? = null
    val handle: NpcHandle get() = checkNotNull(current)

    init {
        for (x in -12..12) for (z in -12..12) for (y in -1..4)
            set(base.offset(x, y, z), if (y == -1) Blocks.STONE.defaultBlockState() else Blocks.AIR.defaultBlockState())
        actor.teleportTo(level, position.x + 1, position.y + 3, position.z + 2, 0F, 0F)
        for (entry in setup.getAsJsonArray("chests") ?: com.google.gson.JsonArray()) {
            val chest = entry.asJsonObject
            val p = chest.getAsJsonObject("position")
            val block = BlockPos(p["x"].asInt, p["y"].asInt, p["z"].asInt)
            set(block.below(), Blocks.STONE.defaultBlockState())
            set(block, Blocks.CHEST.defaultBlockState())
            val entity = level.getBlockEntity(block) as ChestBlockEntity
            chest["name"]?.let { entity.setCustomName(Component.literal(it.asString)) }
            val contents = chest.getAsJsonObject("contents") ?: JsonObject()
            check(contents.size() <= entity.containerSize)
            for ((index, field) in contents.entrySet().withIndex()) entity.setItem(index, stack(field.key, field.value.asInt))
        }
        setup.getAsJsonArray("nearbyWood")?.forEachIndexed { index, selector ->
            val block = when (selector.asString) {
                "samcnpc:oak" -> Blocks.OAK_LOG
                "samcnpc:birch" -> Blocks.BIRCH_LOG
                "samcnpc:spruce" -> Blocks.SPRUCE_LOG
                else -> error("Unsupported frozen fixture wood")
            }
            set(base.offset(index - 1, 0, 3), block.defaultBlockState())
        }
        setup.getAsJsonObject("runningOperation")?.let { running ->
            val p = running.getAsJsonObject("parameters")
            val bounds = p.getAsJsonObject("area").getAsJsonObject("bounds")
            val min = bounds.getAsJsonObject("min"); val max = bounds.getAsJsonObject("max")
            val tree = BlockPos((min["x"].asInt + max["x"].asInt) / 2, min["y"].asInt,
                (min["z"].asInt + max["z"].asInt) / 2)
            set(tree.below(), Blocks.DIRT.defaultBlockState())
            for (height in 0..2) set(tree.above(height), Blocks.OAK_LOG.defaultBlockState())
        }
        setup["targetUuid"]?.let {
            val cow = checkNotNull(EntityType.COW.create(level))
            cow.uuid = UUID.fromString(it.asString)
            cow.setNoAi(true)
            cow.moveTo(position.x + 3, position.y, position.z + 3, 0F, 0F)
            check(level.addFreshEntity(cow)); target = cow
        }
        val summoned = service.summon(NpcSummonRequest(actor.uuid, "SAM evaluation", level.dimension().location().toString(), position, 0F))
        check(summoned.result.status == NpcActionStatus.SUCCEEDED)
        current = checkNotNull(summoned.handle)
        for ((id, count) in (setup.getAsJsonObject("inventory") ?: JsonObject()).entrySet()) {
            var remaining = count.asInt
            check(remaining in 1..2304)
            while (remaining > 0) {
                val amount = minOf(remaining, 64)
                val value = stack(id, amount)
                val item = ItemEntity(level, position.x, position.y, position.z, value)
                item.setNoPickUpDelay(); item.deltaMovement = Vec3.ZERO
                check(level.addFreshEntity(item))
                check(checkNotNull(service.runtime(handle)).pickupItem(item.uuid).status == NpcActionStatus.SUCCEEDED)
                remaining -= amount
            }
        }
    }

    private fun stack(id: String, count: Int): ItemStack {
        val item = BuiltInRegistries.ITEM.get(checkNotNull(ResourceLocation.tryParse(id)))
        check(item != net.minecraft.world.item.Items.AIR && count in 1..item.maxStackSize)
        return ItemStack(item, count)
    }
    private fun set(position: BlockPos, state: BlockState) {
        level.getChunk(position.x shr 4, position.z shr 4)
        if (position !in saved) {
            check(level.getBlockEntity(position) == null) { "Corpus must not overwrite an existing container" }
            saved[position] = level.getBlockState(position)
        }
        level.setBlockAndUpdate(position, state)
    }
    override fun close() {
        current?.let { check(service.dismiss(it, NpcDismissMode.DROP_INVENTORY).status == NpcActionStatus.SUCCEEDED) }
        current = null
        target?.discard(); target = null
        for (item in level.getEntitiesOfClass(ItemEntity::class.java, AABB(base.offset(-16, -3, -16), base.offset(16, 8, 16)))) item.discard()
        for ((position, state) in saved) {
            (level.getBlockEntity(position) as? ChestBlockEntity)?.clearContent()
            level.setBlockAndUpdate(position, state)
        }
        saved.clear()
    }
}
