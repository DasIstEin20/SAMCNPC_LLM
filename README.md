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

**Translator and bounded stock Supervisor are implemented.** Translator turns one
player goal into one validated Behavior operation. Supervisor maintains an explicit
stock target with hysteresis, fresh observations and bounded failure history.
Commands, budgets and conservative restart recovery are documented in
[Translator](docs/LLM_TRANSLATOR.md) and [Supervisor](docs/LLM_SUPERVISOR.md).
Plan: 27/36; Planner and final endurance acceptance remain next.
CPU HTTP emulator tests verify integration; actual model understanding and backend
profiles remain unverified/user-deferred. See [the plan](docs/LLM_INTEGRATION_PLAN.md).

Stock reads use visible reachable vanilla chests and current player authorization.
[Stock sensor scope](docs/STOCK_OBSERVATION.md) describes visibility and limitations.

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
fresh `-PllmGoalRestartId=<id>` for restart recovery. Supervisor has corresponding
`runServerSupervisorSmoke`, `runClientSupervisorSmoke`, server/client
`SupervisorFaultsSmoke` and `runServerSupervisorSaveSmoke` / `runServerSupervisorLoadSmoke`
tasks. Use a fresh restart ID for each campaign. Servers require EULA acceptance.

## Validation

Canonical clean build: 504 units (49 Core/364 Behavior/91 LLM), 830 matching frozen
source/build files and three-JAR guards. Dedicated and client each passed 14
Supervisor cases / 22 HTTP calls, plus all eight Translator cases / nine HTTP calls.
Supervisor exercised refill/hysteresis, stale stock, full/missing storage, A/B and
WAIT loops, manual control, provider disable, malformed JSON and repeated HTTP 503.
Healthy tasks caused no extra inference. Client rendered 14 Supervisor cases and
observed walking in five. Two-JVM restart retained task IDs, completed physical
delivery, held uncertain admission and reobserved stock without replay.

Standalone build: 91 LLM units, 102 matching source files.
[Evidence and hashes](docs/SUPERVISOR_VALIDATION.json) distinguish emulator/runtime
proof from real-model quality. Unchanged Core146/Behavior215 native and Core52
animation cases retain their earlier evidence. [Project state](PROJECT_STATE.md)
records limits. Two-account skins remain manual/nonblocking; real model profiles,
Planner and the active one-hour soak remain pending.

[Core](https://github.com/DasIstEin20/SAMCNPC_Core) ·
[Behavior](https://github.com/DasIstEin20/SAMCNPC_Behavior)
