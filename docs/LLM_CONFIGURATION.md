# LLM configuration

Default API address: **http://127.0.0.1:1234/v1**. Integration is disabled by
default and makes no startup request. Install/load your model separately when ready.

Open **Mods → SAMCNPC LLM → Config** to edit the address and exact model ID.
Use Limits for timeouts, temperature and input/output bounds. Apply saves the draft;
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

Current delivery: configuration and logo verified in the real Forge client.
Bounded HTTP transport is verified with the local test emulator. Context/admission,
scheduling and autonomous planning are tracked in
[LLM_INTEGRATION_PLAN](LLM_INTEGRATION_PLAN.md); enabling a config switch alone
does not make unfinished modes operational. The transport is not yet wired to user goals. Tests use a CPU-only local HTTP
emulator, with no model installation, GPU allocation or claim of real-model quality.
