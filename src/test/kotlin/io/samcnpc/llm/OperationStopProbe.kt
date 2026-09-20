package io.samcnpc.llm

import com.mojang.authlib.GameProfile
import io.netty.channel.embedded.EmbeddedChannel
import io.samcnpc.behavior.api.*
import io.samcnpc.core.api.*
import net.minecraft.network.Connection
import net.minecraft.network.ConnectionProtocol
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.GameType
import net.minecraft.world.level.levelgen.Heightmap
import java.util.UUID

/** Real dedicated-server shutdown consumer; only public first-party APIs, never shipped. */
internal object OperationStopProbe {
    private var player: ServerPlayer? = null
    private var channel: EmbeddedChannel? = null
    private var npcUuid: UUID? = null
    private var subscription: OperationSubscription? = null
    var stopped = false
        private set

    fun start(server: MinecraftServer) {
        val level = server.overworld()
        val connection = Connection(PacketFlow.SERVERBOUND)
        val embedded = EmbeddedChannel(connection)
        connection.setProtocol(ConnectionProtocol.PLAY)
        val actor = ServerPlayer(server, level, GameProfile(UUID.randomUUID(), "EventStopProbe"))
        server.playerList.placeNewPlayer(connection, actor)
        actor.setGameMode(GameType.SPECTATOR)
        val spawn = level.sharedSpawnPos
        val ground = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, spawn)
        val position = NpcPosition(ground.x + 0.5, ground.y + 1.0, ground.z + 0.5)
        actor.teleportTo(level, position.x, position.y + 2, position.z + 2, 0F, 0F)
        val summoned = CoreNpcApi.service(server).summon(NpcSummonRequest(actor.uuid, "StopProbe",
            level.dimension().location().toString(), position, 0F))
        check(summoned.result.status == NpcActionStatus.SUCCEEDED)
        val id = checkNotNull(summoned.handle).npcUuid
        subscription = checkNotNull(OperationEventApi.subscribe(server, actor, id) {
            error("idle shutdown probe must have no task event")
        }.subscription)
        player = actor
        channel = embedded
        npcUuid = id
    }

    fun beforeStop() {
        check(checkNotNull(subscription).state == OperationSubscriptionState.ACTIVE)
    }

    fun afterBehaviorStop(server: MinecraftServer) {
        check(checkNotNull(subscription).state == OperationSubscriptionState.SERVER_STOPPED)
        stopped = true
        // Remove disposable fixtures after verifying Behavior closed its listeners, before world save.
        val id = checkNotNull(npcUuid)
        server.overworld().getEntity(id)?.discard()
        player?.let { server.playerList.remove(it) }
        channel?.finishAndReleaseAll()
        player = null
        channel = null
        npcUuid = null
    }
}
