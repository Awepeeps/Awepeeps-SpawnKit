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
`A` = anvil, `t` = crafting table, `C` = chest, `L` = lodestone, `F` = furnace, `B` = bed foot,
`b` = bed head, `.` = walkable interior.

```
        x=-3  -2  -1   0   1   2   3
      +-------------------------------+
 z=-3 |  #   #   #   #   #   #   #    |
 z=-2 |  #   .   A   t   C   L   #    |
 z=-1 |  #   .   *   *   *   .   #    |
 z= 0 |  #   B   *   *   *   F   #    |
 z= 1 |  #   b   *   *   *   .   #    |
 z= 2 |  #   .   .   .   .   .   #    |
 z= 3 |  #   #   #   D   #   #   #    |
      +-------------------------------+
```

The 3x3 in the middle (`*`, plus the block you stand on) is the protected zone. The ring of `.`
cells between it and the walls is ordinary house interior; the furnishings lie in that ring and
never inside the protected zone. The anvil, crafting table, chest and lodestone run along the back
(north) wall, the furnace is against the east wall and the bed against the west wall, matching the
owner's arrangement. The door sits in the south wall; the column at `(0, 0..1, 3)` is the walkable
exit, and the cell at `(0, 0..1, 2)` is kept clear so the route from any core-top cell to the door
is always two-high walkable air.

### Front elevation (looking north at the south wall, from outside)

`x` is horizontal, `y` vertical. `W` = white wall banner, `R` = red wall banner, `T` = wall torch,
`D` = door, `S` = plank doorstep, `#` = cobblestone wall, `~` = a step built down to the terrain
when the ground outside is lower.

```
        x=-3  -2  -1   0   1   2   3
   y= 3 |  R   R   R   R   R   R   R  |  oak-plank roof
   y= 2 |  #   #   #   #   #   #   #  |  wall
   y= 1 |  #   W   T   D   T   R   #  |  banners and torches at head height
   y= 0 |  #   #   #   D   #   #   #  |  door lower half
   y=-1 |  .   .   .   S   .   .   .  |  plank doorstep
   y=-2 |  .   .   .   ~   .   .   .  |  one step per block of drop (up to 3)
```

The white banner is on the wall to the left of the door and the red one to the right, as seen from
outside facing the door; a wall torch hangs on each side of the doorway. All four hang on the outer
face of the south wall, facing south, and none sits in the doorway column or on the doorstep path.

### Section view (at `x = 0`, looking west)

`y` is vertical, `z` horizontal. `K` = protected core block, `*` = protected arrival air,
`F` = house floor, `#` = wall, `R` = roof, `D` = door, `t` = crafting table, `S` = plank doorstep,
`f` = foundation fill.

```
        z=-3  -2  -1   0   1   2   3   4
   y= 3 |  R   R   R   R   R   R   R   .  |  roofline (destructible)
   y= 2 |  #   .   *   *   *   .   #   .  |
   y= 1 |  #   .   *   *   *   .   D   .  |  door upper half
   y= 0 |  #   t   *   *   *   .   D   .  |  crafting table / floor level (feet)
   y=-1 |  F   F   K   K   K   F   F   S  |  core top / house floor / plank doorstep
   y=-2 |  f   f   K   K   K   f   f   .  |
   y=-3 |  .   .   K   K   K   .   .   .  |  core bottom
```

Interior wall torches sit higher, at `y = 2`, on the back wall (`x = ±1`, `z = -2`) and the side
walls (`x = ±2`, `z = 0`), so nothing hangs directly over a container.

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
| `spawn-kit.house.furnishings.anvil` | `true` | Place the anvil on the back wall. |
| `spawn-kit.house.furnishings.furnace` | `true` | Place the (unlit) furnace against the east wall. |
| `spawn-kit.house.furnishings.lodestone` | `true` | Place the lodestone in the north-east ring cell (for testing waypoint plugins). |
| `spawn-kit.house.furnishings.crafting-table` | `true` | Place the crafting table on the back wall. |
| `spawn-kit.house.furnishings.exterior-banners` | `true` | Place the two wall banners on the south wall's outer face. |
| `spawn-kit.house.furnishings.exterior-torches` | `true` | Place the two wall torches beside the doorway. |
| `spawn-kit.house.furnishings.banners.left` | `WHITE` | Dye colour of the banner to the left of the door (west, seen from outside). |
| `spawn-kit.house.furnishings.banners.right` | `RED` | Dye colour of the banner to the right of the door (east). |
| `spawn-kit.house.furnishings.extras` | `[]` | Optional single-block utilities placed in free ring cells. |

The house is always at least 3 blocks of interior height, symmetric about spawn, one block wider
than the arrival space per `margin` step, with a closed wall/roof shell except for the door, and
lit by wall torches so hostile mobs cannot spawn inside while it is intact. Nothing is ever placed
inside the protected zone, on the doorway column or in the walkable route to the door; the anvil
rests on the floor, the chest has air above it, and every container keeps a reachable face. The
plank doorstep is flush with the house floor; when the ground outside is lower, up to three plank
steps are built down to it, and when it is higher a walkable approach is cleared (only replaceable
blocks are removed).

#### `extras`

`extras` accepts only a fixed allow-list of harmless single-block utilities:

```
SMITHING_TABLE, GRINDSTONE, STONECUTTER, CARTOGRAPHY_TABLE, LOOM, BARREL, ENDER_CHEST,
FLETCHING_TABLE, COMPOSTER, LECTERN, JUKEBOX, BREWING_STAND, RESPAWN_ANCHOR
```

Chests and shulker boxes are excluded so an extra can never be confused with the issued supply
chest (`BARREL` and `ENDER_CHEST` are deliberately allowed); gravity blocks other than the anvil,
redstone, fluids and blocks needing special placement are excluded too. An unknown or disallowed
name is skipped with a single warning and never an error. Extras fill the free ring cells; if there
are more than fit, as many as fit are placed and one line is logged.

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
    furnishings:
      anvil: true
      furnace: true
      lodestone: true
      crafting-table: true
      exterior-banners: true
      exterior-torches: true
      banners:
        left: WHITE
        right: RED
      extras: []
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
- clears only natural vegetation, trees and snow in the footprint, and never crops on farmland or
  anything a player made (those refuse the site),
- fills a foundation so nothing floats, and raises the floor above any water surface,
- sets the world spawn so its Y is the arrival floor (the top of the core).

### What counts as natural terrain

Classification prefers the vanilla block tags (`#leaves`, `#logs`, `#saplings`, `#flowers`, `#dirt`,
the overworld base-stone tag, `#sand`, `#snow`, `#ice`, `#moss_blocks`, `#replaceable_by_trees`,
`#cave_vines`) and falls back to explicit material sets and name patterns, so it keeps working for
blocks a future Minecraft version adds. Everything world generation places on or near the surface of
an unmodified overworld chunk is accepted:

- **Clearable when it is vegetation, a tree or ground cover** inside the build volume: every leaf
  and log variant (including pale oak, mangrove and cherry, and their unstripped forms), saplings,
  flowers and tall flowers, grass and fern variants, dry grass, dead bushes, mushrooms, pumpkins,
  melons, sugar cane, bamboo, cactus, berry bushes, vines (`#cave_vines` included), glow lichen,
  moss and pale-moss carpet, leaf litter, bushes, pink petals, wildflowers, firefly bushes, hanging
  roots, dripleaves, cocoa and a **generated bee nest** (a generated bee nest goes with its tree like
  any other attachment; a crafted `BEEHIVE` is refused).
- **Acceptable as foundation when it is solid ground**: dirt and its variants, grass, podzol,
  mycelium, mud, clay, sand, gravel, snow and ice, stone and its overworld base variants, deepslate,
  ores, sandstone, terracotta, moss and amethyst.

A block of any other type is a **refusal**: containers other than a generated bee nest (chests,
barrels, furnaces, hoppers, shulker boxes, ...), beds, doors, signs, rails, torches and redstone
components, crafted or processed blocks (planks, stairs, slabs, fences, glass, wool, bricks,
**stripped** logs, crops on farmland), spawners, and blocks left by a generated structure (which are
not natural terrain and so are refused). When in doubt between "natural" and "player-made" for a
block type generation never places on the surface, the plugin refuses the site.

### Trees and the build volume

The plugin clears the whole build volume: every vegetation and tree cell in the footprint and door
columns, from the world floor to the top of its column, plus the entire connected tree when a trunk
stands inside the footprint — its logs, leaves, vines, cocoa and any generated bee nest — out to a
bounded radius of `4` blocks beyond the footprint and `12` blocks above the roof. Trees entirely
outside the footprint are left alone. The walk is capped at `512` blocks; if the cap is hit the build
still completes and the leftover is logged. This guarantees no floating canopy is left hanging over
or beside the house.

### Refusal and retry behaviour

Building is refused and deferred with a clear reason when the footprint intersects non-natural
blocks, containers or a generated structure, when there is lava beneath, when the column is void,
when the terrain is too uneven, or when there is not enough headroom. The protected core and the
foundation the plugin filled beneath it are ignored by that check, so a second pass — the automatic
house, or `/spawnkit build confirm` — is not refused for blocks the plugin placed itself.

A refusal caused by **generated terrain** (everything above except "spawn chunks were not loaded")
is deterministic: the message names the stage, the number of offending blocks and the distinct block
types with an example position each, e.g.

```
Spawn zone build deferred: 3 block(s) in the footprint are not natural terrain: LEAF_LITTER at
(x, y, z), BUSH at (x, y, z).
```

It is logged once in full and the silent timer retry stops, telling the operator to clear the site and
run the build command while the kit window is active (`force` is accepted only on a fresh zone, never
on an additive upgrade). A **transient** refusal (chunks not loaded yet) keeps retrying with increasing
backoff and logs only when the reason changes. While a zone is deferred the world spawn and the
vanilla `respawn_radius` remain in use, and a one-time warning says that new players are not being
placed on a spawn pad yet.

### Forcing a blocked site

`/spawnkit build confirm force` builds even when the footprint contains non-natural blocks, replacing
them. It still refuses, and never destroys, a container that holds items or any other block entity
that carries contents. `/spawnkit build confirm force items` is the explicit second opt-in that also
replaces those, with the obvious risk that their contents are lost. `supplies` combines with either.
The plain `/spawnkit build confirm` never replaces non-natural blocks.

`force` overrides **only** the occupancy check. A site that is also too uneven, lacks headroom, is
over lava, is void, or crosses the world border is still refused with that deterministic reason, so a
forced build always has a valid floor to build on. `force`/`items` describe replacing existing
blocks on a fresh site; they are rejected with a clear message on an additive upgrade (a version-2
world, or one already at the current layout version), which never overwrites an existing house or
chest.

> Trees are cleared (trunks, leaves and their attachments) because a spawn inside a forest is common,
> and the owner accepts that the trees at spawn are removed. A structure built purely out of
> unprocessed logs is indistinguishable from a tree and would be cleared; anything else man-made
> (planks, cobblestone, containers, doors, signs, rails, redstone) refuses the site.

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
- A world built with the **1.2.x layout (layout version 2)** is **not** rebuilt automatically,
  because players may have changed the house. On enable the plugin logs once that an upgrade is
  available. Running `/spawnkit build confirm` in that world performs an **additive upgrade**: it
  adds each new furnishing only where the target cell is air or a replaceable natural block and its
  support exists, never replaces or removes an existing block, never touches an existing chest's
  contents, and skips (and logs) any piece whose cell is occupied. The record is moved to layout
  version 3 afterwards, so a second run is a no-op apart from retrying pieces that were skipped.
  `supplies` keeps its meaning: it adds another set of configured items to an existing chest.
- A world already at the **current layout version** is reported as already current by
  `/spawnkit build confirm`; nothing structural is changed. The command still retries any additive
  piece that was skipped earlier and reports what it added and skipped. While the house pass has not
  recorded completion (the zone is built but the house has not run yet, or it was deferred), the
  command leaves the world to the automatic house build instead of running the additive pass over
  the bare zone; it reports that nothing was changed and the house pass resumes on its next cycle
  while the kit window is active.
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
   layout without handing out new supplies. On a 1.2.x world this is the **additive upgrade** that
   adds the anvil, furnace, lodestone, banners, exterior torches and doorstep (and leaves anything
   the players changed in place); the log lists every piece it added and every piece it skipped. If
   the old 1.1.x shelter is still standing where the new footprint goes, clear it first: the build
   refuses a footprint that intersects non-natural blocks and says so in the log.
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

The reproducible jar is written to `build/libs/awepeeps-spawn-kit-1.3.3.jar`.

## Behaviour changes in 1.3.0

- **New default house layout.** The ring between the protected core and the walls is furnished: the
  anvil, crafting table and chest along the back (north) wall, an (unlit) furnace against the east
  wall, the bed against the west wall and a lodestone in the north-east corner. Interior wall
  torches now hang at `y = 2` on the back and side walls.
- **Exterior decorations.** Wall torches at head height on each side of the doorway and white/red
  wall banners to the left and right of the door (seen from outside), on the outer face of the south
  wall. The lodestone and the banners are real blocks so waypoint plugins can be tested against
  them.
- **Entry steps.** The plank doorstep is kept; when the ground outside the door is lower, up to
  three plank steps are built down to it, and when it is higher a walkable approach is cleared.
  Non-natural blocks are never destroyed for a step.
- **Configurable furnishings.** A new `spawn-kit.house.furnishings` section toggles each piece and
  takes optional `extras` from a fixed allow-list, plus configurable banner colours. Existing
  configs without the section get the new defaults.
- **Layout version 3 and additive upgrades.** A fresh world gets the full new layout automatically.
  A 1.2.x world (layout version 2) is not rebuilt automatically; `/spawnkit build confirm` upgrades
  it additively and logs what it added and skipped. 1.1.x worlds keep their manual-migration
  behaviour.
- The protected zone, the issuance-record-before-build guarantee, asynchronous chunk loading with
  tickets released on every path, bounded main-thread batches, and the "house is never repaired or
  protected" rule are all unchanged.

## Behaviour changes in 1.3.1

- **Fixed: a fresh world whose spawn is in a forest built nothing.** Blocks that world generation
  places in ordinary forests (leaf litter and bushes, and a generated bee nest on a trunk) were
  classified as "non-natural blocks, containers or a structure", so the zone build refused the site
  and — after one log line — retried silently forever, leaving no protected core, no house, no chest
  and the vanilla `respawn_radius`. Classification now prefers the vanilla block tags with explicit
  fallbacks, so generated surface terrain is accepted (see
  [What counts as natural terrain](#what-counts-as-natural-terrain)).
- **New: bounded tree clearing.** A tree whose trunk stands inside the footprint is removed as a
  whole (logs, leaves, vines, cocoa, generated bee nest) within a bounded radius and height, so no
  floating canopy is left over the house; trees entirely outside the footprint are untouched.
- **New: actionable deferral messages and a stopped timer.** A deterministic refusal names the
  offending block types with example positions, is logged once in full, and stops retrying; only
  transient causes keep retrying. While a zone is deferred, a one-time warning says arrivals are not
  on a spawn pad yet.
- **New: `force` (and `force items`) on `/spawnkit build confirm`** to build on a genuinely blocked
  site without ever silently destroying an item-holding container.
- **Fixed: `/spawnkit build confirm` on a world already at the current layout** reports that nothing
  was changed instead of refusing the site for the house the plugin built.
- The protected zone, the pre-build issuance record, ticket release on every path, bounded batches,
  the never-repair-the-house rule and the additive upgrade are unchanged.

## Behaviour changes in 1.3.2

- **Fixed: a forced build on a site that was also uneven or without headroom aborted.** `force`
  cleared the occupancy defect and the build then continued with no floor level, which threw instead
  of building or refusing. `force` now bypasses occupancy only while every shape, height and
  world-bound check still holds; otherwise the site is refused with that explicit deterministic
  reason and no block changes are planned. Without `force` the message is unchanged.
- **Fixed: `force`/`items` were silently ignored on an additive upgrade.** They are now rejected with
  a clear message before any block change or supply, because an additive upgrade never overwrites an
  existing house or chest. They remain valid for a fresh blocked site.
- **Fixed: an operator build could race the automatic house pass.** `/spawnkit build confirm` on a
  zone-built world whose house pass had not recorded completion ran the additive pass over the bare
  zone and marked the world complete, so the house shell was never built. The command now leaves such
  a world to its automatic house build and reports that nothing was changed, so the two writers never
  overlap and the house is not lost.

## Behaviour changes in 1.3.3

- **Fixed: exposed ground cover on the arrival cells was not cleared by the initial build.** The
  clearing scan is bounded by the top of each column, and that bound was read from the server's
  `MOTION_BLOCKING` heightmap. Leaf litter, bushes and short grass do not block motion, so cover
  sitting one block above the grass (for example on flat grass at `y = 66` when the grass is `y = 65`)
  was above the bound and never scheduled for clearing. The periodic core repair then removed it
  later, once the chunks loaded, which is the "repaired N protected spawn block(s)" line an owner saw
  once. The bound now counts every non-air block (`WORLD_SURFACE`), so the initial zone and house
  passes clear the exposed cover themselves. The floor selection and the ground lookup
  (`MOTION_BLOCKING_NO_LEAVES`) are unchanged, and the fix still clears nothing but approved
  vegetation: man-made blocks and item-holding containers are preserved, and the clearing stays inside
  the same bounded build columns.
- **Tests: the heightmap mismatch can no longer hide behind the test double.** The in-memory reader
  used by the existing tests already counted every non-air block, so it could not catch the
  production reader asking for the wrong heightmap. New tests drive the real `WorldBlockReader`
  against a small fake world and compose the two real passes (zone, then house) to assert the
  protected core and an all-air arrival space with no repair, plus negative controls for a container
  and for a non-vegetation block above the cleared cover. The test suite grows from 75 to 85 tests.
- **Wording: the deferral and house-await messages are scoped.** The deterministic-refusal advice now
  says to clear the site and run the build command while the kit window is active, and notes that
  `force` is only accepted on a fresh zone, never on an additive upgrade (which rejects it). The
  "zone built, house not yet" reply now says the automatic house pass runs on its next cycle while the
  kit window is active.

This is a source-only change: it has not been run on a live server yet.

## Repository docs

- [`AGENTS.md`](AGENTS.md) — repository-wide guidance for agents working on this plugin.
- [`.agents/skills/spawnkit-spawn-layout-change/SKILL.md`](.agents/skills/spawnkit-spawn-layout-change/SKILL.md)
  — the build-and-test workflow for changing the spawn layout.
