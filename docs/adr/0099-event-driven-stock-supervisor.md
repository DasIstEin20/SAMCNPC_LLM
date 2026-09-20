# ADR 0099: Event-driven stock Supervisor

Date: 2026-09-20
Status: Accepted (CPU HTTP emulator scope)

Build stock maintenance as an explicit server-authorized goal over the published
visible-stock API. The player supplies item, recipient and low/target thresholds;
natural goal text remains data. This is a bounded first Supervisor mode, not arbitrary
continuous planning. Translator continues to support the complete 16-family catalog.

Persist goal mode and bounded supervision state in goal-store v2; migrate v1 as
Translator with all budgets/task bindings retained. Hysteresis state is intent:
historical counts/fingerprints are not fresh world knowledge. A new authorized
observation is mandatory at each decision and before admission.

Use the existing finite queue, rate/cost/token budgets and one-use admission slot.
Healthy task progress/recovery/interruption resume makes no inference request.
Sample waiting stock with a fixed global work cap and cooldown; enqueue one wake
on shortage after the target has been reached. No background thread accesses a world.

The first stock policy permits transport, delivery and lumberjack to currently visible/reachable storage, with exact item/recipient/deficit checks. Model task controls,
replacement and amendments remain disallowed. Manual task changes hold supervision.
A fresh goal must not compete with another active watch for the same storage/item, including adjacent halves of a possible double chest. This conservative
exclusion also rejects independently placed adjacent chests; it cannot race a double chest
through its other half.

Remember at most eight failed semantic decision/state fingerprints. Two failures of
the same decision at the same useful state, or three consecutive nonprogress outcomes
(including A/B), force user intervention. Context UUIDs, ticks, retry budgets, task IDs
and incidental body movement cannot create a new failure identity. Only confirmed
stock progress or explicit user intervention may reset the consecutive counter;
resource charges remain for the whole goal. No automatic replay of uncertain admission. Explicit Core dismissal cancels inference
and removes the bounded goal; unload/missing observations do not imply deletion.

Required proof: physical 256-item supply, removal/refill with hysteresis, full/missing
stores, same/A-B failures, provider/authority loss and restart. Count real HTTP calls.
No real-model semantic quality claim is possible until the user's model is supplied.

Evidence: 504 unit tests; 14 Supervisor cases / 22 HTTP calls per client/server side;
clean build, frozen-source guards and two-JVM restart PASS. Real models remain
user-deferred. See LLM_SUPERVISOR.md and standalone SUPERVISOR_VALIDATION.json.
