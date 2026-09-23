# 0114 — SAM Expression v1 is a bounded model output format

Date: 2026-09-23. Status: accepted.

Keep JSON_SCHEMA as the default and keep the existing JSON_OBJECT compatibility
mode. Add an explicitly experimental SAM_EXPRESSION_V1 wire mode. It requests plain
assistant text without response_format; this is the UNCONSTRAINED experimental arm.
There is no claimed backend grammar enforcement and no automatic format fallback.
HTTP limits, whole-request budgeting, preparation/refund accounting and retries
apply unchanged. The internal request still carries the existing closed schema.

The language is data, not Python. A hand-written Kotlin tokenizer/parser accepts
exactly one top-level constructor. No interpreter, reflection, code generation,
imports, variables, operators, attribute access, loops, indexing or interpolation
exist. Parsing has no I/O and accepts no world/server/entity callbacks. A constructor
name selects a published data shape; it never invokes a method named by the model.

## Grammar and bounds

```text
response = call EOF
call     = identifier "(" [arguments [","]] ")"
arguments = argument {"," argument}
argument = value | identifier "=" value
value    = call | string | number | "True" | "False" | "None" | list | tuple
list     = "[" [value {"," value} [","]] "]"
tuple    = "(" number "," number "," number [","] ")"
```

Whitespace is ASCII space/tab/CR/LF. Identifiers are ASCII and bounded; allowed names
and argument names come only from the closed protocol and published catalog. Strings
use single or double quotes, JSON escapes plus escaped single quotes, and valid
Unicode scalar values. No comments, code fences, extraction from prose, adjacent
string concatenation or second expression. Numbers use a bounded decimal form with
optional minus and fractional part; no exponents, leading plus, hex, NaN or infinity.
Integer fields require integer lexical tokens, not rounded floats. Coordinates retain
their signs and axes. Decimal conversion happens only after checking token length.

Hard parser maxima: 16,384 UTF-8 bytes; 8,192 tokens; 4,096 AST nodes; nesting 16;
128 elements per list; 64 arguments per constructor; 64 identifier characters;
1,024 decoded UTF-16 string units; 32 number characters. Numbers are finite and
bounded to +/-30,000,000 before catalog-specific bounds. Typed field limits can
only tighten these. Duplicate/unknown/missing arguments and positional arguments
after keyword arguments are rejected. The adapter rejects duplicate aliases too.

## Closed mapping

Top-level constructors:

| Expression | Existing decision |
| --- | --- |
| `continue_task()` | CONTINUE |
| `ask_user("question")` | ASK_USER, existing 256-character limit |
| `wait(trigger="TASK_TERMINAL")` | WAIT, omitted ticks means null |
| `wait(trigger="DEADLINE", ticks=40)` | Existing bounded DEADLINE |
| `pause()` / `resume()` / `cancel()` | Existing controls |
| `assign(operation, plan=plan(...))` | ASSIGN; plan required only for Planner |
| `amend(change)` | AMEND |

Operation names are the suffixes of the published samcnpc IDs, with unchanged
definition versions: navigate1, deliver2, transport1, machine1, fish1, explore1,
attack2, defend1, attack_area1, patrol1, inventory_work1, mine1, farm1, plant_trees1,
food1, lumberjack2. All sixteen families remain representable. Arguments map
one-to-one from published fields to snake_case. Required fields remain required;
omitted optional fields keep existing Behavior defaults. Neither current position
nor dimension is silently inserted into an operation. Explicit anchor/standing/etc.
requirements therefore also apply to expression examples.

Changes use `change_quantity`, `change_recipients`, `change_sources`,
`change_extend_time`, `change_replace`, `change_tactics`, `change_reaction`,
`change_logistics`; fields and defaults are the same public change catalog.
REPLACE contains an ordinary operation constructor, then receives all existing
replacement/objective restrictions. These names are data, never execution hooks.

Referenced records use snake_case catalog shape names (e.g. containers, supply,
need, mining_work). Anonymous records use `record(...)`. Alternatives select one
of their explicit referenced constructors. Lists stay lists. Single-valued `kind`
discriminators may be omitted only when the selected named shape fixes that value.
`position(x,y,z)` and `block(x,y,z)` additionally accept positional coordinates;
`pos` aliases position and `chest` aliases block. A three-number tuple is accepted
only where a position/block is expected. `chest` is coordinates, never a world read
or proof of an actual chest. `box(min=(...), max=(...))` and
`area(bounds=box(...), exclusions=[...])` keep catalog semantics; no widened bounds.

Planner `plan(steps=[...], required_items=[record(item_id="...", minimum=1)],
minimum_empty_slots=0)` maps exactly to PlanProposal v2. No omitted preconditions
or invented future steps. Translator/Supervisor reject a supplied plan. Step text
remains non-executable. Summary is internally empty; it is not needed to execute.

## Adapter, binding and validation

The tokenizer builds a small AST. A separate catalog-driven adapter checks expected
data shapes and constructs the same canonical decision document for DecisionDecoder.
OperationDocumentApi remains the semantic decoder, including cross-field relations,
defaults and unsupported variants. Expression parsing cannot extend the catalog.
No second implementation of Behavior semantics is introduced.

The adapter binds schemaVersion/contextId from the ORIGINAL immutable inference
input, never the latest goal or latest NPC snapshot. Before parsing, the worker
checks response.requestId against input.requestId. Cross-request substitutions,
delayed/cancelled results, stale revisions and restart recovery retain the normal
DecisionPolicy, GoalConstraints, freshness, authority and admission checks. The
missing model echo is not a freshness exemption. Persisted decisions/goals remain
typed/JSON/NBT as before; the new language ends at the model-output boundary.

Prompt identity and task policy are shared with JSON. Only the format instructions
and visible contract differ. The contract is generated from the same public catalog
and current schema/policy restrictions, showing required arguments, bounds, choices,
defaults and relations. No unavailable operations are offered as an escape hatch.

## Evidence required

Differential tests cover all families and changes, defaults, coordinate types,
Planner preconditions and typed equality with JSON. Adversarial/fuzz tests cover
all limits, duplicate fields, unknown constructors, executable-looking syntax,
Unicode confusables, malformed surrogate pairs and trailing content. Native tests
exercise transport, old-context replay, two-NPC request substitution, cancellation,
restart and real physical work through unchanged admission.

Run the frozen 92-case corpus, identical legal snapshots/oracles, three alternating
repetitions per arm, at most one repair, and a separate physical subset. Report
every attempt, independent semantic dimensions, actual usage/latency, preparation
failure and gameplay. Preserve failed campaigns. A shorter request alone is not
a reason to change the default. JSON_SCHEMA stays default unless documented evidence
meets the previously frozen safety, correctness and quality gates.
