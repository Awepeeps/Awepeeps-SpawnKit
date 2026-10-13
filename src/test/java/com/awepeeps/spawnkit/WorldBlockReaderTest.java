package com.awepeeps.spawnkit;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

/**
 * Pins the heightmap choice of the production {@link WorldBlockReader} against a small fake
 * {@link org.bukkit.World}. {@link WorldBlockReader} is the only reader a live server uses, so the
 * in-memory {@link MapBlockReader} test double cannot guard it: the double already returned the
 * highest non-air block while production used {@code MOTION_BLOCKING}, which is exactly how missed
 * ground cover shipped unnoticed.
 */
class WorldBlockReaderTest {
  private static final int X = 100;
  private static final int Z = 100;

  @Test
  void topYCountsExposedGroundCoverAboveTheGrassAndUsesWorldSurface() {
    SyntheticWorld grid = new SyntheticWorld()
        .put(X, 65, Z, Material.GRASS_BLOCK)
        .put(X, 66, Z, Material.LEAF_LITTER)
        .put(X, 67, Z, Material.BUSH);

    WorldBlockReader reader = new WorldBlockReader(grid.world());

    assertEquals(67, reader.topY(X, Z),
        "topY must reach the highest non-air block, including non-solid ground cover");
    List<HeightMap> queries = grid.heightMapQueries();
    assertTrue(queries.contains(HeightMap.WORLD_SURFACE), "topY must ask for WORLD_SURFACE");
    assertFalse(queries.contains(HeightMap.MOTION_BLOCKING),
        "topY must not use MOTION_BLOCKING, which ignores non-solid ground cover");
  }

  @Test
  void productionTopYAgreesWithTheTestDoubleAboveExposedGroundCover() {
    SyntheticWorld grid = new SyntheticWorld()
        .put(X, 65, Z, Material.GRASS_BLOCK)
        .put(X, 66, Z, Material.LEAF_LITTER);
    MapBlockReader doubleReader = new MapBlockReader(SyntheticWorld.MIN_Y, SyntheticWorld.MAX_Y)
        .put(X, 65, Z, Material.GRASS_BLOCK)
        .put(X, 66, Z, Material.LEAF_LITTER);

    assertEquals(66, doubleReader.topY(X, Z), "the test double counts the leaf litter");
    assertEquals(66, new WorldBlockReader(grid.world()).topY(X, Z),
        "production must agree with the double, so the double cannot hide a production regression");
  }

  @Test
  void theGroundLookupStillIgnoresLeavesAndNonSolidCover() {
    SyntheticWorld grid = new SyntheticWorld()
        .put(X, 64, Z, Material.DIRT)
        .put(X, 65, Z, Material.GRASS_BLOCK)
        .put(X, 66, Z, Material.LEAF_LITTER)
        .put(X, 70, Z, Material.OAK_LEAVES);

    WorldBlockReader reader = new WorldBlockReader(grid.world());

    assertEquals(65, reader.surfaceY(X, Z),
        "the ground lookup stays MOTION_BLOCKING_NO_LEAVES so the floor is not raised by cover");
  }
}
