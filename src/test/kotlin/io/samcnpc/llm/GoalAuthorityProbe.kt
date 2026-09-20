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
            for (reply in listOf(controller.start(stranger, npc, "Steal this NPC", 0),
                controller.status(stranger, npc), controller.answer(stranger, npc, "32", 0),
                controller.stop(stranger, npc), controller.resume(stranger, npc, 0), controller.forget(stranger, npc),
                controller.place(stranger, npc, "base", io.samcnpc.core.api.NpcBlockPosition(0, 64, 0)),
                controller.place(stranger, npc, "base", null))) {
                check(!reply.accepted && reply.record == null)
                checks++
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
            checks += 2
        } finally { actor.setPos(position.x, position.y, position.z) }
        check(controller.store.get(npc) == before)
        check(!controller.start(actor, npc, "x".repeat(1025), 0).accepted)
        check(server.commands.dispatcher.execute("samcnpc llm status 1-2-3-4-5", actor.createCommandSourceStack()) == 0)
        check(server.commands.dispatcher.execute("samcnpc llm status $npc", server.createCommandSourceStack()) == 0)
        check(controller.store.get(npc) == before)
        return checks + 3
    }
}
