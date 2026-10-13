package com.awepeeps.spawnkit;

import java.util.function.Supplier;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

/**
 * One planned block change. The block data is created lazily (as a supplier) so a plan can be
 * produced and asserted in a unit test without a running server, where {@code Bukkit.createBlockData}
 * is unavailable.
 */
record SpawnEdit(int x, int y, int z, Material material, Supplier<BlockData> data, boolean overwrite) {
  static SpawnEdit place(int x, int y, int z, Material material, Supplier<BlockData> data) {
    return new SpawnEdit(x, y, z, material, data, true);
  }

  static SpawnEdit clear(int x, int y, int z) {
    return new SpawnEdit(x, y, z, Material.AIR, null, true);
  }
}
