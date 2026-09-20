# ADR 0097: Translator goals and conservative restart recovery

Date: 2026-09-20
Status: Accepted

Add bounded player commands and one LLM-owned SavedData store for at most 256 NPC
goals, each at most 16 KiB encoded NBT. The initial Translator accepts one operation
at a time. It can ask one clarification, wait or report a known task outcome.
It does not infer completion from model summary and never renews a goal budget
when the player answers a question or resumes supervision.

A goal records stable actor/NPC/goal identities, revision, bounded original text
and bounded cumulative clarification/current question, status, manual hold, conservative resource reservation,
and the exact task identity/revisions returned by Behavior. It stores no world
snapshot, credentials, HTTP body, arbitrary code or transcript. Unknown/corrupt
store versions remain read-only and preserve their source data for recovery.

Reserve and mark dirty before inference. Mark admission uncertain before invoking
the existing public Behavior gateway. SavedData dirty marks are not a WAL or an
atomic transaction with Behavior TaskStore. Any restored queued/in-flight/dispatching
goal requires explicit user review before new inference; no automatic assignment
retry. Retain resource charges and settle a restored held request conservatively.
A known executing task may be observed by exact identity without repeating mutation.

Only the current connected actor may supervise within the published authority/range
checks. Manual pause/cancel/replacement/revision changes impose a durable hold;
inference cannot resume that task. Explicit user resume re-observes current state
and preserves the goal budget. Behavior remains autonomous when LLM is offline.

Tests must cover bounded/strict persistence, permissions/range/text limits, queued
and in-flight restart recovery, physical delivery through published typed operations,
clarification, cancel/manual control, endpoint failure and missing/full destinations.
Model fixtures are deterministic CPU HTTP scripts, not proof of real model quality.

The user-facing profile declaration lives in server-owned COMMON configuration.
It requires the exact endpoint/model and backend/tokenizer/template evidence before
inference, and preserves existing rolling server reservations on config changes.
It does not certify any real model automatically. Translator excludes model-driven
amendments/controls; explicit player controls continue through public APIs.

Evidence: 491 canonical units (80 LLM), eight physical dedicated/client cases each,
11 client authority checks, 16-family scripted contract corpus, and two-JVM actual
save/load with injected lost receipt all passed. Clean build, source/distribution
guards and 809 frozen file hashes passed. No real-model quality or arbitrary disk
crash atomicity is claimed. See LLM_TRANSLATOR.md and TRANSLATOR_VALIDATION.json.
