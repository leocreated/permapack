# Build verification

Version 0.1.2 checked on 2026-11-03 with JDK 25 on Windows x64.

```text
gradlew.bat --no-daemon test runGameTest build
```

- Production build passed.
- All 13 unit tests passed, with no failures, errors or skipped tests.
- All five required Minecraft server GameTests passed: four custom gameplay
  scenarios and Fabric's registration test.
- The new movement scenario uses three connected server players and real
  container-click packets. It checks ordinary moves/swaps, full-budget moves,
  split/merge accounting, left/right drag distribution, shift transfers,
  cursor/pickup capacity, simultaneous gestures, storage protection and
  save-codec round-trip without changing the save format.
- Existing crafting, recipe remainders, conversions, reconnect, team limits,
  death/respawn and safe-stop scenarios still pass.

Artifact: `permapack-0.1.2.jar`

SHA256: `4c7dda1136e9f92ced96ad993c327c5bce6f3d608d587a4071fafedf4956b12b`

Interactive checks used a disposable world in a Prism instance with Essential
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
workstation/extra-mod interactions remain open. Flashback recording/playback
was checked on the prior version; replay playback was not repeated for 0.1.2.
