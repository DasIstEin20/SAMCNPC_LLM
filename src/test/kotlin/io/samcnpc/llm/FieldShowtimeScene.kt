package io.samcnpc.llm

import io.samcnpc.behavior.api.*
import io.samcnpc.core.api.*
import io.samcnpc.llm.goal.*
import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks

/** Disposable native fixtures only. Qwen's proposal still passes the ordinary controller/admission. */
internal object FieldShowtimeScene {
    val names=listOf("field_pl","field_en","planner_field_en","field_missing_area_pl")
    fun constraints(): io.samcnpc.llm.intent.GoalConstraints {
        val home = NpcPosition(-39.5, 63.0, 78.5)
        return io.samcnpc.llm.intent.GoalConstraints(setOf(OperationType.FIELD_PREPARATION), emptySet(),
            "minecraft:overworld", io.samcnpc.llm.intent.GoalQuantityMeaning.NONE, 0, 0,
            OperationWorkBox(NpcBlockPosition(-37, 62, 80), NpcBlockPosition(-35, 62, 82)),
            emptyList(), emptyList(), emptyList(), listOf(home), false, true, true, requiredReturnTo = home)
    }
    fun prepare(server: MinecraftServer,player: ServerPlayer,handle: NpcHandle) {
        val level=player.serverLevel()
        for(x in -38..-35) for(z in 80..82) {
            level.setBlockAndUpdate(BlockPos(x,62,z),Blocks.DIRT.defaultBlockState())
            level.setBlockAndUpdate(BlockPos(x,63,z),Blocks.AIR.defaultBlockState())
        }
        level.setBlockAndUpdate(BlockPos(-34,62,81),Blocks.WATER.defaultBlockState())
        val npc=checkNotNull(CoreNpcApi.service(server).runtime(handle));val p=npc.snapshot().position
        val drop=ItemEntity(level,p.x,p.y,p.z,ItemStack(Items.IRON_HOE));drop.setNoPickUpDelay()
        check(level.addFreshEntity(drop));check(npc.pickupItem(drop.uuid).status==NpcActionStatus.SUCCEEDED)
    }
    fun goal(case: Int): String = when(case) {
        0 -> "Przygotuj motyką poletko z ziemi od (-37,62,80) do (-35,62,82), a potem wróć do (-39.5,63,78.5). Kotwica (-39.5,63,78.5). Nie siej, nie zbieraj plonów, nie usuwaj bloków i nie rozszerzaj obszaru."
        3 -> "Przygotuj motyką poletko w moim ogrodzie. Nie podałem jeszcze jego położenia."
        else -> "Hoe the soil plane from (-37,62,80) to (-35,62,82), with anchor and return (-39.5,63,78.5). Do not sow, harvest, clear blocks or enlarge the area."
    }
    fun verify(server: MinecraftServer,player: ServerPlayer,handle: NpcHandle,record: GoalRecord,case: Int): String {
        val task=OperationSupervisionApi.observe(server,player,handle.npcUuid).observation?.task
        check(record.budget.settledAttempts==1) { "unnecessary repair calls=${record.budget.settledAttempts}" }
        if(case==3) {
            check(record.phase==GoalPhase.ASK_USER && task==null) { "missing area was not clarified: ${record.phase}/${record.code} task=$task" }
            return "ASK_USER no task admitted"
        }
        check(if(case==2) record.phase==GoalPhase.ASK_USER && record.code=="PLAN_CONFIRMATION_REQUIRED"
            else record.phase==GoalPhase.COMPLETED && record.code=="TASK_COMPLETED") { "${record.phase}/${record.code} question=${record.question} task=$task" }
        check(task?.state==OperationTaskState.COMPLETED && task.reason=="FIELD_PREPARED")
        val inspection=checkNotNull(OperationInspectionApi.inspect(server,player,handle.npcUuid).inspection)
        val definition=inspection.frames.first().definition
        check(definition.operationId=="samcnpc:prepare_field")
        val area=definition.parameters.fields["area"] as OperationValue.Record
        val bounds=area.fields["bounds"] as OperationValue.Record
        fun coordinate(key: String,axis: String): Int = ((bounds.fields[key] as OperationValue.Record).fields[axis] as OperationValue.Whole).value.toInt()
        check(listOf(coordinate("min","x"),coordinate("min","y"),coordinate("min","z"))==listOf(-37,62,80))
        check(listOf(coordinate("max","x"),coordinate("max","y"),coordinate("max","z"))==listOf(-35,62,82))
        val npc=checkNotNull(CoreNpcApi.service(server).runtime(handle))
        val items=npc.inventoryContents().filter { !it.stack.isEmpty }
        check(items.size==1 && items.single().stack.itemId=="minecraft:iron_hoe" && items.single().stack.damage==9)
        val p=npc.snapshot().position
        check((p.x+39.5)*(p.x+39.5)+(p.y-63)*(p.y-63)+(p.z-78.5)*(p.z-78.5)<=0.75*0.75) {
            "requested return (-39.5,63,78.5) not reached: actual=$p proposed=${definition.parameters.fields["returnTo"]}"
        }
        for(x in -37..-35) for(z in 80..82) {
            check(player.serverLevel().getBlockState(BlockPos(x,62,z)).`is`(Blocks.FARMLAND))
            check(player.serverLevel().getBlockState(BlockPos(x,63,z)).isAir)
        }
        check(player.serverLevel().getBlockState(BlockPos(-38,62,80)).`is`(Blocks.DIRT))
        return "FIELD_PREPARED nine soil cells, nine native hoe uses, no seeds, exact return"
    }
}
