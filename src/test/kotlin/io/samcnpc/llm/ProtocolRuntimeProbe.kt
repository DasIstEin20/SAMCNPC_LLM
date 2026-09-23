package io.samcnpc.llm

import io.samcnpc.core.api.NpcPosition
import io.samcnpc.llm.config.ResponseFormat
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

/** The same physical/fault scenarios run once per wire format, without relaxing their assertions. */
internal class ProtocolRuntimeProbe(private val server: MinecraftServer, private val actor: ServerPlayer,
                                    private val origin: NpcPosition,
                                    private val canFinish: (UUID) -> Boolean = { true }) : GoalRuntimeProbe {
    private var expression = false
    private var first: String? = null
    private var finished: String? = null
    private var current = TranslatorRuntimeProbe(server, actor, origin, canFinish)
    override fun renderView(): Pair<UUID, String>? = current.renderView()?.let {
        it.first to (if (expression) "expression_" + it.second else it.second)
    }
    override fun poll(): String? {
        finished?.let { return it }
        val result = current.poll() ?: return null
        current.close()
        if (!expression) {
            first = result
            expression = true
            current = TranslatorRuntimeProbe(server, actor, origin, canFinish, ResponseFormat.SAM_EXPRESSION_V1)
            return null
        }
        finished = "protocols=2 json{$first} expression{$result}"
        return finished
    }
    override fun close() = current.close()
    companion object {
        val CASES = TranslatorRuntimeProbe.CASES + TranslatorRuntimeProbe.CASES.map { "expression_$it" }
    }
}
