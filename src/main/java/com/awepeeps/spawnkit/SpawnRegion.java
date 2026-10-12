package com.awepeeps.spawnkit;

/**
 * Immutable axis-aligned box in block coordinates. Pure geometry so containment (including the
 * off-by-one behaviour on every face) can be unit tested without a running server.
 */
record SpawnRegion(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
  SpawnRegion {
    if (minX > maxX || minY > maxY || minZ > maxZ) {
      throw new IllegalArgumentException("Invalid region bounds.");
    }
  }

  boolean contains(int x, int y, int z) {
    return x >= minX && x <= maxX
        && y >= minY && y <= maxY
        && z >= minZ && z <= maxZ;
  }

  boolean contains(SpawnBlockPos pos) {
    return contains(pos.x(), pos.y(), pos.z());
  }

  boolean containsColumn(int x, int z) {
    return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
  }

  int volume() {
    return (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
  }

  SpawnRegion translated(int dx, int dy, int dz) {
    return new SpawnRegion(minX + dx, minY + dy, minZ + dz,
        maxX + dx, maxY + dy, maxZ + dz);
  }

  /** Builds a box from a centre and the inclusive radii along each axis. */
  static SpawnRegion around(int centerX, int centerY, int centerZ,
                            int radiusX, int radiusY, int radiusZ) {
    return new SpawnRegion(
        centerX - radiusX, centerY - radiusY, centerZ - radiusZ,
        centerX + radiusX, centerY + radiusY, centerZ + radiusZ);
  }

  static SpawnRegion of(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    return new SpawnRegion(minX, minY, minZ, maxX, maxY, maxZ);
  }
}
