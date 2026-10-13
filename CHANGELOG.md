# Changelog

## 1.3.3

Fix for ground cover left on the arrival cells by the initial build. This is a source-only change: it
has not been run on a live server yet.

- **Fixed: exposed ground cover on the arrival cells was not cleared by the initial build.** The
  clearing scan clears "clearable vegetation" up to the top of each column, but the production reader
  read the top from the server's `MOTION_BLOCKING` heightmap. Leaf litter, bushes and short grass do
  not block motion, so a cell of cover one block above the grass (for example on flat grass at
  `y = 66` with the grass at `y = 65`) lay above the bound and was never scheduled for clearing; the
  periodic core repair removed it later, once the chunks loaded. The reader now uses `WORLD_SURFACE`
  (the highest non-air block) for that bound, so the initial zone and house passes clear the exposed
  cover themselves and no self-heal is needed. The floor selection and the ground lookup
  (`MOTION_BLOCKING_NO_LEAVES`) are unchanged, and nothing but approved vegetation is ever cleared:
  man-made blocks and item-holding containers are still preserved.
- **Tests: the production heightmap choice is pinned, so the mismatch cannot ship unnoticed again.**
  The existing in-memory test reader already counted every non-air block and so hid the production
  divergence. New tests exercise the real `WorldBlockReader` through a small fake world, compose the
  two real passes (zone then house) and assert an intact protected core and an all-air arrival space
  with no repair, and add negative controls (an item-holding container; a non-vegetation block above
  the cleared cover) and a bounds check. Test suite grows from 75 to 85 tests.
- **Wording: deferral and house-await messages scoped.** The deterministic-refusal advice now tells
  the operator to clear the site and run the build command while the kit window is active, and notes
  that `force` is only accepted on a fresh zone, not on an additive upgrade (which rejects it). The
  "zone built, house not yet" reply now says the automatic house pass runs on its next cycle while the
  kit window is active.

## 1.3.2

Review fixes for 1.3.1. This is a source-only change: it has not been run on a live server yet.

- **Fixed: `force` on a site that is both occupied and uneven (or without headroom) aborted the
  build.** The occupancy check was ordered ahead of the shape checks, so `force` cleared the
  occupancy defect and then the build continued with no floor level, throwing
  (`floorY + 1` unboxed a null) instead of building or refusing. `force` now overrides occupancy
  only while every shape, height and world-bound check still holds; a site that is also uneven or
  lacks headroom is refused with that explicit deterministic defect (and plans no block changes).
  Without `force`, the original "not natural terrain" message is unchanged.
- **Fixed: `force`/`items` were silently ignored on an additive upgrade.** A world offered an
  additive upgrade (layout version 2, or already at the current version) now rejects `force` and
  `items` with a clear message *before* any reservation, block change or supply, because the
  additive upgrade never overwrites an existing house or chest. `force` remains available for a
  genuinely blocked fresh site.
- **Fixed: an operator build could race the automatic house pass.** `/spawnkit build confirm` on a
  world whose protected zone was built but whose house had not recorded completion ran the additive
  pass over the bare zone and then marked the world complete, leaving the wall/floor/roof shell
  unbuilt. The command now leaves that world to its automatic house build (which the command
  un-defers and which holds the durable reservation) and reports that nothing was changed, so the
  two never overlap and the house is not lost.
- Test suite grows from 64 to 75 tests: forced-build refusal on occupied+uneven and
  occupied+no-headroom sites (with and without `items`), the bounded tree-clear horizon and the
  `512`-block cap, a container-preservation zero-write default build, the additive-upgrade flag
  refusal, the house-pass/operator build decision, and the command's argument validation.

## 1.3.1

Fixes a fresh-world defect that could leave a brand-new world with **no protected core and no
house at all**.

- **Fixed: a fresh world whose spawn is in a forest built nothing.** The zone scan classified
  several blocks that world generation places in ordinary forests as "not natural terrain, a
  container or a structure", so the zone build refused the site. On the reported world the offending
  blocks were `LEAF_LITTER` (31 cells) and `BUSH` (2 cells) — ground cover added in recent Minecraft
  versions that the old hand-written material lists missed — and, on other forest worlds, anywhere a
  generated `BEE_NEST` sat on a trunk. The classifier now prefers the vanilla block tags (`#leaves`,
  `#logs`, `#saplings`, `#flowers`, `#dirt`, the overworld base-stone tag, `#sand`, `#snow`, `#ice`,
  `#moss_blocks`, `#replaceable_by_trees`, `#cave_vines`) and falls back to explicit sets and name
  patterns, so it keeps working for blocks a future version adds. Leaf litter, bushes, tall flowers,
  dry grass, every tree variant (logs, leaves, vines, cocoa and a generated bee nest) and the other
  surface ground cover are now clearable natural terrain; player-made and structure blocks are still
  refused (a **crafted** `BEEHIVE` is refused, a generated `BEE_NEST` is cleared with its tree).
- **New: bounded tree clearing that leaves no floating canopy.** When a tree trunk stands inside the
  footprint, its connected logs, leaves, vines, cocoa and generated bee nest are removed out to a
  bounded radius (`4` blocks beyond the footprint) and height (`12` blocks above the roof), capped at
  `512` blocks (the build completes and logs any leftover if the cap is hit). Trees entirely outside
  the footprint are untouched.
- **New: actionable deferral diagnostics.** The message now names the offending block types with an
  example position each, e.g.
  `Spawn zone build deferred: 3 block(s) in the footprint are not natural terrain: LEAF_LITTER at
  (x, y, z), BUSH at (x, y, z).` A refusal caused by generated terrain is logged once in full and the
  silent timer retry stops, telling the operator to clear the site or run the build command;
  transient refusals (chunks not loaded yet) still retry. While a zone is deferred, a one-time
  warning now says the world spawn and the vanilla `respawn_radius` are still in use and new players
  are not being placed on a spawn pad.
- **New: `/spawnkit build confirm force [items] [supplies]`** for a genuinely blocked site. `force`
  builds even when the footprint contains non-natural blocks, replacing them, but still never
  destroys a container that holds items or any other block entity that carries contents; `force
  items` is the explicit second opt-in that replaces those too. The plain command never does this.
- **Fixed: `/spawnkit build confirm` on a world already at the current layout** no longer reports a
  spurious refusal for the house the plugin itself built; it reports that the layout is already
  current and nothing was changed (still retrying any previously skipped additive piece and
  reporting what it added or skipped).
- Test suite grows from 46 to 64 tests: a classifier table over the exact block list of the failing
  footprint and the other generated surface blocks, a player/structure refusal list, a bounded-tree
  clearing and build-volume scan over an in-memory model of the failing site, refusal messages that
  name the block and position, forced-build behaviour, and the deterministic-deferral and
  already-current policies.

## 1.3.0

Furnished house layout, published for the owner's waypoint-plugin tests. The protected zone and
every 1.2.1 guarantee are unchanged.

- New default house layout. The ring between the protected 3x3 core and the walls is furnished:
  anvil, crafting table and chest along the back (north) wall, an unlit furnace against the east
  wall, the bed against the west wall, and a lodestone in the north-east ring cell. The doorway
  and a two-high walkable route from every core-top cell to the door stay clear.
- Exterior decorations on the outer face of the south wall: a wall torch at head height on each
  side of the doorway, and a white wall banner to the left of the door and a red one to the right
  (seen from outside), each on a solid wall block and facing south. The lodestone and the banners
  are real placed blocks, so a companion waypoint plugin can be tested against them.
- Plank doorstep kept flush with the house floor; when the ground outside the door is lower, up to
  three plank steps are built down to it, and when it is higher a walkable approach is cleared.
  Non-natural blocks are never destroyed for a step. Interior wall torches now hang higher
  (`y = 2`) on the back and side walls so nothing hangs over a container.
- New `spawn-kit.house.furnishings` config section: `anvil`, `furnace`, `lodestone`,
  `crafting-table`, `exterior-banners`, `exterior-torches` (all default `true`), `banners.left`
  (`WHITE`) and `banners.right` (`RED`) validated against the dye colours, and `extras` from a fixed
  allow-list of harmless single-block utilities. Unknown or disallowed extras are skipped with one
  warning (never an error); too many extras are truncated with one log line. A config without the
  section gets the new defaults.
- Layout version bumped to 3. A fresh world gets the full new layout automatically; a 1.2.x world
  (layout version 2) is not rebuilt automatically and logs once that an upgrade is available.
  `/spawnkit build confirm` on such a world performs an additive upgrade that only fills cells that
  are air or replaceable natural blocks, never removes or replaces an existing block, never touches
  an existing chest (unless `supplies` is given), logs each skipped piece with a reason, and records
  version 3; a second run is a no-op. A 1.1.x world keeps its manual-migration behaviour.
- Test: the suite grows from 32 to 46 tests (furnishing geometry and uniqueness across margins and
  the maximum bed/chest counts, the two-high door route with the maximum extras, exterior
  mounting/facing, anvil support / chest headroom / reachability, config defaults and toggles,
  extras validation and truncation, banner-colour fallback, the upgrade policy, and entry-step
  planning).

## 1.2.1

Review fixes for 1.2.0. Nothing in 1.2.0 was run on a server yet.

- Fix: only a player's **first ever join** is placed on the core. 1.2.0 redirected every login for
  any player without a bed spawn, so returning players were pulled back to world spawn on each
  login and lost their position. Joins now use Paper's `AsyncPlayerSpawnLocationEvent`
  (`isNewPlayer()`) instead of the deprecated `org.spigotmc.event.player.PlayerSpawnLocationEvent`,
  whose documentation states the player entity is not usable at that point.
- Fix: the second build pass — the automatic house, an operator `/spawnkit build confirm`, or
  `supplies` — refused the site whenever the terrain dipped three or more blocks below the chosen
  floor, because the foundation the first pass had just filled beneath the core counted as a
  non-natural block. The plugin's own core and foundation are ignored by the refusal check now, so
  the house and chest can still be built on the uneven terrain the core accepts.
- Fix: `BlockMultiPlaceEvent` now checks **every** placed half, so a bed or door can no longer be
  placed with one half inside the arrival space (only the primary block was checked).
- Fix: entities that would obstruct or trap arrivals (boats, minecarts, armour stands, end crystals,
  item frames, paintings) can no longer be placed in the arrival space.
- Fix: a dispenser facing into the zone can no longer place its fluid or block there; the target is
  derived from the dispenser direction.
- Fix: player-planted crops (wheat, carrots, potatoes, beetroots, pumpkin and melon stems) are no
  longer classified as clearable natural vegetation, so a farm refuses the site instead of being
  silently destroyed by a build.
- Fix: re-issuing the layout no longer replaces the supply chest block, which previously wiped
  whatever players had stored in it. `supplies` remains the only path that adds another set.
- Fix: the original `respawn_radius` is now durably recorded before the rule is pinned to `0`. A
  failed write used to pin the rule anyway, and a crash could then make `0` permanent.
- Fix: an in-progress build is no longer reported as an interrupted one (the operator was told to
  review a healthy record).
- Fix: the periodic core check is skipped while the zone's chunks are not loaded (it could load them
  synchronously every cycle) and no longer repeats an identical repair line every five seconds.
- Fix: enabling the BlueMap marker without BlueMap installed no longer throws
  `NoClassDefFoundError` from the scheduler every five seconds.
- Fix: chunk tickets are tracked and released on plugin disable, so an interrupted build cannot
  leave its chunks permanently loaded.
- Fix: `spawn-zone.enabled: false` now clears the recorded protection region and spawn location, and
  `/spawnkit build confirm` refuses to build while the zone is disabled.
- Change: out-of-range or non-numeric spawn-zone dimensions are clamped with a warning instead of
  disabling the plugin (which also switched off spawn protection). Invalid chest items leave the
  chest empty with a warning instead of disabling the plugin.
- Change: the `BlockPhysicsEvent` cancellation was removed. It was the only untargeted cancellation
  (a known cause of client-side ghost blocks and lag) and every route by which a block can enter the
  zone is covered by a specific event; the periodic core check is the backstop.
- Change: `Plugin#getDescription()` replaced with the non-deprecated `Plugin#getPluginMeta()`; the
  build is warning-free again.
- Test: the suite grows from 23 to 32 tests (first-join vs returning, multi-block halves inside the
  arrival space, crops as occupied, dispenser target, chest preservation, own-build exclusion during
  a rebuild, clamping, torch mounting, and the documented floor plan coordinates).

## 1.2.0

- Replace the searched-for shelter with a protected spawn area: a solid 3x3x`core-depth` core
  whose top is the arrival floor, plus a 3x3x`clear-height` protected clear column above it.
- Enforce the protected zone in the plugin through block, fluid, spread, form, fade, grow,
  fertilize, ignite, physics, dispense, piston, explosion, entity-change and structure events,
  independent of operators and of `server.properties`, with a creative-operator exemption for
  player edits. Log a startup warning when `spawn-protection` is greater than `0`.
- Build a destructible house around the core with walls, a roof, a two-half door with a walkable
  exit, beds (`spawn-zone.beds`), chests (`spawn-zone.chests`), a crafting table and wall torches.
  Size, materials and placements are config-driven; the house is never protected or rebuilt.
- Add `spawn-zone` configuration (`enabled`, `core-depth`, `clear-height`, `margin`, `beds`,
  `chests`, `manage-spawn-radius`) that works independently of the test-only house/chest and the
  date window.
- Control spawns: set the world spawn to the core top, pin the `respawn_radius` game rule to `0`
  (recording and restoring the original), and handle spawn-location and respawn events without
  overriding beds or respawn anchors.
- Choose the floor level from the terrain under the footprint, clear only natural vegetation/trees/
  snow, fill a foundation, raise above water, and refuse/defer with a reason and backoff when the
  footprint is occupied, void, lava-covered or too uneven. Never destroy non-natural blocks.
- Repair a damaged protected core on enable; never repair the destructible house. Add
  `/spawnkit build confirm [supplies]` to build or migrate a world without handing out a second set
  of supplies. Refuse to auto-migrate a 1.1.x world and log what to do.
- Keep async chunk loading with tickets released afterwards, the persisted issuance record written
  before any world change, the config-driven chest contents, the BlueMap marker, and deferral
  logging with backoff. Build block changes in bounded main-thread batches and move players out of
  the footprint onto the core after construction.
- Remove `spawn-kit.search-radius` (no site search anymore) and drop the marker lodestone so the
  protected core stays uniform.

## 1.1.4

- Load and temporarily ticket spawn-search chunks asynchronously, then release
  tickets after setup.
- Accept and clear whitelisted surface vegetation and snow while preserving
  structures, containers, and unsafe terrain.
- Keep the world spawn unchanged and place the supply chest outside its
  configured protection square.
- Publish the BlueMap spawn marker independently of shelter setup and avoid
  repeating unchanged deferral diagnostics.
