# LLM endurance benchmark (preflight passed; full campaign pending)

This is a test-only, restricted PATROL profile around the production
InferenceScheduler, NpcContextBuilder and DecisionAdmission. It does not replace
the product goal controller. Translator/Supervisor/Planner command, persistence,
fault and client evidence remains the separately frozen Planner campaign.

The workload has six real NPCs in isolated arenas. All follow finite, zero-dwell
routes; three also defeat visible, immobile fixture zombies using ordinary melee.
Every counted defeat must identify that NPC as the attacker. Every completed task
must report PATROL_FINISHED and physically return. No teleport completes a task.

The clock counts only consecutive ticks when all six have a live task and moved
within the preceding second. Setup, idle transitions and scheduling gaps over one
second do not count. FULL requires both >=3600 active seconds and >=72000 active
ticks, at least one completed task per NPC, >=3000 travelled blocks per NPC and
at least ten confirmed defeats per combat NPC. The whole-process bound is 5400
seconds /108000 ticks. PROBE is a separate 60-active-second check, never hour proof.

BASELINE runs the same short route/fixture through public Behavior assignment
without an inference coordinator. PROBE/FULL use real loopback HTTP, conservative
request-byte charging and unchanged rolling server/goal limits. The first two
responses are held while at least twenty real ticks advance. In FULL the actual
emulator endpoint and workers are closed for twenty minutes while healthy tasks
continue, then a new endpoint/coordinator reuses the same budgets and rate gate.

Bounded measurements record server-side LLM/host supervision work, whole tick,
snapshot/admission costs, request-to-admission latency, heap/GC, queue/worker
peaks, charged input/output, calls, distances, defeats and task durations. Host
inspection/assertion overhead is included in the LLM work upper bound. The first 200 warmup ticks, setup, endpoint lifecycle changes and
report-writing ticks are excluded from performance samples; cold snapshot/admission
timings are retained separately. Targets: LLM work
p95 <=1 ms and p99 <=2 ms, queue <=32, workers <=2. Native stop must retain no
inference/emulator workers. Scripted provider usage is not a real tokenizer score.

Freeze the source manifest and chosen round count after preflight, before FULL.
Keep failures and both baseline/probe results. Do not tune performance thresholds
after measuring the full campaign.

Implemented Gradle tasks: `runServerLlmHourProbe`, `runServerLlmHourBaseline`,
`runServerLlmHour`. In the workspace prefix each with `:samcnpc-llm:`.
Supply a fresh `-PllmGoalRestartId=<id>`, manifest SHA-256 via
`-PllmHourSourceHash=<sha256>` and frozen full `-PllmHourRounds=<n>`.
Each run uses its own world and loopback port 25577. Run sequentially.
Reports are `llm-hour-progress.json`, `llm-hour-samples.json` and the final
`llm-hour-result.txt` in the corresponding module run directory.

This workload measures long-lived navigation/combat under an explicit restricted
policy. It is not the earlier eight-family Behavior Zoo or an hour of autonomous
language understanding. Real backend/model quality remains USER_DEFERRED.


Preflight source manifest: 4728f0064f7ed5f2eb261119e27229db40e26b40b969f988ffec2e19e47f891c.
Six NPCs passed both PROBE and BASELINE, with completed native patrols and
confirmed melee defeats. PROBE used 12 HTTP calls, kept the endpoint physically
closed for 20 seconds and left zero workers after native stop. Full campaign
is frozen at 14 rounds of a 16-waypoint route, with the thresholds above.
The short run does not close L7.2. See ENDURANCE_PREFLIGHT.json.
