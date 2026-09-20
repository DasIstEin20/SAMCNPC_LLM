package io.samcnpc.llm.goal

import io.samcnpc.llm.SamcnpcLlm
import io.samcnpc.llm.config.LlmConfig
import io.samcnpc.llm.scheduling.InferenceRateGate
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.entity.player.PlayerEvent
import net.minecraftforge.event.server.ServerStartedEvent
import net.minecraftforge.event.server.ServerStoppingEvent
import net.minecraftforge.eventbus.api.EventPriority
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod

/** One physical server lifecycle; config changes retain the rolling server reservations and monotonic epoch. */
@Mod.EventBusSubscriber(modid = SamcnpcLlm.MOD_ID)
internal object LlmServerEvents {
    private var activeServer: MinecraftServer? = null
    private var current: LlmGoalController? = null
    private var rates: InferenceRateGate? = null
    private var configRevision = -1L
    private var startedNanos = 0L

    fun controller(server: MinecraftServer): LlmGoalController? {
        check(server.isSameThread)
        return if (server === activeServer) current else null
    }

    fun nowMillis(): Long = (System.nanoTime() - startedNanos) / 1_000_000L

    @SubscribeEvent
    fun started(event: ServerStartedEvent) {
        check(activeServer == null)
        activeServer = event.server
        startedNanos = System.nanoTime()
        val snapshot = LlmConfig.snapshot()
        val sharedRates = InferenceRateGate(snapshot.values.inference.serverResources())
        rates = sharedRates
        current = LlmGoalController(event.server, snapshot.values, rates = sharedRates)
        configRevision = snapshot.revision
        event.server.playerList.players.forEach { current?.reconnect(it) }
    }

    @SubscribeEvent
    fun tick(event: TickEvent.ServerTickEvent) {
        if (event.phase != TickEvent.Phase.END || event.server !== activeServer) return
        val snapshot = LlmConfig.snapshot()
        if (snapshot.revision != configRevision) {
            current?.close()
            val sharedRates = checkNotNull(rates)
            sharedRates.reconfigure(snapshot.values.inference.serverResources())
            current = LlmGoalController(event.server, snapshot.values, rates = sharedRates)
            configRevision = snapshot.revision
            event.server.playerList.players.forEach { current?.reconnect(it) }
        }
        current?.poll(nowMillis())
    }

    @SubscribeEvent
    fun login(event: PlayerEvent.PlayerLoggedInEvent) {
        val actor = event.entity as? ServerPlayer ?: return
        if (actor.server === activeServer) current?.reconnect(actor)
    }

    @SubscribeEvent
    fun removed(event: io.samcnpc.core.api.NpcRemovedEvent) {
        if (activeServer?.isSameThread != true) return
        if (event.lifecycle.state == io.samcnpc.core.api.NpcLifecycleState.DISMISSED)
            current?.dismissed(event.handle.npcUuid)
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    fun stopping(event: ServerStoppingEvent) {
        if (event.server !== activeServer) return
        current?.close()
        current = null
        rates = null
        activeServer = null
        configRevision = -1
    }
}
