# ADR 0087: Physical-server provider configuration and local Forge editor

Date: 2026-09-20. Status: accepted; clean/server/client verification passed.

The user requested a default local IP with an editable Forge Mods Config screen,
a logo derived from Core/Behavior, and a lightweight HTTP emulator instead of
installing a large model. Actual endpoint/model selection is deferred by the user.

Use Forge COMMON configuration in config/samcnpc-llm-common.toml, with the same
schema on a dedicated server and a local integrated server. Forge SERVER configs
are synchronized to clients; provider settings should stay with the server
process instead. A remote client does not configure the dedicated server.
The local Mods Config screen edits title-screen/singleplayer settings only and
shows a read-only administrator hint when connected to a remote world.
No new network packet or remote administrative permission is introduced.

Default baseUrl is http://127.0.0.1:1234/v1; enabled=false and model is empty.
There is no startup probe, discovery, installation or model loading. Explicit
configuration may select a LAN/remote endpoint. Reject URL credentials, query,
fragment, unsupported schemes and ambiguous paths. Optional authentication is an
environment-variable reference; the secret is never stored in TOML or shown in GUI.

Immutable configuration snapshots have revisions. Apply validates the entire
draft and rejects a stale draft after external reload. Cancel does not save;
failed persistence restores the previous values. Runtime request admission will
capture the revision and invalidate old work when configuration changes.
The current configuration slice does not execute an NPC operation.

Connection defaults: 5s connect, 45s complete-response deadline, temperature 0.1,
1024 output tokens, 64KiB request limit. The transport envelope may use up to 256KiB
to accommodate escaped content/usage metadata; decoded decision text retains the
plan's separate 16KiB ceiling. Administrators may lower these bounds in the GUI.
JSON_SCHEMA and JSON_OBJECT are explicit profiles, never a silent fallback;
both must undergo the same later local decision validation.

Client registration is Dist.CLIENT-only. Logo uses the shared robot, cyan/white
palette and LLM badge, 1774x887 PNG with alpha, displayed in Forge and this screen.
No new dependency, fourth artifact or Core/Behavior runtime change is required.

Evidence: autonomy/run-20260920-llm/config-*.log and the three-mod smoke reports.
The emulator proves transport and fault handling only; real-model quality and
backend/version compatibility remain unverified until the user supplies them.

References: [Forge configuration](https://docs.minecraftforge.net/en/1.20.1/misc/config/),
[LM Studio structured output](https://lmstudio.ai/docs/developer/openai-compat/structured-output).
