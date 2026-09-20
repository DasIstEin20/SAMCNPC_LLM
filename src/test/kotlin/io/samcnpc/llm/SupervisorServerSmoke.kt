package io.samcnpc.llm

import com.mojang.authlib.GameProfile
import io.netty.channel.embedded.EmbeddedChannel
import io.samcnpc.core.api.NpcPosition
import net.minecraft.network.Connection
import net.minecraft.network.ConnectionProtocol
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.GameType
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.server.ServerStoppingEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

@Mod.EventBusSubscriber(modid = SamcnpcLlm.MOD_ID)
internal object SupervisorServerSmoke {
    private val enabled = java.lang.Boolean.getBoolean("samcnpc.supervisorServerSmoke")
    private var actor: ServerPlayer? = null
    private var channel: EmbeddedChannel? = null
    private var probe: SupervisorRuntimeProbe? = null
    private var ticks = 0
    private var done = false

    @SubscribeEvent
    fun tick(event: TickEvent.ServerTickEvent) {
        if (!enabled || done || event.phase != TickEvent.Phase.END) return
        val server = event.server
        try {
            check(server.isDedicatedServer)
            if (++ticks < 20) return
            var current = probe
            if (current == null) {
                val level = server.overworld()
                val connection = Connection(PacketFlow.SERVERBOUND)
                channel = EmbeddedChannel(connection); connection.setProtocol(ConnectionProtocol.PLAY)
                val player = ServerPlayer(server, level, GameProfile(UUID.randomUUID(), "StockSmoke"))
                server.playerList.placeNewPlayer(connection, player); player.setGameMode(GameType.SPECTATOR)
                actor = player
                val ground = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, level.sharedSpawnPos)
                val origin = NpcPosition(ground.x + 0.5, ground.y + 1.0, ground.z + 0.5)
                player.teleportTo(level, origin.x + 20, origin.y + 7, origin.z + 14, 0F, 30F)
                current = SupervisorRuntimeProbe(server, player, origin); probe = current
            }
            val result = current.poll() ?: return
            Files.writeString(Path.of("server-supervisor-result.txt"), "PASS dedicated=true " + result + "\n")
            done = true; server.halt(false)
        } catch (error: Exception) {
            Files.writeString(Path.of("server-supervisor-result.txt"), "FAIL " + error.stackTraceToString())
            done = true; server.halt(false)
        }
    }

    @SubscribeEvent
    fun stopping(event: ServerStoppingEvent) {
        if (!enabled) return
        probe?.close(); probe = null
        actor?.let { event.server.playerList.remove(it) }; actor = null
        channel?.finishAndReleaseAll(); channel = null
    }
}
