# 0119 — Explicit required return in trusted goal constraints

Status: accepted, 2026-09-23.

A permitted navigation point does not require an operation to return there. An
otherwise valid proposal can omit the requested return and therefore fail to
fulfill the goal even when its physical execution succeeds.

GoalConstraints version 2 adds a nullable `requiredReturnTo` feet position. It must
also appear in the trusted navigation allow-list. Assignment admission requires
the typed operation to request exactly that return; missing or different points
are rejected before reservation or Behavior dispatch. For Navigate, its destination
is the endpoint. Unsupported operation families fail closed. Existing area, side
effects, authorization, context freshness and quantity checks remain in force.

The constraint comes from the player's bounded-goal document, never from model
output or a natural-language guess. Free-text goals still rely on model comprehension;
the prompt explains that an anchor alone does not request a return. Separate
free-text and bounded-goal trials must not be combined into a single model score.

GoalStore version 7 writes an explicit BOUNDED_V2 mode and validates it against the
constraint payload version. Version 6 BOUNDED_V1 records retain their original
permissions and reservation balances without inventing a mandatory return.
Malformed, missing or mismatched constraint versions preserve the whole file
read-only. Both the return point and constraint version form part of trusted
constraint identity used for freshness checks.

The operation catalog and physical task implementations do not change for this
constraint. Behavior performs return navigation and reports physical completion;
neither an LLM summary nor admission itself is evidence of success.
