package com.awepeeps.spawnkit;

/**
 * Immutable block coordinate, relative to the world spawn block when used by {@link SpawnZoneLayout}.
 */
record SpawnBlockPos(int x, int y, int z) {
  SpawnBlockPos offset(int dx, int dy, int dz) {
    return new SpawnBlockPos(x + dx, y + dy, z + dz);
  }
}
