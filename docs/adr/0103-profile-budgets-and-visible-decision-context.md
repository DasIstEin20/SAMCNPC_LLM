# ADR 0103: Profile-scaled budgets and visible decision context

Date: 2026-09-21. Status: accepted for implementation; runtime/model validation is
recorded separately in the gameplay repair evidence.

## Problem

A verified 63488-input reservation hit the historical 196608 goal and 491520
hourly token caps after only three/seven requests. Those defaults had assumed
8192 input per call. Timed waits updated saved state but did not notify the
player. A default context requested entities but zero block cells, leaving even
a nearby chest invisible to the model. Idle CONTINUE was structurally permitted
although it could not start a Translator task. Real-model tests also exposed
wrong counts and unnecessary low navigation speed despite valid JSON.

## Decision

New goals retain the hard 24-attempt cap. Their input/output quotas are the
verified input reservation and configured maximum output multiplied by 24.
Server token quotas similarly fund its configured hourly call count. Cost caps,
10-second NPC cooldown, bounded workers, cancellation, no-refund reservations and
server reconfiguration history remain enforced. Hourly calls are configurable in
COMMON TOML and Forge GUI: defaults 12 per NPC /60 per server, hard maxima 360/720.
Existing saved goals retain their original limits; this is not a budget reset.
No persistence version change is necessary.

Timed deferrals now notify on a changed reason, excluding ordinary NPC cooldown.
Status includes charged/allowed input. No request is claimed sent while deferred.

At a decision boundary, after Behavior authorization, LLM uses the published Core
real-eye block sensor on at most 128 deterministic cells within a four-block local
radius (feet level through two blocks above). It selects at most eight visible
container/wood surfaces, reobserves these and up to eight same-dimension location
aliases through public Behavior inspection on the same server tick, and returns
immutable values. No background world access, raw block scan, container contents,
chunk loading or new Core/Behavior dependency is introduced. Occluded/unavailable
cells are not facts; the sample is explicitly non-exhaustive. Context v4 provides
visible container endpoints separately from generic block observations.

The decision schema excludes controls outside policy and ASSIGN while a task is
active. Idle Translator cannot emit CONTINUE; local policy enforces this even
when a provider ignores the schema. Planner retains its existing terminal
confirmation path. Prompt v4 describes chest endpoints, supply counts, supported
inventory operations and omission of optional parameters so Behavior defaults
apply. Bulk take-all and standalone armor equipping are not supported operations;
the model must explain this instead of inventing UUIDs or fields.

Frozen evaluation corpus/profile v1 remains unchanged. A v2 profile pins the new
prompt and references the same 56-case corpus, retaining 168 scripted candidates.
Real-model quality is evaluated separately; scripted candidates are not language
or gameplay evidence. Response format remains an explicit provider setting; no
automatic fallback is added.

The installed LM Studio endpoint rejects json_object. A local text-format experiment
failed semantic checks and is not included. The verified profile uses JSON_SCHEMA.

Worker-side context also includes up to eight explicit comma-separated x/y/z
triples parsed from user goal text, with USER_TEXT_ONLY_NOT_OBSERVED_WORLD
provenance. This helps small models preserve coordinate signs; it does not infer
locations, change authority, bypass validation or translate the entire command.
The native wood scenario asserts exact bounds and requested species as well as
physical output, after earlier iterations exposed scope expansion despite delivery.

The LLM schema projection now preserves the catalog's default annotations.
Dropping them left speed/arrival bounds visible but normal values hidden; live
models selected minimum speed and stalled. Defaults remain annotations, with
unchanged schema/Behavior validation. Prompt v4 uses concise intent examples and
explicitly distinguishes enum alternatives from lists of items to perform.
Native admission probes assert idle CONTINUE rejection and active CONTINUE no-op;
scheduler-only idle fixtures use a legal WAIT while retaining all race/charge checks.

Default annotations remain in the model-facing prompt. The JSON_SCHEMA transport
omits their duplicate copy in the decoding grammar, along with uniqueItems=false
(the standard default). Every effective validation constraint is retained. This
keeps the native full-context probe under the unchanged 64 KiB request cap; the
first duplicated-default build exceeded that cap and was rejected, not exempted.

Transport definitions also use short internal names with rewritten references;
this changes no operation fields or constraints. Unit/native probes independently
expand both schemas and compare every effective constraint. Full-catalog CPU
Translator/Planner fixtures now reserve 63488 input bytes/tokens rather than the
older 49152 because the prompt includes defaults; exact charge/restart assertions
and request-size bounds remain in place. These are emulator declarations, not
permission to copy an unverified token profile into a real backend.
