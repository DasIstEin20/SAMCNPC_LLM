package io.samcnpc.llm.goal

import io.samcnpc.core.api.NpcHandle

/** Match Core command precedence without depending on its command/entity implementation. */
internal object LlmNpcSelector {
    fun matches(selector: String, nearby: List<NpcHandle>): List<NpcHandle> {
        if (selector.isBlank()) return emptyList()
        val exactUuid = nearby.filter { it.npcUuid.toString().equals(selector, ignoreCase = true) }
        if (exactUuid.isNotEmpty()) return exactUuid
        val exactName = nearby.filter { it.displayName.equals(selector, ignoreCase = true) }
        if (exactName.isNotEmpty()) return exactName
        val namePrefix = nearby.filter { it.displayName.startsWith(selector, ignoreCase = true) }
        if (namePrefix.isNotEmpty()) return namePrefix
        return nearby.filter { it.npcUuid.toString().startsWith(selector, ignoreCase = true) }
    }
}
