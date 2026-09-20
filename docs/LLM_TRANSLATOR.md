# Translator goals

Implementation status: L4 integration verified with the emulator (2026-09-20).
Real model quality remains USER_DEFERRED. All current HTTP probes use a CPU-only,
scripted OpenAI-compatible endpoint. It does not install or load a model.

## Commands

Use a connected summoner or authorized operator, in the NPC's dimension and within
the public Behavior inspection range (256 blocks). UUID means the full NPC UUID.

```text
/samcnpc llm goal <uuid> <goal text>
/samcnpc llm status <uuid>
/samcnpc llm answer <uuid> <clarification>
/samcnpc llm stop <uuid>
/samcnpc llm resume <uuid>
/samcnpc llm forget <uuid>
/samcnpc llm remember <uuid> <name> <x> <y> <z>
/samcnpc llm forget_place <uuid> <name>
```

Goal text is bounded to 1024 characters, accumulated clarification to 512, and the
one active question to 256. Text is data, never a Minecraft command. A fresh goal
requires the previous goal to be finished/stopped and no active Behavior task.
Answer applies only to the current question. Forget applies only to a finished goal
without an outstanding request. Console commands cannot impersonate a player.

Translator requests one typed operation from the published 16-family catalog.
It may ASK_USER or WAIT. Model amendments and task controls are excluded by its
policy; explicit player stop/resume still use the public Behavior controls.
Ordinary task execution, recovery and interruptions generate no further inference.
CONTINUE is not a completion receipt. WAIT requires a later user action in this mode.

Status reports stable server codes, persisted phase and attempt count. Completion
comes from the exact Behavior task, never the model's summary. Manual pause,
replacement or definition/control changes hold supervision. Explicit resume observes
the current task and preserves the goal's budget. Stop invalidates an outstanding
response before cancelling a known task; it never cancels a different/unknown task.

## Persistence and restart

The version-4 `samcnpc_llm_goals` SavedData (v1/v2 migrate with empty memory; v3 retains memory; budgets/task bindings stay unchanged) contains at most 256 records, each
limited to 16 KiB encoded NBT. At most 32 goals have live sessions. Records retain
goal/actor/NPC IDs, text, revision, question, manual hold, reserved resource charges
and exact task identity/revisions. They contain no cached world, raw HTTP, secrets,
chat transcript or executable content. Bounded named places and authoritative
historical results are described in [LLM_MEMORY](LLM_MEMORY.md).

Queued/in-flight/admitting records recover as REVIEW_REQUIRED. Held attempts are
settled once without refund. Resume refuses an uncertain admission: inspect the
actual Behavior task, stop/resolve it explicitly and submit a fresh goal if needed.
Known executing tasks can reconnect to the authorized actor and finish with the
provider disabled. A restart never automatically replays an assignment.
Unknown/corrupt saved data is preserved read-only with a diagnostic.

SavedData dirty marks are not a write-ahead log or an atomic transaction with
Behavior. Recovery deliberately prefers review over guessing whether a mutation
happened. See ADR0097. World observations are recaptured for each new decision.

## Configuration and limits

See [LLM_CONFIGURATION](LLM_CONFIGURATION.md). Besides endpoint/model and enabled,
inference requires a verified metering profile for that exact endpoint/model.
No real profile is preapproved. Test emulator profile declarations are fixtures,
not configurations to copy to an arbitrary model.

The existing scheduler enforces two simultaneous requests, a ten-second NPC gap,
12 requests/NPC/hour and 60/server/hour. A goal retains 24 attempt slots and
196608 input / 24576 output token reservations across answers and resumes.
Server input/output reservations are 491520/61440 per rolling hour. Reservations
use conservative bounds, not reported usage refunds. Config changes preserve the
current server lifetime's rolling history. Rate windows restart with the server;
persisted goal charges do not. A healthy Behavior task continues without inference.

## Evidence scope

Dedicated and integrated-client probes exercise actual command → HTTP → strict
decision → public admission → world effects. The campaign covers clarification,
32-item transport/delivery, three-log lumberjack, missing source, full destination,
manual pause/resume, provider disable and delayed-response cancellation. Eight
client NPCs are rendered; five moving scenarios require walking animation.
Eleven authority/input probes cover strangers, range, overlong text, UUID and console.

The fixed `translator-corpus.json` supplies one complete Polish goal and one
ambiguous goal for each of 16 families. Scripted responses test HTTP, typed decoding,
policy and ASK_USER contracts. They do not measure semantic understanding by a real
model or claim physical execution of all 16 families in the LLM campaign. Existing
Behavior mechanics retain their separately recorded native/client evidence.
Authenticated skin comparison remains MANUAL_PENDING and nonblocking.

## Reproducing the automated campaign

From the canonical workspace, run:

```text
gradlew.bat clean build verifySourceBoundaries verifyCoreBoundary testDistributionGuard :samcnpc-llm:runClientLoadingSmoke :samcnpc-llm:runServerLoadingSmoke :samcnpc-llm:runClientGoalsSmoke :samcnpc-llm:runServerGoalsSaveSmoke :samcnpc-llm:runServerGoalsLoadSmoke verifyDistribution -PllmGoalRestartId=<fresh-id>
```

Use a fresh letters/digits/hyphen/underscore ID for each save/load pair. Both JVMs
share only that pair's isolated run directory. The save probe assigns two real tasks
through registered commands, then injects one original ADMITTING snapshot before
normal world save to represent a lost receipt. The load probe requires another PID,
retained task IDs, unchanged goal charges, physical completion of the known task,
and REVIEW_REQUIRED for the uncertain task with the provider disabled. This is
controlled persistence fault injection, not a claim to reproduce arbitrary disk loss.

Standalone repository commands omit `:samcnpc-llm:`. Root `verifyAll` includes the
new goal client and restart pair and chooses a fresh pair ID automatically. Gradle
checks explicit PASS reports; a successful Java process alone is insufficient.
JUnit uses dependency class outputs before reobfuscated Forge JARs, matching the
existing Behavior test setup and preventing named/SRG linkage mismatches.

Final campaign: clean build, 491 units (47 Core / 364 Behavior / 80 LLM), static
boundaries and exactly-three-JAR guard PASS. All 809 frozen source/build files match.
Dedicated and client each passed 8 Translator cases / 9 HTTP requests; client passed
11 authority checks. Two-JVM restart and lost-receipt recovery PASS. Core/Behavior
sources/builds are unchanged from the preceding evidence. Standalone build passed
80 LLM units with all own source files matching. See TRANSLATOR_VALIDATION.json in
the publication repository; local detailed evidence is translator-evidence.json.

## NPC selection and a previously waiting goal

All LLM commands accept names and unique prefixes like Core, including `Sam` or
an eight-character UUID prefix. Tab suggests authorized nearby NPCs. To retry a
previously idle goal after correcting provider configuration, use
`/samcnpc llm resume Sam`. Respond to ASK_USER using
`/samcnpc llm answer Sam <clarification>`. To replace an existing goal, use
`/samcnpc llm stop Sam` before `/samcnpc llm goal Sam <new goal>`.
CONTINUE is never completion and cannot start a missing task. The goal/status
code remains authoritative; a model summary is not an execution receipt.
