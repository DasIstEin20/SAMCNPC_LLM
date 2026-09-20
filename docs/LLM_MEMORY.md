# Bounded goal memory

Goal SavedData v3 adds at most eight unverified plan descriptions, sixteen named
places and sixteen authoritative historical task results. Intent/alias UTF-8
accounting is capped at 4096 bytes; results at 2048 with oldest-result eviction.
The existing 16 KiB encoded record and 256-record server limits remain unchanged.
Versions 1 and 2 migrate with empty memory and unchanged budgets/task identity.
Malformed saves are retained read-only.

The current slice wires named places and task history into Translator and stock
Supervisor contexts. Planner step selection is the next slice; descriptions are
never executable orders or proof of current world state.

For an existing idle, waiting, asking, stopped or finished goal:

```text
/samcnpc llm remember <npc UUID> <name> <x> <y> <z>
/samcnpc llm forget_place <npc UUID> <name>
/samcnpc llm status <npc UUID>
```

Names contain 1–64 lowercase letters, digits, underscores or hyphens. Coordinates
refer to the authorized player's current dimension; remembering them does not scan
or load that location. The usual current summoner/operator, range and dimension
checks apply. Memory editing works with a disabled provider. Inference/assignment
in flight and executing goals reject edits. Edits advance revision, retain charges
and do not resume held goals. A new goal inherits named places only.

Context aliases explicitly say USER_LABEL and currentWorldContents UNKNOWN.
All memory remains untrusted data. A decision captured against different memory
is rejected before dispatch, even if the caller supplied an unchanged revision.
Only an exact bound task's server-observed terminal state creates a historical
result; model summaries cannot write one. Explicit forget/dismiss removes memory,
ordinary unload retains it. Restart holds uncertain admission and never replays it.

Validation: 509 unit tests (49 Core/364 Behavior/96 LLM), clean build and all
833 frozen source/build hashes PASS. Dedicated and client each exercised registered
remember/forget_place commands, labels as UNKNOWN world contents in the real HTTP
request, immutable memory while execution was active, thirteen authority/input
checks and server-confirmed physical task history. Dedicated admission includes
three additional memory-freshness rejections (33 total checks). Two JVMs retained
aliases across a new goal/restart and recorded known completion without claiming
a result for uncertain admission. Full Translator and stock Supervisor regressions
passed. Standalone clean build: 96 LLM tests, 105 matching source files.
See MEMORY_VALIDATION.json in the standalone repo. Planner execution remains next.
