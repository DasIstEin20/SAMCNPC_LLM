# 0111 — Optional inference quotas and bounded metering

Date: 2026-09-23. Status: accepted for Q1/Q2 implementation; not yet a validation claim.

An explicit LIMITED/UNLIMITED mode is configured separately for the whole goal and
the rolling hourly policy. LIMITED remains the default. UNLIMITED disables that
scope's call/token/cost caps; rates may still meter diagnostic charges. No endpoint
address or model name selects a mode, and no large magic quota stands for unlimited.
The GUI and status must expose the choice. Existing goals keep their saved policy
and charges across configuration changes, answers, resumes and restart.

Goal limits gain a persisted mode with a versioned strict migration: older records
remain LIMITED with identical counts and outstanding reservations. Unlimited goals
have no remaining-call number (explicit null plus mode in STATE), while the ledger
retains one in-flight reservation and cumulative counters. Arithmetic exhaustion
fails explicitly rather than wrapping or granting a refund. Behavior task budgets,
Planner step bounds, failure-loop protection and manual holds are unchanged.

Hourly accounting retains exact reservations for the existing limited policy.
After proven submission/uncertainty is settled in UNLIMITED mode, old request IDs
are unnecessary: merge their counters into bounded minute buckets. Retain exact
live reservations for proven-unsent refunds. At most 61 buckets, 720 tracked NPC
identities and 720 exact entries may exist; normal scheduler concurrency is still
two workers and its queue is still 32. Tracking-capacity pressure is explicit
backpressure, not an unbounded allocation or a disguised call quota.

Switching policies retains both exact and aggregated spending. A bucket expires
one hour after its latest contained request, so enabling limited quotas after
unlimited operation may conservatively retain older spending for less than one
extra minute. Never undercount the rolling hour or refund submitted/uncertain
work. NPC cooldown remains based on its latest actual retained reservation, not
on bucket boundaries. Proven-unsent release removes only its exact live entry.

Validation must cover more than the old hourly/goal call caps, bounded history over
many rolling windows, configuration switches, exact unsent release, unknown sends,
counter overflow, old-record migration and separate-JVM recovery. Native tests
must still demonstrate bounded queue/concurrency, healthy Behavior without calls,
and unchanged authorization/freshness/admission. No unlimited preparation retries.
