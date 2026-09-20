package io.samcnpc.llm.client

import io.samcnpc.llm.config.LlmConfig
import io.samcnpc.llm.config.ProviderSettings
import io.samcnpc.llm.config.ResponseFormat
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import org.slf4j.LoggerFactory

/** Local installation settings only; a multiplayer client cannot edit the remote server. */
internal class LlmConfigScreen(private val parent: Screen) : Screen(Component.literal("SAMCNPC LLM")) {
    private var accepted = LlmConfig.snapshot()
    private var draft = accepted.values
    private var advanced = false
    private var status = ""
    private val textValues = linkedMapOf<String, String>()
    private val labels = mutableListOf<Pair<String, Int>>()
    private val panelWidth: Int get() = minOf(430, width - 20)
    private val left: Int get() = (width - panelWidth) / 2
    private val top: Int get() = maxOf(5, (height - 242) / 2)
    private val footer: Int get() = minOf(height - 27, top + 215)
    private val editable: Boolean get() = minecraft?.level == null || minecraft?.hasSingleplayerServer() == true

    init { resetText() }

    override fun init() {
        clearWidgets()
        labels.clear()
        addRenderableWidget(Button.builder(tr(if (advanced) "basic" else "advanced")) {
            advanced = !advanced
            init()
        }.bounds(left + panelWidth - 105, top + 13, 105, 20).build())
        if (advanced) {
            field("connectTimeoutSeconds", 0, 3)
            field("requestTimeoutSeconds", 1, 3)
            field("temperature", 2, 12)
            field("maxOutputTokens", 3, 5)
            field("maxContextBytes", 4, 6)
            field("maxResponseBytes", 5, 6)
        } else {
            field("baseUrl", 0, 512)
            field("model", 1, 256)
            label("enabled", 2)
            addRenderableWidget(Button.builder(tr(if (draft.enabled) "on" else "off")) {
                draft = draft.copy(enabled = !draft.enabled)
                init()
            }.bounds(inputX(), rowY(2), inputWidth(), 20).build()).active = editable
            label("responseFormat", 3)
            addRenderableWidget(Button.builder(Component.literal(draft.responseFormat.name)) {
                draft = draft.copy(responseFormat = if (draft.responseFormat == ResponseFormat.JSON_SCHEMA)
                    ResponseFormat.JSON_OBJECT else ResponseFormat.JSON_SCHEMA)
                init()
            }.bounds(inputX(), rowY(3), inputWidth(), 20).build()).active = editable
            field("apiKeyEnvironment", 4, 128)
        }
        addRenderableWidget(Button.builder(tr("apply")) { applyDraft() }
            .bounds(left, footer, 90, 20).build()).active = editable
        addRenderableWidget(Button.builder(tr("reset")) {
            draft = ProviderSettings()
            resetText()
            status = ""
            init()
        }.bounds(left + 96, footer, 90, 20).build()).active = editable
        addRenderableWidget(Button.builder(Component.translatable("gui.done")) { onClose() }
            .bounds(left + panelWidth - 90, footer, 90, 20).build())
    }

    private fun rowY(row: Int): Int = top + 58 + row * 23
    private fun inputX(): Int = left + minOf(155, panelWidth / 2)
    private fun inputWidth(): Int = left + panelWidth - inputX()
    private fun label(key: String, row: Int) { labels.add(key to rowY(row) + 6) }

    private fun field(key: String, row: Int, limit: Int) {
        label(key, row)
        val box = EditBox(font, inputX(), rowY(row), inputWidth(), 20, tr(key))
        box.setMaxLength(limit)
        box.value = textValues.getValue(key)
        box.setResponder { textValues[key] = it }
        box.setEditable(editable)
        box.tooltip = Tooltip.create(tr("$key.hint"))
        addRenderableWidget(box)
    }

    private fun resetText() {
        textValues["baseUrl"] = draft.baseUrl
        textValues["model"] = draft.model
        textValues["apiKeyEnvironment"] = draft.apiKeyEnvironment
        textValues["connectTimeoutSeconds"] = draft.connectTimeoutSeconds.toString()
        textValues["requestTimeoutSeconds"] = draft.requestTimeoutSeconds.toString()
        textValues["temperature"] = draft.temperature.toString()
        textValues["maxOutputTokens"] = draft.maxOutputTokens.toString()
        textValues["maxContextBytes"] = draft.maxContextBytes.toString()
        textValues["maxResponseBytes"] = draft.maxResponseBytes.toString()
    }

    private fun applyDraft() {
        if (!editable) return
        val candidate = draft.copy(
            baseUrl = textValues.getValue("baseUrl"), model = textValues.getValue("model"),
            apiKeyEnvironment = textValues.getValue("apiKeyEnvironment"),
            connectTimeoutSeconds = textValues.getValue("connectTimeoutSeconds").toIntOrNull() ?: -1,
            requestTimeoutSeconds = textValues.getValue("requestTimeoutSeconds").toIntOrNull() ?: -1,
            temperature = textValues.getValue("temperature").toDoubleOrNull() ?: Double.NaN,
            maxOutputTokens = textValues.getValue("maxOutputTokens").toIntOrNull() ?: -1,
            maxContextBytes = textValues.getValue("maxContextBytes").toIntOrNull() ?: -1,
            maxResponseBytes = textValues.getValue("maxResponseBytes").toIntOrNull() ?: -1,
        )
        val problem = candidate.problem()
        if (problem != null) {
            status = tr("invalid", tr(problem)).string
            return
        }
        try {
            if (!LlmConfig.update(accepted.revision, candidate)) {
                accepted = LlmConfig.snapshot()
                draft = accepted.values
                resetText()
                status = tr("conflict").string
                init()
                return
            }
            accepted = LlmConfig.snapshot()
            draft = accepted.values
            status = tr("saved").string
        } catch (exception: RuntimeException) {
            // Config libraries can embed values in exception messages; never print their contents.
            LOGGER.error("Could not save LLM configuration ({})", exception.javaClass.simpleName)
            status = tr("saveFailed").string
        }
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        renderBackground(graphics)
        graphics.fill(left - 6, top - 3, left + panelWidth + 6, footer + 24, 0xDF202020.toInt())
        graphics.blit(LOGO, left, top, 82, 41, 0.0F, 0.0F, 1774, 887, 1774, 887)
        graphics.drawString(font, title, left + 90, top + 7, 0xFFFFFF)
        val hint = tr(if (editable) "localHint" else "remoteHint").string
        graphics.drawString(font, font.plainSubstrByWidth(hint, panelWidth), left, top + 44, 0xB8DDF2)
        for ((key, y) in labels) {
            graphics.drawString(font, font.plainSubstrByWidth(tr(key).string, inputX() - left - 6), left, y, 0xEEEEEE)
        }
        if (status.isNotEmpty()) {
            graphics.drawString(font, font.plainSubstrByWidth(status, panelWidth), left, footer - 12, 0xB8DDF2)
        }
        super.render(graphics, mouseX, mouseY, partialTick)
    }

    override fun onClose() { minecraft?.setScreen(parent) }
    override fun isPauseScreen(): Boolean = false

    private fun tr(key: String, vararg args: Any): Component = Component.translatable("samcnpc.llm.config.$key", *args)

    companion object {
        private val LOGO = ResourceLocation.fromNamespaceAndPath("samcnpc_llm", "textures/gui/logo.png")
        private val LOGGER = LoggerFactory.getLogger("samcnpc_llm")
    }
}
