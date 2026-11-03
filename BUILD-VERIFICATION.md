# Build verification

Publication snapshot checked on 2026-11-03 with JDK 25 on Windows x64.

```text
gradlew.bat --no-daemon test runGameTest build
```

- Production build passed.
- 8 unit tests passed, with no failures, errors or skipped tests.
- All 4 required Minecraft server GameTests passed, including the three custom
  inventory, conversion/team-limit and crafting-remainder scenarios.
- Production classes contain no bossbar creation or bossbar-packet references.
- The production JAR matches the previously verified 0.1.1 artifact.

Artifact: `permapack-0.1.1.jar`

SHA256: `e5cbee1d8320ac6c6c05ab47b7f2c01aeb0ffc9a15abc3a485e52d41b13862db`

Earlier live-client checks covered native crafting gestures, exact `/give`
counts, Q-drop refusal, save/restart persistence, fullbright at midnight and
Flashback save/playback in a full Prism instance. Those interactive checks
were not rerun for source publication. A real three-human Essential session,
long Survival runs and exhaustive extra-mod interaction testing remain open.
