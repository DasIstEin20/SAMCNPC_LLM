<p align="center">
  <img src="assets/samcnpc-llm-logo.png" width="720" alt="SAMCNPC LLM" />
</p>

# SAMCNPC LLM

English | [Polski](README.pl.md)

Optional high-level decision module for SAMCNPC on Minecraft Forge 1.20.1.
LLM selects an operation; Behavior executes and recovers; Core provides body mechanics.
Core and Behavior remain useful without this mod.

Implemented: typed bounded OpenAI-compatible HTTP transport, strict JSON input checks,
timeouts/cancellation, explicit errors, Forge configuration and a Mods Config screen.
Default endpoint is **http://127.0.0.1:1234/v1**, editable together with the model ID.
Integration is disabled by default, with no startup request or automatic model loading.
See [configuration](docs/LLM_CONFIGURATION.md).
The pinned Behavior API also provides [authorized body/task inspection](docs/OPERATION_INSPECTION_API.md)
for the context builder under development.

**Work in progress:** context, decision admission, scheduling and
Translator → Supervisor → Planner are still being built. This version does not yet
turn player goals into NPC work. Tests use a lightweight loopback HTTP emulator;
real backend/model compatibility and model reasoning are not claimed verified.
See [the plan](docs/LLM_INTEGRATION_PLAN.md) and [the boundary](docs/LLM_BOUNDARY.md).

## Build

Use Java 17. Forge 47.4.21, Kotlin 2.2.21 and Kotlin for Forge 4.12.0 are pinned.
Clone recursively, or initialize dependencies before building:

```sh
git submodule update --init --recursive
./gradlew clean build
```

Windows: `gradlew.bat clean build`. Artifact: `build/libs/samcnpc-llm-0.1.0.jar`.
Install it together with corresponding Core, Behavior and Kotlin for Forge.
The pinned Behavior submodule pins Core in turn.

`test` runs the protocol emulator and unit tests without Minecraft or a model.
`runServerLoadingSmoke` and `runClientLoadingSmoke` launch real Forge, exercise the
transport emulator and verify configuration/loading. The client also tests the
actual Config screen, saving, invalid fields, stale edits and logo resources.
Minecraft test servers require EULA acceptance.

## Validation

Canonical workspace: clean build, 418 units (45 Core/337 Behavior/19 LLM),
boundary/distribution checks, dedicated and client HTTP/configuration tests passed.
This checkout's independent build/runtime results are recorded in [PROJECT_STATE](PROJECT_STATE.md).
Authenticated skin appearance remains a manual, nonblocking test.

[Core](https://github.com/DasIstEin20/SAMCNPC_Core) ·
[Behavior](https://github.com/DasIstEin20/SAMCNPC_Behavior)

2026-09-20: Behavior observation generations verified; canonical 423 units, 211 native Behavior tests, 12 client cases (9 generation probes), three-mod smokes. Standalone LLM clean build/19 units passed; [dependency evidence](docs/LIFETIME_DEPENDENCY_VALIDATION.json). On-demand events and ContextBuilder remain unfinished.
