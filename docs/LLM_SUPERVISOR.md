# Stock Supervisor

The first Supervisor maintains one explicit stock target in a visible, reachable
vanilla chest. This is an optional goal mode; Translator still supports the complete
16-operation catalog. A real model and its measured compatibility profile remain
user-deferred. Tests use a CPU HTTP emulator and do not establish model quality.

```text
/samcnpc llm maintain <npc> <item ID> <low> <target> <x> <y> <z> <goal text>
```

For example, with low=192 and target=256, initial stock below 256 starts a refill.
After reaching 256, 220 items does not wake inference; dropping below 192 does.
The next refill requests exactly the current deficit to 256. Coordinates and item
are server-validated policy; model prose cannot redirect supplies. The supplied
dimension is the player's current dimension. Source locations can be supplied in
goal text as intent, without claiming their current contents.

One goal may use Transport, Deliver or Lumberjack. Model task controls, replacement
and amendments are excluded. Transport has exactly one destination and no returnTo;
Lumberjack uses the exact item ID, not a broader wood preset, and no replant.
Behavior retains ordinary navigation, resource handling, retries and interruptions.

A waiting watch samples at most once per 20 ticks, with at most two watched sessions
visited per tick. It does not call the model for healthy task progress or ordinary
recovery. At decision capture and immediately before admission, the stock read is
authorized again. Changed stock rejects the old candidate. Unknown, locked, hidden,
unloaded or out-of-reach storage holds the goal; it is never considered empty.
One active watch is allowed per storage/item, conservatively including horizontally
adjacent positions to cover both halves of a double chest.

WAIT/DEADLINE schedules one bounded wake subject to the existing rate/token/cost
limits. One transport retry and one malformed-output repair remain scheduler-bounded;
a terminal inference failure holds the watch, so stock polling cannot create an
unbounded new request after the retry allowance is spent. WAIT/USER_UPDATE and idle CONTINUE require user intervention. A completed
Behavior task alone does not complete a maintenance goal: fresh physical stock
determines whether to wait or select another step. Missing authority, manual task
changes and uncertain admission stop automatic planning.

At most eight semantic decision/state failures are retained. Two failures of the
same candidate at the same useful state block its next admission; three consecutive
nonprogress outcomes also block A/B alternation. Retry budgets, context/task UUIDs,
ticks and incidental movement cannot disguise the same candidate. Only observed
stock progress or an explicit user answer/resume resets the nonprogress streak.
All inference charges remain for the whole goal.

Goal SavedData v4 includes bounded memory; v1/v2/v3 migrate without resetting task
identities or budgets (v1 records remain Translator). An explicit Core dismissal removes the watch; an unload never deletes
it. It persists target policy, hysteresis, failure hashes and historical
attempt accounting. It does not persist stock as current world truth. On reconnect,
known execution and waiting watches require current authorization and observations.
Queued/inflight/uncertain admission is held for review and never replayed.
This remains ordinary Minecraft save semantics, not an atomic transaction or WAL.

Verified with a CPU HTTP emulator: 504 total unit tests (91 LLM), clean build,
boundary/distribution guards and 830 frozen source/build files. Dedicated and client
each passed 14 Supervisor scenarios / 22 HTTP requests across separate physical
and fault campaign lifetimes. Healthy execution generated zero extra calls.
The client rendered all 14 cases and observed walking in five delivery scenarios.
Malformed JSON repaired once; repeated HTTP 503 stopped after the allowed retry.
Full storage, missing source, A/B and WAIT loops all stopped within their budgets.
Existing Translator/config/scheduler/admission regressions also passed.

Two real JVMs verified retained task IDs, physical 32-item delivery, uncertain
admission hold without replay, and fresh waiting-watch stock after removal.
This uses controlled lost-receipt injection and ordinary saves, not abrupt-crash
atomicity. Standalone LLM clean build passes 91 tests with all 102 source files
matching the canonical runtime-tested tree. See SUPERVISOR_VALIDATION.json in
the standalone repository. Planner and the active one-hour soak remain next.

`<npc>` accepts a name, unique name/UUID prefix or full UUID, with Tab completion.
See [NPC selection](LLM_INSTALLATION.md#using-an-npc).
