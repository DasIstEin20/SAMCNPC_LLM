# Bounded inference scheduling

The scheduler is an internal server-thread coordinator. It receives wake events and
captures a fresh authorized context through its host only when a request can start.
The player goal controller and durable recovery are the next milestone (L4).
No scheduler is created automatically on mod startup.

| Resource | Bound |
|---|---:|
| Active worker requests | 2 per server lifetime, 1 per NPC |
| Pending wake events | 32 NPCs; coalesced FIFO |
| Dispatch/completion work | At most 2 each per poll |
| NPC request spacing | 10 seconds |
| Reservations per rolling hour | 12 per NPC, 60 per server |
| Goal reservations | 24 attempts, 196608 input tokens, 24576 output tokens |
| Server rolling token reservations | 491520 input, 61440 output |
| Default per-request allocation | 8192 input, 1024 output |
| Transport retry / output repair | At most 1 of each per decision cycle |
| Circuit breaker | 3 transport failures, 60-second cooldown, 1 probe |

These are conservative reservations, not a promise that a model fits the request.
A reservation is charged before worker dispatch and is not implicitly refunded on
cancellation, errors or a missing usage field. Goal-budget rejection happens before
charging the shared server budget. Admission runs before the held attempt settles,
so the final permitted response still has its authorization budget.

Only detached values reach the worker: context, provider settings, token profile,
allocation and a bounded stable correction code. Encoding, schema generation, HTTP,
strict decoding and pure policy checks happen there. Fresh-world admission stays
on the server thread. Physical worker slots remain occupied until drained after
cancellation; late results cannot admit or generate another retry. Cancel/close
from start and completion callbacks is also honored.

FIFO wake entries retain position when coalesced. A cooling/busy NPC does not block
a ready NPC behind it. The controller must validate goal identity/revision before
dispatch and preserve the same goal budget across revisions. A half-open provider
probe temporarily defers other wakes instead of dropping them. The first 429 already
enforces bounded Retry-After globally; an older successful request cannot bypass it.
Authentication/configuration/incompatibility failures require a new endpoint lifetime.
The server controller must retain the rolling rate/resource gate across configuration
changes; reopening a provider must not replenish it.

Token metering is explicitly profile-bound. The default Unverified profile rejects
before contacting HTTP. A VerifiedByteLevel profile declares the exact configured
endpoint/model and evidence identifiers for backend, model, tokenizer and chat
template. Its conservative upper bound counts the complete serialized request bytes
plus a verified template reserve. It does not report bytes as measured tokens.
Reported usage above that bound invalidates compatibility. The CPU emulator exercises
this contract; no real model/backend tuple is verified yet. Changing endpoint/model
invalidates the declaration. Full 16-operation requests are about 47/52 KiB, so they
cannot be assumed to fit an 8192-token allocation. Mandatory state is never silently
dropped to make a request fit.

Optional cost rates are integer microcurrency units per million tokens. Input and
output charges round upward separately. Default rates and goal/server cost budgets
are zero for the local emulator; nonzero rates require explicit sufficient budgets.
The coordinator records reserved attempts and completed provider API invocations.
Those API invocations are not advertised as exact network request counts.

Transient coordinator state is not a persistence transaction. L4 must durably track
goal reservations/admission and recover ambiguity by observation or WAIT, never by
automatically repeating an uncertain assignment or control. Provider failures do
not cancel, pause or corrupt a deterministic Behavior task.

Validation: 62 LLM unit tests and 15 actual dedicated-server scheduler scenarios,
with 15 loopback HTTP requests; real tick progress during delayed HTTP, out-of-order
NPC completion, finite transport retry/JSON repair, Retry-After, profile/token/goal
budget rejection, reported usage violation, final-call admission and cancellation/
closure before dispatch, during HTTP and during completion. The rate clock is virtual
in the probe; the L7 one-hour soak and actual local model testing remain separate.
See ADR 0096 and the milestone evidence referenced by PROJECT_STATE.md.
