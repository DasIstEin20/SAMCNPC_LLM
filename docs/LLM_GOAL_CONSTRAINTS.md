# Trusted goal constraints

`/samcnpc llm goal_bounded <npc> <document>` and `plan_bounded` accept a JSON
document containing `goal` text and a separate `constraints` object. The latter
is player-authorized input. The model cannot add permissions through its reply.
The shorter typed commands remain useful where Minecraft's chat packet limit
prevents submitting a complete document.

Version 2 retains the version 1 fields and adds mandatory nullable
`requiredReturnTo`. A non-null value requires that exact feet position as the
typed operation's return endpoint. Include it in `navigation` as well. Listing a
point in `navigation` alone only permits it; it does not request a return. The
anchor is not a return instruction.

For example, this contract permits preparing only the specified soil plane and
requires a physical return. It authorizes no crop production or resource gathering:

```json
{
  "goal": "Hoe this soil plane and return. Do not sow or harvest.",
  "constraints": {
    "version": 2,
    "operations": ["samcnpc:prepare_field"],
    "resourceIds": [],
    "dimensionId": "minecraft:overworld",
    "quantityMeaning": "NONE",
    "quantity": 0,
    "workBox": {
      "min": {"x": -37, "y": 62, "z": 80},
      "max": {"x": -35, "y": 62, "z": 82}
    },
    "exclusions": [],
    "sources": [],
    "destinations": [],
    "navigation": [{"x": -39.5, "y": 63, "z": 78.5}],
    "requiredReturnTo": {"x": -39.5, "y": 63, "z": 78.5},
    "allowAcquisition": false,
    "allowDestruction": true,
    "allowAuxiliaryBlockWork": true,
    "allowPlayers": false
  }
}
```

Both block-work flags are required for soil transformation. The family validator
still prohibits clearing blocks, planting or expanding the area. A missing or
different proposed return is rejected with
`INTENT_REQUIRED_RETURN_NOT_SATISFIED` before mutation. Unsupported combinations
also fail closed. Behavior remains responsible for executing an admitted operation.

Saved GoalStore version 7 preserves old BOUNDED_V1 records without adding new
permissions or mandatory endpoints. Plain `goal`/`plan` text does not automatically
produce trusted constraints; its meaning still depends on model comprehension.
See [ADR 0119](adr/0119-required-return-goal-constraint.md).
