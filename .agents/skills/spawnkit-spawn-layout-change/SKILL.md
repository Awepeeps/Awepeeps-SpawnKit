---
name: spawnkit-spawn-layout-change
description: Change or verify the Awepeeps Spawn Kit protected-zone and spawn-house build (spawn-zone, spawn-kit, config, or terrain classifier). Use when editing SpawnKit build, protection, or spawn-location behaviour and its tests.
---

# Spawn Kit layout change

Work in an isolated worktree off `main`. The plugin is public, so keep docs and examples
source/generic (see [AGENTS.md](../../../AGENTS.md)).

## Before you change code

Confirm which part you are touching and its contract:

- `spawn-zone` (protected core, arrival column, spawn control) is independent of the date window.
- `spawn-kit` (house and chest) is test-only and gated by the inclusive `starts-on`/`expires-on` window.
- Nothing may ever be placed in the arrival space; the house stays destructible and is never repaired.
- `issued-kits.yml` is written before any world change; do not trigger a reset.

## Make and test the change

1. Edit `src/main/java/com/awepeeps/spawnkit` (or `src/main/resources/config.yml`).
2. Add or update a test in `src/test/java/com/awepeeps/spawnkit`. For anything that depends on the
   terrain scan or clearance bound, drive the real `WorldBlockReader` through the fake world rather
   than the in-memory reader: the `WORLD_SURFACE` vs `MOTION_BLOCKING` choice is exactly what broke
   in 1.3.3. Extend the classifier table for new block types instead of hand-listing materials.
3. Run `bash ./gradlew --no-daemon clean test build`; the jar must stay byte-reproducible.
4. Update the config table in `README.md` and add a `CHANGELOG.md` entry for user-visible changes.

## Do not

- Add a reset, force, or migration path that overwrites an existing house or chest.
- Pin a moving branch; releases pin the merge commit SHA plus jar SHA-256 in the deployment repo.
- Put host paths, coordinates, seeds, or secrets in this public repo.
