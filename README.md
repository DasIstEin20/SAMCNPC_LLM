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
See [configuration](docs/LLM_CONFIGURATION.md) and [context](docs/LLM_CONTEXT.md).

**Work in progress:** decision admission, scheduling and Translator → Supervisor →
Planner. This version does not yet turn player goals into NPC work. The plan is
12/36 complete. Tests use a lightweight local HTTP emulator; actual model/backend
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

Canonical clean build: 436 units (47 Core/364 Behavior/25 LLM), actual dedicated
context/HTTP and client GUI/HTTP, boundaries and three-JAR distribution PASS.
Standalone clean build: 25 LLM units; source match and artifact hashes in
[evidence](docs/CONTEXT_VALIDATION.json). [Project state](PROJECT_STATE.md) separates
current checks from unchanged Core/Behavior gameplay evidence. Two-account skin
appearance is a manual, nonblocking check.

[Core](https://github.com/DasIstEin20/SAMCNPC_Core) ·
[Behavior](https://github.com/DasIstEin20/SAMCNPC_Behavior)
