package io.samcnpc.llm.goal

import io.samcnpc.behavior.api.OperationType
import io.samcnpc.core.api.NpcBlockPosition
import io.samcnpc.llm.context.LlmMode
import io.samcnpc.llm.decision.DecisionSchema
import io.samcnpc.llm.intent.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class BoundedGoalPolicyTest {
    @Test fun boundedPlayerIntentNarrowsAdvertisedSchemaWithoutExpandingModePolicyOrBudgets() {
        for (mode in listOf(LlmMode.TRANSLATOR, LlmMode.PLANNER)) {
            val record = GoalRecord(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, "Deliver, never harvest", mode = mode)
            val base = GoalPolicies.forRecord(record)
            val contract = GoalConstraints(setOf(OperationType.DELIVER), setOf("minecraft:oak_log"), "minecraft:overworld",
                GoalQuantityMeaning.EXACT_ADDITIONAL, 16, 16, null, emptyList(), emptyList(),
                listOf(NpcBlockPosition(-1, 64, -2)), emptyList(), false, false, false)
            val bounded = GoalPolicies.forRecord(record.copy(constraints = contract))
            assertEquals(setOf(OperationType.DELIVER), bounded.operations)
            assertTrue(base.operations.containsAll(bounded.operations)); assertTrue(base.changes.containsAll(bounded.changes))
            assertEquals(base.maxTaskTicks, bounded.maxTaskTicks); assertEquals(base.maxTaskAttempts, bounded.maxTaskAttempts)
            assertEquals(base.controls, bounded.controls); assertEquals(base.maxExtensionTicks, bounded.maxExtensionTicks)
            val schema = DecisionSchema.forContext(UUID.randomUUID(), bounded, planner = mode == LlmMode.PLANNER)
            assertTrue(schema.contains("samcnpc:deliver")); assertFalse(schema.contains("samcnpc:lumberjack"))
            assertFalse(schema.contains("samcnpc:attack")); assertFalse(schema.contains("samcnpc:mine"))
        }
    }
}
