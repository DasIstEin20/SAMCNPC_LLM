# ADR 0096: Bounded event-driven inference scheduling

Date: 2026-09-20
Status: Accepted; scheduler verified, player goal controller remains L4

Keep inference work outside the authoritative tick. A server-thread coordinator
owns at most 32 coalesced queued NPC requests and two active attempts; workers receive
immutable context/config/request data and return typed results. One NPC cannot have
two active requests. Freshness admission from ADR0095 remains a separate server action.

Use monotonic elapsed time for local scheduling/cooldowns, and game ticks for the
existing context and task deadlines. Reserve attempts conservatively before dispatch:
at least 10 seconds apart per NPC, at most 12/hour/NPC and 60/hour/server. A rejected goal
preflight consumes no server reservation; once worker dispatch is reserved, later
local/transport failures do not refund it. Provider API invocation metrics are
separate and do not claim exact wire request counts.
Retry and repair must consume the same goal/global budgets and have finite counts.

One endpoint/config lifetime owns a circuit breaker. Three transport failures without
a valid successful provider response open a 60-second cooldown, extended by bounded
Retry-After when necessary. A half-open circuit allows one probe. Older in-flight
successes and duplicate replies cannot close a newer circuit. Configuration/auth/
compatibility errors require a new configured lifetime, not endless polling.
Per-attempt 429 delays must also respect Retry-After before the circuit threshold.

The coordinator must enforce finite goal/token/cost reservations as well as call
counts. A concrete real tokenizer and chat-template profile is user-deferred.
UTF-8 bytes are not measured tokens; no unverified real profile may claim an exact
token budget. The emulator can test explicit known metering and reservation failure.
Full 16-family requests currently occupy about 47/52 KiB in the two JSON profiles,
so an 8192-token model window cannot be assumed to fit the complete catalog.

Transient scheduling state is not crash persistence. L4 adds the bounded goal store
and reservation/admission recovery; it must not claim an atomic cross-mod SavedData
transaction or retry an uncertain world effect. Existing deterministic Behavior work
continues when inference fails, disconnects or is unavailable.

Validation covers FIFO/coalescing, all rate/queue limits, cancellation, shutdown,
out-of-order completion, circuit probes, retry/repair limits, meter/cost failures,
actual Forge thread boundaries and CPU-only HTTP fault injection.

Evidence: 473 units, 15 actual Forge scheduler scenarios / 15 loopback HTTP calls,
client/server loading/configuration, clean build and distribution guards PASS.
Rate time is virtual in the probe. All 789 frozen source/build files match; unchanged
Core/Behavior native evidence is retained explicitly. See LLM_SCHEDULING.md.
