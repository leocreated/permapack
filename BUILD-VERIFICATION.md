# Build verification

Version 0.1.4 checked on 2026-11-05 with JDK 25 on Windows x64.

```text
gradlew.bat --no-daemon test runGameTest build
```

- Production build passed.
- All 17 unit tests passed with no failures, errors or skipped tests.
- All nine required Minecraft server GameTests passed: eight custom gameplay
  scenarios and Fabric's registration test.
- Equipment tests use three connected server players, each with their own full
  armor set and shield, with zero shared allocations. They cover personal gear
  at start/join, normal clicks, partial stacks, safe cursor returns, and atomic
  F/number-key/right-click swaps at the full allocation limit.
- Allocation-origin checks cover partial offhand cycling, interleaved players,
  merging into occupied offhands, resets, external cursors and closed processing
  screens, preserving unrelated historic reservations.
- Empty/water/lava bucket state changes reuse one family even at 36/36.
  Multiple occupied bucket stacks still require multiple allocations.
- Legacy-save tests cover bucket migration, worn armor and nonstackable offhand
  cleanup, reconnect idempotence, and save-codec round-trip.
- Both drop aliases clear the whole selected stack and its one allocation.
  Empty reservation markers can also be cleared. Tests verify exact real drops,
  item components, operator permission, silent ordinary-drop blocking, and
  immediate acceptance of a new item type into the freed allocation.
- Existing three-player shared inventory, crafting/remainders, conversions,
  split/merge limits, death/respawn, reconnect and safe-stop scenarios pass.
- A read-only Codex reviewer using the inherited selected model checked the
  transfer design and rechecked six raised findings after their fixes.
- A full Prism client with Essential 1.5.1, Flashback 0.43.6 and Gamma Utils
  3.1.3 loaded an existing disposable challenge world. Native Shift-clicks
  equipped a shield and iron helmet, removed their bag reservations, and
  retained the world's original four claims with zero protected returns.
  The disposable world saved successfully and the test client was closed.

Artifact: `permapack-0.1.4.jar`

SHA256: `a8050ae31781208921a5f120f97f1c0e73e05c5cc4707d6a9944a92453afaa25`

Migration retains ambiguous historical reservations for stackable offhand
items. Select an unwanted empty marker and run `/pp drop` to clear it.
A fresh three-human Essential session, long Survival runs and exhaustive
workstation/extra-mod interactions remain open. Flashback replay playback
was not repeated for this update.
