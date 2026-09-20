package io.samcnpc.llm.hour

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.samcnpc.behavior.api.*
import io.samcnpc.core.api.*
import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.monster.Zombie
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.UUID
import kotlin.math.sqrt

internal class HourArena(private val server: MinecraftServer, private val actor: ServerPlayer,
                         val index: Int, private val center: BlockPos, rounds: Int, routeCopies: Int) : AutoCloseable {
    private val level = actor.serverLevel()
    private val service = CoreNpcApi.service(server)
    private val saved = linkedMapOf<BlockPos, BlockState>()
    val handle: NpcHandle
    val body get() = checkNotNull(service.runtime(handle))
    val orderJson: String
    val order: OperationOrder
    var task: UUID? = null
    var completed = 0
    var distance = 0.0
    var lastMovedTick = -1000
    var kills = 0
    var taskStartedTick = 0
    val completedDurations = mutableListOf<Int>()
    private var previous: NpcPosition? = null
    private var enemy: Zombie? = null
    private var enemyTick = 0
    private var closed = false
    val combat get() = index % 2 == 1
    val spawn = NpcPosition(center.x + 0.5, center.y.toDouble(), center.z + 0.5)

    init {
        for (x in -18..18) for (z in -18..18) for (y in -1..4) {
            val p = center.offset(x,y,z)
            check(level.getBlockEntity(p) == null)
            saved[p] = level.getBlockState(p)
        }
        check(level.getEntitiesOfClass(ItemEntity::class.java, bounds()).isEmpty())
        for (p in saved.keys) level.setBlockAndUpdate(p,
            if (p.y == center.y - 1) Blocks.STONE.defaultBlockState() else Blocks.AIR.defaultBlockState())
        val result = service.summon(NpcSummonRequest(actor.uuid, "LlmHour-" + index,
            level.dimension().location().toString(), spawn, 0F))
        check(result.result.status == NpcActionStatus.SUCCEEDED)
        handle = checkNotNull(result.handle)
        give(ItemStack(Items.DIAMOND_SWORD))
        give(ItemStack(Items.COOKED_BEEF, 64))
        orderJson = document(rounds, routeCopies)
        val parsed = OperationDocumentApi.decodeOrder(orderJson)
        check(parsed is OperationDocumentResult.Accepted) { parsed.toString() }
        order = parsed.value
        check(OperationSupervisionApi.validateOrder(order).status == NpcActionStatus.SUCCEEDED)
    }
    private fun give(stack: ItemStack) {
        val drop = ItemEntity(level, spawn.x, spawn.y, spawn.z, stack)
        drop.setNoPickUpDelay(); drop.deltaMovement = Vec3.ZERO
        check(level.addFreshEntity(drop))
        check(body.pickupItem(drop.uuid).status == NpcActionStatus.SUCCEEDED)
    }
    fun observe(tick: Int): OperationTaskSnapshot? {
        val snapshot = body.snapshot()
        check(snapshot.healthFraction > 0 && !snapshot.inWater && !snapshot.inLava)
        val p = snapshot.position
        val old = previous
        if (old != null) {
            val d = sqrt((p.x-old.x)*(p.x-old.x) + (p.z-old.z)*(p.z-old.z))
            check(d < 2.0) { "Unexpected body teleport" }
            distance += d
            if (d > 0.01) lastMovedTick = tick
        }
        previous = p
        val task = OperationSupervisionApi.observe(server, actor, handle.npcUuid).observation?.task
        if (this.task != null) check(task?.taskId == this.task)
        check(task?.state !in setOf(OperationTaskState.FAILED, OperationTaskState.CANCELLED, OperationTaskState.PAUSED)) { task?.detail.orEmpty() }
        return task
    }
    /** Fixture enemies are immobile, visible and weak; only an actual NPC hit counts as a defeat. */
    fun combatFixture(tick: Int, interval: Int, running: Boolean) {
        val current = enemy
        if (current != null) {
            if (!current.isAlive) {
                check(current.lastHurtByMob?.uuid == handle.npcUuid) { "Fixture enemy died without this NPC attacking" }
                kills++; current.discard(); enemy = null
            } else check(tick - enemyTick < 600) { "Patrol did not defeat visible fixture enemy" }
        }
        if (combat && running && enemy == null && tick % interval == 0) {
            val p = body.snapshot().position
            val zombie = checkNotNull(EntityType.ZOMBIE.create(level))
            zombie.isNoAi = true; zombie.setPersistenceRequired(); zombie.health = 4F
            zombie.setPos(p.x + 1.5, p.y, p.z)
            check(level.addFreshEntity(zombie))
            enemy = zombie; enemyTick = tick
        }
        if (tick % 600 == 0) for (item in level.getEntitiesOfClass(ItemEntity::class.java, bounds())) item.discard()
    }
    fun terminal(tick: Int, view: OperationTaskSnapshot) {
        check(view.state == OperationTaskState.COMPLETED && view.reason == "PATROL_FINISHED")
        check(task == view.taskId)
        val p = body.snapshot().position
        check((p.x-spawn.x)*(p.x-spawn.x) + (p.z-spawn.z)*(p.z-spawn.z) <= 2.25) { "Patrol failed to return" }
        completed++; completedDurations.add(tick - taskStartedTick)
        check(completedDurations.size <= 12)
        task = null
    }
    fun cancel() {
        val view = checkNotNull(OperationSupervisionApi.observe(server, actor, handle.npcUuid).observation)
        val task = view.task ?: return
        if (task.state == OperationTaskState.COMPLETED) return
        check(OperationSupervisionApi.control(server, actor, handle.npcUuid,
            OperationControlRequest(task.taskId, task.controlRevision, task.definitionRevision,
                view.observedTick, view.observedTick + 100, OperationControl.CANCEL)).result.status == NpcActionStatus.SUCCEEDED)
    }
    fun evidence() = mapOf("index" to index, "npc" to handle.npcUuid.toString(), "combat" to combat,
        "completed" to completed, "completedTaskTicks" to completedDurations.toList(), "distance" to distance, "kills" to kills)
    private fun bounds() = AABB(center.offset(-19,-2,-19), center.offset(20,6,20))
    override fun close() {
        if (closed) return
        closed = true
        enemy?.discard(); enemy = null
        if (service.runtime(handle) != null) service.dismiss(handle, NpcDismissMode.DROP_INVENTORY)
        for (drop in level.getEntitiesOfClass(ItemEntity::class.java, bounds())) drop.discard()
        for ((p,s) in saved) level.setBlockAndUpdate(p,s)
    }
    private fun document(rounds: Int, routeCopies: Int): String {
        fun point(x: Double, z: Double) = JsonObject().also {
            it.addProperty("x", x); it.addProperty("y", spawn.y); it.addProperty("z", z) }
        val p = JsonObject()
        p.addProperty("dimensionId", level.dimension().location().toString())
        p.add("anchor", point(spawn.x,spawn.z)); p.addProperty("leash", 28)
        val route = JsonArray()
        repeat(routeCopies) { for ((x,z) in listOf(-12 to -12,12 to -12,12 to 12,-12 to 12))
            route.add(point(spawn.x + x,spawn.z + z)) }
        p.add("route", route); p.addProperty("rounds", rounds); p.addProperty("dwellTicks", 0)
        p.addProperty("reaction", if (combat) "AREA" else "PASSIVE")
        if (combat) p.add("filter", JsonObject().also { f -> f.add("typeIds", JsonArray().also { it.add("minecraft:zombie") }) })
        p.add("budget", JsonObject().also { it.addProperty("ticks",72000); it.addProperty("attempts",8); it.addProperty("backoffTicks",20) })
        return JsonObject().also {
            it.addProperty("documentVersion",1); it.addProperty("type","samcnpc:patrol")
            it.addProperty("definitionVersion",1); it.add("parameters",p)
        }.toString()
    }
}
