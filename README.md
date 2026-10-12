# Awepeeps Spawn Kit

Configurable Paper plugin for setting up a protected spawn area and an optional one-time starter
house with a chest. The plugin owns the spawn protection itself, independent of the server's
`spawn-protection` setting.

Two independent parts, each toggled separately:

- **`spawn-zone`** — the protected spawn area, spawn control, and the house footprint. Available
  whenever `spawn-zone.enabled` is true, independent of the date window and of the house/chest.
- **`spawn-kit`** — the test-only house interior, starter chest and BlueMap marker, gated by
  `enabled` and the date window. Nothing is issued by default.

## The protected zone

**Protected zone = the solid 3x3x`core-depth` core plus the 3x3x`clear-height` clear column above
it.** The core's top surface is the floor players arrive on; the clear column is the air they stand
and walk in when they arrive. Everything inside that box is protected; the house around it is not.

Players arrive standing on top of the core, inside the arrival space.

### Protection rules

Inside the protected zone, blocks cannot be broken, replaced, moved, burned or blown up — by
anyone or anything — with a single exception: an **operator in creative mode** may edit it. All
other players (survival, adventure, spectator, and non-op creative) are blocked, and environmental
effects are always blocked. This is enforced by the plugin, so it does not depend on whether the
server has operators and does not depend on `server.properties`.

The **arrival space** (the 3x3 air column above the core) is protected against placement: nobody
can place blocks, fluids, or push blocks into it, so arriving players can never be suffocated or
boxed in.

The protection covers, in addition to ordinary breaking and placing:

- every half of a **multi-block placement** (beds, doors, tall plants), so a bed whose head would
  land in the arrival space cannot be placed from just outside it;
- **fluids**, from a bucket, from flowing water or lava, from a dispenser facing into the zone, or
  from a sponge;
- **pistons**, extend and retract, including the blocks they move and the cells they move them into;
- **explosions** (only the protected blocks are removed from the blast list, so the destructible
  house around them still blows up);
- **entities**: boats, minecarts, armour stands, end crystals, item frames and paintings would
  obstruct or trap arrivals, so they cannot be placed in the zone either;
- environmental change: burning, fire spreading and lightning, fire/lava/water ignition,
  snow and ice forming and melting, frost walker, bone meal and structure growth into the zone,
  endermen taking or placing blocks, and falling blocks landing in the arrival space.

The plugin deliberately does **not** cancel `BlockPhysicsEvent`. Cancelling physics for live blocks
is a common cause of client-side ghost blocks, and every way a block can appear in the zone is
already covered by a targeted event. A block that somehow still appears in the arrival space (an
operator command, for example) is removed by the periodic check below.

A creative operator's edits **to the core itself** are deliberately reverted by that periodic check
(see [Core repair](#core-repair)); the exemption exists so an operator can work in and around the
zone, not to redesign the core permanently. Change the core through configuration and a rebuild.

The **house** — walls, roof, floor, door, beds, chests, crafting table, torches and everything
inside — is ordinary, fully destructible world. Players may break, take, rearrange or extend it.
The plugin does not protect it and does not rebuild it.

### Floor plan (defaults: `core-depth: 3`, `clear-height: 3`, `margin: 1`)

Looking straight down at the arrival level (`y = 0`, the plane the player's feet occupy). `x`
increases east, `z` increases south. `*` = protected arrival air, `#` = wall, `D` = door,
`B` = bed, `C` = chest, `t` = crafting table, `.` = walkable interior.

```
        x=-3  -2  -1   0   1   2   3
      +-------------------------------+
 z=-3 |  #   #   #   #   #   #   #    |
 z=-2 |  #   .   .   t   .   .   #    |
 z=-1 |  #   .   *   *   *   .   #    |
 z= 0 |  #   .   *   *   *   C   #    |
 z= 1 |  #   .   *   *   *   .   #    |
 z= 2 |  #   .   .   .   B   B   #    |
 z= 3 |  #   #   #   D   #   #   #    |
      +-------------------------------+
```

The 3x3 in the middle (`*`, plus the block you stand on) is the protected zone. The ring of `.`
cells between it and the walls is ordinary house interior. The door sits in the south wall; the
column at `(0, 0..1, 3)` is the walkable exit.

### Section view (at `x = 0`, looking west)

`y` is vertical, `z` horizontal. `K` = protected core block, `*` = protected arrival air,
`F` = house floor, `#` = wall, `R` = roof, `D` = door, `t` = crafting table, `f` = foundation fill.

```
        z=-3  -2  -1   0   1   2   3
   y= 3 |  R   R   R   R   R   R   R  |  roofline (destructible)
   y= 2 |  #   .   *   *   *   .   #  |
   y= 1 |  #   .   *   *   *   .   D  |  door upper half
   y= 0 |  #   t   *   *   *   .   D  |  floor level (feet)
   y=-1 |  F   F   K   K   K   F   F  |  core top layer / house floor
   y=-2 |  f   f   K   K   K   f   f  |
   y=-3 |  .   .   K   K   K   .   .  |  core bottom
```

The protected zone spans `y = -core-depth .. clear-height-1` (default `y = -3 .. 2`) and
`x, z = -1 .. 1`. The world spawn is set one block above the top core layer, on the arrival floor.

### Dimensions and keys the owner can change

Everything is config-driven; no code change is needed.

| Key | Default | Meaning |
| --- | --- | --- |
| `spawn-zone.enabled` | `true` | Build and enforce the protected spawn area, and manage the world spawn. |
| `spawn-zone.core-depth` | `3` | Depth of the solid 3x3 core (1–8). |
| `spawn-zone.clear-height` | `3` | Height of the protected clear column above the core (2–8). |
| `spawn-zone.margin` | `1` | Walkable/furnishing blocks between the arrival space and the walls (1–2). |
| `spawn-zone.beds` | `1` | Beds placed in the house (1–4). |
| `spawn-zone.chests` | `1` | Chests placed in the house (1–4). |
| `spawn-zone.manage-spawn-radius` | `true` | Pin the `respawn_radius` game rule to `0` so joins land on the core. |

The house is always at least 3 blocks of interior height, symmetric about spawn, one block wider
than the arrival space per `margin` step, with a closed wall/roof shell except for the door, and
lit by wall torches so hostile mobs cannot spawn inside while it is intact.

## Server-provided configuration

The operator edits `plugins/AwepeepsSpawnKit/config.yml`. Defaults issue nothing:

```yaml
spawn-zone:
  enabled: true
  core-depth: 3
  clear-height: 3
  margin: 1
  beds: 1
  chests: 1
  manage-spawn-radius: true
spawn-kit:
  enabled: false
  starts-on: ""
  expires-on: ""
  timezone: ""
  spawn-marker:
    enabled: false
    name: "World Spawn"
  house:
    enabled: false
    chest:
      enabled: false
      items: []
```

The date window is inclusive in the configured timezone. The plugin activates at local midnight on
`starts-on` and stops at local midnight after `expires-on`. After changing configuration, restart
the server (or run `/spawnkit build confirm`). Materials are Bukkit `Material` names; amounts must
be positive integers; all configured items must fit within 27 chest slots. Numbers outside the
documented range are clamped with a startup warning. The `spawn-marker` toggle publishes a named
BlueMap POI at the world spawn when BlueMap is installed (no lodestone is placed, so the protected
core stays uniform); if BlueMap is not installed the plugin logs one warning and retries once a
minute instead of failing. See
[`examples/consumer-config.yml`](examples/consumer-config.yml) for a neutral example to replace per
server.

## Spawn scatter and spawn location

Vanilla spreads joining and respawning players randomly around the world spawn. In Java Edition
26.3, the game rule is `respawn_radius` (renamed from `spawnRadius` in 1.21.11 / 25w44a), default
`10`, minimum `0` — "the number of blocks outward from the world spawn coordinates that a player
spawns in when first joining a server or when dying without a personal spawnpoint" (Minecraft Wiki,
*World spawn* and *Game rule*). The default search area is 21×21 and the player is placed on the
highest solid non-waterlogged block, which would put arrivals outside a 3x3 pad or on the roof.

To keep arrivals on the core the plugin:

1. sets the world spawn to the centre of the core top,
2. pins `respawn_radius` to `0` while `spawn-zone.manage-spawn-radius` is true, recording the
   original value and restoring it when the feature is disabled or the plugin is disabled, and
3. handles the join and respawn events, overriding only spawns that are neither a bed nor a
   respawn-anchor spawn.

Joins are handled through Paper's `AsyncPlayerSpawnLocationEvent`, **not** the deprecated
`org.spigotmc.event.player.PlayerSpawnLocationEvent` (deprecated since 1.21.9, marked for removal).
The deprecated event is documented as firing during the configuration phase, before a player entity
exists, so reading `Player#hasPlayedBefore()` or `Player#getBedSpawnLocation()` from it is
unreliable. The replacement event reports `isNewPlayer()` — the server's own first-ever-join flag —
and already defaults to the player data location for returning players. Consequently:

- **first ever join** → placed on the core top;
- **returning player** → keeps the position stored in their player data (their logout position),
  whether or not they have a bed. The 1.2.0 code used the deprecated event and redirected every
  login, dragging bed-less players back to spawn; that is fixed in 1.2.1;
- **death without a bed or a respawn anchor** → placed on the core top;
- **bed or respawn-anchor respawn, including returning from the End through the exit portal** → left
  exactly as vanilla resolved it, and a missing or obstructed bed falls back to the core, which is
  where it lands anyway because the world spawn is the core top.

If an operator moves the world spawn while the plugin is running, the plugin restores it to the
recorded zone location within one cycle (five seconds) and keeps using the recorded location for
protection and spawn control: the zone stays where it was built. An explicit
`/spawnkit build confirm` builds at the current world spawn, which the plugin keeps at the recorded
zone location, so a rebuild lands on the existing core instead of a second one.

## Core repair

The record for a world states where the zone is (`spawn-x/y/z`). While it says the zone is
complete, the plugin re-seats any core block that is not the core material and clears anything that
is not air from the arrival column, once per tick cycle (five seconds). The check:

- never runs before the record says the zone is complete, and never for a build that is still in
  progress;
- is skipped entirely while the zone's chunks are not loaded, so it can never pull chunks back in
  on the main thread;
- only logs when the number of repaired blocks changes, so a repeated edit cannot spam the log.

This is also the backstop for the few changes that no event reports (an operator's
`/setblock`, or a server feature that writes a block without firing a Bukkit event).

## Chest contents on re-issue

Re-issuing the layout with `/spawnkit build confirm` (without `supplies`) never replaces the supply
chest block, so whatever players have stored in it survives a rebuild. Only
`/spawnkit build confirm supplies` adds another set of configured items, and it adds them to the
existing chest.

## Placement rules

The house is built around the world spawn, not searched for. The plugin:

- chooses the floor level from the terrain under the whole house footprint (levelling height
  variations of up to four blocks with a cobblestone foundation),
- clears only whitelisted natural vegetation, trees and snow in the footprint, and never crops on
  farmland (those refuse the site),
- fills a foundation so nothing floats, and raises the floor above any water surface,
- sets the world spawn so its Y is the arrival floor (the top of the core).

Building is refused and deferred with a clear reason when the footprint intersects player-placed
blocks, containers or a generated structure, when there is lava beneath, when the column is void,
when the terrain is too uneven, or when there is not enough headroom. The protected core and the
foundation the plugin filled beneath it are ignored by that check, so a second pass — the
automatic house, or `/spawnkit build confirm` — is not refused for blocks the plugin placed itself.
Non-natural blocks are never
destroyed, and that includes player-planted crops: farmland and crops are not "natural vegetation",
so a farm in the footprint refuses the site rather than being cleared. Deferrals retry with
increasing backoff and log only when the reason changes.

> Trees are cleared (trunks and leaves) because a spawn inside a forest is common. A structure built
> purely out of unprocessed logs is therefore indistinguishable from a tree and would be cleared;
> anything else man-made (planks, cobblestone, containers, doors, signs, rails, redstone) refuses the
> site. This is unchanged from 1.1.x and is called out here because it is the one case where a
> player-built block can be removed.

## Building, idempotency and upgrades

- A **fresh world** (no record) automatically builds the protected zone, and the house/chest when
  the kit is active, at the current world spawn. After a nightly reset with the record cleared the
  new layout is built automatically at the new spawn.
- A **damaged or missing protected core** is repaired periodically and logged (see
  [Core repair](#core-repair)); the destructible house is never repaired or rebuilt.
- A **rebuild** (the automatic house pass, `/spawnkit build confirm`, or `supplies`) ignores the
  core and the foundation the plugin placed itself, so it is not refused for its own blocks.
- A world that already holds a **1.1.x record** is not migrated automatically: the plugin logs what
  to do and leaves the world alone. Run `/spawnkit build confirm` in that world to place the new
  layout. Add the `supplies` argument only if a new chest should be handed out:
  - `/spawnkit build confirm` — new layout; an existing chest keeps its contents.
  - `/spawnkit build confirm supplies` — new layout and another set of configured items.
- A world that is mid-build (an interrupted reservation) refuses to build again until the record is
  reviewed, exactly as before. A build that this instance is *still running* is never reported as an
  interruption.
- `/spawnkit build confirm` refuses to build while `spawn-zone.enabled` is false, and the unknown
  third argument is rejected instead of silently building without supplies.

The issuance record is stored per world UUID in `plugins/AwepeepsSpawnKit/issued-kits.yml` and is
persisted before any block in the world changes. A fresh world reset must delete that file after the
world directories are removed, as before.

## Configuration errors

Out-of-range or non-numeric dimensions are clamped to the documented range and reported at startup
instead of disabling the plugin, because disabling it would also switch off spawn protection and
spawn control. A config file that still contains the removed `spawn-kit.search-radius` key, or no
`spawn-zone` section at all, loads normally: unknown keys are ignored and missing keys fall back to
the bundled defaults. Invalid chest items are reported and the chest is left empty, again instead of
disabling the plugin.

## What the deployer must change

1. **Set `spawn-protection=0` in `server.properties`.** Vanilla spawn protection only blocks
   placing and breaking for non-operators, only in the Overworld, is bypassed by operators, and is
   disabled silently when the server has no operators (Minecraft Wiki, *Spawn protection*). A
   non-zero value would make the whole square around spawn — including the destructible house —
   unbreakable and unusable for ordinary players, and would fight the plugin. The plugin logs a
   warning at startup when this is greater than `0`.
2. **Nothing else in `server.properties`** is required. The plugin manages `respawn_radius` itself
   and restores the previous value when `spawn-zone.manage-spawn-radius` is set to false.
3. **Existing test world:** run `/spawnkit build confirm` (console or an operator) to place the new
   layout without handing out new supplies. If the old 1.1.x shelter is still standing where the new
   footprint goes, clear it first: the build refuses a footprint that intersects non-natural blocks
   and says so in the log.
4. **Fresh world resets:** keep clearing `plugins/AwepeepsSpawnKit/issued-kits.yml` after deleting
   the world directories.

> Vanilla spawn-protection had no documented effect on opening chests, beds, doors or crafting
> tables in the sources reviewed; only block placing/breaking are documented as blocked. This is
> marked **unverified** because it could not be confirmed against a live 26.3 server.

## Behaviour changes in 1.2.1

- **Fixed: every login moved a bed-less player to world spawn.** Joins now use Paper's
  `AsyncPlayerSpawnLocationEvent` and its `isNewPlayer()` flag, so only a first ever join is placed
  on the core; returning players keep their logout position.
- **Fixed: a bed or door could be placed with one half inside the arrival space.**
  `BlockMultiPlaceEvent` now checks every placed half, not only the primary block.
- **Fixed: entities could be placed in the arrival space.** Boats, minecarts, armour stands, end
  crystals, item frames and paintings are now refused there.
- **Fixed: a dispenser facing into the zone could still place its fluid or block.** The target block
  is now derived from the dispenser's facing and refused.
- **Fixed: crops on farmland could be cleared by a build.** Player-planted crops (wheat, carrots,
  potatoes, beetroots, pumpkin and melon stems) are no longer treated as natural vegetation, so a
  farm refuses the site instead of being silently destroyed.
- **Fixed: re-issuing the layout replaced the supply chest block**, wiping whatever was stored in it.
- **Fixed: a lucky/unlucky game-rule write could lose the original `respawn_radius`.** The rule is now
  pinned to `0` only after the original value is durably recorded, so a failed write leaves the rule
  alone instead of making `0` permanent.
- **Fixed: an in-progress build was reported as an interrupted one**, telling the operator to review
  a record that was perfectly healthy.
- **Fixed: the periodic core check could synchronously load unloaded chunks** every cycle; it is now
  skipped while the zone's chunks are not loaded, and repeated repairs no longer spam the log.
- **Fixed: the BlueMap marker task could throw `NoClassDefFoundError`** when the marker was enabled
  without BlueMap installed.
- **Fixed: chunk tickets could be left behind** when a build was interrupted by a plugin disable;
  they are now tracked and released on disable.
- **Changed: out-of-range or non-numeric config values are clamped with a warning** instead of
  disabling the plugin (and with it spawn protection). Invalid chest items now leave the chest empty
  with a warning instead of disabling the plugin.
- **Changed: `BlockPhysicsEvent` is no longer cancelled.** It was the only untargeted cancellation
  and a known source of client-side ghost blocks; every way a block can enter the zone is covered by
  a specific event, and the periodic check is the backstop.
- **Changed: `spawn-zone.enabled: false` now also drops the recorded protection region and spawn
  location** at runtime, and `/spawnkit build confirm` refuses to build while the zone is disabled.
- `Plugin#getDescription()` (deprecated) replaced with `Plugin#getPluginMeta()`.

## Behaviour changes in 1.2.0

- The plugin now owns spawn protection through listeners instead of relying on
  `server.properties`. It logs a startup warning when `spawn-protection` is greater than `0`.
- The world spawn is moved to the top of the protected core and `respawn_radius` is pinned to `0`
  (restored when disabled).
- The old "search for a safe natural site near spawn" behaviour is gone; the layout is built around
  the world spawn and refused/deferred if that terrain is unsuitable. The `spawn-kit.search-radius`
  key is removed.
- The shelter is replaced by the core layout above; the marker is now a BlueMap POI only (no
  lodestone), so the protected core stays uniform.
- New config sections `spawn-zone` (with `core-depth`, `clear-height`, `margin`, `beds`, `chests`,
  `manage-spawn-radius`) and the `/spawnkit build confirm [supplies]` command.

## Build and tests

Requires JDK 25. Run:

```sh
bash ./gradlew --no-daemon clean build
```

The reproducible jar is written to `build/libs/awepeeps-spawn-kit-1.2.1.jar`.
