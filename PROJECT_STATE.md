# Project state — 2026-09-20

LLM plan: 17/36 complete (L0, L1, L2, L3.1/.2/.4).
Implemented: Forge configuration/logo, bounded HTTP/emulator, authorized NpcLlmContext,
strict eight-decision schema/decoder/policy, current-authority/generation/revision/TTL
admission, one-use context slots and read-only exact amendment reconciliation.
Default disabled; no player goal controller, scheduler or persistent planner enabled.

Canonical clean build: 449 units (Core47/Behavior364/LLM38), 214 native Behavior tests,
12 actual client scenarios with 9 receipt consumers, 30 dedicated admission probes,
171 independent schema checks and two full-context HTTP decisions PASS.
A late response after manual pause is rejected; a fresh authorized RESUME is applied
once. JSON_SCHEMA/JSON_OBJECT complete requests measured 47149/52405 bytes.
All 774 frozen source/build hashes, client GUI/server shutdown, boundaries and
exactly three Java 17 mod artifacts PASS. Core retains unchanged 141 native tests.

Standalone clean build: 38 LLM units; all 53 source/resource/test files match the
canonical milestone. Behavior is pinned to d03d3de07f166c838b78d922bb64ef7d7330867d;
Core is pinned transitively to 53e3f25069e404f60d7d8c3d08c6bcc4d4802283.
Runtime evidence is the canonical campaign; standalone runtime was not repeated.
See docs/DECISION_VALIDATION.json, docs/LLM_DECISIONS.md and ADR0095. Earlier validation
JSON files retain historical evidence for their specifically dated source snapshots.

Next: bounded scheduler, then Translator commands and durable goal/admission state,
Supervisor and Planner. The goal controller must retain manual hold until explicit
user resume; the transient admission slot alone is not restart persistence.
Real model/backend profiles remain USER_DEFERRED, with no model installed.
Authenticated skins remain MANUAL_PENDING/nonblocking. Existing LICENSE is preserved;
generated worlds/configs/EULA/screenshots/private logs stay out of published source.
