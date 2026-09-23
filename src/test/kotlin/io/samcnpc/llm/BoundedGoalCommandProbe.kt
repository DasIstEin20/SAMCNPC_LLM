package io.samcnpc.llm

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.exceptions.CommandSyntaxException
import io.samcnpc.behavior.api.OperationType
import io.samcnpc.core.api.NpcBlockPosition
import io.samcnpc.core.api.NpcPosition
import io.samcnpc.llm.goal.BoundedGoalCommands
import io.samcnpc.llm.intent.*
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.server.level.ServerPlayer

/** Same registered builders with a capture-only sink, then authority checks use the real dispatcher. */
internal object BoundedGoalCommandProbe {
    fun verify(actor: ServerPlayer): Int {
        val dispatcher = CommandDispatcher<CommandSourceStack>()
        val branch = Commands.literal("llm")
        var received: BoundedGoalRequest? = null
        BoundedGoalCommands.register(branch) { _, npc, request -> check(npc == "Sam"); received = request; 1 }
        dispatcher.register(Commands.literal("samcnpc").then(branch))
        fun execute(text: String): GoalConstraints {
            received = null
            val command = "samcnpc llm $text"
            check(command.length < 256)
            check(dispatcher.execute(command, actor.createCommandSourceStack()) == 1)
            val request = checkNotNull(received)
            check(request.text.isNotBlank())
            check(request.constraints.initialStock == 0 && !request.constraints.allowPlayers)
            return request.constraints
        }
        val to = execute("stock_to Sam minecraft:cobblestone 32 -10 64 -30")
        check(to.operations == setOf(OperationType.INVENTORY) && to.quantityMeaning == GoalQuantityMeaning.TARGET_INVENTORY)
        check(to.sources == listOf(NpcBlockPosition(-10, 64, -30)) && to.destinations.isEmpty())
        val more = execute("take_more Sam minecraft:cobblestone 32 -10 64 -30")
        check(more.quantityMeaning == GoalQuantityMeaning.EXACT_ADDITIONAL && more.allowAcquisition)
        val carried = execute("deliver_carried Sam minecraft:oak_log 16 120 64 -30")
        check(carried.operations == setOf(OperationType.DELIVER) && !carried.allowAcquisition && !carried.allowDestruction)
        val transport = execute("transport_exact Sam minecraft:stone 16 -10 64 -30 10 65 -40")
        check(transport.operations == setOf(OperationType.TRANSPORT) && transport.sources.single() == NpcBlockPosition(-10, 64, -30))
        check(transport.destinations.single() == NpcBlockPosition(10, 65, -40))
        val wood = execute("wood_min Sam samcnpc:oak 16 -10 64 -30 10 80 -20 120 64 -30")
        check(wood.operations == setOf(OperationType.LUMBERJACK) && wood.resourceIds == setOf("samcnpc:oak"))
        check(wood.allowAuxiliaryBlockWork && wood.workBox?.min == NpcBlockPosition(-10, 64, -30))
        val mine = execute("mine_min Sam minecraft:stone minecraft:cobblestone 16 -10 64 -30 10 80 -20 120 64 -30")
        check(mine.operations == setOf(OperationType.MINING) && mine.resourceIds == setOf("minecraft:stone", "minecraft:cobblestone"))
        check(!mine.allowAuxiliaryBlockWork && mine.quantityMeaning == GoalQuantityMeaning.MINIMUM_HARVEST)
        val go = execute("go_bounded Sam -10.5 64 -30.5")
        check(go.navigation == listOf(NpcPosition(-10.5, 64.0, -30.5)) && go.quantityMeaning == GoalQuantityMeaning.NONE)
        check(go.resourceIds.isEmpty() && !go.allowAcquisition)
        for (bad in listOf("stock_to Sam minecraft:stone 0 -10 64 -30", "go_bounded Sam NaN 64 0",
            "mine_min Sam minecraft:stone minecraft:cobblestone 16 -10 64 -30 10 80 -20 120 3000 -30")) {
            received = null
            try { check(dispatcher.execute("samcnpc llm $bad", actor.createCommandSourceStack()) == 0) }
            catch (_: CommandSyntaxException) { /* Expected Brigadier rejection before the capture-only sink. */ }
            check(received == null)
        }
        received = null
        check(dispatcher.execute("samcnpc llm wood_min Sam samcnpc:oak 16 10 80 10 -10 64 -30 120 64 -30", actor.createCommandSourceStack()) == 0)
        check(received == null)
        return 11
    }
}
