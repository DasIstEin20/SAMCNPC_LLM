# ADR 0101: One-step bounded Planner (proposal)

Date: 2026-09-20
Status: Accepted (bounded CPU HTTP emulator scope)

Use the same scheduler, public Behavior admission, current authority and whole-goal
resource ledger. The first Planner policy permits Food, Lumberjack, Inventory Work,
Navigate, Transport and Deliver; no model task controls, amendments, building or
crafting. Ordinary deterministic execution makes no model requests.

Keep existing Translator/Supervisor decision schema v1. Planner uses a strict v2
envelope extending the same eight decisions with one nullable plan proposal.
ASSIGN must carry 1..8 remaining step descriptions and typed bounded preconditions
for the current operation. Other decisions carry no plan. The proposal contains
no operation sequence: the envelope still authorizes at most one current operation.
Future descriptions are unverified intent. A remaining plan may be revised at the
next decision, within an eight-completed-step goal cap and all existing budgets.

Check current-step inventory preconditions both against detached captured state
and a newly authorized server observation immediately before dispatch. Existing
policy/schema/semantic validators and freshness checks still apply. A changed
precondition rejects the decision without assignment. This does not make world
targets immutable: ordinary Behavior failure/recovery remains authoritative.

Persist only remaining descriptions, completed-step accounting and the exact
admitted task/receipt identity. Apply a proposed plan to memory only after a valid
assignment receipt; unknown admission remains review-required, never replayed.
On authoritative completion of that exact task, record its server result and
remove the current description. The next step requires fresh context and a new
model decision; nothing executes merely because it appears in SavedData.
Terminal failure or rejected/incomplete admission asks the user or holds; no
automatic failed-operation retry loop. Ordinary scheduler HTTP/output repair
allowances remain unchanged.

After the last successful step, ask for explicit player confirmation of the open
goal. CONTINUE and model summary never imply completion. A new authorized complete
command is limited to this confirmation state with no active task. User answers
may revise the remaining goal within its retained eight-step and inference budgets.
Do not claim measurable stock totals from a completed task description.

Manual pause/replacement, changed authority, cancellation, uncertain persistence
or invalid old plan holds automatic continuation. Reconnect only resumes observation
of known task identities or schedules a fresh decision at a known safe boundary.
A repeated restart cannot consume a completed step twice.

Required proof:
- strict v1/v2 envelopes; invalid/oversized plans and preconditions; no v2 bypass in
  Translator/Supervisor; no model-generated accomplishments;
- real food -> wood -> permitted inventory supply -> return, per-step physical
  inventory/storage/position balances; one HTTP per decision boundary;
- held HTTP while a required item disappears; no stale assignment;
- impossible next step asks/holds, user/manual control wins, provider offline leaves
  an existing task intact and creates no retry loop;
- two-JVM resume during known execution and uncertain admission, retaining step
  count/history/task IDs with no duplicate delivery or blind plan replay;
- clean/static/distribution, client/dedicated regressions and standalone publication.

Evidence: 514 units, 841 frozen files, full clean/runtime regressions and standalone
101 units/113 matching sources. Dedicated/client six scenarios /nine HTTP each and
three-NPC two-JVM restart PASS. See LLM_PLANNER.md. Real model semantics remain
user-deferred; final active endurance acceptance follows separately.
