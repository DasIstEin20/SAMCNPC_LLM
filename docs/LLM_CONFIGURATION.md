# LLM configuration

Default API address: **http://127.0.0.1:1234/v1**. Integration is disabled by
default and makes no startup request. Install/load your model separately when ready.

Open **Mods → SAMCNPC LLM → Config** to edit the address and exact model ID.
Use Limits for timeouts, temperature and input/output bounds, and Call budgets for
per-NPC/server hourly calls. Apply saves the draft;
Done/Escape discards unsaved edits. Defaults changes the draft and still requires Apply.
The screen supports English and Polish and uses the SAMCNPC LLM logo.

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
New goals fund 24 reservations at the configured input/output limits. Server token
caps fund `serverCallsPerHour` reservations. `[inference] npcCallsPerHour` defaults to
12 (range 1..360), `serverCallsPerHour` to 60 (range 1..720). The local gameplay
profile uses 60/120; this is an operator choice, not a global default. A ten-second
NPC cooldown and monetary caps still apply. Existing saved goals retain their
original limits across answers/resumes. Changed server limits retain spent hourly
reservations. Deferred requests show a reason and bounded retry delay in chat.

Rates and cost caps use integer millionths of the same operator-chosen currency;
zero rates/caps describe a local endpoint with no configured monetary charge.
A priced endpoint needs explicit rates and caps. No budget is silently enlarged or
refunded on transport failure. Endpoint changes preserve server-lifetime rate history.
