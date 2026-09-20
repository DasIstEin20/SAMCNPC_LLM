# LLM endurance benchmark — verified restricted profile

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
The short run is retained separately in ENDURANCE_PREFLIGHT.json. The full result below closes L7.2 together with the client/server/restart/fault Planner campaign.

## Full campaign result — 2026-09-20

PASS: 3600.046 active seconds / 72001 active ticks, six NPCs, 6 completed native patrols and 12 HTTP calls. The endpoint was physically closed for 1200.003 seconds. The second patrol of each NPC was still running at the hour boundary and was cancelled during cleanup; it is not counted as completed.

Measured LLM/host work p95 0.0185 ms, p99 0.0277 ms; whole server tick p95 0.681 ms, p99 0.9069 ms. Cold snapshot maximum 30.0317 ms and admission maximum 17.4495 ms remain visible. These percentiles are not a claim that every tick costs less than two milliseconds. Native stop retained zero inference/emulator workers. Whole-JVM heap samples are observations, not a universal no-leak proof.

Clean build: 518 units (49 Core/364 Behavior/105 LLM), seven Python guard tests, source/Core/distribution checks and 850 unchanged frozen inputs. Standalone clean build passed 105 LLM tests with 122 matching own sources. See [full measurements](ENDURANCE_VALIDATION.json).

## Reproduce from the standalone repository

After initializing recursive submodules, run `gradlew.bat clean build` first.
Then freeze the checked-out source with Python 3 (standard library only):

```text
python scripts/freeze_endurance.py --manifest build/endurance-source-manifest.json
gradlew.bat runServerLlmHour -PllmGoalRestartId=<fresh-id> -PllmHourRounds=14 -PllmHourSourceHash=<printed-sha256> --console=plain
python scripts/freeze_endurance.py --manifest build/endurance-source-manifest.json --verify --report run-server-llm-hour-<fresh-id>/llm-hour-progress.json
```

Accept the Minecraft EULA for this development server. The manifest must remain
unchanged between freeze and report verification. Use the hash printed for your
checkout, not the canonical workspace hash quoted above. Do not run `clean`
between freezing a manifest under `build/` and verifying it. Native server stop
also writes `retainedWorkers=0` to `llm-hour-result.txt`; inspect that result.
Run baseline/probe before FULL after changing the scenario, and freeze thresholds
before measuring. No other server/client/build workload should overlap the run.
