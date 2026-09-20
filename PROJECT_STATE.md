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
