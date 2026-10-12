# Changelog

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
