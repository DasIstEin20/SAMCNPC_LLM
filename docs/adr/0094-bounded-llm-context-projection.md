# ADR 0094: Bounded LLM context projection

Date: 2026-09-20. Status: accepted and verified.

Capture once through authorized OperationInspectionApi on the server thread.
Keep server/player/facade/world objects out of CapturedContext. Serialization is a
pure worker operation over detached values; deleting the body after capture must
not make encoding touch the world. Admission of the resulting decision remains a
separate later server-thread step and must recheck all authority and generations.

NpcLlmContext v1 has a server-created binding: context/NPC/actor/goal IDs, goal and
policy revisions, Behavior server/registry/body generations, published operation
schema hash, issued/expires ticks (1..1200) and original task revisions. A copy of
these fields in model JSON will never be an authority to replace the binding.

Project fields explicitly. Never serialize an entire Core/Behavior object with
reflection: legacy human diagnostics can contain another NPC's reservation.
Expose all 36 slots, seven equipment stores and a main-hand slot alias, bounded
effects/enchantments, semantic roles/resource readiness, task parameters/progress/
accounting, action channels, legal visual facts, own reservations and sampled
history. Task destinations and user aliases are intentions, not current world
facts. Resource ledgers are checkpoints, not fresh container contents.

Unknown values stay explicit. Core completion failures have no invented task
correlation, failed target or recovery-step list. Preserve source codes; derive
only categories directly supported by those codes. Summaries count displayed
failure entries, not all historical attempts. World facts carry source and age at
capture; they do not become current again during HTTP processing.

The state ceiling is 24 KiB strict UTF-8. Deterministic compaction reduces
enchantment details 16->4->0 and ordinary recent events 16->8->0. Retain all slots,
item counts, task identity/revisions/parameters, policy, goal, effects and the last
eight retained failure entries; report every omission. If the mandatory set does
not fit, return CONTEXT_TOO_LARGE without inference. Reject invalid Unicode and
non-finite numbers. The provider still enforces 64 KiB on the complete escaped
request including prompt and schema. Bytes are not a token estimate; model-profile
token/window limits belong to L3 and are not claimed by this context milestone.

ContextPolicy is a copied typed input for operation/change/control allow-lists and
operation/extension budgets. It is not yet an admission implementation. Bounded
goal/memory views are inputs, not a new persistence layer: plan text is unverified
intent, aliases are user labels, and confirmed-results text must originate in
server-confirmed outcomes. L4/L6 will provide the durable source.

Evidence is recorded in PROJECT_STATE.md. Actual model compatibility remains
user-deferred; CPU HTTP emulation does not prove a model's planning quality.
