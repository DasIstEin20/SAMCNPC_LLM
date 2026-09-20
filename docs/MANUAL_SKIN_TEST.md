# Manual summoner skin test

Status: **MANUAL_PENDING, nonblocking** by the user decision of 2026-09-20.
The test does not block autonomous implementation or release preparation. It is
not marked passed by the automated signed-profile fixtures.

Use the matching Minecraft 1.20.1 / Forge 47.4.21 / Java 17 mod set, an online-mode
server and two genuinely signed-in Minecraft accounts with different skins,
including one slim model. The LLM provider can remain disabled.

1. Each player summons an NPC with `/samcnpc summon SkinA` or
   `/samcnpc summon SkinB`.
2. On both clients compare each NPC with its summoner: distinct texture, correct
   classic/slim arm width, hat, jacket, sleeves and trouser layers. Check equipped
   armor/items as well. Record versions and screenshots with the test result.
3. Run `/samcnpc skin refresh <npc>` for each player's own NPC. Verify an ordinary
   player cannot refresh the other summoner's NPC.
4. Reconnect both players, then save, stop and restart the server. Check identity,
   skin correspondence and layers again.
5. Record any unavailable skin/fallback behavior and refresh recovery. A resolved
   texture for a public profile fixture does not prove two authenticated sessions.

Attach the observations to the project acceptance record. Keep an untested step
pending; a screenshot of the default skin alone is not a successful summoner-skin
comparison. Automated fixture coverage remains separate from this manual result.
