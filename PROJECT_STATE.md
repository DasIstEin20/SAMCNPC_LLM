# NPC selectors and Qwen connection repair — 2026-09-21

LLM commands accept Core-style names, unique name/UUID prefixes and authorized
Tab completion. Ambiguous matches are rejected; execution retains existing
summoner/operator, range, dimension and revision checks. Core/Behavior sources
and pinned dependency commits are unchanged.

The observed idle goal had a successful HTTP response but no useful assignment.
JSON_SCHEMA now includes a compact operation contract in the model messages,
while preserving the full decoding schema and local validation. The prior mode
had exposed the contract only through backend response_format. No automatic
retry/fallback or quota increase was introduced.

Validation: canonical and standalone clean builds each passed 525 unit tests
(49 Core / 364 Behavior / 112 LLM); 128 own source files match. Seven Python guards
and source/Core/distribution checks passed. Dedicated loading and client Translator
passed 8 scenarios/9 HTTP each with 23 command authority checks. Server Supervisor
physical group passed 10/10, Planner 6/9; fresh-JVM goal restart retained identities
without replay. Native HTTP exercised both response formats and delayed replies.

Actual LM Studio/Qwen3.5-4B Q4_K_M passed three native cases: clarification of
missing chest/tool details, physical delivery of 32 cobblestone, and navigation
from a Polish goal. One request per case. Its verified 65536-context profile uses
63488 reserved input / 1024 output / 2048 framing reserve; existing goal/server
quotas remain unchanged. Full real-model corpus and other-backend evaluation
remain pending (L3.5 PARTIAL, plan 35/36). Authenticated two-account skins remain
MANUAL_PENDING and nonblocking. The historical one-hour benchmark was not rerun.

See docs/LLM_CONNECTION_REPAIR.md, docs/CONNECTION_REPAIR_VALIDATION.json and
docs/CONNECTION_REPAIR_SHA256SUMS for current evidence/artifacts. All sections
below describe earlier checkpoints and retain their original scope.

# Integration acceptance — 2026-09-20

Automated LLM scope complete: **35/36**. Translator, explicit stock Supervisor and bounded Planner are implemented. L3.5 remains USER_DEFERRED until the user supplies a real endpoint/model; authenticated two-account skins remain MANUAL_PENDING and nonblocking. No actual model was installed. The default endpoint is http://127.0.0.1:1234/v1, editable with the model in Forge Mods Config.

Clean canonical build: **518 units** (49 Core /364 Behavior /105 LLM), seven Python guard tests, source/Core/distribution checks and 850 unchanged frozen inputs. Standalone clean build: 105 LLM tests, 122 matching own sources; fresh standalone client and dedicated-server loading passed. Core 1834e5d and Behavior c6e0273 remain pinned.

The frozen restricted PATROL/melee campaign passed **3600.046 active seconds /72001 ticks**, six NPCs, six completed tasks, twelve HTTP calls and twenty minutes with the actual endpoint stopped. Its scope differs from the separately accepted product-mode client/server/restart/fault campaign and historical mixed Behavior Zoo. Native stop retained zero workers. Cold costs and exclusions remain reported; no universal performance, model quality or power-loss atomicity claim is made.

See docs/RELEASE_ACCEPTANCE.md, RELEASE_VALIDATION.json, ENDURANCE_VALIDATION.json, PLANNER_VALIDATION.json and LLM_INSTALLATION.md. The three retained validation artifacts have SHA-256 checksums in docs/ARTIFACT_SHA256SUMS. Full commands and known limitations are recorded there. Earlier dated sections below are historical milestones, not the current remaining-work list.

# Verified LLM endurance — 2026-09-20

L7.2 complete; plan 33/36. Six native NPCs completed 3600.046 active seconds /72001 ticks in the frozen PATROL/melee profile. Twelve HTTP calls, six completed tasks, twenty minutes of actual endpoint shutdown and zero retained workers. Client/server/restart/fault goal-mode evidence remains the separately accepted Planner campaign.

Clean build/static/distribution: 518 units and seven Python guard tests; 850 frozen inputs unchanged. Standalone: 105 units /122 matching own sources. See LLM_ENDURANCE.md and ENDURANCE_VALIDATION.json. Final packaging/standalone loading and publication remain pending; real model USER_DEFERRED, skins MANUAL_PENDING/nonblocking.

# Endurance preflight — 2026-09-20

L7.2 remains IN_PROGRESS. Six real NPCs passed the short restricted patrol/melee
HTTP and direct-Behavior baseline. Source/profile and performance thresholds are
frozen before the >=3600-active-second run. The short proof is not an hour pass.
See LLM_ENDURANCE.md and ENDURANCE_PREFLIGHT.json. No production code changed.

# Frozen bilingual evaluation — 2026-09-20

L7.1 complete; integration plan 32/36. Corpus v1 has 56 complete PL/EN,
clarification and adversarial inputs with pinned prompt/corpus hashes and three
repetitions. Workspace and standalone each passed 168 scripted HTTP candidates:
96 typed assignments and 72 questions without executable payloads. This proves
the wire/decoder/policy contract; real-model operation selection, injection
resistance and gameplay success are separate scores, not inferred from fixtures.

Clean build/static/distribution: 516 units (49 Core / 364 Behavior / 103 LLM),
844 frozen source/build files; standalone 103 tests / 116 matching own sources.
Production and runtime tests match the accepted Planner evidence. See
LLM_EVALUATION.md and standalone EVALUATION_VALIDATION.json.

Next: active multi-NPC endurance and final packaging/acceptance. Real model is
USER_DEFERRED; authenticated skins are MANUAL_PENDING and nonblocking.

# Verified bounded Planner — 2026-09-20

L6.2–L6.4 complete; LLM plan 31/36. Planner admits one current operation through the
same public Behavior gateway, retains at most eight descriptions/eight completed
steps, rechecks inventory preconditions and waits for a fresh decision after each
authoritative result. Open goals require explicit player confirmation. No model
controls/amendments, crafting, building or stored operation sequences are admitted.
Goal-store v4 retains old memory/charges and holds uncertain admission after restart.

Evidence: planner-evidence.json; clean build, 514 units (Core 49 / Behavior 364 / LLM 101),
841 frozen source/build hashes and boundary/three-JAR guards PASS. Dedicated/client
each passed six Planner scenarios /nine HTTP, including real food/wood/inventory/
return balances and zero extra inference during healthy tasks. Client rendered 6,
walking 3. Full Translator/Supervisor regressions and all three restart suites passed;
37 admission and 15 authority checks include mode/version/step-budget rejection.
Three-NPC Planner restart retained identities and remaining intent, completed known
work, held lost receipt and honored manual pause over a stale save. This is ordinary
save fault injection, not a crash/WAL atomicity guarantee.

Standalone clean build: 101 LLM units and 113 matching own source files. Pinned Core
1834e5d and Behavior c6e0273 remain unchanged. Next: L7 frozen bilingual evaluation,
active >=3600-second multi-NPC performance/fault campaign and final release checks.
Actual model/backend semantics remain USER_DEFERRED; authenticated skins remain
MANUAL_PENDING/nonblocking. Publication is still local until authorized closeout.

# Verified bounded goal memory — 2026-09-20

L6.1 complete; LLM plan 28/36. Goal-store v3 migrates v1/v2 without resetting charges
or task identity. Immutable plan/place/result memory has explicit byte/list caps;
remember/forget_place require current authorization, reject busy goals, retain
budgets and never scan the named location. Only exact observed task outcomes enter
history. New goals inherit place labels only; forget/dismiss removes memory.

Evidence: memory-evidence.json; 509 unit tests (49 Core/364 Behavior/96 LLM),
833 frozen source/build hashes, clean/static/distribution and full Translator/
Supervisor client/server/restart regressions PASS. Actual memory commands and HTTP
projection passed on both sides; 13 authority checks and 33 dedicated admission
checks include rejected memory changes. Two JVMs retained named places and recorded
physical known-task completion while leaving uncertain admission unclaimed.
Standalone: 96 LLM tests, 105 matching source files; dependencies remain Core1834e5d
and Behaviorc6e0273. No real model was loaded. Planner execution remains next
(L6.2–L6.4), followed by L7 active endurance/release. Skins remain manual/nonblocking.

# Verified stock Supervisor — 2026-09-20

L5 complete in the CPU HTTP emulator scope; LLM plan 27/36. The explicit maintain
command replenishes one visible chest to the exact target, observes hysteresis,
retains whole-goal budgets and stops repeated/A-B/WAIT failures. Current authorization,
fresh stock and manual task changes govern every automatic step. Provider retries
remain bounded; no model request is issued during healthy deterministic execution.

Evidence: supervisor-evidence.json; clean build, 504 units (Core49/Behavior364/LLM91),
830 frozen source/build hashes and boundary/three-JAR guards PASS. Dedicated and
integrated client each passed 14 Supervisor scenarios / 22 HTTP calls, including
malformed-output repair, repeated HTTP 503 and stale stock admission. Client rendered
14 cases and observed walking in five. Complete Translator/config/scheduler/admission
regressions also passed. Two JVMs retained task identities, physically delivered 32,
held uncertain admission without replay and freshly detected removal from stock.

Standalone clean build: 91 LLM units, all 102 own source files matched; Behavior
c6e0273 and Core1834e5d remain pinned and unchanged. Prior Core146/Behavior215 native
and Core52 animation evidence is retained through source identity. Controlled
lost-receipt injection is not a crash/WAL atomicity proof. See LLM_SUPERVISOR.md and
ADR0099. Real models remain USER_DEFERRED; two-account skins MANUAL_PENDING/nonblocking.
Next: L6 bounded Planner, then L7 active one-hour multi-NPC acceptance and release.
Publication checkpoints remain local until the user-authorized 20%-remaining closeout.

# Verified visible stock observation — 2026-09-20

Core now provides an explicit one-item stock sensor for visible reachable vanilla
normal/trapped single/double chests. It refuses locks/ungenerated loot, unknown
chunks and unsupported containers, and never generates loot via getItem. Behavior
projects the read through current summoner/operator, range and dimension checks.
No automatic inventory scan or Supervisor runtime is enabled by this API alone.

Evidence: stock-evidence.json; clean build, 493 units (Core49/Behavior364/LLM80),
146 Core and 215 Behavior native cases, Core animation client, config GUI, dedicated
and integrated client with five stock reads after real deliveries on each side.
All 816 frozen source/build hashes and boundary/three-JAR guards PASS. Raw NBT stays
inside Core; serialization cost depends on existing item tags and must be considered
when scheduling explicit reads. See docs/STOCK_OBSERVATION.md and ADR0098.
LLM plan remains 23/36; next is the Supervisor runtime. Real model tests remain
USER_DEFERRED and authenticated skins MANUAL_PENDING/nonblocking.

# Verified Translator integration — 2026-09-20

L4 complete with CPU HTTP emulator; LLM plan 23/36. Player goal/status/answer/
stop/resume/forget commands, one-operation Translator, bounded SavedData, retained
budgets and conservative restart recovery are implemented. No real model loaded.
Default endpoint and model remain editable in Forge Mods Config; advanced metering
profile is server-owned and unverified by default. Supervisor/Planner remain next.

Evidence: translator-evidence.json; clean build, 491 units (Core47/Behavior364/LLM80),
8 dedicated and 8 integrated-client cases / 9 HTTP each; 11 authority/input checks;
physical transport/deliver/lumberjack, missing/full storage, clarification, manual
pause/resume, provider disable and cancelled late response PASS. Eight client NPCs
rendered and five moving scenarios showed walking. Two-JVM save/load retained task
IDs and charges; known work physically completed with provider disabled, injected
lost receipt required review and produced zero replay assignments. This is controlled
persistence fault injection, not an arbitrary crash/WAL atomicity guarantee.

All 809 frozen source/build hashes and boundary/three-JAR guards PASS. Core/Behavior
sources/builds unchanged: prior Core141/Behavior214 native and 12 client cases retained.
Standalone clean build: 80 LLM units and all own source files match. The 16-family
corpus tests scripted HTTP/typed policy/ASK_USER; real semantic translation quality
remains USER_DEFERRED. Two-account skin comparison remains MANUAL_PENDING/nonblocking.
See docs/LLM_TRANSLATOR.md and ADR0097. Publication checkpoint is local until the
user-authorized 20%-remaining closeout. Next: L5 Supervisor and fail-loop protection.

Failed attempts retained privately: natural terminal revision initially mistaken for
manual control (fixed with regression); oversized fixture reservation hit the real
server cap (fixture reduced, cap unchanged); restart fixture exceeded attempts and
navigation distance (fixed data, public validation unchanged); JUnit named/SRG class
mismatch (dependency outputs now precede reobfuscated JARs). No assertions disabled.

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
