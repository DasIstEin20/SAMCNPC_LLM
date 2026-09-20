package io.samcnpc.llm.goal

import io.samcnpc.behavior.api.OperationSubscription
import io.samcnpc.llm.context.CapturedContext
import io.samcnpc.llm.decision.DecisionAdmission
import io.samcnpc.llm.scheduling.InferenceBudget
import java.util.UUID

/** Transient server-thread state. No player/entity references or historical world snapshots. */
internal class GoalSession(record: GoalRecord) {
    val goalId: UUID = record.goalId
    val actorUuid: UUID = record.actorUuid
    val budget = InferenceBudget(record.limits, record.budget)
    val admission = DecisionAdmission()
    var captured: CapturedContext? = null
    var subscription: OperationSubscription? = null
}
