package io.samcnpc.llm.intent

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.samcnpc.behavior.api.*
import io.samcnpc.core.api.NpcBlockPosition
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class GoalConstraintCodecTest {
    private fun constraints() = GoalConstraints(setOf(OperationType.DELIVER), setOf("minecraft:oak_log"),
        "minecraft:overworld", GoalQuantityMeaning.EXACT_ADDITIONAL, 32, 0, null, emptyList(),
        emptyList(), listOf(NpcBlockPosition(-10, 64, -7)), emptyList(), false, false, false)
    private fun document(value: JsonObject): String = JsonObject().also {
        it.addProperty("goal", "Dostarcz niesione 32 kłody. Do not harvest.")
        it.add("constraints", value)
    }.toString()

    @Test fun playerCannotForgeInitialStockAndSavedContractRoundTripsWithoutGivingAuthorityToText() {
        val value = constraints().withInitialStock(12)
        val wire = GoalConstraintCodec.encode(value, persisted = false)
        val decoded = GoalConstraintCodec.playerDocument(document(wire))
        assertEquals(value.withInitialStock(0), decoded.constraints)
        assertEquals(value, GoalConstraintCodec.saved(GoalConstraintCodec.encode(value).toString()))
        assertThrows(IllegalArgumentException::class.java) {
            GoalConstraintCodec.playerDocument(document(GoalConstraintCodec.encode(value)))
        }
        assertEquals(setOf(OperationType.DELIVER), decoded.constraints.operations)
        assertEquals(setOf("minecraft:oak_log"), decoded.constraints.resourceIds)
        assertEquals(0, decoded.constraints.acquisitionLimit)
        assertEquals(32, decoded.constraints.deliveryLimit)
    }

    @Test fun requiredPermissionsUnknownFieldsDuplicatesAndUnsupportedFamiliesFailClosed() {
        val original = GoalConstraintCodec.encode(constraints(), persisted = false)
        for (key in original.keySet()) {
            val missing = original.deepCopy(); missing.remove(key)
            assertThrows(IllegalArgumentException::class.java) { GoalConstraintCodec.playerDocument(document(missing)) }
        }
        val variants = listOf(
            original.deepCopy().also { it.addProperty("allowPlayers", true) },
            original.deepCopy().also { it.addProperty("unknownPermission", true) },
            original.deepCopy().also { it.addProperty("quantity", "32") },
            original.deepCopy().also { it.addProperty("quantity", 32.5) },
            original.deepCopy().also { it.addProperty("quantity", 1e100) },
            original.deepCopy().also { it.add("operations", JsonArray().also { a -> a.add("samcnpc:attack") }) },
            original.deepCopy().also { it.add("resourceIds", JsonArray().also { a -> a.add("oak_log") }) },
        )
        for (bad in variants) assertThrows(IllegalArgumentException::class.java) { GoalConstraintCodec.playerDocument(document(bad)) }
        val duplicate = document(original).replace("\"quantity\":32", "\"quantity\":32,\"quantity\":1")
        assertThrows(IllegalArgumentException::class.java) { GoalConstraintCodec.playerDocument(duplicate) }
        assertThrows(IllegalArgumentException::class.java) { GoalConstraintCodec.playerDocument(document(original) + "{}") }
        assertThrows(IllegalArgumentException::class.java) { GoalConstraintCodec.playerDocument(" ".repeat(8193)) }
    }

    @Test fun targetStockAndCumulativeReservationsDoNotRenewAcrossStepsOrAliasCallerCollections() {
        val operations = mutableSetOf(OperationType.INVENTORY)
        val resources = mutableSetOf("minecraft:cobblestone")
        val sources = mutableListOf(NpcBlockPosition(-1, 64, -3))
        val target = GoalConstraints(operations, resources, "minecraft:overworld", GoalQuantityMeaning.TARGET_INVENTORY,
            32, 12, null, emptyList(), sources, emptyList(), emptyList(), true, false, false)
        operations.clear(); resources.clear(); sources.clear()
        assertEquals(setOf(OperationType.INVENTORY), target.operations)
        assertEquals(setOf("minecraft:cobblestone"), target.resourceIds)
        assertEquals(1, target.sources.size); assertEquals(20, target.acquisitionLimit)
        val spent = checkNotNull(GoalIntentReservation().reserve(GoalIntentReservation(acquired = 12), target))
        assertNotNull(spent.reserve(GoalIntentReservation(acquired = 8), target))
        assertNull(spent.reserve(GoalIntentReservation(acquired = 9), target))
        assertNull(spent.reserve(GoalIntentReservation(delivered = 1), target))
        assertEquals(GoalIntentReservation(acquired = 12), spent)
    }
}
