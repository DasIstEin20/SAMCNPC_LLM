package io.samcnpc.llm.context

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class GoalItemMentionsTest {
    @Test fun eachQuantityStaysWithItsOwnLiteralItemAndSourceSpan() {
        val goal = "Pobierz 1 minecraft:iron_helmet, 64 minecraft:dirt i 16 minecraft:wheat_seeds."
        val rows = GoalItemMentions.encode(goal).map { it.asJsonObject }
        assertEquals(listOf("1", "64", "16"), rows.map { it["quantityText"].asString })
        assertEquals(listOf("minecraft:iron_helmet", "minecraft:dirt", "minecraft:wheat_seeds"), rows.map { it["itemId"].asString })
        for (row in rows) assertEquals(row["sourceText"].asString,
            goal.substring(row["sourceStart"].asInt, row["sourceEndExclusive"].asInt))
    }

    @Test fun textAnnotationsNeverInferAuthorityQuantityMeaningOrNegationAndRemainBounded() {
        val rows = GoalItemMentions.encode("Do NOT take 2 minecraft:tnt. Quoted name: 5 example:thing").map { it.asJsonObject }
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.keySet() == setOf("sourceStart", "sourceEndExclusive", "sourceText", "quantityText", "itemId") })
        assertEquals(0, GoalItemMentions.encode("a stack of dirt, 1.5 minecraft:stone, -2 minecraft:coal").size())
        assertEquals(32, GoalItemMentions.encode("1 minecraft:stone ".repeat(100)).size())
    }
}
