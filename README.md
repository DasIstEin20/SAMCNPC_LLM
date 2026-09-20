<p align="center">
  <img src="assets/samcnpc-llm-logo.png" width="720" alt="SAMCNPC LLM" />
</p>

# SAMCNPC LLM

English | [Polski](README.pl.md)

Optional high-level decision integration for Minecraft Forge 1.20.1.
Architecture: LLM selects an operation; Behavior executes and recovers; Core provides
body mechanics. Core and Behavior remain useful without this mod.

Implemented: bounded OpenAI-compatible HTTP transport, timeout/cancellation and
error handling, Forge Mods configuration, and an authorized immutable NPC context
with all 36 inventory slots, task/progress, legal visual facts and bounded journals.
The default endpoint is **http://127.0.0.1:1234/v1**, editable with the model ID.
Integration is disabled by default: no startup request or automatic model loading.
Strict eight-decision decoding, policy/freshness admission, replay rejection and
read-only amendment reconciliation are implemented through public Behavior APIs.
See [configuration](docs/LLM_CONFIGURATION.md), [context](docs/LLM_CONTEXT.md) and
[decision contract](docs/LLM_DECISIONS.md) and [bounded scheduling](docs/LLM_SCHEDULING.md).

Bounded event scheduling, worker cancellation, retry/circuit and resource budgets are implemented.

**Translator implemented:** registered goal/status/answer/stop/resume commands turn
one player goal into one validated Behavior operation, retain budgets and recover
conservatively after restart. See [commands](docs/LLM_TRANSLATOR.md). Supervisor and
Planner remain in progress; plan 23/36. CPU HTTP emulator tests verify integration.
Actual model understanding and backend profiles remain unverified/user-deferred.
See [the plan](docs/LLM_INTEGRATION_PLAN.md) and [boundary](docs/LLM_BOUNDARY.md).

## Build

Use Java 17. Forge 47.4.21, Kotlin 2.2.21 and Kotlin for Forge 4.12.0 are pinned.
Clone recursively, or initialize the pinned Behavior/Core dependencies:

```sh
git submodule update --init --recursive
./gradlew clean build
```

Windows: `gradlew.bat clean build`. Artifact: `build/libs/samcnpc-llm-0.1.0.jar`.
Install it with corresponding Core, Behavior and Kotlin for Forge.
`test` uses no Minecraft client or model. `runServerLoadingSmoke` and
`runClientLoadingSmoke` launch real Forge, exercise HTTP/configuration, and verify
NPC context capture or the actual Mods Config screen. `runClientGoalsSmoke` exercises
physical commands/tasks; `runServerGoalsSaveSmoke` + `runServerGoalsLoadSmoke` use a
fresh `-PllmGoalRestartId=<id>` for restart recovery. Servers require EULA acceptance.

## Validation

Canonical clean build: 491 units (47 Core/364 Behavior/80 LLM). Dedicated and client
each passed eight physical Translator cases / nine HTTP requests; client passed
11 authority/input checks. Transport, delivery, lumberjack, missing/full storage,
manual pause, provider disable and cancellation passed. Two JVMs retained task IDs,
completed known work and held a simulated lost receipt without replay. All 809 frozen
source/build files and three-JAR guards match. The 16-family scripted corpus checks
wire/typed contracts, not real-model semantic quality. Prior unchanged Core141/
Behavior214 native and 12 client cases retain their evidence.
Standalone build: 80 LLM units, 88 matching source files. [Evidence and hashes](docs/TRANSLATOR_VALIDATION.json).
[Project state](PROJECT_STATE.md) records limits. Two-account skins are a manual,
nonblocking check. Real model profiles and the one-hour soak remain unverified.

[Core](https://github.com/DasIstEin20/SAMCNPC_Core) ·
[Behavior](https://github.com/DasIstEin20/SAMCNPC_Behavior)
