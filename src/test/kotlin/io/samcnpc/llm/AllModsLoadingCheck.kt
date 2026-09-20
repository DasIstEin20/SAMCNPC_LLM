package io.samcnpc.llm

import io.samcnpc.behavior.api.BehaviorPackValidationApi
import io.samcnpc.behavior.api.BehaviorCatalogApi
import net.minecraftforge.fml.ModList

internal object AllModsLoadingCheck {
    fun verify(): String {
        val config = io.samcnpc.llm.config.LlmConfig.snapshot()
        check(config.values.problem() == null && !config.values.enabled)
        check(java.nio.file.Files.exists(java.nio.file.Path.of("config/samcnpc-llm-common.toml")))
        val expected = setOf("samcnpc_core", "samcnpc_behavior", "samcnpc_llm")
        val loaded = ModList.get().mods.map { it.modId }.filter { it.startsWith("samcnpc_") }.toSet()
        check(loaded == expected) { "Expected exactly $expected, loaded $loaded" }
        val report = BehaviorPackValidationApi.validateCandidate(
            """{"schemaVersion":1,"id":"samcnpc:loading_check","description":"loading check","priority":0,
                "channels":["movement"],"rules":[{"id":"idle","priority":0,
                "when":{"test":{"condition":"samcnpc:always"}},"actions":[{"action":"samcnpc:stop_movement"}]}]}""",
            "three-mod-loading-smoke",
        )
        check(report.accepted) { report.messages.joinToString() }
        val schema = io.samcnpc.behavior.api.BehaviorSchemaApi.registeredSchema()
        check(schema.contains("samcnpc:move_to_summoner") && schema.contains("x-samcnpc-greater-than"))
        val order = io.samcnpc.behavior.api.OperationOrder.Navigate("minecraft:overworld",
            io.samcnpc.core.api.NpcPosition(0.5, 64.0, 0.5))
        check(io.samcnpc.behavior.api.OperationSupervisionApi.validateOrder(order).status ==
            io.samcnpc.core.api.NpcActionStatus.SUCCEEDED)
        val catalog = BehaviorCatalogApi.snapshot()
        check(catalog.documentVersion == 1 && catalog.conditions.size == 14 && catalog.actions.size == 23)
        check(catalog.actions.single { it.id == "samcnpc:stop_movement" }.channels == listOf("movement"))
        return "mods=${loaded.sorted()} behaviorValidation=true componentCatalog=14/23 registeredSchema=true typedOrders=true configLoaded=true defaultDisabled=true"
    }
}
