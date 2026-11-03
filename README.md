# PermaPack

A Fabric Minecraft challenge for up to three players: **one shared inventory,
and every slot stays claimed by the first item type stored in it.**

Current prototype: **0.1.1**, for **Minecraft 26.2**, **Fabric Loader 0.20.1**,
**Fabric API 0.161.0+26.2**, and **Java 25**.

## How it plays

- All players share the same 36 inventory/hotbar slots and stack quantities.
- Using or crafting away an item leaves its slot permanently reserved.
- A reserved empty slot can refill with the same item type. Extra stacks can
  claim additional physical slots, including more slots for the same type.
- Hover an inventory slot and press a hotbar number, 1-9, to swap both items
  and their reservations. Normal stack sizes and item components are preserved.
- Armor and offhands are personal, but their item types still claim reservations.
- Dropping items and depositing into storage are blocked. Looting, crafting
  and workstation use are allowed. Bundles and filled portable storage are blocked.
- The default death rule keeps shared items and permanent reservations.
- There is **no HUD slot counter or bossbar**. Counts are available on demand.

## Install and start

Install the mod JAR and the matching Fabric API on every player's Fabric instance.
Build the JAR below, or use an attached GitHub release when one is available.
Essential hosting and Flashback are optional companion mods, obtained from
their own publishers. They are not bundled in this source repository.

1. Create a fresh Survival world with commands enabled.
2. Invite the other players through Essential, or connect to the same server.
3. Start with empty inventories, armor, offhands and crafting grids.
4. The host runs `/permapack start`.

Starting alone also works. Empty-inventory Survival players can automatically
join until the team has three members. World saves preserve membership,
shared items and reservations across restarts.

## Commands

The short alias is `/pp`. Start, join, death, reset and stop require host/operator
permissions. Status and slot inspection are available to players.

| Command | Effect |
| --- | --- |
| `/permapack start` | Start with 1-3 online players who have empty inventories. |
| `/permapack status` | Inspect claimed slots, settings and protected returns. |
| `/permapack slots` | List each reservation and its item ID. |
| `/permapack join PlayerName` | Add an empty-inventory player, maximum three. |
| `/permapack death keep` | Keep shared items and reservations on death, the default. |
| `/permapack death lose` | A death wipes shared items and online players' equipment; reservations remain. |
| `/permapack stop` | End the run and return one copy of the shared inventory to the host. |
| `/permapack reset` | Show the destructive reset explanation. |
| `/permapack reset confirm` | Delete the team's items/equipment and clear reservations. |

Crafting and closing leftovers have a protected return queue if space is
temporarily unavailable. Check `/permapack status` and use items to make room.
Stop refuses while protected returns cannot fit.

## Build and verify

Use a JDK 25 and the included Gradle wrapper. On Windows:

```powershell
.\gradlew.bat test runGameTest build
```

On other systems, use `./gradlew test runGameTest build`. Output is
`build/libs/permapack-0.1.1.jar`. The test mod is a separate source set and is
not shipped in the production JAR.

The existing suite covers permanent reservations, three connected server
players, concurrent cursor grabs, crafting, single-cell drag gestures,
shift-crafting, drops and storage, loot, exact `/give` counts, item conversions,
full-budget limits, persistence, reconnect, death/respawn and safe stop.

## Prototype limits

Dragging to distribute across bag slots is disabled. Normal clicks and hotbar
number swaps work; crafting-grid and workstation-input drag gestures work.
The prototype has been checked in a full Prism client with Essential,
Flashback and fullbright. A three-human Essential session and long Survival
runs still need group testing. Every workstation, conversion and extra-mod
interaction has not been exhaustively checked. Use a test world first.

Version 0.1.1 removes the slot bar; recordings made with an older version can
retain the bar that was already captured.

## License

PermaPack source is [MIT licensed](LICENSE). Minecraft, Fabric, Essential,
Flashback and other companion projects retain their own licenses.
