# ADR 0100: Bounded durable goal memory

Date: 2026-09-20
Status: Accepted (CPU HTTP emulator scope)

Extend goal SavedData to v3 with immutable intent descriptions (at most eight),
user-defined place aliases (at most sixteen), and server-confirmed historical task
results (at most sixteen). Intent/alias UTF-8 accounting is capped at 4096 bytes;
results at 2048 bytes with oldest-result eviction. The existing 16 KiB record and
256-record server caps remain. Migrate v1/v2 with empty memory and unchanged task
identity, authority and charges. Preserve invalid saves read-only.

Names and coordinates are labels supplied by the current authorized player, not
observations. Editing labels while inference or an assigned goal task is active is
rejected, so a response cannot silently use an obsolete location. Edits increase
the goal revision and do not resume a held goal or reset budgets. New goals inherit
place aliases only; forget/dismiss removes memory, unload does not.

Only an exact bound task's authoritative terminal observation records a result;
a model summary never creates one. Memory participates in decision freshness.
Planner intent selection and sequential execution follow in the next slice.
No world facts, item NBT, credentials or executable operations are persisted here.

Required evidence: strict migration/corruption and bounds tests, actual command/
context projection, physical result recording and separate-JVM persistence.

Evidence: 509 unit tests, 833 frozen source/build hashes, clean/static/distribution,
registered client/server memory commands and two-JVM persistence PASS. Standalone:
96 LLM units and 105 matching source files. See LLM_MEMORY.md.
