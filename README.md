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

**Work in progress:** Translator → Supervisor →
Planner. This version does not yet turn player goals into NPC work. The plan is
18/36 complete. Tests use a lightweight local HTTP emulator; actual model/backend
compatibility and planning quality remain unverified.
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
NPC context capture or the actual Mods Config screen. Servers require EULA acceptance.

## Validation

Canonical clean build: 473 units (47 Core/364 Behavior/62 LLM), 15 actual dedicated
scheduler scenarios / 15 HTTP calls, plus 30 admission checks and full-context HTTP.
Client configuration/logo and three-JAR distribution PASS. Rate time in the scheduler
probe is virtual. Unchanged Core141/Behavior214 native and 12 client gameplay cases
retain the preceding milestone evidence; they were not rerun for this LLM-only slice.
Standalone source matching/build and artifact hashes: [evidence](docs/SCHEDULER_VALIDATION.json).
[Project state](PROJECT_STATE.md) records limits. Two-account skins are a manual,
nonblocking check. Real model profiles and the one-hour soak remain unverified.

[Core](https://github.com/DasIstEin20/SAMCNPC_Core) ·
[Behavior](https://github.com/DasIstEin20/SAMCNPC_Behavior)
