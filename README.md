# PermaPack

A Fabric Minecraft challenge for up to three players: **one shared inventory,
36 permanent item allocations, and freely movable stacks.**

Current prototype: **0.1.4**, for **Minecraft 26.2**, **Fabric Loader 0.20.1**,
**Fabric API 0.161.0+26.2**, and **Java 25**.

## How it plays

- All players share the same 36 inventory/hotbar slots and stack quantities.
- Storing a stack permanently claims an allocation for its item type. Using,
  placing or crafting away the item does not free that allocation.
- Items can occupy any of the 36 positions. Move dirt out of hotbar slot 1
  and put a sword there; reservations follow the layout without extra cost.
- Normal clicks, hotbar number swaps, shift transfers and left/right drag
  distribution work. Normal stack sizes and item components are preserved.
- Splitting into extra stored stacks can claim extra allocations. Merging them
  does not refund allocations. Empty reservations can refill with their own
  item type; their visible markers move aside when you rearrange other items.
- Each player has their own armor and offhand. Equipped items cost **zero**
  shared allocations. Moving a whole bag stack into personal equipment frees
  its allocation; moving equipment back into the bag needs capacity again.
- Empty, water and lava buckets share one interchangeable allocation family.
  Filling or emptying one bucket adds no allocation. Separate occupied bucket
  stacks still need separate allocations.
- Ordinary dropping is silently blocked. Hosts/operators can explicitly drop
  the whole selected stack with `/pp drop` or `/pp drop stack`. Both commands
  also free that stack's allocation. Selecting an empty reservation marker and
  running either command clears that reservation too.
- Depositing into storage is blocked. Looting, crafting
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
3. Start with empty bags/hotbars and crafting grids. Personal armor and offhands
   can stay equipped.
4. The host runs `/permapack start`.

Starting alone also works. Empty-bag Survival players can automatically
join until the team has three members. World saves preserve membership,
shared items and reservations across restarts.

## Commands

The short alias is `/pp`. Start, join, drop, death, reset and stop require host/operator
permissions. Status and slot inspection are available to players.

| Command | Effect |
| --- | --- |
| `/permapack start` | Start with 1-3 online players who have empty bags; personal gear is allowed. |
| `/permapack status` | Inspect claimed slots, settings and protected returns. |
| `/permapack slots` | List each reservation and its item ID. |
| `/permapack join PlayerName` | Add an empty-bag player, maximum three; personal gear is allowed. |
| `/permapack drop` | Drop the selected whole stack and free its allocation, or clear a selected empty reservation. |
| `/permapack drop stack` | Same whole-stack correction as `/permapack drop`. |
| `/permapack death keep` | Keep shared items and reservations on death, the default. |
| `/permapack death lose` | A death wipes shared items and online players' equipment; reservations remain. |
| `/permapack stop` | End the run and return one copy of the shared inventory to the host. |
| `/permapack reset` | Show the destructive reset explanation. |
| `/permapack reset confirm` | Delete the team's items/equipment and clear reservations. |

Crafting and closing leftovers have a protected return queue if space is
temporarily unavailable. Check `/permapack status` and use items to make room.
Stop refuses while protected returns cannot fit.

## Updating an existing run

Replace the previous PermaPack JAR with 0.1.4 and restart Minecraft. Open the
existing world normally; no reset is needed. Old bucket-state claims merge,
without discarding actual bucket stacks. Old armor and nonstackable offhand
reservations are corrected once as each team member reconnects.
Historical reservations for stackable offhand items are retained conservatively;
select an unwanted empty marker and use `/pp drop` to clear it.

## Build and verify

Use a JDK 25 and the included Gradle wrapper. On Windows:

```powershell
.\gradlew.bat test runGameTest build
```

On other systems, use `./gradlew test runGameTest build`. Output is
`build/libs/permapack-0.1.4.jar`. The test mod is a separate source set and is
not shipped in the production JAR.

The suite has 17 unit tests and nine required Minecraft GameTests. It covers
three connected server players, real click packets, free positioning at the
full allocation limit, ordinary swaps, split/merge budgets, left/right drags,
pickups while another player holds a stack, concurrent gestures, crafting,
drops and storage, loot, item conversions, persistence, reconnect,
death/respawn and safe stop. Drop checks verify that ordinary attempts send no
overlay, command permission is enforced, real drops preserve quantities and
components, and correction commands free capacity for a different item type.
Equipment checks cover three complete armor sets and shields, partial transfers,
full-budget F/number-key/right-click swaps, safe cursor returns, unrelated
historic claims, bucket conversions and legacy-save migration.
See [BUILD-VERIFICATION.md](BUILD-VERIFICATION.md).

## Prototype limits

Live Prism checks covered version 0.1.4 with Essential, Flashback and fullbright,
including loading an existing world and equipping a shield and helmet without
leaving shared claims. Server packet and item-entity tests cover the other rules.
The existing save format and permanent allocation counts are retained.
A three-human Essential session and long Survival runs
still need group testing. Every workstation, conversion and extra-mod
interaction has not been exhaustively checked. Use a test world first.

Version 0.1.1 removes the slot bar; recordings made with an older version can
retain the bar that was already captured.

## License

PermaPack source is [MIT licensed](LICENSE). Minecraft, Fabric, Essential,
Flashback and other companion projects retain their own licenses.
