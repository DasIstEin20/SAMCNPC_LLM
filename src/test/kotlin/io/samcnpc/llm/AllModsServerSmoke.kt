package io.samcnpc.llm

import io.samcnpc.core.api.CoreNpcApi
import io.samcnpc.core.api.NpcLoadedQuery
import io.samcnpc.core.api.NpcPosition
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.server.ServerStartedEvent
import net.minecraftforge.event.server.ServerStoppingEvent
import net.minecraftforge.event.server.ServerStoppedEvent
import net.minecraftforge.eventbus.api.EventPriority
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
    private var finalReport: String? = null
    private var transportStartedTick: Int? = null

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
        check(++ticks < 6000) { "Provider runtime smoke timed out" }
        if (ticks == 20) OperationStopProbe.start(event.server)
        if (ticks <= 20) return
        val context = ContextRuntimeProbe.poll() ?: return
        val richContext = OperationStopProbe.pollRichContext(event.server) ?: return
        val started = transportStartedTick
        if (started == null) {
            ProviderRuntimeProbe.start()
            transportStartedTick = ticks
            return
        }
        val transport = ProviderRuntimeProbe.poll() ?: return
        check(ticks - started >= 10) { "Server did not tick while HTTP body was stalled" }
        val decisions = OperationStopProbe.pollDecisions(event.server) ?: return
        val scheduling = OperationStopProbe.pollScheduler(event.server) ?: return
        val translator = OperationStopProbe.pollTranslator(event.server) ?: return
        OperationStopProbe.beforeStop()
        finalReport = "dedicated=true ticks=$ticks coreApi=true $result $transport $context $richContext $decisions $scheduling $translator ${OperationStopProbe.admissionReport}"
        Files.writeString(Path.of("server-loading-result.txt"), "PENDING_STOP $finalReport\n")
        event.server.halt(false)
    }
    @SubscribeEvent(priority = EventPriority.LOWEST)
    fun stopping(event: ServerStoppingEvent) {
        if (enabled && OperationStopProbe.started) OperationStopProbe.afterBehaviorStop(event.server)
    }

    @SubscribeEvent
    fun stopped(event: ServerStoppedEvent) {
        if (!enabled || finalReport == null) return
        check(OperationStopProbe.stopped)
        Files.writeString(Path.of("server-loading-result.txt"), "PASS $finalReport subscriptionsStopped=true\n")
    }
}
