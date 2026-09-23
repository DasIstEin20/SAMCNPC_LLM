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

@Mod.EventBusSubscriber(modid = SamcnpcLlm.MOD_ID)
internal object LlmGoalCommands {
    @SubscribeEvent
    fun register(event: RegisterCommandsEvent) {
        val branch = Commands.literal("llm")
        BoundedGoalCommands.register(branch) { source, npc, request ->
            execute(source, "bounded_typed", npc, request.text, constraints = request.constraints)
        }
        for (action in listOf("goal", "answer", "plan", "goal_bounded", "plan_bounded")) {
            branch.then(Commands.literal(action).then(LlmNpcArguments.argument()
                .then(Commands.argument("text", StringArgumentType.greedyString()).executes { context ->
                    execute(context.source, action, StringArgumentType.getString(context, "npc"),
                        StringArgumentType.getString(context, "text"))
                })))
        }
        branch.then(Commands.literal("remember").then(LlmNpcArguments.argument()
            .then(Commands.argument("name", StringArgumentType.word())
            .then(Commands.argument("x", IntegerArgumentType.integer(-29999984, 29999984))
            .then(Commands.argument("y", IntegerArgumentType.integer(-2048, 2048))
            .then(Commands.argument("z", IntegerArgumentType.integer(-29999984, 29999984)).executes { context ->
                execute(context.source, "remember", StringArgumentType.getString(context, "npc"),
                    StringArgumentType.getString(context, "name"), position = NpcBlockPosition(
                        IntegerArgumentType.getInteger(context, "x"), IntegerArgumentType.getInteger(context, "y"),
                        IntegerArgumentType.getInteger(context, "z")))
            }))))))
        branch.then(Commands.literal("forget_place").then(LlmNpcArguments.argument()
            .then(Commands.argument("name", StringArgumentType.word()).executes { context ->
                execute(context.source, "forget_place", StringArgumentType.getString(context, "npc"),
                    StringArgumentType.getString(context, "name"))
            })))
        branch.then(Commands.literal("maintain")
            .then(LlmNpcArguments.argument()
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
        for (action in listOf("status", "stop", "resume", "forget", "complete", "compact")) {
            branch.then(Commands.literal(action).then(LlmNpcArguments.argument().executes { context ->
                execute(context.source, action, StringArgumentType.getString(context, "npc"), "")
            }))
        }
        event.dispatcher.register(Commands.literal("samcnpc").then(branch))
    }

    private fun execute(source: CommandSourceStack, action: String, rawNpc: String, text: String, stock: StockTarget? = null, position: NpcBlockPosition? = null,
                        constraints: io.samcnpc.llm.intent.GoalConstraints? = null): Int {
        val actor = source.entity as? ServerPlayer ?: return fail(source, "PLAYER_REQUIRED")
        val npc = LlmNpcArguments.resolve(source, actor, rawNpc) ?: return 0
        val controller = LlmServerEvents.controller(source.server) ?: return fail(source, "LLM_SERVER_NOT_READY")
        val now = LlmServerEvents.nowMillis()
        val reply = when (action) {
            "goal" -> controller.start(actor, npc, text, now)
            "bounded_typed" -> controller.start(actor, npc, text, now, constraints = checkNotNull(constraints))
            "plan" -> controller.start(actor, npc, text, now, planner = true)
            "goal_bounded" -> controller.startBounded(actor, npc, text, now)
            "plan_bounded" -> controller.startBounded(actor, npc, text, now, planner = true)
            "complete" -> controller.complete(actor, npc)
            "maintain" -> controller.start(actor, npc, text, now, checkNotNull(stock))
            "answer" -> controller.answer(actor, npc, text, now)
            "remember" -> controller.place(actor, npc, text, checkNotNull(position))
            "forget_place" -> controller.place(actor, npc, text, null)
            "status" -> controller.status(actor, npc)
            "compact" -> controller.compact(actor, npc)
            "stop" -> controller.stop(actor, npc)
            "resume" -> controller.resume(actor, npc, now)
            "forget" -> controller.forget(actor, npc)
            else -> error("Unregistered LLM command")
        }
        val record = reply.record
        val unlimited = record?.limits?.quotaMode == io.samcnpc.llm.scheduling.InferenceQuotaMode.UNLIMITED
        var message = "SAMCNPC LLM: " + reply.code + if (record == null) "" else
            "\nNPC " + record.npcUuid + " | " + record.phase.name + " | goal " + record.goalId +
                " rev " + record.revision + " | quota " + record.limits.quotaMode.name +
                " | attempts " + record.budget.settledAttempts + "/" + (if (unlimited) "unlimited" else record.limits.attempts) +
                " | input " + record.budget.chargedInputTokens + "/" + (if (unlimited) "unlimited" else record.limits.inputTokens) +
                " | places " + record.memory.aliases.joinToString(",") { it.name } +
                " | confirmed results " + record.memory.results.size +
                " | intent " + (if (record.constraints == null) "FREE_TEXT_UNCONTRACTED" else
                    "BOUNDED_V${record.constraints.version} acquired=" + record.intentReservation.acquired + "/" + record.constraints.acquisitionLimit +
                        " delivered=" + record.intentReservation.delivered + "/" + record.constraints.deliveryLimit) +
                (if (record.mode == io.samcnpc.llm.context.LlmMode.PLANNER) " | steps " + record.planStepsCompleted + "/8" else "") +
                (record.question?.let { "\n" + it } ?: "")
        if (action == "status" && reply.accepted)
            message += "\nhourlyQuotaMode=" + controller.settings.inference.hourlyQuotaMode.name + "\n" +
                (reply.requestReport?.describe() ?: "lastRequest=unavailable (restart, reconfiguration or bounded history)")
        reply.compactionReport?.let { message += "\n" + it.describe() }
        if (reply.accepted) source.sendSuccess({ Component.literal(message) }, false)
        else source.sendFailure(Component.literal(message))
        return if (reply.accepted) 1 else 0
    }

    private fun fail(source: CommandSourceStack, code: String): Int {
        source.sendFailure(Component.literal("SAMCNPC LLM: " + code))
        return 0
    }
}
