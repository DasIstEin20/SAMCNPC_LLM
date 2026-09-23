package io.samcnpc.llm

import com.mojang.authlib.GameProfile
import io.netty.channel.embedded.EmbeddedChannel
import io.samcnpc.llm.goal.LlmGoalController
import net.minecraft.network.Connection
import net.minecraft.network.ConnectionProtocol
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

/** Current connected stranger, distance, malformed command and bounded text checks against registered commands. */
internal object GoalAuthorityProbe {
    fun verify(server: MinecraftServer, actor: ServerPlayer, npc: UUID, controller: LlmGoalController): Int {
        val before = checkNotNull(controller.store.get(npc))
        val connection = Connection(PacketFlow.SERVERBOUND)
        val channel = EmbeddedChannel(connection)
        connection.setProtocol(ConnectionProtocol.PLAY)
        val stranger = ServerPlayer(server, actor.serverLevel(), GameProfile(UUID.randomUUID(), "GoalStranger"))
        server.playerList.placeNewPlayer(connection, stranger)
        stranger.setPos(actor.x, actor.y, actor.z)
        var checks = 0
        try {
            check(!stranger.hasPermissions(2))
            for (action in listOf("stock_to", "take_more", "deliver_carried")) {
                check(server.commands.dispatcher.execute("samcnpc llm $action $npc minecraft:cobblestone 32 0 64 0", stranger.createCommandSourceStack()) == 0)
                checks++
            }
            for (reply in listOf(controller.start(stranger, npc, "Steal this NPC", 0),
                controller.status(stranger, npc), controller.answer(stranger, npc, "32", 0),
                controller.stop(stranger, npc), controller.resume(stranger, npc, 0), controller.forget(stranger, npc),
                controller.place(stranger, npc, "base", io.samcnpc.core.api.NpcBlockPosition(0, 64, 0)),
                controller.place(stranger, npc, "base", null),
                controller.start(stranger, npc, "Take over the plan", 0, planner = true),
                controller.complete(stranger, npc), controller.compact(stranger, npc),
                controller.startBounded(stranger, npc, "{}", 0), controller.startBounded(stranger, npc, "{}", 0, planner = true))) {
                check(!reply.accepted && reply.record == null)
                checks++
            }
            val handle = checkNotNull(io.samcnpc.core.api.CoreNpcApi.service(server).find(npc))
            for (selector in listOf(handle.displayName, npc.toString().take(8))) {
                check(server.commands.dispatcher.execute("samcnpc llm stop $selector", stranger.createCommandSourceStack()) == 0)
                val input = "samcnpc llm status $selector"
                val suggestions = server.commands.dispatcher.getCompletionSuggestions(
                    server.commands.dispatcher.parse(input, stranger.createCommandSourceStack())).join()
                check(suggestions.list.none { it.text == selector })
                checks += 2
            }
            val ownSuggestions = server.commands.dispatcher.getCompletionSuggestions(
                server.commands.dispatcher.parse("samcnpc llm status ", actor.createCommandSourceStack())).join()
            check(ownSuggestions.list.any { it.text == handle.displayName })
            check(ownSuggestions.list.any { it.text == npc.toString().take(8) })
            checks += 2
            val duplicate = io.samcnpc.core.api.CoreNpcApi.service(server).summon(io.samcnpc.core.api.NpcSummonRequest(
                actor.uuid, handle.displayName, actor.serverLevel().dimension().location().toString(),
                io.samcnpc.core.api.NpcPosition(actor.x + 1, actor.y, actor.z), 0F))
            val duplicateHandle = checkNotNull(duplicate.handle)
            try {
                check(server.commands.dispatcher.execute("samcnpc llm stop " + handle.displayName, actor.createCommandSourceStack()) == 0)
                check(server.commands.dispatcher.execute("samcnpc llm status $npc", actor.createCommandSourceStack()) == 1)
                checks += 2
            } finally {
                check(io.samcnpc.core.api.CoreNpcApi.service(server).dismiss(duplicateHandle,
                    io.samcnpc.core.api.NpcDismissMode.ONLY_IF_EMPTY).status == io.samcnpc.core.api.NpcActionStatus.SUCCEEDED)
            }
            check(controller.store.get(npc) == before)
        } finally {
            server.playerList.remove(stranger)
            channel.finishAndReleaseAll()
        }
        val position = actor.position()
        try {
            actor.setPos(position.x + 300, position.y, position.z)
            check(!controller.status(actor, npc).accepted)
            check(!controller.stop(actor, npc).accepted)
            check(!controller.compact(actor, npc).accepted)
            check(!controller.startBounded(actor, npc, "{}", 0).accepted)
            checks += 4
        } finally { actor.setPos(position.x, position.y, position.z) }
        check(controller.store.get(npc) == before)
        check(!controller.start(actor, npc, "x".repeat(1025), 0).accepted)
        check(server.commands.dispatcher.execute("samcnpc llm status 1-2-3-4-5", actor.createCommandSourceStack()) == 0)
        check(server.commands.dispatcher.execute("samcnpc llm status $npc", server.createCommandSourceStack()) == 0)
        check(controller.store.get(npc) == before)
        return checks + 3
    }
}
