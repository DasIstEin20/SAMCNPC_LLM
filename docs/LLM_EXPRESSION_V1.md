# Experimental SAM Expression v1

This format is experimental. JSON_SCHEMA remains the recommended default.

`responseFormat = "SAM_EXPRESSION_V1"` selects a model-output experiment in the LLM
configuration screen or server TOML. `JSON_SCHEMA` remains the default. The expression
mode requests unconstrained text; it does not claim backend grammar enforcement.
No automatic fallback changes the selected format. Existing JSON_OBJECT remains
available. Configuration changes use the normal cancellation/reconfiguration path.

The model proposes one Behavior operation, change, control, question or wait. This
is a custom data syntax parsed by Kotlin. It is never passed to Python, a shell,
Minecraft commands, reflection or an interpreter. Behavior still selects tools,
paths, recovery and individual actions; Core still performs physical mechanics.

Examples (one expression per response):

```text
assign(lumberjack(
    dimension_id="minecraft:overworld",
    wood=["samcnpc:oak"], quantity=16,
    area=area(bounds=box(min=(100,64,-24), max=(110,78,-14))),
    destination=chest(120,64,-30)))
```

```text
assign(transport(
    dimension_id="minecraft:overworld", item_id="minecraft:cobblestone", quantity=32,
    sources=containers(positions=[chest(10,64,20)]),
    destinations=containers(positions=[chest(30,64,50)]), anchor=(10,64,20)))
```

```text
assign(inventory_work(
    dimension_id="minecraft:overworld", anchor=(0,64,-30),
    work=supply(needs=[need(item_id="minecraft:cobblestone", minimum=32, target=32)],
                sources=containers(positions=[chest(-3,64,-30)]))))
```

The last example fills to32 total when current stock is below32. To obtain32 more
when12 are carried, target is44. Explicit server goal constraints independently
check that meaning; an uncontracted natural-language goal does not acquire a new
semantic guarantee merely by selecting this format.

Other top-level forms are `continue_task()`, `ask_user("...")`,
`wait(trigger="TASK_TERMINAL")`, `wait(trigger="USER_UPDATE")`,
`wait(trigger="DEADLINE", ticks=40)`, `pause()`, `resume()`, `cancel()` and
`amend(change_extend_time(ticks=40))`. Availability depends on the current policy
and task. Planner assignments additionally require
`plan=plan(steps=["..."], required_items=[], minimum_empty_slots=0)`.

All16 published operation families and eight amendment kinds use the existing
typed decoders. The visible contract is generated from the same published catalog.
Field names use snake_case. Required arguments stay required; optional omissions
retain Behavior defaults. Lists use brackets; booleans are True/False; null is None.
Position/block values can be tuples or named constructors. `chest` is only a block
coordinate alias, not proof that a chest exists. Coordinates in block fields must
be integer tokens. No expressions compute coordinates or quantities.

The response omits schemaVersion, contextId, summary and unused null envelope
fields. The server supplies identity from the original immutable inference input
and verifies the provider response request ID, then applies unchanged policy,
freshness, permissions and admission. A delayed answer cannot bind itself to a
newer goal. Canonical operation documents and saved goals do not change format.

Strict limits and grammar are in [ADR0114](adr/0114-sam-expression-model-wire.md).
Malformed input, extra arguments, extra expressions, code fences and executable
syntax are rejected rather than extracted or repaired locally. Normal bounded
model repair can request a new answer; uncertain world effects are never replayed.

Experimental status is intentional. Unit parsing/transport tests establish the
boundary, not comprehension. Only the frozen A/B model and physical reports can
establish relative quality; shorter wire size alone does not justify promotion.
