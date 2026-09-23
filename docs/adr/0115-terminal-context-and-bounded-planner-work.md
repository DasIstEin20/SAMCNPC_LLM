# 0115 — Active work, terminal history and additional bounded Planner operations

Status: accepted, 2026-09-23.

A retained terminal Behavior task is still necessary for compare-and-set admission
and resource accounting. Presenting it as the active task confuses a new goal with
previous work, particularly after an inventory transfer succeeded but its return
failed. Context v10 presents active work in `task` and the retained final snapshot
in `lastTerminalTask`. No journal, receipt or binding revision is discarded.
Admission continues to compare against the actual retained Behavior task.
Terminal history omits obsolete intent parameters, while preserving resource
receipts, phases, identifiers and revisions. Literal item mentions include source
spans and explicit untrusted provenance; they never become goal constraints.

An idle Planner cannot CONTINUE a nonexistent operation. The response schema and
local policy enforce this independently. WAIT event triggers carry null ticks;
only a bounded deadline carries an integer. These restrictions reach both backend
decoding and the existing local decoder. A model summary never advances a plan.

Planner policy revision 4 adds the already published Mining and Farm operations
to its existing six operation families. They retain ordinary Behavior execution,
physical resource accounting, time/attempt limits and terminal outcomes. A plan
still dispatches only its current operation, has at most eight steps, asks after
terminal failure, and requires user confirmation after its final completed step.
Combat, controls and amendments are not added to this Planner policy.
Its response schema limits the step list to the remaining execution budget and
preconditions to currently observed inventory. Fresh admission checks remain
necessary because resources can change while inference is running. A retrieval
postcondition such as retaining the items is not a separate executable step.

Retrieving named armor items is inventory supply, like tools or blocks. It does
not imply equipping. Prompt rules distinguish these intents and tell the model
to preserve each explicit item quantity and the body's feet position. These are
translation aids, not authorization: typed goal constraints and fresh admission
remain authoritative. Common intent instructions are shared explicitly between
JSON and SAM Expression; neither protocol derives them by matching prose markers.

The planner body projection exposes exact feet coordinates and their reference label.
It omits eye coordinates, which the high-level operation selector does not need.
Core still uses actual eyes for all physical visibility/reach checks; the observation
gateway and admission binding are unchanged. This avoids two competing body positions
without rewriting a candidate, guessing a supported height or changing task execution.

Validation evidence and model measurements stay in the project-local autonomy
directory. This ADR records the architectural choice, not a model-quality claim.
