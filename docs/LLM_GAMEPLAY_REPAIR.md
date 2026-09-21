# Gameplay command repair — 2026-09-21

The player report exposed two independent integration faults: requests stopped
before reaching LM Studio because the historical token budgets funded only 3/7
large-profile calls, and a default context requested no block observations.
A nearby chest therefore appeared unknown even when the NPC could see it.

New goals fund their declared 24 attempts at the configured token reservation;
server token quotas fund the hourly call count. Cost limits, cooldowns, bounded
workers and retained charges remain enforced. Per-NPC/server hourly counts are
editable in Forge Config and COMMON TOML. Deferred requests show their cause and
retry delay instead of silently staying queued. Saved goals retain their limits.

At a decision boundary, a bounded real-eye sample exposes visible nearby container
positions with UNKNOWN contents. It cannot see through walls or read arbitrary
containers. Named SUPPLY can proceed and let Behavior check actual stock. The
prompt explains supported operations, required parameters, counts and defaults.
Idle Translator cannot return CONTINUE as a substitute for starting work. User
coordinate hints retain signed axes with explicit user-intent provenance.

## Local model verification

Non-thinking Qwen iterations could complete physical work while widening its area
or selecting extra wood species. The native test now checks the requested bounds
and oak selector in addition to delivered blocks and exact chest/NPC item balances.
Eight scenarios cover clarification, delivery, Polish navigation, supply in Polish
and English, repeated supply, lumberjack and unsupported bulk/equipment requests.
Every request uses the real endpoint and every successful task is measured in a
fresh disposable Forge world. Full model-quality corpus evaluation remains separate.

The final local profile remains Qwen3.5-4B Q4_K_M without thinking, temperature
0.1, 65536 context, 63488 input reservation and 1024 output. Hourly caps are 60/NPC
and 120/server on this installation; shared defaults remain 12/60. A separate
Qwen3-4B-Instruct-2507 Q4_K_M comparison did not improve all scenarios and is not
the active profile. Thinking-mode experiments exhausted the bounded output and
were abandoned. No API key or hosted service was introduced.

Prompt v4 retains normal Behavior defaults. The prompt distinguishes them from
minimum/maximum limits; duplicate default annotations are omitted only from the
backend decoding schema to stay within the unchanged request-size bound. Internal
definition names are shortened there; unit/native probes expand references and
compare every effective constraint with the original catalog schema.

Final run evidence and installed profile are recorded in GAMEPLAY_REPAIR_VALIDATION.json.
Failed experiments remain in local autonomy/run-20260921-gameplay logs. JSON_OBJECT
is rejected by this LM Studio version; the profile uses JSON_SCHEMA without fallback.

## Trying commands

Use the short NPC name, for example:

```text
/samcnpc llm goal Sam Pobierz 32 sztuki minecraft:cobblestone z najbliższej widocznej skrzyni do swojego ekwipunku.
/samcnpc llm status Sam
```

For wood, state the species, quantity, work area and destination (a nearby visible
chest can be named that way). Use coordinates or a saved alias for a container
outside the local visual sample. Use `answer Sam ...` for clarification and `plan`
for supported multi-step goals. Stop an old held goal before starting a new one if
necessary. Existing goals do not receive larger budgets through an answer/resume.

Arbitrary "take everything" and standalone armor equipping are absent from the
published Behavior catalog. They must remain a question/unsupported outcome,
not an invented operation. A small model can still misunderstand free-form text;
these focused scenarios do not establish reliability for every command or prove
resistance to all hostile input. Skins remain a manual, nonblocking acceptance test.
