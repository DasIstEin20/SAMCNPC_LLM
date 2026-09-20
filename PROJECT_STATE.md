# Resource dependency update — 2026-09-20

Behavior now exposes immutable per-item accounting and own work reservations
behind the existing inspection authorization. Canonical clean build passed
418 units, 210 Behavior native cases, 12 real client operations and both
three-mod GUI/HTTP smokes; the 737-file source snapshot matches. Core sources
are unchanged from the 141-case native visual campaign.
Standalone LLM clean build passed 19 units with exact canonical LLM sources.
Runtime proof is the canonical resource campaign; standalone loading smokes were
not repeated for this dependency-only update. See docs/RESOURCE_DEPENDENCY_VALIDATION.json.
No goal executor is enabled. See docs/RESOURCE_INSPECTION.md.

# Visual dependency update — 2026-09-20

Core and Behavior now expose authorized bounded real-eye visual observations.
The LLM executor/context is still in progress and no NPC goal execution is enabled.
Canonical visual milestone passed clean build (412 units), 141 Core and 210
Behavior native tests, 12 real client operations and both three-mod GUI/HTTP
smokes. Independent LLM clean build passed all 19 units; module sources match canonical.
Runtime evidence is the canonical visual campaign (standalone smokes were not
repeated for this dependency-only update). See docs/VISUAL_DEPENDENCY_VALIDATION.json.
Pinned Core 53e3f25069e404f60d7d8c3d08c6bcc4d4802283 and Behavior 64ad9eba0852bc7a7d4f58330264262809c00603.
See docs/VISUAL_OBSERVATIONS.md. Earlier dated evidence follows.

# Inspection dependency update — 2026-09-20

Behavior now supplies an authorized immutable own-body/task inspection: actual
parameters for 16 operation families, original definition versions and measured
progress with uncertainty. The final LLM ContextBuilder, sensors, journals and
admission remain in progress. No goal execution is enabled by this update.

Pinned Behavior d36eeebb3eb99ffb0093aa6fa833c9b6cfc6a881 and transitive Core
74da575fd659c82046040849470726e23a75ecab. Canonical clean build passed 410 units,
209 native Behavior cases, 12 real client operation cases (9 inspection probes),
three-mod client/GUI and dedicated HTTP emulator, and boundary/distribution checks.
Standalone clean build passed 19 LLM units and both real three-mod loading smokes, including GUI persistence and emulated HTTP timeout/503/recovery. Sources match the canonical LLM module; evidence: docs/VALIDATION.json. Earlier evidence follows.

# Project state — 2026-09-20

F02: 8/36 points complete (L0, L1.1 and L3.1/.2/.4).
Implemented configuration, Forge GUI/logo, typed HTTP provider and CPU-only emulator.
Canonical clean build passed 401 units (Core45/Behavior337/LLM19), boundaries,
three Java17 artifacts, real client GUI/HTTP and dedicated server HTTP.
The server kept ticking during a body timeout; both sides recovered after timeout/503.
See ADRs 0085–0088 and docs/LLM_INTEGRATION_PLAN.md.

This standalone checkout pins Behavior ea56e6e1232fdc680a9e532bf0d56d8a7553c1b1
and transitive Core 74d2ba9c9775cbd4729fbb146a750db6ba12ea32.
Independent clean build, all 19 LLM unit tests, dedicated and real client GUI/HTTP
checks passed. Source matches the canonical module; exactly three dependency/mod
JARs target Java17 and contain no test-only drivers. See docs/VALIDATION.json.

Next: L1 public observations/events and ContextBuilder, then L2 strict decisions/admission,
L3.3 bounded scheduling, Translator, Supervisor and Planner.
No player-goal command, automatic NPC decisions or persistent plan is implemented yet.
Default disabled. Actual model/backend selection is USER_DEFERRED; no model installed.
Authenticated two-account skin rendering is MANUAL_PENDING and nonblocking.

The repository LICENSE is preserved. Test-generated configs, worlds, EULA files,
screenshots and logs are excluded from source publication.
