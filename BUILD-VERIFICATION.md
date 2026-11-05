# Build verification

Version 0.1.3 checked on 2026-11-04 with JDK 25 on Windows x64.

```text
gradlew.bat --no-daemon test runGameTest build
```

- Production build passed.
- All 13 unit tests passed, with no failures, errors or skipped tests.
- All six required Minecraft server GameTests passed: five custom gameplay
  scenarios and Fabric's registration test.
- The new movement scenario uses three connected server players and real
  container-click packets. It checks ordinary moves/swaps, full-budget moves,
  split/merge accounting, left/right drag distribution, shift transfers,
  cursor/pickup capacity, simultaneous gestures, storage protection and
  save-codec round-trip without changing the save format.
- Existing crafting, recipe remainders, conversions, reconnect, team limits,
  death/respawn and safe-stop scenarios still pass.
- The new drop scenario uses three connected server players. It records
  outgoing packets to verify Q, Ctrl+Q, inventory throws and outside clicks
  send no action-bar or overlay-chat warning. Ordinary drops remain blocked.
- The command dispatcher enforces operator permission. Both command names
  drop the correct single-item or whole-stack quantity as real item entities,
  preserve components, update the shared inventory and retain allocations.
  Another player can pick up an item into its original allocation. Empty
  reservation markers and inactive challenges cannot use the command.
- The drop exception ends immediately after the command; Q is still blocked.

Artifact: `permapack-0.1.3.jar`

SHA256: `4ed91116db57fc50d118563e515f3674ddf6ce647eb609535bff724a824ee1aa`

Earlier 0.1.2 interactive checks used a disposable world in a Prism instance with Essential
1.5.1, Flashback 0.43.6 and Gamma Utils 3.1.3. They confirmed that dirt moves out
of hotbar slot 1, a sword can occupy that slot, a left drag distributes 64 dirt
as three stacks of 21 plus one on the cursor, and closing returns that one
exactly once. Merging restores 64 dirt without refunding the three dirt
allocations. A sword can occupy the visible position of an empty dirt
reservation; its marker moves aside. Status remains four allocations with
zero protected returns. There is no HUD slot counter.
Saving and reloading preserved that layout, the 64 dirt, the sword and both
empty reservations. Loading the existing 0.1.1 world also retained its claims.

A real three-human Essential session, long Survival runs and exhaustive
workstation/extra-mod interactions remain open. Interactive Prism tests and
Flashback replay playback were not repeated for the 0.1.3 drop update.
