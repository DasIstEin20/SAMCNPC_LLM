# LLM decision contract and admission

The optional LLM module interprets eight bounded decisions. Behavior remains the
executor and owns movement, recovery, arbitration and persistence. This document
describes the implemented boundary; user goal commands, scheduling and durable goal
recovery are subsequent milestones. No inference starts with the default configuration.

Every response is one JSON object with exactly these required fields:

```json
{"schemaVersion":1,"contextId":"12345678-1234-4abc-8def-123456789abc","decision":"CONTINUE","summary":"The current task can continue.","operation":null,"change":null,"question":null,"wait":null}
```

| Decision | Non-null payload | Effect |
| --- | --- | --- |
| CONTINUE | None | No mutation; does not declare a goal completed. |
| ASSIGN | operation: published Behavior order document | Assign with original prior task UUID and TTL; never replace an active task. |
| AMEND | change: published Behavior change document | Submit one amendment using the context UUID as request UUID. |
| PAUSE, RESUME, CANCEL | None | Public control with the original task and both revisions. |
| WAIT | wait: trigger and ticks | No mutation; caller must register the bounded wake condition. |
| ASK_USER | question | No mutation; caller owns delivery and one outstanding question. |

WAIT triggers are TASK_TERMINAL, USER_UPDATE or DEADLINE. Only DEADLINE has a
non-null delay, 20..1200 ticks. Other payload fields must be null. A question is
nonblank and at most 256 UTF-16 units; summary has the same upper bound. Summary
and user/model prose never become permissions, code or completion evidence.

The strict decoder accepts at most 16 KiB UTF-8, rejects malformed Unicode,
duplicate fields, extra fields, trailing documents, unknown decisions and inconsistent
payloads, and delegates operation/change decoding to the published Behavior decoder.
Known IDs and valid JSON do not establish resource availability or world feasibility.

The generated schema uses the current published operation definitions, pruned by
the server policy. Only annotation fields and unreachable definitions are removed.
The portable structural schema is supplemented by local cross-field and semantic
checks. JSON_SCHEMA sends the contract as response_format; explicit JSON_OBJECT
includes it in the trusted system message. Neither profile bypasses local validation.
There is no automatic fallback after a provider compatibility failure.

Admission checks current connected-actor authorization through OperationInspectionApi,
then actor/NPC, goal and policy revisions, policy values, server/registry/body
generations, catalog hash, dimension, TTL, task identity, definition/control revisions,
task state, interruption IDs and pending amendment identity. Policy also limits
operation/change/control allow-lists, task ticks/attempts and time extensions.
Behavior repeats its own authorization and semantic/mechanical checks at execution.
An old rejected request never receives replacement revisions or a renewed TTL.

DecisionAdmission is one transient slot owned by one server-thread goal controller.
Binding a new context invalidates an older response. A consumed context cannot dispatch
again. Controller lifecycle events call invalidate; manualHold prohibits every
decision, including RESUME. The goal controller must retain manual hold until explicit
user authorization to resume. This boundary alone is not a user-command controller
or a persistent admission ledger.

The dispatch outcome is marked UNCERTAIN before calling Behavior. An unexpected
RuntimeException retains that state and logs only context UUID and exception class.
PENDING is not APPLIED. Read-only amendmentReceipt queries the exact original
task/request/actor/revision/timestamps/change and never submits a missing request.
Recorded PENDING/APPLIED/REJECTED/EXPIRED outcomes remain distinct.

Assignment and controls provide no historical exactly-once receipt. After an
uncertain reply, reconcile reads current state and retains UNCERTAIN; a matching
paused task is not proof that this particular request caused it. A new context cannot
replace an unresolved PENDING/UNCERTAIN slot. No automatic mutation retry occurs.
Durable restart reconciliation belongs to the later bounded goal store.

Verification is recorded in PROJECT_STATE and the decision evidence campaign.
The emulator validates actual HTTP framing and admission, not real model quality.
Backend/model profiles remain user-deferred.
