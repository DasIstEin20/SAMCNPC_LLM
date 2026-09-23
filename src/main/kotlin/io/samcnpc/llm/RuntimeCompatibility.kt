package io.samcnpc.llm

import net.minecraftforge.fml.ModList

/** Do not use Behavior/Core API classes to discover whether those classes are compatible. */
internal object RuntimeCompatibility {
    fun verify() {
        requireApi("samcnpc_behavior", "Behavior", "requiresBehaviorApi")
        requireApi("samcnpc_core", "Core", "requiresCoreApi")
    }

    private fun requireApi(modId: String, name: String, requirementKey: String) {
        val own = ModList.get().mods.first { it.modId == "samcnpc_llm" }.modProperties
        val required = own[requirementKey] as? String
            ?: error("LLM build lacks required $name API metadata")
        val installed = ModList.get().mods.firstOrNull { it.modId == modId }?.modProperties.orEmpty()
        check(installed["apiVersion"] == required) {
            "LLM requires $name API $required, installed ${label(installed["apiVersion"])} " +
                "(build ${label(installed["buildId"])}). Install matching SAMCNPC JARs."
        }
    }

    private fun label(value: Any?): String = (value as? String)?.takeIf { text ->
        text.length in 1..64 && text.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "._-" }
    } ?: "missing/invalid"
}
