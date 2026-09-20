package io.samcnpc.llm.goal

import com.mojang.brigadier.arguments.StringArgumentType
import io.samcnpc.core.api.CoreNpcApi
import io.samcnpc.core.api.NpcHandle
import io.samcnpc.core.api.NpcLoadedQuery
import io.samcnpc.core.api.NpcPosition
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

internal object LlmNpcArguments {
    fun argument() = Commands.argument("npc", StringArgumentType.word()).suggests { context, builder ->
        val actor = context.source.entity as? ServerPlayer
        if (actor != null) {
            val service = CoreNpcApi.service(actor.server)
            val choices = nearby(actor).filter {
                actor.hasPermissions(2) || service.runtime(it)?.snapshot()?.summonerUuid == actor.uuid
            }
            val values = choices.flatMap { listOf(it.displayName, it.npcUuid.toString().take(8)) }
            for (value in values.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)) {
                if (value.isNotBlank() && value.none(Char::isWhitespace) &&
                    value.startsWith(builder.remaining, ignoreCase = true)) builder.suggest(value)
            }
        }
        builder.buildFuture()
    }

    fun resolve(source: CommandSourceStack, actor: ServerPlayer, selector: String): UUID? {
        // Preserve full-UUID access to lifecycle/authorization diagnostics, including unloaded NPCs.
        val exactUuid = try { UUID.fromString(selector) } catch (_: IllegalArgumentException) { null }
        if (exactUuid != null && exactUuid.toString().equals(selector, ignoreCase = true)) return exactUuid
        val nearby = nearby(actor)
        // A capped public query cannot establish uniqueness: never silently select from a partial set.
        if (nearby.size == NpcLoadedQuery.MAX_LIMIT) {
            source.sendFailure(Component.literal("SAMCNPC LLM: NPC_SEARCH_LIMIT. Use the full NPC UUID."))
            return null
        }
        val matches = LlmNpcSelector.matches(selector, nearby)
        if (matches.size == 1) return matches.single().npcUuid
        val message = if (matches.isEmpty())
            "NPC_NOT_FOUND: '$selector' (same dimension, within 256 blocks)."
        else "NPC_AMBIGUOUS: '$selector'. Use a longer name or UUID: " + matches
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName })
            .joinToString(", ") { it.displayName + " [" + it.npcUuid.toString().take(8) + "]" }
        source.sendFailure(Component.literal("SAMCNPC LLM: " + message))
        return null
    }

    private fun nearby(actor: ServerPlayer): List<NpcHandle> = CoreNpcApi.service(actor.server).loadedNearby(
        NpcLoadedQuery(actor.serverLevel().dimension().location().toString(),
            NpcPosition(actor.x, actor.y, actor.z), NpcLoadedQuery.MAX_RADIUS, NpcLoadedQuery.MAX_LIMIT))
}
