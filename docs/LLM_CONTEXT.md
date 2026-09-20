# NpcLlmContext v1

NpcContextBuilder captures one authorized immutable Behavior inspection on the
server thread. NpcContextEncoder accepts that detached capture on an inference
worker and returns bounded JSON plus its server-created ContextBinding. There is
no world access, HTTP, action dispatch or reflection in encoding.

The context includes identity, body, all 36 inventory slots, equipment, current
task and frames, counted progress/resources, simultaneous current mechanics,
bounded events/failures, legal visual observations, own reservations, typed
policy/capabilities, goal/memory, capture authority and clock/generations.
Main hand aliases a real inventory slot. Ranged readiness describes resources;
it does not certify target/range/channel validity. Ordinary edible items are not
claimed to heal: Core's separate healing facts are retained.

Visible facts carry source and age at capture. Missing/unrequested sensors are
explicit. A task destination, user place alias or reservation does not certify a
block's current existence or contents. Raw task/action detail strings and private
damage/relationship metadata are omitted. The model only gets source error codes
and supported categories; unknown failed targets/phases/recovery steps stay null.

The state limit is 24 KiB UTF-8. Three deterministic detail levels retain every
inventory slot and mandatory task/policy/goal value, plus up to eight source
failure entries. Optional enchantments and ordinary recent events may be reduced,
with explicit omitted/truncated counts. Oversized mandatory data is refused.
The provider applies its separate complete-request byte limit after escaping;
model tokens/context-window accounting is not inferred from character counts.

The binding includes UUIDs, original revisions, catalog schema SHA-256, server/
registry/body generation and a 1..1200-tick TTL capped by the goal deadline.
It does not authorize delayed actions by itself. Decision parsing, admission,
scheduler, persisted goals and the actual Translator remain later work.

Validation: six pure projection/privacy tests and a dedicated-server probe are
implemented. The probe captures an actual assigned task, rejects an actor outside
the permitted range, omits a real out-of-range ore, removes the NPC, and encodes on
a worker. It checks UTF-8 length, 36 slots, aliases, malformed Unicode and an
oversized mandatory context. Final clean build, 436 units, dedicated/client and boundary/distribution checks
passed with 760 frozen sources. Evidence is in PROJECT_STATE.md.
