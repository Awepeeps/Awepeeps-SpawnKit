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

**Protected zone = the solid 3x3x3 core plus the 3x3 clear column above it.** The core's top
surface is the floor players arrive on; the clear column is the air they stand and walk in when
they arrive. Everything inside that box is protected; the house around it is not.

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
be positive integers; all configured items must fit within 27 chest slots. The `spawn-marker`
toggle publishes a named BlueMap POI at the world spawn when BlueMap is installed (no lodestone is
placed, so the protected core stays uniform). See [`examples/consumer-config.yml`](examples/consumer-config.yml)
for a neutral example to replace per server.

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
3. handles the player spawn-location and respawn events, overriding only spawns that are not a
   bed or respawn-anchor spawn. Bed and respawn-anchor respawns are never overridden.

## Placement rules

The house is built around the world spawn, not searched for. The plugin:

- chooses the floor level from the terrain under the whole house footprint (levelling height
  variations of up to four blocks with a cobblestone foundation),
- clears only whitelisted natural vegetation, trees and snow in the footprint,
- fills a foundation so nothing floats, and raises the floor above any water surface,
- sets the world spawn so its Y is the arrival floor (the top of the core).

Building is refused and deferred with a clear reason when the footprint intersects player-placed
blocks, containers or a generated structure, when there is lava beneath, when the column is void,
when the terrain is too uneven, or when there is not enough headroom. Non-natural blocks are never
destroyed. Deferrals retry with increasing backoff and log only when the reason changes.

## Building, idempotency and upgrades

- A **fresh world** (no record) automatically builds the protected zone, and the house/chest when
  the kit is active, at the current world spawn. After a nightly reset with the record cleared the
  new layout is built automatically at the new spawn.
- A **damaged or missing protected core** is repaired on enable and logged; the destructible house
  is never repaired or rebuilt.
- A world that already holds a **1.1.x record** is not migrated automatically: the plugin logs what
  to do and leaves the world alone. Run `/spawnkit build confirm` in that world to place the new
  layout. Add the `supplies` argument only if a new chest should be handed out:
  - `/spawnkit build confirm` — new layout, chests placed empty.
  - `/spawnkit build confirm supplies` — new layout and a fresh chest of configured items.
- A world that is mid-build (an interrupted reservation) refuses to build again until the record is
  reviewed, exactly as before.

The issuance record is stored per world UUID in `plugins/AwepeepsSpawnKit/issued-kits.yml` and is
persisted before any block in the world changes. A fresh world reset must delete that file after the
world directories are removed, as before.

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
   layout without handing out new supplies.
4. **Fresh world resets:** keep clearing `plugins/AwepeepsSpawnKit/issued-kits.yml` after deleting
   the world directories.

> Vanilla spawn-protection had no documented effect on opening chests, beds, doors or crafting
> tables in the sources reviewed; only block placing/breaking are documented as blocked. This is
> marked **unverified** because it could not be confirmed against a live 26.3 server.

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

The reproducible jar is written to `build/libs/awepeeps-spawn-kit-1.2.0.jar`.
