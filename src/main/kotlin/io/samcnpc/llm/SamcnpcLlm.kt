package io.samcnpc.llm

import net.minecraftforge.fml.common.Mod
import org.slf4j.LoggerFactory

@Mod(SamcnpcLlm.MOD_ID)
class SamcnpcLlm {
    init {
        RuntimeCompatibility.verify()
        io.samcnpc.llm.config.LlmConfig.register()
        LOGGER.info("SAMCNPC LLM configuration registered; startup does not contact a provider.")
    }

    companion object {
        const val MOD_ID: String = "samcnpc_llm"
        private val LOGGER = LoggerFactory.getLogger(MOD_ID)
    }
}
