# 0110 — Share repeated item facts before reducing optional context

Date: 2026-09-23. Status: accepted; implementation and validation in progress.

A native NPC with 36 carried enchanted swords, two real terminal failures and four
user aliases produced 40,837 STATE bytes and 86,134 complete HTTP bytes. Removing
all optional enchantments/recent events still left 27,181 STATE / 70,578 HTTP bytes,
above both limits. See c3-rich-native-baseline-2.log. The earlier fixture timeouts
were contact pickup racing the test driver, not production context defects.

STATE v5 may share identical item-detail objects in a bounded root `itemFacts` array.
Each inventory slot remains explicit with its original slot, item ID and count.
`itemFactsRef` is a zero-based reference to all remaining details of that item; it
does not infer facts from another item ID. Equipment uses the same dictionary.
Equality includes durability, enchantments, omission/unknown flags and every other
detail. Different observations never merge. References are flat, generated only
from trusted detached snapshots, and the dictionary is at most 36 plus the fixed
equipment slot count. The projection adds no parser or executable semantics.

Use four bounded stages: original 16 enchants/16 events; lossless item sharing with
the same details; sharing with 4 enchants/8 recent events; sharing with 0/0. Keep
failure history and all mandatory goal/task/authority/revision/receipt data. Recheck
the complete serialized request after each stage. A dictionary is used only when
the complete STATE shrinks. Oversized mandatory payloads still reject before HTTP.

Prompt v5 explains the flat observation references. JSON_SCHEMA stays the default.
Tests independently expand the references to compare all item facts, exercise all
36 actual slots and actual failed tasks, retain rejection coverage, then run the
real model with the resulting representation. No persistence or Behavior change.
