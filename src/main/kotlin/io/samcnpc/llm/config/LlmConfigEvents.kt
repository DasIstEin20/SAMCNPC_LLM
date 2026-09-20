package io.samcnpc.llm.config

import io.samcnpc.llm.SamcnpcLlm
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.event.config.ModConfigEvent

@Mod.EventBusSubscriber(modid = SamcnpcLlm.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
internal object LlmConfigEvents {
    @SubscribeEvent
    fun changed(event: ModConfigEvent) { LlmConfig.changed(event) }
}
