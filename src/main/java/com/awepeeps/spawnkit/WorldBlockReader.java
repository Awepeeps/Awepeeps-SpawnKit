package com.awepeeps.spawnkit;

import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.TileState;

/** {@link BlockReader} backed by a live {@link World}. All calls are read-only. */
final class WorldBlockReader implements BlockReader {
  private final World world;

  WorldBlockReader(World world) {
    this.world = world;
  }

  @Override
  public int minHeight() {
    return world.getMinHeight();
  }

  @Override
  public int maxHeight() {
    return world.getMaxHeight();
  }

  @Override
  public boolean isLoaded(int blockX, int blockZ) {
    return world.isChunkLoaded(blockX >> 4, blockZ >> 4);
  }

  @Override
  public Material blockAt(int x, int y, int z) {
    return world.getBlockAt(x, y, z).getType();
  }

  @Override
  public int topY(int blockX, int blockZ) {
    return world.getHighestBlockYAt(blockX, blockZ, HeightMap.MOTION_BLOCKING);
  }

  @Override
  public int surfaceY(int blockX, int blockZ) {
    return world.getHighestBlockYAt(blockX, blockZ, HeightMap.MOTION_BLOCKING_NO_LEAVES);
  }

  @Override
  public boolean insideWorldBorder(int blockX, int blockZ) {
    return world.getWorldBorder().isInside(new Location(world, blockX + 0.5, 0, blockZ + 0.5));
  }

  @Override
  @SuppressWarnings("deprecation")
  public boolean forceReplaceable(int x, int y, int z) {
    BlockState state = world.getBlockAt(x, y, z).getState();
    if (state instanceof Container container) {
      return container.getInventory().isEmpty();
    }
    // Anything else that is a block entity (a sign, a banner, a skull, ...) counts as carrying
    // contents; a forced build must not silently destroy it.
    return !(state instanceof TileState);
  }
}
