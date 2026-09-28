package io.samcnpc.llm.mission

import io.samcnpc.llm.decision.*
import io.samcnpc.llm.goal.*

internal object MissionOutcomes {
    fun adopt(record: GoalRecord, reply: MissionReply): GoalRecord {
        val mission = checkNotNull(record.mission)
        return when (reply) {
            is MissionReply.Question -> ask(record, "MISSION_QUESTION", reply.text)
            is MissionReply.Requirements -> {
                require(mission.stage == MissionStage.REQUIREMENTS)
                require(reply.contract.groundingProblem(record.contextText()) == null)
                record.copy(mission = MissionState(reply.contract), phase = GoalPhase.WAITING,
                    code = "MISSION_NEXT_STAGE", question = null, task = null)
            }
            is MissionReply.Plan -> {
                require(mission.stage == MissionStage.PLAN)
                record.copy(mission = MissionState(checkNotNull(mission.contract), reply.plan),
                    phase = GoalPhase.WAITING, code = "MISSION_NEXT_STAGE", question = null, task = null)
            }
        }
    }

    fun admitted(record: GoalRecord, decision: LlmDecision, outcome: DecisionOutcome): GoalRecord {
        val next = TranslatorOutcomes.admitted(record, decision, outcome)
        if (next.phase in setOf(GoalPhase.EXECUTING, GoalPhase.REVIEW_REQUIRED, GoalPhase.ASK_USER)) return next
        return if (outcome.state == DecisionOutcomeState.REJECTED) ask(next.copy(task = null), "MISSION_OPERATION_REJECTED",
            "The operation was rejected (" + outcome.code + "). Please revise the goal or resources.")
        else next.copy(manualHold = true)
    }

    fun terminal(record: GoalRecord, evaluation: MissionEvaluation): GoalRecord {
        require(record.phase in setOf(GoalPhase.COMPLETED, GoalPhase.FAILED))
        val mission = checkNotNull(record.mission)
        val next = record.copy(mission = MissionState(mission.contract, mission.plan, evaluation.receipts),
            phase = GoalPhase.WAITING, task = null, question = null,
            planStepsCompleted = record.planStepsCompleted + 1)
        if (record.phase == GoalPhase.FAILED) return ask(next, "MISSION_OPERATION_FAILED",
            "The operation failed. Verified mission requirements are retained; please revise how to proceed.")
        if (evaluation.complete) return next.copy(phase = GoalPhase.COMPLETED, code = "MISSION_COMPLETED")
        if (next.planStepsCompleted >= 8) return ask(next, "MISSION_OPERATION_BUDGET_EXHAUSTED",
            "The operation budget ended before all mission requirements were observed satisfied.")
        return next.copy(code = "MISSION_NEXT_STAGE")
    }

    fun boundary(record: GoalRecord): Boolean = record.mission != null && record.phase == GoalPhase.WAITING &&
        !record.manualHold && record.code == "MISSION_NEXT_STAGE"

    private fun ask(record: GoalRecord, code: String, question: String) = record.copy(
        phase = GoalPhase.ASK_USER, code = code, question = question, manualHold = false)
}
