# ADR 0104: complete request preflight and proven-unsent reservations

Date: 2026-09-23. Status: accepted for audit work A1/A2.

STATE compaction currently precedes whole-request encoding, and scheduler goal/hour
reservations survive a worker-side rejection without a provider call. Preserve the
bounded scheduler and the conservative treatment of uncertain external effects.

Prepare the complete request on the worker from detached data. Try the existing
three deterministic detail levels against both the STATE cap and complete serialized
HTTP/token allocation. The same codec produces provider payloads and byte diagnostics.
Keep authority, task identity/revisions, all inventory slots and failure-loop facts.
Report raw UTF-8 section sizes separately from the escaped total and verified token
upper bound; diagnostics never include the user's text or credentials.

An entered provider is not necessarily a submitted request. Add an explicit immutable
submission result to LlmCall: NOT_SENT, SUBMITTED, UNKNOWN. Existing/custom providers
default to UNKNOWN. OpenAiCompatibleProvider returns NOT_SENT only before its HTTP
submission boundary, UNKNOWN if crossing it failed ambiguously, and SUBMITTED once
sendAsync accepted the request. SUBMITTED is not proof the model executed it.

InferenceCancellation serializes cancellation against beginning provider invocation.
Before invocation, cancellation can prove NOT_SENT. Once entered, an unknown call
stays chargeable until a trustworthy result is available. Closing during that interval
settles conservatively; a later callback cannot refund it or admit a stale decision.

Keep reservations before work for concurrency. Release the exact in-memory reservation
only after proven NOT_SENT. Goal call/token/cost and the matching hourly entry are
released together; duplicate or restored reservations cannot invent a refundable charge.
There is one preparation per wake and zero automatic retries for local preparation
failure. All three compaction trials are within that preparation, not three calls.
Existing one transport retry/one output repair apply only after possible submission.
Local preparation failures are surfaced and await a new user/config decision.

The initial slice does not change the SavedData layout. Loaded in-flight reservations
remain uncertain and charged under the existing restart rule, since disk state alone
cannot prove that a worker never submitted. Historical charged counts are retained;
they are conservative budget accounting, not measured model usage. A later quota or
diagnostic persistence change must version that layout and test migrations explicitly.

Validation: reproduce preflight charging through the existing native scheduler probe;
test full-request compaction, exact byte units, proof of non-submission, cancellation
races, resource conservation, provider early rejection and uncertain outcomes. Run
unit/static, clean build and native client/server/restart before closing the slice.
