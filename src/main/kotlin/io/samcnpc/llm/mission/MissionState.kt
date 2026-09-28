package io.samcnpc.llm.mission

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag

internal class MissionState(val contract: MissionContract? = null, val plan: MissionPlan? = null,
                           receipts: List<MissionReceipt> = emptyList()) {
    val receipts: List<MissionReceipt> = java.util.List.copyOf(receipts)
    val stage: MissionStage get() = when {
        contract == null -> MissionStage.REQUIREMENTS
        plan == null -> MissionStage.PLAN
        else -> MissionStage.OPERATION
    }
    init {
        require(plan == null || contract != null && plan.problem(contract) == null)
        require(receipts.size <= 24 && receipts.map { it.requirementId }.distinct().size == receipts.size)
        require(receipts.isEmpty() || plan != null)
        require(receipts.all { receipt -> contract?.requirements?.any {
            it.id == receipt.requirementId && it.target !is MissionTarget.Items } == true })
        require(contract == null || MissionCodec.encode(contract).toString().toByteArray(Charsets.UTF_8).size <= MissionCodec.MAX_BYTES)
        require(plan == null || MissionCodec.encode(plan).toString().toByteArray(Charsets.UTF_8).size <= 4096)
    }
    override fun equals(other: Any?): Boolean = other is MissionState &&
        contract == other.contract && plan == other.plan && receipts == other.receipts
    override fun hashCode(): Int = listOf(contract, plan, receipts).hashCode()
}

/** Strict bounded payload. An absent v2 payload is distinct from an empty extraction stage. */
internal object MissionStateCodec {
    fun encode(state: MissionState): CompoundTag = CompoundTag().also { tag ->
        tag.putInt("version", 1)
        state.contract?.let { tag.putString("contract", MissionCodec.encode(it).toString()) }
        state.plan?.let { tag.putString("plan", MissionCodec.encode(it).toString()) }
        val receipts = ListTag()
        for (receipt in state.receipts) receipts.add(CompoundTag().also {
            it.putString("requirement", receipt.requirementId); it.putUUID("task", receipt.taskId)
        })
        tag.put("receipts", receipts)
    }

    fun decode(tag: CompoundTag): MissionState? {
        if (!tag.allKeys.containsAll(setOf("version", "receipts")) ||
            tag.allKeys.any { it !in setOf("version", "contract", "plan", "receipts") } ||
            !tag.contains("version", Tag.TAG_INT.toInt()) || tag.getInt("version") != 1 ||
            !tag.contains("receipts", Tag.TAG_LIST.toInt())) return null
        if (listOf("contract", "plan").any { tag.contains(it) && !tag.contains(it, Tag.TAG_STRING.toInt()) }) return null
        val rows = tag.get("receipts") as ListTag
        if (rows.size > 24 || !rows.isEmpty() && rows.elementType != Tag.TAG_COMPOUND) return null
        return try {
            val receipts = rows.map { entry ->
                val row = entry as CompoundTag
                require(row.allKeys == setOf("requirement", "task") && row.contains("requirement", Tag.TAG_STRING.toInt()) && row.hasUUID("task"))
                MissionReceipt(row.getString("requirement"), row.getUUID("task"))
            }
            MissionState(if (tag.contains("contract")) MissionCodec.contract(tag.getString("contract")) else null,
                if (tag.contains("plan")) MissionCodec.plan(tag.getString("plan")) else null, receipts)
        } catch (_: IllegalArgumentException) { null }
    }
}
