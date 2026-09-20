package io.samcnpc.llm.client

import io.samcnpc.llm.AllModsLoadingCheck
import io.samcnpc.llm.SamcnpcLlm
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.event.TickEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod
import java.nio.file.Files
import java.nio.file.Path

@Mod.EventBusSubscriber(modid = SamcnpcLlm.MOD_ID, value = [Dist.CLIENT])
object AllModsClientSmoke {
    private val enabled = java.lang.Boolean.getBoolean("samcnpc.clientLoadingSmoke")
    private var ticks = 0
    private var frames = 0
    private var finished = false
    private var transportStarted = false
    private var transportStartFrames = 0

    @SubscribeEvent
    fun frame(event: TickEvent.RenderTickEvent) {
        if (enabled && event.phase == TickEvent.Phase.END) {
            frames++
            LlmConfigClientProbe.frame(Minecraft.getInstance())
        }
    }

    @SubscribeEvent
    fun tick(event: TickEvent.ClientTickEvent) {
        if (!enabled || finished || event.phase != TickEvent.Phase.END) return
        check(++ticks < 1200) { "Three-mod client did not reach its title screen" }
        val minecraft = Minecraft.getInstance()
        if (minecraft.screen is AccessibilityOnboardingScreen && minecraft.overlay == null) {
            minecraft.screen?.onClose()
        }
        if ((!LlmConfigClientProbe.started && minecraft.screen !is TitleScreen) || minecraft.overlay != null || frames < 20) return
        if (!transportStarted) {
            transportStarted = true
            transportStartFrames = frames
            io.samcnpc.llm.ProviderRuntimeProbe.start()
        }
        if (!LlmConfigClientProbe.tick(minecraft)) return
        val transport = io.samcnpc.llm.ProviderRuntimeProbe.poll() ?: return
        check(frames - transportStartFrames >= 10) { "Client did not render while HTTP body was stalled" }
        val result = AllModsLoadingCheck.verify()
        Files.writeString(Path.of("client-loading-result.txt"), "PASS titleScreen=true frames=$frames configGui=true configSave=true staleDraft=true logo=true $result $transport\n")
        finished = true
        minecraft.stop()
    }
}
