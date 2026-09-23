package io.samcnpc.llm.client

import io.samcnpc.llm.SamcnpcLlm
import io.samcnpc.llm.config.LlmConfig
import io.samcnpc.llm.scheduling.InferenceQuotaMode
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraftforge.client.ConfigScreenHandler
import net.minecraftforge.fml.ModList
import java.nio.file.Files
import java.nio.file.Path

/** Real Forge screen factory, widgets, disk save, stale draft and resize; no production driver. */
internal object LlmConfigClientProbe {
    private var original = LlmConfig.snapshot()
    private var step = 0
    private var age = 0
    private var rendered = false
    private var pendingScreenshot: String? = null
    private val address = "http://127.0.0.1:18743/v1"
    private const val MODEL = "samcnpc-test-emulator"
    val started: Boolean get() = step > 0

    fun tick(mc: Minecraft): Boolean {
        if (++age < 8 || mc.overlay != null) return false
        age = 0
        when (step) {
            0 -> {
                original = LlmConfig.snapshot()
                check(!original.values.enabled) { "Loading smoke expects disabled fixture configuration" }
                val logo = ResourceLocation.fromNamespaceAndPath("samcnpc_llm", "textures/gui/logo.png")
                check(mc.resourceManager.getResource(logo).isPresent)
                val info = ModList.get().getModContainerById(SamcnpcLlm.MOD_ID).orElseThrow().modInfo
                check(info.logoFile.orElseThrow() == "assets/samcnpc_llm/textures/gui/logo.png")
                open(mc)
                check(field(mc, "baseUrl").value == original.values.baseUrl)
            }
            1 -> {
                field(mc, "baseUrl").value = "http://user:secret@localhost/v1"
                click(mc, "apply")
                check(LlmConfig.snapshot() == original) { "GUI admitted URL credentials" }
                field(mc, "baseUrl").value = address
                field(mc, "model").value = MODEL
                click(mc, "off")
                click(mc, "quotas")
                field(mc, "npcCallsPerHour").value = "0"
                click(mc, "apply")
                check(LlmConfig.snapshot() == original) { "GUI admitted invalid call budget" }
                field(mc, "npcCallsPerHour").value = "60"
                field(mc, "serverCallsPerHour").value = "120"
                click(mc, "advanced")
                field(mc, "requestTimeoutSeconds").value = "0"
                click(mc, "apply")
                check(LlmConfig.snapshot() == original) { "GUI admitted an unbounded timeout" }
                field(mc, "requestTimeoutSeconds").value = "23"
                field(mc, "temperature").value = "0.2"
                click(mc, "apply")
                val saved = LlmConfig.snapshot()
                check(saved.revision > original.revision && saved.values.enabled)
                check(saved.values.baseUrl == address && saved.values.model == MODEL)
                check(saved.values.requestTimeoutSeconds == 23 && saved.values.temperature == 0.2)
                check(saved.values.inference.npcCallsPerHour == 60 && saved.values.inference.serverCallsPerHour == 120)
                val file = Path.of("config/samcnpc-llm-common.toml")
                check(Files.readString(file).contains(address)) { "GUI save did not reach TOML" }
                pendingScreenshot = "llm-config-limits.png"
            }
            2 -> {
                check(rendered); rendered = false
                click(mc, "basic")
                pendingScreenshot = "llm-config-connection.png"
            }
            3 -> {
                check(rendered); rendered = false
                mc.screen?.onClose()
                check(mc.screen is TitleScreen)
                open(mc)
                check(field(mc, "baseUrl").value == address && field(mc, "model").value == MODEL)
                val current = LlmConfig.snapshot()
                check(LlmConfig.update(current.revision, current.values.copy(model = "external-edit")))
                field(mc, "model").value = "stale-gui"
                click(mc, "apply")
                check(LlmConfig.snapshot().values.model == "external-edit")
                check(field(mc, "model").value == "external-edit") { "Conflict did not refresh the GUI" }
                field(mc, "model").value = "unsaved"
                mc.screen?.onClose()
                check(LlmConfig.snapshot().values.model == "external-edit") { "Cancel saved a draft" }
                open(mc)
                val screen = checkNotNull(mc.screen)
                screen.resize(mc, 320, 240)
                field(mc, "baseUrl").value = address + "/alternate"
                screen.resize(mc, mc.window.guiScaledWidth, mc.window.guiScaledHeight)
                check(field(mc, "baseUrl").value == address + "/alternate") { "Resize lost draft" }
                mc.screen?.onClose()
                check(LlmConfig.update(LlmConfig.snapshot().revision, original.values))
            }
            4 -> {
                val bounded = original.values.copy(maxOutputTokens = 1024,
                    inference = original.values.inference.copy(contextWindow = 32768, inputTokens = 30000))
                check(LlmConfig.update(LlmConfig.snapshot().revision, bounded))
                open(mc)
                click(mc, "advanced")
                val before = LlmConfig.snapshot()
                field(mc, "maxOutputTokens").value = "4096"
                click(mc, "apply")
                check(LlmConfig.snapshot() == before) { "GUI saved input plus output above the model window" }
                check(field(mc, "maxOutputTokens").value == "4096") { "GUI silently changed the rejected allocation" }
                field(mc, "maxOutputTokens").value = "2048"
                click(mc, "apply")
                check(LlmConfig.snapshot().values.maxOutputTokens == 2048)
                check(LlmConfig.snapshot().values.inference.contextWindow == 32768)
                mc.screen?.onClose()
                check(LlmConfig.update(LlmConfig.snapshot().revision, original.values))
            }
            5 -> {
                open(mc); click(mc, "quotas")
                val screen = checkNotNull(mc.screen)
                // Both buttons can share the translated value; position identifies the scope.
                val modes = screen.children().filterIsInstance<Button>().filter {
                    it.message.string == label("quotaMode.LIMITED")
                }.sortedBy { it.y }
                check(modes.size == 2)
                modes[0].onPress()
                screen.resize(mc, 320, 240)
                click(mc, "apply")
                val goalOnly = LlmConfig.snapshot()
                check(goalOnly.values.inference.goalQuotaMode == InferenceQuotaMode.UNLIMITED)
                check(goalOnly.values.inference.hourlyQuotaMode == InferenceQuotaMode.LIMITED)
                click(mc, "quotaMode.LIMITED"); click(mc, "apply")
                val both = LlmConfig.snapshot()
                check(both.values.inference.goalQuotaMode == InferenceQuotaMode.UNLIMITED)
                check(both.values.inference.hourlyQuotaMode == InferenceQuotaMode.UNLIMITED)
                val config = Files.readString(Path.of("config/samcnpc-llm-common.toml"))
                check(config.contains("goalQuotaMode = \"UNLIMITED\"") && config.contains("hourlyQuotaMode = \"UNLIMITED\""))
                check(LlmConfig.update(both.revision, both.values.copy(inference = both.values.inference.copy(
                    hourlyQuotaMode = InferenceQuotaMode.LIMITED))))
                click(mc, "apply")
                check(LlmConfig.snapshot().values.inference.hourlyQuotaMode == InferenceQuotaMode.LIMITED)
                screen.resize(mc, mc.window.guiScaledWidth, mc.window.guiScaledHeight)
                mc.screen?.onClose()
                check(LlmConfig.update(LlmConfig.snapshot().revision, original.values))
            }
            else -> return true
        }
        step++
        return false
    }

    fun frame(mc: Minecraft) {
        val filename = pendingScreenshot ?: return
        val directory = mc.gameDirectory.toPath().resolve("screenshots")
        Files.createDirectories(directory)
        Screenshot.takeScreenshot(mc.mainRenderTarget).use { it.writeToFile(directory.resolve(filename)) }
        pendingScreenshot = null
        rendered = true
    }

    private fun open(mc: Minecraft) {
        val info = ModList.get().getModContainerById(SamcnpcLlm.MOD_ID).orElseThrow().modInfo
        mc.setScreen(ConfigScreenHandler.getScreenFactoryFor(info).orElseThrow().apply(mc, checkNotNull(mc.screen)))
        check(mc.screen is LlmConfigScreen)
    }

    private fun label(key: String): String = Component.translatable("samcnpc.llm.config.$key").string
    private fun field(mc: Minecraft, key: String): EditBox =
        checkNotNull(mc.screen).children().filterIsInstance<EditBox>().single { it.message.string == label(key) }

    private fun click(mc: Minecraft, key: String) {
        val screen = checkNotNull(mc.screen)
        val button = screen.children().filterIsInstance<Button>().single { it.message.string == label(key) }
        check(button.active && button.visible)
        check(screen.mouseClicked(button.x + 3.0, button.y + 3.0, 0))
        screen.mouseReleased(button.x + 3.0, button.y + 3.0, 0)
    }
}
