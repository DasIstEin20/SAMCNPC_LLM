package io.samcnpc.llm.decision

import io.samcnpc.behavior.api.*
import io.samcnpc.core.api.NpcPosition
import io.samcnpc.llm.context.ContextPolicy
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DecisionPolicyTest {
    private val dimension = "minecraft:overworld"
    private fun policy(ticks: Int = 10000, extension: Int = 1000) = ContextPolicy(1,
        setOf(OperationType.NAVIGATE), setOf("EXTEND_TIME", "REPLACE", "QUANTITY"),
        setOf(OperationControl.PAUSE), ticks, 3, extension)
    private fun order() = OperationOrder.Navigate(dimension, NpcPosition(5.0, 64.0, 0.0))

    @Test fun catalogSyntaxDoesNotAuthorizeADifferentDimensionOrOperation() {
        val urlLikeIdentifier = order().copy(dimensionId = "https://example.invalid")
        // ResourceLocation permits ':' followed by '/'; it is an identifier here, never an HTTP endpoint.
        assertEquals(io.samcnpc.core.api.NpcActionStatus.SUCCEEDED,
            OperationSupervisionApi.validateOrder(urlLikeIdentifier).status)
        assertEquals("DIMENSION_MISMATCH", DecisionPolicy.orderProblem(urlLikeIdentifier, policy(), dimension))
        val disallowed = policy()
        val otherPolicy = ContextPolicy(1, emptySet(), emptySet(), emptySet(), 10000, 3, 1000)
        assertNull(DecisionPolicy.orderProblem(order(), disallowed, dimension))
        assertEquals("OPERATION_NOT_ALLOWED", DecisionPolicy.orderProblem(order(), otherPolicy, dimension))
    }

    @Test fun operationBudgetAndAttemptLimitsCannotBeExpandedByTheModel() {
        assertEquals("TASK_TIME_POLICY_EXCEEDED",
            DecisionPolicy.orderProblem(order().copy(budget = OperationBudget(10001)), policy(), dimension))
        assertEquals("TASK_ATTEMPT_POLICY_EXCEEDED",
            DecisionPolicy.orderProblem(order().copy(budget = OperationBudget(attempts = 4)), policy(), dimension))
        assertEquals("INVALID_OPERATION", DecisionPolicy.orderProblem(order().copy(speed = 100F), policy(), dimension))
    }

    @Test fun extensionAndReplacementCannotBypassTheSamePolicy() {
        assertNull(DecisionPolicy.changeProblem(OperationChange.ExtendTime(20), policy(), dimension, "samcnpc:navigate", 6000))
        assertEquals("EXTENSION_POLICY_EXCEEDED",
            DecisionPolicy.changeProblem(OperationChange.ExtendTime(1001), policy(), dimension, "samcnpc:navigate", 6000))
        assertEquals("TASK_TIME_POLICY_EXCEEDED",
            DecisionPolicy.changeProblem(OperationChange.ExtendTime(20), policy(ticks = 6000), dimension, "samcnpc:navigate", 6000))
        assertEquals("DIMENSION_MISMATCH", DecisionPolicy.changeProblem(
            OperationChange.Replace(order().copy(dimensionId = "minecraft:the_nether")),
            policy(), dimension, "samcnpc:navigate", 6000))
        assertEquals("CHANGE_UNSUPPORTED_BY_CURRENT_OPERATION",
            DecisionPolicy.changeProblem(OperationChange.Quantity(3), policy(), dimension, "samcnpc:navigate", 6000))
    }
}
