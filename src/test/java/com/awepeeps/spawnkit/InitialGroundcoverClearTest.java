package com.awepeeps.spawnkit;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.junit.jupiter.api.Test;

/**
 * Regression for the initial ground cover the 1.3.2 build left on the arrival cells. The builder
 * clears "clearable vegetation" up to the top of each column; the old production reader asked the
 * server for the {@code MOTION_BLOCKING} heightmap, which does not count leaf litter, bushes or
 * short grass, so exposed cover one block above the grass was never scheduled for clearing. The
 * backstop {@code repairZone} fixed the four cells later, once the chunks loaded.
 *
 * <p>The composed, two-pass tests drive the real {@link WorldBlockReader} over a {@link
 * SyntheticWorld} fake. The legacy test uses the same grid but restores the pre-fix
 * {@code MOTION_BLOCKING} top so the defect is still reproducible. Coordinates are synthetic
 * (100/100); nothing here is captured game data.
 */
class InitialGroundcoverClearTest {
  private static final int SX = 100;
  private static final int SZ = 100;

  private static SpawnZoneBuilder builder() {
    return new SpawnZoneBuilder(null, NamespacedKey.fromString("spawnkit:spawn_kit_chest"));
  }

  private static SpawnZoneLayout layout() {
    return new SpawnZoneLayout(3, 3, 1, 1, 1);
  }

  /** Flat grass at y=65, exposed ground cover and a tree with a canopy over part of the core. */
  private static SyntheticWorld site() {
    SyntheticWorld grid = flatGround();
    // Exposed ground cover on the core columns at y=66: two cells sit under the canopy, four do not.
    grid.put(SX - 1, 66, SZ - 1, Material.LEAF_LITTER); // under canopy
    grid.put(SX - 1, 66, SZ, Material.LEAF_LITTER);     // under canopy
    grid.put(SX, 66, SZ - 1, Material.LEAF_LITTER);     // no cover -> must still be cleared
    grid.put(SX, 66, SZ + 1, Material.LEAF_LITTER);
    grid.put(SX + 1, 66, SZ - 1, Material.LEAF_LITTER);
    grid.put(SX + 1, 66, SZ + 1, Material.LEAF_LITTER);
    // A few surrounding ground-cover cells, as a forest floor would have.
    grid.put(SX - 2, 66, SZ - 2, Material.LEAF_LITTER);
    grid.put(SX + 2, 66, SZ, Material.LEAF_LITTER);
    grid.put(SX - 3, 66, SZ - 1, Material.BUSH);
    grid.put(SX + 3, 66, SZ + 3, Material.SHORT_GRASS);
    // The tree whose trunk stands in the footprint at rel (-3,-2); its canopy covers (-1,-1), (-1,0).
    int tx = SX - 3;
    int tz = SZ - 2;
    for (int y = 66; y <= 70; y++) {
      grid.put(tx, y, tz, Material.OAK_LOG);
    }
    for (int y = 67; y <= 69; y++) {
      for (int dx = -2; dx <= 1; dx++) {
        for (int dz = -1; dz <= 1; dz++) {
          grid.put(tx + dx, y, tz + dz, Material.OAK_LEAVES);
        }
      }
    }
    grid.put(SX - 1, 67, SZ - 1, Material.AIR);
    grid.put(SX - 1, 68, SZ - 1, Material.OAK_LEAVES);
    grid.put(SX - 1, 69, SZ - 1, Material.OAK_LEAVES);
    grid.put(SX - 1, 69, SZ, Material.OAK_LEAVES);
    return grid;
  }

  private static SyntheticWorld flatGround() {
    SyntheticWorld grid = new SyntheticWorld();
    for (int dx = -10; dx <= 10; dx++) {
      for (int dz = -10; dz <= 10; dz++) {
        grid.put(SX + dx, 61, SZ + dz, Material.STONE);
        for (int y = 62; y <= 64; y++) {
          grid.put(SX + dx, y, SZ + dz, Material.DIRT);
        }
        grid.put(SX + dx, 65, SZ + dz, Material.GRASS_BLOCK);
      }
    }
    return grid;
  }

  /** The pre-fix production reader: {@code topY} is MOTION_BLOCKING, so non-solid cover is missed. */
  private static BlockReader legacyMotionBlocking(SyntheticWorld grid) {
    return new BlockReader() {
      @Override public int minHeight() { return grid.minHeight(); }
      @Override public int maxHeight() { return grid.maxHeight(); }
      @Override public boolean isLoaded(int x, int z) { return grid.isLoaded(x, z); }
      @Override public Material blockAt(int x, int y, int z) { return grid.blockAt(x, y, z); }
      @Override public int topY(int x, int z) { return grid.motionBlockingTopY(x, z); }
      @Override public int surfaceY(int x, int z) { return grid.surfaceY(x, z); }
      @Override public boolean insideWorldBorder(int x, int z) { return grid.insideWorldBorder(x, z); }
      @Override public boolean forceReplaceable(int x, int y, int z) {
        return grid.forceReplaceable(x, y, z);
      }
    };
  }

  private static void apply(SyntheticWorld grid, SpawnZoneBuilder.Prepared prepared) {
    for (SpawnEdit edit : prepared.edits()) {
      grid.put(edit.x(), edit.y(), edit.z(), edit.material());
    }
  }

  private static SpawnZoneBuilder.Prepared zonePass(BlockReader reader) {
    SpawnZoneBuilder.Prepared prepared = builder().prepare(reader, SX, SZ, layout(),
        false, false, List.of(), null, false, false);
    assertTrue(prepared.accepted(), prepared.defect());
    return prepared;
  }

  private static SpawnZoneBuilder.Prepared housePass(BlockReader reader, SpawnRegion core) {
    SpawnZoneBuilder.Prepared prepared = builder().prepare(reader, SX, SZ, layout(),
        true, true, List.of(), core, false, false);
    assertTrue(prepared.accepted(), prepared.defect());
    return prepared;
  }

  private record TwoPass(List<String> leftovers, int spawnY) {}

  /** The two-pass build exactly as production composes it, returning the leftover protected cells. */
  private static TwoPass twoPass(BlockReader reader, SyntheticWorld grid) {
    SpawnZoneBuilder.Prepared pass1 = zonePass(reader);
    apply(grid, pass1);
    SpawnZoneBuilder.Prepared pass2 = housePass(reader, pass1.protectedRegion());
    apply(grid, pass2);
    return new TwoPass(defects(grid, layout(), SX, pass1.spawnY(), SZ), pass1.spawnY());
  }

  /** The cells {@code repairZone} would have to fix: a wrong core block, or any non-air arrival cell. */
  private static List<String> defects(SyntheticWorld grid, SpawnZoneLayout layout,
                                      int sx, int sy, int sz) {
    List<String> cells = new ArrayList<>();
    for (SpawnBlockPos offset : layout.offsetsWithRole(SpawnZoneLayout.Role.CORE)) {
      if (grid.blockAt(sx + offset.x(), sy + offset.y(), sz + offset.z())
          != SpawnZoneBuilder.CORE_MATERIAL) {
        cells.add("core " + offset);
      }
    }
    SpawnRegion arrival = layout.arrival();
    for (int x = arrival.minX(); x <= arrival.maxX(); x++) {
      for (int y = arrival.minY(); y <= arrival.maxY(); y++) {
        for (int z = arrival.minZ(); z <= arrival.maxZ(); z++) {
          Material material = grid.blockAt(sx + x, sy + y, sz + z);
          if (!SpawnZonePolicy.isAir(material)) {
            cells.add("arrival (" + x + "," + y + "," + z + ")=" + material);
          }
        }
      }
    }
    return cells;
  }

  @Test
  void productionReaderLeavesNoDefectsWithoutAnyRepair() {
    SyntheticWorld grid = site();
    TwoPass result = twoPass(new WorldBlockReader(grid.world()), grid);

    assertTrue(result.leftovers().isEmpty(),
        "the fixed heightmap must clear all ground cover in the two-pass build, was: "
            + result.leftovers());
    assertEquals(66, result.spawnY(), "the floor is unchanged; the fix does not move the build");

    // The protected core is intact and the whole arrival space is air before repairZone runs.
    for (SpawnBlockPos offset : layout().offsetsWithRole(SpawnZoneLayout.Role.CORE)) {
      assertEquals(SpawnZoneBuilder.CORE_MATERIAL,
          grid.blockAt(SX + offset.x(), result.spawnY() + offset.y(), SZ + offset.z()),
          "core cell " + offset);
    }
    SpawnRegion arrival = layout().arrival();
    for (int x = arrival.minX(); x <= arrival.maxX(); x++) {
      for (int y = arrival.minY(); y <= arrival.maxY(); y++) {
        for (int z = arrival.minZ(); z <= arrival.maxZ(); z++) {
          assertTrue(SpawnZonePolicy.isAir(
                  grid.blockAt(SX + x, result.spawnY() + y, SZ + z)),
              "arrival cell (" + x + "," + y + "," + z + ") must be air");
        }
      }
    }
  }

  @Test
  void legacyMotionBlockingHeightmapStillLeavesFourArrivalDefects() {
    SyntheticWorld grid = site();
    TwoPass result = twoPass(legacyMotionBlocking(grid), grid);

    assertEquals(4, result.leftovers().size(),
        "the pre-fix heightmap misses exactly four cells: " + result.leftovers());
    assertEquals(Set.of(
        "arrival (0,0,-1)=LEAF_LITTER",
        "arrival (0,0,1)=LEAF_LITTER",
        "arrival (1,0,-1)=LEAF_LITTER",
        "arrival (1,0,1)=LEAF_LITTER"), new HashSet<>(result.leftovers()));
  }

  @Test
  void theZonePassAloneLeavesTheSameFourDefectsUnderTheLegacyHeightmap() {
    SyntheticWorld grid = site();
    SpawnZoneBuilder.Prepared pass1 = zonePass(legacyMotionBlocking(grid));
    apply(grid, pass1);

    List<String> leftovers = defects(grid, layout(), SX, pass1.spawnY(), SZ);
    assertEquals(4, leftovers.size(),
        "the zone pass alone already leaves the exposed cover: " + leftovers);
  }

  @Test
  void occupiedContainerRefusesTheSiteAndPlansNoWrites() {
    SyntheticWorld grid = flatGround().put(SX, 66, SZ, Material.CHEST).protect(SX, 66, SZ);
    WorldBlockReader reader = new WorldBlockReader(grid.world());

    SpawnZoneBuilder.Prepared plain = builder().prepare(reader, SX, SZ, layout(),
        true, true, List.of(), null, false, false);
    assertFalse(plain.accepted(), "a container in the footprint must refuse the build");
    assertTrue(plain.defect().contains("CHEST"), plain.defect());
    assertTrue(plain.edits().isEmpty(), "a refused site plans no block writes");
    assertTrue(plain.postOps().isEmpty(), "a refused site plans no deferred writes");

    SpawnZoneBuilder.Prepared forced = builder().prepare(reader, SX, SZ, layout(),
        true, true, List.of(), null, true, false);
    assertFalse(forced.accepted(), "a forced build must not replace a container holding contents");
    assertTrue(forced.defect().contains("holds contents"), forced.defect());
    assertTrue(forced.edits().isEmpty(), "a refused forced build plans no block writes");
  }

  @Test
  void nonVegetationAboveTheClearedCoverIsNeverRemoved() {
    SyntheticWorld grid = flatGround()
        .put(SX, 66, SZ, Material.LEAF_LITTER)
        .put(SX, 75, SZ, Material.TORCH); // player-placed, above the scanning range
    SpawnZoneBuilder.Prepared prepared = builder().prepare(new WorldBlockReader(grid.world()),
        SX, SZ, layout(), true, true, List.of(), null, false, false);

    assertTrue(prepared.accepted(), prepared.defect());
    assertEquals(65, prepared.floorY(), "the raised clear limit must not move the floor");
    Set<String> cleared = clearedCells(prepared);
    assertTrue(cleared.contains(key(SX, 66, SZ)), "the exposed ground cover is cleared");
    assertFalse(cleared.contains(key(SX, 75, SZ)), "a torch above the cover is never cleared");
    for (SpawnEdit edit : prepared.edits()) {
      assertFalse(edit.x() == SX && edit.y() == 75 && edit.z() == SZ,
          "no edit may touch the non-vegetation block");
    }
  }

  @Test
  void clearingStaysInsideTheBuildColumns() {
    SyntheticWorld grid = flatGround()
        .put(SX, 66, SZ, Material.LEAF_LITTER)
        .put(SX + 5, 66, SZ, Material.LEAF_LITTER)
        .put(SX, 66, SZ + 5, Material.BUSH);
    SpawnZoneBuilder.Prepared prepared = builder().prepare(new WorldBlockReader(grid.world()),
        SX, SZ, layout(), true, true, List.of(), null, false, false);

    assertTrue(prepared.accepted(), prepared.defect());
    Set<String> cleared = clearedCells(prepared);
    assertTrue(cleared.contains(key(SX, 66, SZ)), "cover inside the footprint is cleared");
    assertFalse(cleared.contains(key(SX + 5, 66, SZ)), "cover outside the footprint is left alone");
    assertFalse(cleared.contains(key(SX, 66, SZ + 5)), "cover outside the footprint is left alone");
    for (SpawnEdit edit : prepared.edits()) {
      assertTrue(edit.y() < grid.maxHeight(),
          "no edit may reach the world ceiling");
    }
  }

  @Test
  void clearingIsCappedAtTheColumnTopSoTheRoofIsNeverScanned() {
    SyntheticWorld grid = flatGround().put(SX, 66, SZ, Material.LEAF_LITTER);
    SpawnZoneBuilder.Prepared prepared = builder().prepare(new WorldBlockReader(grid.world()),
        SX, SZ, layout(), true, true, List.of(), null, false, false);
    assertTrue(prepared.accepted(), prepared.defect());
    Map<String, Material> placed = placedBlocks(prepared);
    assertEquals(Material.STONE_BRICKS, placed.get(key(SX, 65, SZ)), "the core top is placed");
    assertEquals(Material.LEAF_LITTER, grid.blockAt(SX, 66, SZ),
        "preparing a plan never mutates the world; only the applied edits do");
  }

  private static Set<String> clearedCells(SpawnZoneBuilder.Prepared prepared) {
    Set<String> cells = new HashSet<>();
    for (SpawnEdit edit : prepared.edits()) {
      if (edit.material() == Material.AIR) cells.add(key(edit.x(), edit.y(), edit.z()));
    }
    return cells;
  }

  private static Map<String, Material> placedBlocks(SpawnZoneBuilder.Prepared prepared) {
    Map<String, Material> blocks = new HashMap<>();
    for (SpawnEdit edit : prepared.edits()) {
      if (edit.material() != Material.AIR) {
        blocks.put(key(edit.x(), edit.y(), edit.z()), edit.material());
      }
    }
    return blocks;
  }

  private static String key(int x, int y, int z) {
    return x + "," + y + "," + z;
  }
}
