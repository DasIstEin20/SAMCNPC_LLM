package io.samcnpc.llm.goal

import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.ArgumentBuilder
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import io.samcnpc.behavior.api.OperationType
import io.samcnpc.behavior.api.OperationWorkBox
import io.samcnpc.core.api.NpcBlockPosition
import io.samcnpc.core.api.NpcPosition
import io.samcnpc.llm.intent.*
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.ResourceLocationArgument
import net.minecraft.network.chat.Component

/** Fixed typed entry points fit vanilla's 256-character command packet. No model extracts permission. */
internal object BoundedGoalCommands {
    private val coordinates = listOf("x", "y", "z")
    private fun coordinates(prefix: String) = coordinates.map { prefix + it }
    private val arguments = linkedMapOf(
        "stock_to" to listOf("item", "quantity") + coordinates,
        "take_more" to listOf("item", "quantity") + coordinates,
        "deliver_carried" to listOf("item", "quantity") + coordinates,
        "transport_exact" to listOf("item", "quantity") + coordinates("source_") + coordinates("destination_"),
        "wood_min" to listOf("wood", "quantity") + coordinates("min_") + coordinates("max_") + coordinates("destination_"),
        "mine_min" to listOf("block", "item", "quantity") + coordinates("min_") + coordinates("max_") + coordinates("destination_"),
        "go_bounded" to listOf("position_x", "position_y", "position_z"),
    )

    fun register(root: LiteralArgumentBuilder<CommandSourceStack>,
                 submit: (CommandSourceStack, String, BoundedGoalRequest) -> Int) {
        for ((action, fields) in arguments) {
            var tail: ArgumentBuilder<CommandSourceStack, *> = argument(fields.last()).executes { context ->
                val request = try { request(action, context) } catch (_: IllegalArgumentException) {
                    context.source.sendFailure(Component.literal("SAMCNPC LLM: INVALID_GOAL_CONSTRAINTS"))
                    return@executes 0
                }
                submit(context.source, StringArgumentType.getString(context, "npc"), request)
            }
            for (field in fields.dropLast(1).asReversed()) tail = argument(field).then(tail)
            root.then(Commands.literal(action).then(LlmNpcArguments.argument().then(tail)))
        }
    }

    private fun argument(field: String): ArgumentBuilder<CommandSourceStack, *> = when {
        field in setOf("item", "wood", "block") -> Commands.argument(field, ResourceLocationArgument.id())
        field == "quantity" -> Commands.argument(field, IntegerArgumentType.integer(1, 65536))
        field.startsWith("position_") -> Commands.argument(field, DoubleArgumentType.doubleArg(
            if (field.endsWith("y")) -2048.0 else -29999984.0, if (field.endsWith("y")) 2048.0 else 29999984.0))
        else -> Commands.argument(field, IntegerArgumentType.integer(
            if (field.endsWith("y")) -2048 else -29999984, if (field.endsWith("y")) 2048 else 29999984))
    }

    private fun request(action: String, context: CommandContext<CommandSourceStack>): BoundedGoalRequest {
        fun position(prefix: String = "") = NpcBlockPosition(IntegerArgumentType.getInteger(context, prefix + "x"),
            IntegerArgumentType.getInteger(context, prefix + "y"), IntegerArgumentType.getInteger(context, prefix + "z"))
        fun point(p: NpcBlockPosition) = "(${p.x},${p.y},${p.z})"
        val dimension = context.source.level.dimension().location().toString()
        if (action == "go_bounded") {
            val target = NpcPosition(DoubleArgumentType.getDouble(context, "position_x"),
                DoubleArgumentType.getDouble(context, "position_y"), DoubleArgumentType.getDouble(context, "position_z"))
            return BoundedGoalRequest("Przejdź do (${target.x},${target.y},${target.z}) w $dimension.",
                GoalConstraints(setOf(OperationType.NAVIGATE), emptySet(), dimension, GoalQuantityMeaning.NONE, 0, 0,
                    null, emptyList(), emptyList(), emptyList(), listOf(target), false, false, false))
        }
        val quantity = IntegerArgumentType.getInteger(context, "quantity")
        val wood = action == "wood_min"
        val mining = action == "mine_min"
        val harvest = wood || mining
        val item = ResourceLocationArgument.getId(context, if (wood) "wood" else "item").toString()
        val resource = if (mining) ResourceLocationArgument.getId(context, "block").toString() else item
        val supply = action == "stock_to" || action == "take_more"
        val sources = when { supply -> listOf(position()); action == "transport_exact" -> listOf(position("source_")); else -> emptyList() }
        val destinations = when { supply -> emptyList(); action == "deliver_carried" -> listOf(position()); else -> listOf(position("destination_")) }
        val area = if (harvest) OperationWorkBox(position("min_"), position("max_")) else null
        val type = when { supply -> OperationType.INVENTORY; wood -> OperationType.LUMBERJACK; mining -> OperationType.MINING
            action == "deliver_carried" -> OperationType.DELIVER; else -> OperationType.TRANSPORT }
        val meaning = when { action == "stock_to" -> GoalQuantityMeaning.TARGET_INVENTORY
            harvest -> GoalQuantityMeaning.MINIMUM_HARVEST; else -> GoalQuantityMeaning.EXACT_ADDITIONAL }
        val text = when (action) {
            "stock_to" -> "Uzupełnij ekwipunek DO $quantity $item ze skrzyni ${point(sources.single())}. Nie kop i nie dostarczaj ich nigdzie."
            "take_more" -> "Take $quantity MORE $item from chest ${point(sources.single())}, in addition to your initial carried stock. Do not mine or deliver elsewhere."
            "deliver_carried" -> "Deliver exactly $quantity already-carried $item to chest ${point(destinations.single())}. Do not acquire or mine new resources."
            "transport_exact" -> "Transport exactly $quantity $item from chest ${point(sources.single())} to chest ${point(destinations.single())}. Do not harvest."
            else -> {
                val box = checkNotNull(area)
                "${if (wood) "Zbierz drewno $item" else "Wydobądź bloki $resource dla $item"} wyłącznie w obszarze ${point(box.min)} do ${point(box.max)}. " +
                    "Dostarcz co najmniej $quantity do ${point(destinations.single())}. Nie pobieraj wyposażenia i nie sadź drzew."
            }
        }
        return BoundedGoalRequest(text, GoalConstraints(setOf(type), setOf(item, resource), dimension, meaning, quantity, 0,
            area, emptyList(), sources, destinations, emptyList(), action != "deliver_carried", harvest, wood))
    }
}
