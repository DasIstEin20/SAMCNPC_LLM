# 0112 — Manual context projection without inference

Date: 2026-09-23. Status: accepted for C5 implementation; validation pending.

`/samcnpc llm compact <npc>` explicitly runs the existing bounded request projection
and reports full/compacted STATE, HTTP and token-bound metrics without contacting
a provider. There is no canonical chat transcript to delete: each inference already
captures fresh public observations and budgets its complete request. Manual compact
therefore produces a fresh diagnostic projection, starting with lossless shared item
facts and reducing optional details only if required. It does not rewrite memory,
constraints, plans, receipts or Behavior state, and does not persist a world snapshot
or a preferred stale payload. Subsequent inference uses the same automatic budgeter.

Authorization precedes capture. Diagnostic capture can describe an exhausted goal
without granting another call; normal inference capture still rejects exhausted
budgets. A successful command advances the goal revision and invalidates its old
decision binding. If inference was queued/running, cancel it and leave an explicit
manual hold for the existing resume/review flow. Preserve its charges until normal
settlement proves unsent or charges a submitted/unknown request. A healthy Behavior
task and its subscription continue unchanged; compact itself creates no wake.

Encoding uses one bounded detached worker slot per controller, no provider reference,
and no world access from that worker. Repeated commands while occupied get explicit
backpressure. Server-thread completion checks current actor authorization and exact
goal identity/revision before exposing metrics. A stale/reconfigured/closed job cannot
alter a goal, resurrect inference or expose a report to a different actor. Reports
contain metrics only and are transient. This is a projection, not a factual summary
invented by another model.

Regressions must cover rich inventory/history conservation, no HTTP, authorization,
stale completion, healthy task continuation, in-flight cancellation/late reply,
exhausted quotas, manual holds, and unchanged goal/task/memory/anti-loop accounting.
