package io.samcnpc.llm.goal

import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import io.samcnpc.core.api.NpcBlockPosition
import io.samcnpc.llm.supervision.StockTarget
import net.minecraft.commands.arguments.ResourceLocationArgument
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraftforge.event.RegisterCommandsEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod
import io.samcnpc.llm.SamcnpcLlm
import java.util.UUID

@Mod.EventBusSubscriber(modid = SamcnpcLlm.MOD_ID)
internal object LlmGoalCommands {
    @SubscribeEvent
    fun register(event: RegisterCommandsEvent) {
        val branch = Commands.literal("llm")
        for (action in listOf("goal", "answer")) {
            branch.then(Commands.literal(action).then(Commands.argument("npc", StringArgumentType.word())
                .then(Commands.argument("text", StringArgumentType.greedyString()).executes { context ->
                    execute(context.source, action, StringArgumentType.getString(context, "npc"),
                        StringArgumentType.getString(context, "text"))
                })))
        }
        branch.then(Commands.literal("maintain")
            .then(Commands.argument("npc", StringArgumentType.word())
            .then(Commands.argument("item", ResourceLocationArgument.id())
            .then(Commands.argument("low", IntegerArgumentType.integer(1, 2304))
            .then(Commands.argument("target", IntegerArgumentType.integer(1, 2304))
            .then(Commands.argument("x", IntegerArgumentType.integer(-29999984, 29999984))
            .then(Commands.argument("y", IntegerArgumentType.integer(-2048, 2048))
            .then(Commands.argument("z", IntegerArgumentType.integer(-29999984, 29999984))
            .then(Commands.argument("text", StringArgumentType.greedyString()).executes { context ->
                val target = try {
                    StockTarget(context.source.level.dimension().location().toString(),
                        NpcBlockPosition(IntegerArgumentType.getInteger(context, "x"), IntegerArgumentType.getInteger(context, "y"),
                            IntegerArgumentType.getInteger(context, "z")), ResourceLocationArgument.getId(context, "item").toString(),
                        IntegerArgumentType.getInteger(context, "low"), IntegerArgumentType.getInteger(context, "target"))
                } catch (_: IllegalArgumentException) { return@executes fail(context.source, "INVALID_STOCK_TARGET") }
                execute(context.source, "maintain", StringArgumentType.getString(context, "npc"),
                    StringArgumentType.getString(context, "text"), target)
            })))))))))
        for (action in listOf("status", "stop", "resume", "forget")) {
            branch.then(Commands.literal(action).then(Commands.argument("npc", StringArgumentType.word()).executes { context ->
                execute(context.source, action, StringArgumentType.getString(context, "npc"), "")
            }))
        }
        event.dispatcher.register(Commands.literal("samcnpc").then(branch))
    }

    private fun execute(source: CommandSourceStack, action: String, rawNpc: String, text: String, stock: StockTarget? = null): Int {
        val actor = source.entity as? ServerPlayer ?: return fail(source, "PLAYER_REQUIRED")
        val npc = try { UUID.fromString(rawNpc) } catch (_: IllegalArgumentException) { return fail(source, "INVALID_NPC_UUID") }
        if (!npc.toString().equals(rawNpc, ignoreCase = true)) return fail(source, "INVALID_NPC_UUID")
        val controller = LlmServerEvents.controller(source.server) ?: return fail(source, "LLM_SERVER_NOT_READY")
        val now = LlmServerEvents.nowMillis()
        val reply = when (action) {
            "goal" -> controller.start(actor, npc, text, now)
            "maintain" -> controller.start(actor, npc, text, now, checkNotNull(stock))
            "answer" -> controller.answer(actor, npc, text, now)
            "status" -> controller.status(actor, npc)
            "stop" -> controller.stop(actor, npc)
            "resume" -> controller.resume(actor, npc, now)
            "forget" -> controller.forget(actor, npc)
            else -> error("Unregistered LLM command")
        }
        val record = reply.record
        val message = "SAMCNPC LLM: " + reply.code + if (record == null) "" else
            "\nNPC " + record.npcUuid + " | " + record.phase.name + " | goal " + record.goalId +
                " rev " + record.revision + " | attempts " + record.budget.settledAttempts + "/" + record.limits.attempts +
                (record.question?.let { "\n" + it } ?: "")
        if (reply.accepted) source.sendSuccess({ Component.literal(message) }, false)
        else source.sendFailure(Component.literal(message))
        return if (reply.accepted) 1 else 0
    }

    private fun fail(source: CommandSourceStack, code: String): Int {
        source.sendFailure(Component.literal("SAMCNPC LLM: " + code))
        return 0
    }
}
