# 0102 — Visible decision contract and NPC command selectors

Date: 2026-09-20. Status: accepted.

A local Qwen request reached the endpoint but returned CONTINUE while no task was
active. JSON_SCHEMA passed parameter definitions only in response_format. The
model's messages named operations but did not describe their input documents.
Grammar-constrained output alone did not provide enough decision context.

Keep the full strict JSON Schema in response_format and the existing local
Behavior decoder, policy and authority gates. Also expose a compact type notation
in the system message. It is derived from the same schema, carries required vs
optional fields, references, alternatives and constraints, and factors repeated
types. JSON_OBJECT retains its full-schema system message. The notation is not an
executable language, parser or new authority surface. No automatic format fallback
or model-specific provider branch is introduced. Entire serialized requests still
count toward byte and verified input-token bounds. The local 65536-context Qwen
profile now reserves 63488 input tokens; hourly and goal caps are unchanged.

Resolve command selectors through Core's published loadedNearby handles, with
Core's matching precedence and case behavior. Commands retain exact UUID support.
Names and prefixes require an unambiguous nearby result; query saturation refuses
to guess. Tab suggestions exclude NPCs the actor cannot control. The existing
Behavior gateway rechecks authorization at command execution and operation
admission. LLM imports no Core command/entity implementation and stores UUIDs,
not aliases. No persistence version or lower-module API change is needed.

See LLM_CONNECTION_REPAIR.md for proof and remaining evaluation scope.
