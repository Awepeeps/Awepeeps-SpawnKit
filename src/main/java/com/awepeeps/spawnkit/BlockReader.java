package com.awepeeps.spawnkit;

import org.bukkit.Material;

/**
 * The terrain a spawn build reads, abstracted away from {@code World} so the scan and clearing plan
 * can be unit tested against an in-memory block map. Every method is read-only: the builder never
 * loads a chunk through this interface.
 */
interface BlockReader {
  int minHeight();

  int maxHeight();

  /** True when the chunk containing this block column is already loaded. */
  boolean isLoaded(int blockX, int blockZ);

  Material blockAt(int x, int y, int z);

  /** The top of the column counting everything that blocks motion, leaves included. */
  int topY(int blockX, int blockZ);

  /** The top of the column ignoring leaves (used to find the ground under a canopy). */
  int surfaceY(int blockX, int blockZ);

  boolean insideWorldBorder(int blockX, int blockZ);

  /**
   * For a forced build only: true when the block at (x, y, z) may be replaced. A container that
   * holds items, or any other block entity that carries contents, returns false.
   */
  boolean forceReplaceable(int x, int y, int z);
}
