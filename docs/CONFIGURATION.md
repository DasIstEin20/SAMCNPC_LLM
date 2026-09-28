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


## Verified input bounds

The advanced `[inference]` settings declare the exact backend/model/tokenizer/template
profile, conservative byte-level token bound, template reserve, context window,
input allocation and optional monetary/call quotas. An unverified profile sends no
request (`UNVERIFIED_TOKEN_PROFILE`). Names alone do not establish compatibility.
Input plus output and template reserve must fit the declared window. Keep API keys
in the named server-process environment variable.

Goal and hourly quotas may be LIMITED or UNLIMITED. Unlimited quotas do not remove
context limits, timeouts, cooldown, bounded concurrency, response validation or
permission checks. Submitted requests and uncertain outcomes are not refunded.

Use `/samcnpc llm` command completion for translation, supervision, planner goals,
status, pause/resume and cancellation. Status and compact inspection do not perform
inference. Integration starts disabled. Planner V1 is the default; V2 is explicitly
experimental and opt-in. Long free-text missions are not guaranteed to retain every
requirement. Deterministic operations remain available independently of a provider.
