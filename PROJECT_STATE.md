# Project state — 2026-09-20

LLM plan: 12/36 points complete (L0, L1, L3.1/.2/.4).
Implemented: Forge configuration/logo, bounded HTTP provider/emulator, authorized
NpcLlmContext v1 with full inventory, task/progress/resources, legal sensors,
generations and on-demand Behavior journals. Defaults remain disabled; no player
goal executor, decision admission, scheduler or persistent planner is enabled.

Canonical clean build: 436 units (Core 47 / Behavior 364 / LLM 25), actual dedicated
context capture and worker encoding after dismissal, hidden-ore/authority/Unicode/
byte bounds, real client GUI/HTTP, dedicated shutdown closure, boundaries and
exactly three Java 17 mod JARs PASS. All760 frozen source/build hashes match.
Unchanged Core/Behavior retain 141/214 native tests and 12 actual client cases with 9
event probes from their recorded campaigns.

Standalone clean build: 25 LLM units PASS; all 39 source/resource/test files match
the frozen canonical ContextBuilder milestone. Runtime proof is the canonical
campaign with the same source/dependency graph, not a repeated standalone launch.
See docs/CONTEXT_VALIDATION.json and docs/LLM_CONTEXT.md. Earlier curated validation
JSON files retain historical evidence of their specifically dated source snapshots.

Next: strict eight-decision JSON/schema, policy/freshness admission and receipt
handling; bounded scheduler; Translator, Supervisor and Planner. Actual backend/
model choice is USER_DEFERRED. No model was installed. Authenticated skin appearance
is MANUAL_PENDING and nonblocking. Existing LICENSE is preserved; generated worlds,
configs, EULA files, screenshots and private logs are excluded from publication.
