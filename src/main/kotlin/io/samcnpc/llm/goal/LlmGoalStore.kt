package io.samcnpc.llm.goal

import com.mojang.logging.LogUtils
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.saveddata.SavedData
import java.util.UUID

/** One server's bounded goals. A malformed/unknown file is preserved read-only, never partially overwritten. */
internal class LlmGoalStore private constructor() : SavedData() {
    private val records = linkedMapOf<UUID, GoalRecord>()
    private var preserved: CompoundTag? = null
    var problem: String? = null
        private set

    fun get(npcUuid: UUID): GoalRecord? = records[npcUuid]
    fun records(): List<GoalRecord> = records.values.toList()
    val size: Int get() = records.size

    fun put(record: GoalRecord): String? {
        problem?.let { return it }
        if (record.npcUuid !in records && records.size >= MAX_RECORDS) return "GOAL_STORE_FULL"
        if (!GoalRecordCodec.fits(GoalRecordCodec.encode(record))) return "GOAL_RECORD_TOO_LARGE"
        records[record.npcUuid] = record
        setDirty()
        return null
    }

    /** Explicit user forget or a confirmed Core removal; never infer deletion from unload. */
    fun remove(npcUuid: UUID): String? {
        problem?.let { return it }
        if (records.remove(npcUuid) != null) setDirty()
        return null
    }

    override fun save(tag: CompoundTag): CompoundTag {
        val original = preserved
        if (original != null) return tag.merge(original.copy())
        tag.putInt("version", VERSION)
        val entries = ListTag()
        for (record in records.values.sortedBy { it.npcUuid.toString() }) entries.add(GoalRecordCodec.encode(record))
        tag.put("goals", entries)
        return tag
    }

    companion object {
        const val MAX_RECORDS = 256
        const val VERSION = 2
        private const val NAME = "samcnpc_llm_goals"
        private val LOGGER = LogUtils.getLogger()

        fun forServer(server: MinecraftServer): LlmGoalStore {
            check(server.isSameThread)
            return server.overworld().dataStorage.computeIfAbsent(::load, ::LlmGoalStore, NAME)
        }

        internal fun empty(): LlmGoalStore = LlmGoalStore()

        internal fun load(tag: CompoundTag): LlmGoalStore {
            val store = LlmGoalStore()
            val problem = when {
                tag.allKeys != setOf("version", "goals") || !tag.contains("version", Tag.TAG_INT.toInt()) -> "INVALID_GOAL_STORE"
                tag.getInt("version") !in 1..VERSION -> "UNSUPPORTED_GOAL_STORE_VERSION"
                !tag.contains("goals", Tag.TAG_LIST.toInt()) -> "INVALID_GOAL_STORE"
                else -> null
            }
            if (problem != null) return reject(store, tag, problem)
            val entries = tag.get("goals") as ListTag
            if (entries.size > MAX_RECORDS || !entries.isEmpty() && entries.elementType != Tag.TAG_COMPOUND)
                return reject(store, tag, "INVALID_GOAL_ENTRIES")
            val version = tag.getInt("version")
            var changed = version != VERSION
            for (entry in entries) {
                val record = GoalRecordCodec.decode(entry as CompoundTag, version) ?: return reject(store, tag, "INVALID_GOAL_RECORD")
                if (record.npcUuid in store.records) return reject(store, tag, "DUPLICATE_GOAL_NPC")
                val recovered = record.recovered()
                changed = changed || recovered != record
                store.records[record.npcUuid] = recovered
            }
            if (changed) store.setDirty()
            return store
        }

        private fun reject(store: LlmGoalStore, source: CompoundTag, code: String): LlmGoalStore {
            store.records.clear()
            store.problem = code
            store.preserved = source.copy()
            LOGGER.warn("LLM goal store is read-only: {}; preserve the saved file for repair", code)
            return store
        }
    }
}
