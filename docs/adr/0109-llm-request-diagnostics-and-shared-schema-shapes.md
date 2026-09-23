# 0109 — Request diagnostics and shared backend schema shapes

Date: 2026-09-23. Status: implemented; validation evidence in PROJECT_STATE.md.

The serialized backend schema repeated identical parameter structures and consumed
31,881 bytes in the captured full-contract fixture. This overhead is separate from
the model-visible operation contract and the legal world snapshot. Hiding operation
families based on a guess about user intent would change available behavior.

Keep every authorized family and the existing prompt, defaults, local typed decoder
and admission checks. After removing nonvalidating backend annotations, factor only
identical schema subtrees into local `$defs` references. Select at most 64 repeated
nodes, emit only reachable generated definitions and keep the original if total
bytes would grow. Replacing a complete subtree preserves every constraint; generated
edges point to strictly smaller subtrees and cannot introduce recursion. Independent
reference expansion must compare equal for each family and Translator/Planner.
Real-backend compatibility still requires an actual LM Studio request.

Whole-request metrics distinguish raw section bytes from escaped HTTP JSON bytes,
verified conservative token bounds from provider-reported usage, and provider entry
from proven submission. A server-thread cache retains one report per NPC, at most
32 NPCs, without goal/prompt text or credentials. It survives normal task completion,
is scoped to the goal ID, and is cleared on forgetting/dismissal/reconfiguration.
It is diagnostic, not persistence or execution evidence. Authorized status performs
no inference. Configuration validates the combined input/output allocation and gives
a numeric correction without silently changing the model window.

No persistence, Behavior execution, default protocol or public API changes here.
