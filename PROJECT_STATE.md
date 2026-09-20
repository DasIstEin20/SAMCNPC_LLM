# Project state — 2026-09-20

LLM plan: 18/36 complete (L0, L1, L2, L3.1–L3.4). Configuration/logo, bounded HTTP,
authorized context, strict decisions/admission and bounded inference scheduling
are implemented. Default disabled; player goal commands/controller and SavedData
remain L4, followed by Supervisor/Planner. No actual model is installed or verified.

Canonical clean build: 473 units (Core47/Behavior364/LLM62), 15 actual dedicated
scheduler scenarios / 15 HTTP calls, 30 admission probes and full-context HTTP;
client Mods configuration/logo and dedicated shutdown PASS. All 789 frozen source/
build hashes and boundary/exactly-three-JAR guards PASS. Core/Behavior sources and
builds remain unchanged, retaining Core141/Behavior214 native and 12 client gameplay
cases from earlier milestones. Those native tests were not rerun for this LLM slice.
The scheduler rate clock is virtual in the runtime probe, not an L7 one-hour soak.

Standalone clean build: 62 LLM units; all 68 own source/resource/test files match
the canonical snapshot. Behavior d03d3de07f166c838b78d922bb64ef7d7330867d and transitive
Core 53e3f25069e404f60d7d8c3d08c6bcc4d4802283 are pinned. Standalone runtime was not
rerun; canonical runtime evidence and artifact hashes are in docs/SCHEDULER_VALIDATION.json.
See docs/LLM_SCHEDULING.md and ADR0096; previous validation files are historical.

Real backend/model/tokenizer profiles remain USER_DEFERRED. The unverified profile
cannot dispatch inference; the byte-level bound requires an explicitly verified
backend/model/tokenizer/template tuple. Actual model quality is not established.
Player manual hold and durable admission recovery remain goal-controller work.
Authenticated two-account skins remain MANUAL_PENDING/nonblocking. Generated worlds,
configs/EULA, logs and private monitoring data are excluded from source publication.
