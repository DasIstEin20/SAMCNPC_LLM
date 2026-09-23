# LLM configuration

Default API address: **http://127.0.0.1:1234/v1**. Integration is disabled by
default and makes no startup request. Install/load your model separately when ready.

Open **Mods → SAMCNPC LLM → Config** to edit the address and exact model ID.
Use Limits for timeouts, temperature and input/output bounds, and Call budgets for
per-NPC/server hourly calls. Apply saves the draft;
Done/Escape discards unsaved edits. Defaults changes the draft and still requires Apply.
The screen supports English and Polish and uses the SAMCNPC LLM logo.

`/samcnpc llm status <npc>` reports the last prepared request separately from the
goal quota: raw section bytes, escaped HTTP JSON bytes, conservative token upper
bound, template reserve, input/output allocation and declared model window.
Provider-reported token usage is labeled separately and may be unknown. `requestSent`
means transport submission, not proof of completed inference; `unknown` is never a
refund. Reports survive task completion in a 32-NPC transient cache, but disappear
after restart/reconfiguration, forgetting the goal or eviction. Status does no
inference and requires normal NPC authorization. Configuration rejects input plus
output greater than the window; hover the GUI error to read the full numeric hint.

These settings belong to the installation running the NPC server. The GUI applies
to this installation/singleplayer. On a dedicated server edit
`config/samcnpc-llm-common.toml`; a remote player's local settings cannot redirect
the server's requests. Forge reloads the configuration after valid file changes.
In multiplayer the local editor is read-only.

```toml
enabled = false
baseUrl = "http://127.0.0.1:1234/v1"
model = ""
apiKeyEnvironment = "SAMCNPC_LLM_API_KEY"
connectTimeoutSeconds = 5
requestTimeoutSeconds = 45
temperature = 0.1
maxOutputTokens = 1024
maxContextBytes = 65536
maxResponseBytes = 262144
responseFormat = "JSON_SCHEMA"
```

For Ollama's local compatible endpoint, set baseUrl to
`http://127.0.0.1:11434/v1` and use its exact loaded model ID.
A LAN endpoint can use e.g. `http://192.168.1.50:1234/v1`. The transport appends
`/chat/completions`; do not append that route yourself.
Addresses cannot embed a username/password, query string or fragment.

API keys are optional. Put a key in the named environment variable of the server
process, never in this file, GUI, user goal or behavior pack. Empty variable name
means no authentication. Selecting JSON_OBJECT is an explicit backend setting,
not permission to execute free text. Local schema/semantic validation still applies.

Translator commands are connected to the provider and the public Behavior gateway.
See [LLM_TRANSLATOR](LLM_TRANSLATOR.md) for commands, restart behavior and limits.
Supervisor and Planner are tracked separately in [LLM_INTEGRATION_PLAN](LLM_INTEGRATION_PLAN.md).
Tests use a CPU-only local HTTP emulator: no model installation, GPU allocation or
claim of real-model quality. The local Qwen profile has separate native smoke evidence;
see [gameplay repair](LLM_GAMEPLAY_REPAIR.md).

## Inference metering profile

The advanced `[inference]` section belongs to the same server-owned TOML file.
The GUI preserves it when editing connection fields. Changing endpoint/model makes
an existing exact-tuple declaration inapplicable until it is verified again.

```toml
[inference]
goalQuotaMode = "LIMITED"
hourlyQuotaMode = "LIMITED"
verifiedByteLevel = false
verifiedBaseUrl = "http://127.0.0.1:1234/v1"
verifiedModel = ""
backendVersion = ""
modelDigest = ""
tokenizerDigest = ""
templateDigest = ""
templateReserve = 2048
contextWindow = 32768
inputTokens = 8192
inputMicrosPerMillion = 0
outputMicrosPerMillion = 0
goalCostMicros = 0
hourlyCostMicros = 0
```

These defaults deliberately contain no verified model. `UNVERIFIED_TOKEN_PROFILE`
means no request is sent. The declaration is appropriate only after confirming a
conservative byte-level tokenizer bound and chat-template reserve for the exact
backend/model/tokenizer/template tuple; names alone prove nothing. Record immutable
identifiers in the evidence fields. Other tokenizers require their own validated
metering adapter before support can be claimed.

The current adapter bounds input by the entire UTF-8 serialized HTTP request plus
`templateReserve`, including the output schema. This is conservative accounting,
not measured token consumption. `inputTokens` must cover that bound and, together
with `maxOutputTokens`, fit the verified context window. Defaults are not an assertion
that every catalog prompt fits an 8192-token allocation. The full Translator catalog
uses about 40 KiB in the JSON_OBJECT emulator profile (49152 input reservation).
JSON_SCHEMA also sends a compact parameter contract in the model's system message;
the repaired Qwen profile uses a 63488 input reservation and a 65536 context window,
with 1024 output tokens and 2048 template reserve. Default annotations stay in the
prompt; internal decoding definitions are minified without changing constraints.
These are conservative caps,
not actual tokenizer counts. See [connection repair](LLM_CONNECTION_REPAIR.md).
With LIMITED policy, new goals fund 24 reservations at the configured input/output limits. Server token
caps fund `serverCallsPerHour` reservations. `[inference] npcCallsPerHour` defaults to
12 (range 1..360), `serverCallsPerHour` to 60 (range 1..720). The local gameplay
profile uses 60/120; this is an operator choice, not a global default. A ten-second
NPC cooldown and monetary caps still apply. Existing saved goals retain their
original limits across answers/resumes. Changed server limits retain spent hourly
reservations. Deferred requests show a reason and bounded retry delay in chat.

Rates and cost caps use integer millionths of the same operator-chosen currency;
zero rates/caps describe a local endpoint with no configured monetary charge.
A priced endpoint using quotas needs explicit rates and caps. No budget is silently enlarged or
refunded on transport failure. Endpoint changes preserve server-lifetime rate history.

The Quotas GUI page exposes independent `goalQuotaMode` and `hourlyQuotaMode`
switches (`LIMITED` / `UNLIMITED`). UNLIMITED disables call, token and cost quotas
for that scope. It does not change the context window, input/output allocation,
response/request bounds, profile verification, ten-second NPC cooldown, two-worker
concurrency, 32-wake queue, timeouts, bounded retries or failed-decision protection.
No endpoint address or model name chooses a policy automatically. Status prints the
saved goal mode and current hourly mode; unlimited goals expose no numeric remaining
call allowance. Charges remain diagnostic counters.

New goal records use NBT v6; v1–v4 records migrate as LIMITED without resetting
spending, task identity, memory or manual holds. Existing goals retain their saved
mode when configuration changes or a player answers/resumes. Restart settles a
held request once and retains the existing conservative review rules.
Version 6 adds typed intent contracts and persistent acquisition/delivery reservations.
Versions 1–5 migrate with no inferred semantic contract; their original policies remain.

Hourly switching preserves spending. Unlimited settled work uses at most 61 minute
buckets and 720 tracked NPC identities; live reservations remain exact and refundable
only when proven unsent. Enabling LIMITED can conservatively retain old bucket
spending for less than one extra minute. Capacity/overflow pressure is an explicit
diagnostic; it never expands memory or refunds submitted/uncertain work. Hourly
history remains server-lifetime state; persistent goal charges survive restart.

`/samcnpc llm compact <npc>` runs a fresh diagnostic projection without inference.
It reports raw STATE/prompt/contract/schema bytes, escaped HTTP bytes, token bound
and allocations before/after. It starts with lossless shared item facts and reduces
optional details only if necessary. The canonical goal, aliases, plan, confirmed
results, task and budgets remain intact; this is not `forget` or a model summary.
Actual inference continues to project fresh observations automatically.

Compaction works for exhausted or held goals as a diagnostic, without granting a
call. It invalidates the old decision revision. An in-flight/queued inference is
cancelled and left on a manual hold; use the existing resume/review flow explicitly.
A healthy Behavior task continues. Only one projection job can run at a time, and
its result is discarded if the goal changes or authorization is lost. Status retains
the metric report transiently; restart/reconfiguration clears it. No HTTP is sent
by the command, even if the report says the projected request fits.

These normal chat commands create an explicit typed intent contract:

```text
/samcnpc llm stock_to Sam minecraft:cobblestone 32 120 64 -30
/samcnpc llm take_more Sam minecraft:cobblestone 32 120 64 -30
/samcnpc llm deliver_carried Sam minecraft:oak_log 16 120 64 -30
/samcnpc llm transport_exact Sam minecraft:stone 16 10 64 20 30 64 50
/samcnpc llm wood_min Sam samcnpc:oak 16 100 64 -24 110 78 -14 120 64 -30
/samcnpc llm mine_min Sam minecraft:stone minecraft:cobblestone 16 100 64 -24 110 68 -14 120 64 -30
/samcnpc llm go_bounded Sam 120.5 64 -30.5
```

`stock_to` means target total; `take_more` means additional stock. Container arguments
are source then destination for transport. Harvest arguments are min corner, max
corner, destination. `wood_min` authorizes bounded foliage clearing and temporary
scaffolding inside the work area, with carried equipment and no replanting. `mine_min`
permits exposed/vein resource mining, with separate block and output item IDs.
Use a short unambiguous NPC name or UUID prefix; the vanilla command packet limit
is 256 characters. No oversized packet is introduced.

Advanced server-side integrations can invoke `/samcnpc llm goal_bounded <npc>
<document>` and `plan_bounded` with a strict JSON document containing exactly
`goal` and `constraints`; these long documents do not fit normal chat. The ordinary
free-text `goal` / `plan` commands remain available;
their status explicitly says `FREE_TEXT_UNCONTRACTED`. Schema validation alone does
not independently prove that a model understood that prose.

Example: deliver exactly 16 already-carried oak logs, without acquiring new stock:

```json
{"goal":"Dostarcz 16 niesionych dębowych kłód do skrzyni (120,64,-30). Nie ścinaj nowych drzew.","constraints":{"version":1,"operations":["samcnpc:deliver"],"resourceIds":["minecraft:oak_log"],"dimensionId":"minecraft:overworld","quantityMeaning":"EXACT_ADDITIONAL","quantity":16,"workBox":null,"exclusions":[],"sources":[],"destinations":[{"x":120,"y":64,"z":-30}],"navigation":[],"allowAcquisition":false,"allowDestruction":false,"allowAuxiliaryBlockWork":false,"allowPlayers":false}}
```

All constraint fields are required; unknown/duplicate fields and player-supplied
`initialStock` are rejected. The server captures initial carried stock. Empty lists
authorize no corresponding operation/resource/location. Resource IDs are exact
namespaced IDs; lumberjack uses species selectors such as `samcnpc:oak`. The work box
has integer `min`/`max` positions; exclusion boxes must be retained by proposed work.
Navigation destinations are explicit floating-point positions. This contract bounds
requested operations; it does not freeze the world or suppress external physical effects.

Version 1 supports navigate, carried delivery, container transport, single-item
inventory SUPPLY, exposed/vein mining with delivered-item counting, and lumberjack
without external supply/replanting. Other families/variants fail explicitly. Mining
resource block IDs and output item IDs must both be permitted. Lumberjack requires
explicit auxiliary block permission for its existing bounded foliage/scaffold work;
the Behavior guard also keeps those block effects inside its work area. No bounded
goal authorizes combat or extra logistics policies. AMEND supports only EXTEND_TIME
within the existing policy limits; other changes, including replacement, require a
new authorized goal. Existing uncontracted operations retain their normal catalog.

Quantity modes are `NONE` for navigation, `EXACT_ADDITIONAL` for exact transfer or
additional SUPPLY, `TARGET_INVENTORY` for SUPPLY to a total, and `MINIMUM_HARVEST`.
Supply requires the exact target and an activating minimum above initial stock.
Equal minimum and target is the canonical form; equivalent activating thresholds
are accepted. With 12 carried, target 32 authorizes 20 more;
32 additional authorizes a target of 44. A changed initial stock rejects the stale
interpretation. Harvest minima can overshoot when finishing work; exact/max harvest
guarantees are unsupported. A transfer must request the full authorized quantity;
partial allocation across several transfer operations is not supported in v1.

The goal reserves acquired/delivered intent before dispatch, conservatively retaining
it on uncertain or rejected dispatch. These counters are not gameplay receipts and
never reset at a Planner step, answer, resume, compaction or restart. Each repeated
step must still fit the original grant. Source/destination allowances apply to
container effects, while routes may leave the work box. Explicit return destinations
must be listed under navigation or equal the freshly observed NPC position.
