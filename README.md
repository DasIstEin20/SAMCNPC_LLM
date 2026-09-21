<p align="center">
  <img src="assets/samcnpc-llm-logo.png" width="720" alt="SAMCNPC LLM" />
</p>

# SAMCNPC LLM

English | [Polski](README.pl.md)

Optional high-level decision integration for Minecraft Forge 1.20.1.
LLM chooses bounded operations, Behavior executes and recovers, Core provides body
mechanics. Core and Behavior work independently without this mod.

Implemented modes: **Translator**, **stock Supervisor** and **bounded Planner**.
Translator selects one operation from the 16-family catalog. Supervisor maintains
an explicit visible chest stock target with hysteresis and failure-loop protection.
Planner executes one validated step at a time, with fresh inventory preconditions,
durable bounded memory and explicit player confirmation of an open goal.
Plan: **35/36**. The local Qwen profile now has a narrow native smoke; full
real-model corpus/other-backend evaluation remains pending.

The default endpoint is **http://127.0.0.1:1234/v1**. Address and model are editable
in Forge Mods Config. Integration is disabled by default; it never installs or
automatically loads a model. Routine tests use a CPU HTTP emulator. An opt-in
Qwen3.5-4B Q4_K_M test now covers eight command scenarios, including Polish/English
chest supply, oak harvesting with exact bounds/counts, delivery and navigation;
see [gameplay repair](docs/LLM_GAMEPLAY_REPAIR.md).

Commands accept Core-style NPC names and unique prefixes, with Tab completion:
`/samcnpc llm status Sam`. Full UUIDs still work.

[Configuration](docs/LLM_CONFIGURATION.md) · [Translator commands](docs/LLM_TRANSLATOR.md) ·
[Supervisor](docs/LLM_SUPERVISOR.md) · [Planner](docs/LLM_PLANNER.md) ·
[Memory](docs/LLM_MEMORY.md) · [Plan](docs/LLM_INTEGRATION_PLAN.md)

## Build

Use Java 17, pinned Forge 47.4.21, Kotlin 2.2.21 and Kotlin for Forge 4.12.0.

```sh
git submodule update --init --recursive
./gradlew clean build
```

Windows:  `gradlew.bat clean build`. Artifact: `build/libs/samcnpc-llm-0.1.0.jar`.
Install matching Core, Behavior and Kotlin for Forge. All public dependencies are
pinned submodules. Unit tests start neither Minecraft nor a model.

Real Forge probes include `runServerLoadingSmoke`, `runClientLoadingSmoke`,
`runClientGoalsSmoke`, server/client `SupervisorSmoke`, `SupervisorFaultsSmoke`
and `PlannerSmoke`. Server `GoalsSaveSmoke`/`GoalsLoadSmoke`,
`SupervisorSaveSmoke`/`SupervisorLoadSmoke` and
`PlannerSaveSmoke`/`PlannerLoadSmoke` use a fresh `-PllmGoalRestartId=<id>`.
Server runs require Minecraft EULA acceptance.

## Validation

The gameplay repair adds profile-scaled quotas, Forge GUI call limits, visible
quota waits and bounded real-eye chest observations. Prompt defaults remain
visible; the compact decoding schema preserves every effective constraint.
[Gameplay evidence](docs/GAMEPLAY_REPAIR_VALIDATION.json) records canonical and
standalone tests, native client/server scenarios and the focused real-Qwen run.
Bulk take-all and standalone armor equipping remain unsupported operations.

The following selector/release figures are historical.

The selector/connection repair has 525 passing workspace unit tests (49/364/112),
seven Python guards, dedicated and client Translator regression (8 cases/9 HTTP),
and 23 command authority checks. Standalone clean build also passed (112 LLM
units, 128 matching own sources). Server Supervisor/Planner and goal restart
passed their focused reruns. Real Qwen completed the separate three-case smoke.
[Repair validation](docs/CONNECTION_REPAIR_VALIDATION.json) records the current
artifacts and focused reruns. The following release/endurance evidence is historical.

Canonical clean build: 518 units (49 Core/364 Behavior/105 LLM), 850 frozen files and
three-JAR guards. Client and dedicated server each passed Translator 8 cases/9 HTTP,
Supervisor 14/22 and Planner 6/9. Physical supply balances, stale resources, provider
failures, manual controls and late cancellation passed. Three restart suites
retained known identities/intent without replay and held uncertain admission.
Dedicated admission: 37 checks; goal authority/input: 15 checks.

Standalone clean build: 105 LLM tests, 122 matching own sources.
[Release evidence and hashes](docs/RELEASE_VALIDATION.json) record the previous
release build; [goal-mode evidence](docs/PLANNER_VALIDATION.json) separates emulator/runtime
proof from real-model semantics. Unchanged Core 146/Behavior 215 native and Core 52 animation
cases retain prior evidence. [Project state](PROJECT_STATE.md) records limitations.
The active one-hour restricted patrol/melee test passed with six NPCs, twelve HTTP
calls and twenty minutes of actual endpoint shutdown. Fresh standalone client/server
loading also passed. Two-account skins are manual and nonblocking.

[Core](https://github.com/DasIstEin20/SAMCNPC_Core) ·
[Behavior](https://github.com/DasIstEin20/SAMCNPC_Behavior)

[Frozen evaluation corpus and scoring](docs/LLM_EVALUATION.md): 56 cases, three scripted repetitions; full real-model corpus quality remains pending.

[Installation](docs/LLM_INSTALLATION.md) · [Endurance](docs/LLM_ENDURANCE.md) · [Release acceptance](docs/RELEASE_ACCEPTANCE.md) · [Artifacts](docs/ARTIFACTS.md) · [Manual skin test](docs/MANUAL_SKIN_TEST.md)
