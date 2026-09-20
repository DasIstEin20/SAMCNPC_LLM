# One-step bounded Planner

Use /samcnpc llm plan <npc> <goal text>. The same current summoner/operator,
range and dimension checks, disabled default, endpoint profile and whole-goal budgets
apply. The first Planner permits Food, Lumberjack, Inventory Work, Navigate,
Transport and Deliver. It cannot craft, build, issue task controls/amendments or
execute a stored list of operations.

Planner decisions use strict schema v2: the existing eight-decision envelope plus
a required nullable plan field. ASSIGN contains one current typed operation and:

```json
{
  "steps": ["Acquire food", "Acquire wood", "Load supplies", "Return"],
  "requiredItems": [{"itemId": "minecraft:iron_axe", "minimum": 1}],
  "minimumEmptySlots": 1
}
```

Other decisions have plan=null. The server accepts at most eight remaining
descriptions and at most eight completed steps across this goal; partial replanning
does not renew that allowance or inference charges. Descriptions are unverified
intent. No future operation is persisted or dispatched. The existing memory byte
limits also bound descriptions combined with named places.

Inventory preconditions are checked against the captured context and freshly
authorized inventory immediately before admission. Current world targets can
still change; ordinary Behavior recovery owns that problem. A terminal task failure
asks the user. Rejected/incomplete admission asks or holds. Scheduler transport/
output repair remains bounded; no healthy task progress creates new model calls.

A valid assignment receipt records the proposed remaining descriptions and exact
task identity. Only authoritative completion of that task advances one step.
A new step requires a new authorized context and model decision. CONTINUE and model
summary never mean goal completion. After the last recorded step, the open goal
asks for explicit confirmation:

```text
/samcnpc llm complete <npc>
```

Confirmation works without a provider and requires the specific confirmation state,
no active Behavior task and no in-flight inference. Alternatively answer with a
clarification while step/call budgets remain. Stop/resume retain the existing manual
control semantics. A replaced/uncertain task is never guessed or blindly replayed.

Goal-store v4 migrates v1/v2 with empty memory and v3 with its memory retained.
Old versions cannot introduce Planner records. A completed-step counter and bounded
remaining descriptions survive ordinary saves. A known execution can finish after
restart with the provider disabled; the next decision holds without extra charges.
Uncertain admission stays review-required. A real manual pause overrides a stale
LLM snapshot. This is conservative ordinary-save recovery, not an atomic WAL.

Verified with a CPU HTTP emulator: 514 total units (101 LLM), 841 matching frozen
source/build files, clean/static/distribution guards and full Translator/Supervisor
regressions. Dedicated and client each passed six Planner scenarios /nine HTTP:
physical food -> wood -> inventory supply -> return, a stale required item after
held HTTP, failed step, provider disable, manual pause/resume and late cancellation.
Healthy execution generated zero extra requests. Client rendered six cases and
observed walking in three. Dedicated admission passed 37 checks; client/server
goal commands passed 15 authority/input checks.

Two real JVMs retained three task identities, physically completed known work,
held a lost receipt and honored a real manual pause despite a stale LLM snapshot.
Remaining steps were retained without replay. Standalone clean build: 101 LLM
tests and 113 matching source files. See PLANNER_VALIDATION.json in the standalone
repository. Real-model semantics remain user-deferred; active endurance/release
acceptance is still pending. Controlled stale-save/lost-receipt injection is not
abrupt-crash atomicity.

`<npc>` accepts a name, unique name/UUID prefix or full UUID, with Tab completion.
See [NPC selection](LLM_INSTALLATION.md#using-an-npc).
