# Installing the integration candidate

Use Minecraft **1.20.1**, **Java 17**, Forge **47.4.21** and Kotlin for Forge
**4.12.0**. These are the pinned, tested versions. Use the three SAMCNPC artifacts
from the same recorded build:

- `samcnpc-core-0.1.0.jar`
- `samcnpc-behavior-0.1.0.jar`
- `samcnpc-llm-0.1.0.jar`

The LLM JAR is optional. Core and Behavior provide normal NPC operations without
it. The complete tested client/server configuration includes all three mods plus
Kotlin for Forge. The dependency is a separate runtime library, not a fourth
first-party SAMCNPC mod.

1. Stop the game/server and back up the world and configuration.
2. Replace the SAMCNPC JARs as a matched set in `mods/`. Older development
   snapshots also used version 0.1.0; the recorded hashes and repository pins
   identify this candidate. Mixed old/new snapshots are not a validated set.
   Audit builds also advertise API versions and source/build fingerprints in
   `META-INF/mods.toml`. Behavior checks Core; LLM checks Behavior and Core before
   initializing dependent code, even when inference is disabled. A legacy/mismatched
   JAR stops startup with a message such as `LLM requires Behavior API 2, installed
   missing/invalid`. Install a matched set; do not edit the identifiers to suppress
   this check. Fingerprints are diagnostics, separate from release JAR hashes.
3. Start normally. LLM integration defaults to disabled and does not contact a
   provider at startup. NPC operations remain available through Behavior.
4. When a model is available, configure its address and exact model ID under
   **Mods → SAMCNPC LLM → Config**. Default: `http://127.0.0.1:1234/v1`.
   A dedicated server uses its own `config/samcnpc-llm-common.toml`;
   a remote client cannot redirect the server's provider.

Endpoint/model selection alone is not a verified tokenizer profile. Follow
[LLM_CONFIGURATION](LLM_CONFIGURATION.md) before enabling inference. Its conservative
input bound must include the complete request/schema and fit the model's actual
context window. Emulator profile declarations are test fixtures, not profiles
approved for arbitrary local models. The verified Qwen3.5-4B Q4_K_M profile and final eight-case native smoke are
recorded in [the gameplay repair](LLM_GAMEPLAY_REPAIR.md) and
[validation evidence](GAMEPLAY_REPAIR_VALIDATION.json). Shared hourly defaults
are 12 calls/NPC and 60/server; the local testing installation uses 60/120,
configurable through the Call budgets page. Existing goals retain their saved
budgets; stop an old held goal before submitting a fresh goal when necessary.
JSON_SCHEMA is the recommended default. SAM_EXPRESSION_V1 is an opt-in experiment. This mod installs no model
and allocates no model VRAM.

For authentication, set the API key in the configured environment variable of the
server process. Configuration stores the variable's name, never the key. Do not
put credentials in goals, behavior packs or source control.

## Using an NPC

Use a connected summoner or authorized operator in the same dimension, within
256 blocks. `<npc>` accepts the name (for example `Sam`), a unique name prefix,
the eight-character UUID prefix shown by Core, or the full UUID. Matching ignores
case and uses Core's order: exact UUID, exact name, name prefix, UUID prefix.
Ambiguous matches are rejected. Tab suggests authorized nearby names and short IDs.
Full UUIDs retain lifecycle diagnostics; a capped nearby query requests a full UUID
instead of guessing uniqueness. Current range, dimension and summoner/operator
checks still apply after resolution.

```text
/samcnpc llm goal <npc> <one concrete goal>
/samcnpc llm maintain <npc> <item ID> <low> <target> <x> <y> <z> <goal text>
/samcnpc llm plan <npc> <bounded multi-step goal>
/samcnpc llm status <npc>
/samcnpc llm answer <npc> <clarification>
/samcnpc llm stop <npc>
/samcnpc llm resume <npc>
/samcnpc llm complete <npc>
```

Translator selects one of sixteen operation families. Supervisor maintains a
specific visible chest stock using Transport, Deliver or Lumberjack. Planner
executes one current step from Food, Lumberjack, Inventory Work, Navigate,
Transport or Deliver, with at most eight completed steps. After its recorded
steps, the player explicitly confirms an open goal. Crafting/building and model
commands or direct body controls are outside these modes.

See [Translator](LLM_TRANSLATOR.md), [Supervisor](LLM_SUPERVISOR.md),
[Planner](LLM_PLANNER.md) and [bounded memory](LLM_MEMORY.md) for precise controls.
Healthy deterministic work continues without periodic model calls. A disabled or
unavailable provider prevents a new decision; it does not turn a model summary
into success or reset a task's budget.

## Existing worlds and recovery

Core retains its versioned entity, inventory, summoner and skin persistence.
Behavior retains its own versioned task definitions and pack schemas. LLM goal
SavedData is version 4: v1/v2 records gain empty memory, v3 retains bounded memory,
and existing task identities and resource charges are preserved.

Queued, in-flight or uncertain admission recovers held for review. It is never
blindly submitted again. Known work can finish with the provider disabled.
Unknown/corrupt goal data is preserved read-only with a diagnostic. Named places
are user intent; they do not prove a chest or resource still exists.

Normal Minecraft saves are not an atomic transaction across the three mods.
Separate-JVM tests cover ordinary saves and controlled lost/stale receipts; they
do not prove write-ahead-log guarantees during an abrupt power loss. Restore the
world and matching mods together when rolling back; do not expect older binaries
to understand new SavedData.

## Building and reproducing evidence

Clone SAMCNPC_LLM with its pinned recursive submodules, then run
`gradlew.bat clean build` on Windows (`./gradlew clean build` elsewhere).
The LLM artifact is under `build/libs/`; dependency artifacts are under
`behavior/build/libs/` and `behavior/core/build/libs/`.

[Evaluation](LLM_EVALUATION.md) describes the frozen bilingual corpus;
[endurance](LLM_ENDURANCE.md) describes the explicit server benchmark tasks.
The validation JSON documents separate scripted contracts, physical gameplay,
restart and performance scopes. The two-authenticated-account skin comparison
remains a manual, nonblocking test.
