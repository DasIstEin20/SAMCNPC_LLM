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
Plan: 31/36; final active endurance/release acceptance remains pending.

The default endpoint is **http://127.0.0.1:1234/v1**. Address and model are editable
in Forge Mods Config. Integration is disabled by default; it never installs or
automatically loads a model. Current tests use a CPU HTTP emulator. Actual model
understanding and backend/tokenizer profiles remain unverified/user-deferred.

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

Canonical clean build: 514 units (49 Core/364 Behavior/101 LLM), 841 frozen files and
three-JAR guards. Client and dedicated server each passed Translator 8 cases/9 HTTP,
Supervisor 14/22 and Planner 6/9. Physical supply balances, stale resources, provider
failures, manual controls and late cancellation passed. Three restart suites
retained known identities/intent without replay and held uncertain admission.
Dedicated admission: 37 checks; goal authority/input: 15 checks.

Standalone clean build: 101 LLM tests, 113 matching own sources.
[Evidence and hashes](docs/PLANNER_VALIDATION.json) separate emulator/runtime proof
from real-model semantics. Unchanged Core 146/Behavior 215 native and Core 52 animation
cases retain prior evidence. [Project state](PROJECT_STATE.md) records limitations.
The active one-hour multi-NPC soak remains pending. Two-account skins are manual
and nonblocking.

[Core](https://github.com/DasIstEin20/SAMCNPC_Core) ·
[Behavior](https://github.com/DasIstEin20/SAMCNPC_Behavior)
