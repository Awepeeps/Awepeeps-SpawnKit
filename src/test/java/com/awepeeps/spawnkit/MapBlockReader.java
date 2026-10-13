package com.awepeeps.spawnkit;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.bukkit.Material;

/**
 * An in-memory {@link BlockReader} for scan tests: a hand-built block map with the same heightmap
 * semantics the builder relies on. {@link #topY} is the highest non-air block (the production
 * {@link WorldBlockReader} uses {@code HeightMap.WORLD_SURFACE}); {@link #surfaceY} excludes leaves
 * (the production ground lookup).
 */
final class MapBlockReader implements BlockReader {
  private final Map<Long, Material> blocks = new HashMap<>();
  private final Set<Long> protectedBlocks = new HashSet<>();
  private final int minHeight;
  private final int maxHeight;
  private boolean loaded = true;

  MapBlockReader(int minHeight, int maxHeight) {
    this.minHeight = minHeight;
    this.maxHeight = maxHeight;
  }

  MapBlockReader put(int x, int y, int z, Material material) {
    blocks.put(key(x, y, z), material);
    return this;
  }

  MapBlockReader protect(int x, int y, int z) {
    protectedBlocks.add(key(x, y, z));
    return this;
  }

  MapBlockReader loaded(boolean value) {
    this.loaded = value;
    return this;
  }

  private static long key(int x, int y, int z) {
    return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
  }

  @Override
  public int minHeight() {
    return minHeight;
  }

  @Override
  public int maxHeight() {
    return maxHeight;
  }

  @Override
  public boolean isLoaded(int blockX, int blockZ) {
    return loaded;
  }

  @Override
  public Material blockAt(int x, int y, int z) {
    return blocks.getOrDefault(key(x, y, z), Material.AIR);
  }

  @Override
  public int topY(int blockX, int blockZ) {
    for (int y = maxHeight - 1; y >= minHeight; y--) {
      if (!SpawnZonePolicy.isAir(blockAt(blockX, y, blockZ))) return y;
    }
    return minHeight;
  }

  @Override
  public int surfaceY(int blockX, int blockZ) {
    for (int y = maxHeight - 1; y >= minHeight; y--) {
      Material material = blockAt(blockX, y, blockZ);
      if (!SpawnZonePolicy.isAir(material) && !material.name().endsWith("_LEAVES")) return y;
    }
    return minHeight;
  }

  @Override
  public boolean insideWorldBorder(int blockX, int blockZ) {
    return true;
  }

  @Override
  public boolean forceReplaceable(int x, int y, int z) {
    return !protectedBlocks.contains(key(x, y, z));
  }
}
