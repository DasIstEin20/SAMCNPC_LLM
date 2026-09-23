# 0113 — Trusted goal constraints, independent of model output

Date: 2026-09-23. Status: accepted.

An authorized player can start a bounded goal/plan with a strict typed document
containing the natural-language goal and an explicit intent contract. The normal
free-text entry point stays available and is explicitly described as lacking an
independently verified semantic contract. No model extraction, keyword heuristic,
summary or inferred acknowledgement can create or widen permissions. Existing
stock supervision keeps its stricter stock contract.

Vanilla 1.20.1 ChatScreen and ServerboundChatCommandPacket both cap input at 256
characters (verified against the mapped development JAR). Fixed short commands
stock_to, take_more, deliver_carried, transport_exact, wood_min, mine_min and
go_bounded build the same contract from explicit typed arguments. They provide
the normal chat entry points. The full JSON commands are an advanced server-side
integration surface, not a promise that a long JSON document fits ordinary chat.
No custom oversized command packet, interpreter or NLP permission extraction is added.

Version 1 starts with the audited resource workflows: navigate, carried delivery,
container transport, inventory supply, mining and lumberjack. Unsupported operation
families/variants or guarantees are rejected explicitly by the bounded interface;
ordinary catalog support is unchanged. This is a closed supported subset, not an
implicit permission for effects the checker does not understand. Controls still
need the existing policy. Version 1 permits only EXTEND_TIME amendments; replacement,
quantity, source/destination and side-policy changes explicitly require a new authorized
goal. Public snapshots do not expose all current side policies, so reconstructing
an apparently safe replacement from partial evidence would be unsound.

The bounded immutable contract names permitted operation families, exact accepted
resource/item/wood IDs, dimension, permitted work box/exclusions, source/destination
containers and navigation destinations. Empty lists authorize none. It states
whether acquiring items, harvesting/destruction and auxiliary block work are allowed.
The initial resource subset does not authorize attacking players or autonomous
combat/logistics side policies. Missing required contract fields are invalid.
The box limits work/destruction, not the route to an explicitly permitted container.
Unverifiable auxiliary effects fail closed; do not claim a stronger physical bound
than the existing Behavior operation provides.

Quantity semantics are explicit: exact additional transfer/delivery, target inventory
stock, or minimum harvest output. Harvesting a complete tree/vein can overshoot its
minimum; reject exact/maximum physical-harvest promises rather than relabeling the
existing mechanics. For inventory goals, capture the initial authoritative stock
when accepting the contract; target 32 with 12 carried authorizes a deficit of 20,
while 32 additional means a target of 44. Refresh relevant carried stock at admission
and reject changes that invalidate the authorized interpretation.

Persist the contract and conservative acquired/delivered reservation counters with
the goal. Each executing Planner step uses the same remaining authorization; a new
step, answer, resume or restart cannot renew it. Reserve immediately before crossing
the admission mutation boundary, after policy/authority/freshness checks. Uncertain
dispatch retains its reservation and manual review. No rejected or uncertain dispatch
refunds unknown effects. Each transfer uses the exact requested quantity; supply uses
the exact target and a minimum above initial stock when filling is needed. Behavior
uses minimum only as an activation threshold: with 12 carried, minimum=32/target=44
and minimum=44/target=44 both acquire 32. A minimum at/below initial stock cannot
fulfil that acquisition. Splitting or amending that authorization is not part of v1.
Future plan prose remains data and is never treated as an executable grant.

All checks run after strict decoding and again against fresh authorized inspection
before dispatch. The model receives a compact authoritative contract in STATE, but
prompt compliance is not the enforcement mechanism. Goal revision and contract /
reservation equality participate in freshness checks. SavedData migration preserves
old goals as explicitly uncontracted; no retroactive promise of inferred intent.

Focused regressions cover oak/spruce substitution, enlarged areas/exclusions, carried
delivery versus harvesting, 12-to-32 versus 32-more, containers, ASSIGN/AMEND/REPLACE,
side policies, multiple Planner steps, stale inventory/authority, uncertain dispatch,
and separate-JVM recovery. Reuse the frozen language corpus; report unsupported
bounded capabilities separately from syntax and real-model understanding.
