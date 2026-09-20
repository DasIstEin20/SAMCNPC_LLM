package io.samcnpc.llm

import io.samcnpc.core.api.CoreNpcApi
import io.samcnpc.core.api.NpcLoadedQuery
import io.samcnpc.core.api.NpcPosition
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.server.ServerStartedEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod
import java.nio.file.Files
import java.nio.file.Path

/** A normal dedicated server, not the GameTest server. This driver never ships in a mod JAR. */
@Mod.EventBusSubscriber(modid = SamcnpcLlm.MOD_ID)
object AllModsServerSmoke {
    private val enabled = java.lang.Boolean.getBoolean("samcnpc.serverLoadingSmoke")
    private var loading: String? = null
    private var ticks = 0

    @SubscribeEvent
    fun started(event: ServerStartedEvent) {
        if (!enabled) return
        check(event.server.isDedicatedServer) { "Loading smoke must run a dedicated server" }
        val result = AllModsLoadingCheck.verify()
        CoreNpcApi.service(event.server).loadedNearby(
            NpcLoadedQuery("minecraft:overworld", NpcPosition(0.0, 80.0, 0.0), 16.0),
        )
        loading = result
    }

    @SubscribeEvent
    fun tick(event: TickEvent.ServerTickEvent) {
        if (!enabled || event.phase != TickEvent.Phase.END) return
        val result = loading ?: return
        check(++ticks < 1200) { "Provider runtime smoke timed out" }
        if (ticks == 20) ProviderRuntimeProbe.start()
        if (ticks <= 20) return
        val transport = ProviderRuntimeProbe.poll() ?: return
        check(ticks >= 30) { "Server did not tick while HTTP body was stalled" }
        Files.writeString(Path.of("server-loading-result.txt"), "PASS dedicated=true ticks=$ticks coreApi=true $result $transport\n")
        event.server.halt(false)
    }
}
