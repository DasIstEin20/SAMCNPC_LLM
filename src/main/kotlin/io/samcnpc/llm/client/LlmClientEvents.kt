package io.samcnpc.llm.client

import io.samcnpc.llm.SamcnpcLlm
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.client.ConfigScreenHandler
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.ModList
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent

@Mod.EventBusSubscriber(modid = SamcnpcLlm.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = [Dist.CLIENT])
internal object LlmClientEvents {
    @SubscribeEvent
    fun setup(event: FMLClientSetupEvent) {
        ModList.get().getModContainerById(SamcnpcLlm.MOD_ID).orElseThrow().registerExtensionPoint(
            ConfigScreenHandler.ConfigScreenFactory::class.java,
        ) { ConfigScreenHandler.ConfigScreenFactory { _, parent -> LlmConfigScreen(parent) } }
    }
}
