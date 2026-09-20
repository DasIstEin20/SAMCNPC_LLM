# NPC selection and local-model connection repair — 2026-09-20

All LLM commands now accept Core-style names and unique name/UUID prefixes.
For example: `/samcnpc llm status Sam`, `/samcnpc llm resume Sam`, or the short
UUID shown by `/samcnpc list`. Matching is case-insensitive; duplicate matches
require a more specific selector. Tab suggests names and eight-character IDs for
NPCs the player can control. Authorization, distance and dimension are still
checked by the existing gateway. A capped nearby query requires the full UUID.

## Why a connected model appeared inactive

The real game accepted a goal and received HTTP 200 from Qwen. The model returned
CONTINUE with no task, leaving WAITING / CONTINUE_NOT_COMPLETION. The transport's
JSON_SCHEMA mode provided the parameter contract to the backend's response_format
but not to the model's message content. The STATE named available operations and
referred to a contract that was absent from the messages.

JSON_SCHEMA now sends a compact type description derived from that same schema
in the system prompt as well as retaining the complete strict decoding schema.
JSON_OBJECT retains its full-schema prompt. Both still use local strict decoding,
public Behavior validation, current authority and revision checks. Request byte
and token bounds are retained; there is no automatic format fallback or retry loop.

A comparison with the same captured idle NPC context and goal "take tools from
nearest chest and get 64 wood" returned WAIT without the prompt contract and
ASK_USER about the chest with it (about 2.7 seconds). This is a narrow observation,
not proof that every bad model decision had the same cause.

## Real-model proof

The opt-in dedicated Forge test used the configured OpenAI-compatible endpoint,
real registered commands, real NPCs and actual Behavior operations:

- Missing chest/tool details: ASK_USER, one request, no task assigned.
- English delivery goal: 32 carried cobblestone moved into the destination chest;
  NPC inventory empty afterward, one request, TASK_COMPLETED.
- Polish navigation goal: NPC reached the requested position, one request,
  TASK_COMPLETED.

The profile is LM Studio 0.4.25+1, llama.cpp CUDA 12 runtime 2.41.0, Qwen3.5-4B
Q4_K_M. The local wrapper prefixes the GGUF chat template with `{%- set enable_thinking = false %}`;
API model ID `qwen3.5-4b`, context 65536, input reservation 63488, output 1024,
template reserve 2048. Model/tokenizer/template hashes and focused validation
results are recorded in CONNECTION_REPAIR_VALIDATION.json.

Input reservation uses the complete serialized UTF-8 request plus the verified
framing reserve. It is deliberately larger than actual Qwen token consumption.
The original profile's 49152 reservation was too small after the contract became
visible. Goal and server rolling quotas are unchanged; this profile can reserve
three calls per new goal and seven per rolling server hour before those token
caps are reached. They are not GPU throughput measurements. A waiting resource
budget is visible through `/samcnpc llm status <npc>`.

The multi-case emulator regression uses JSON_OBJECT to keep its nine HTTP calls
within the same rolling quota. JSON_SCHEMA is separately exercised by strict
provider/decoder tests, the native delayed-reply test and the three real-model
cases above. No quota check or fixture scenario was removed.

## Reproducing the opt-in model smoke

Build with the pinned dependencies, load the intended real model, and place its
verified `samcnpc-llm-common.toml` under
`run-server-live-model-smoke/config/`. Accept the Minecraft EULA for this test
server. Use a fresh disposable flat world on a free loopback port. Run
`gradlew.bat runServerLiveModelSmoke` in the standalone repository, or
`gradlew.bat :samcnpc-llm:runServerLiveModelSmoke` in the workspace. The command
never runs as part of ordinary unit tests or `verifyAll` and defaults to no
configured model. Inspect `live-model-result.txt` and the provider log. The test
must report both physical outcomes; an HTTP response alone is insufficient.

## Remaining scope

The full frozen PL/EN/adversarial model-quality corpus, other models/backends and
a live-model client rendering campaign remain unverified. Existing scripted mode,
restart and client evidence is separate. The earlier frozen hour-long benchmark
belongs to the previous build; this repair does not claim a new hour-long run.
Authenticated two-account skins remain manual and nonblocking. No player-world
save was edited by this repair.

After installing the update and restarting Minecraft, a goal previously left
waiting can be retried with `/samcnpc llm resume Sam`. Supply requested details
with `/samcnpc llm answer Sam <details>`. To replace it, stop the old goal first.
