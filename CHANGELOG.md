# Changelog

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
