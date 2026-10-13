package com.awepeeps.spawnkit;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.junit.jupiter.api.Test;

/**
 * Scan and clearing tests over an in-memory model of the real failing site: flat grass/dirt ground,
 * a big-oak-like tree whose trunk stands inside the footprint and whose canopy overlaps the arrival
 * column, the newer ground cover that was really there (leaf litter, a bush), tall grass and flowers,
 * and a generated bee nest on the trunk.
 */
class SpawnZoneBuilderTest {
  private static final int SX = 100;
  private static final int SZ = 100;

  private static SpawnZoneBuilder builder() {
    return new SpawnZoneBuilder(null, NamespacedKey.fromString("spawnkit:spawn_kit_chest"));
  }

  private static SpawnZoneLayout layout() {
    return new SpawnZoneLayout(3, 3, 1, 1, 1);
  }

  private static MapBlockReader flatGround() {
    MapBlockReader reader = new MapBlockReader(-64, 320);
    for (int dx = -8; dx <= 8; dx++) {
      for (int dz = -8; dz <= 8; dz++) {
        reader.put(SX + dx, 55, SZ + dz, Material.STONE);
        for (int y = 56; y <= 64; y++) {
          reader.put(SX + dx, y, SZ + dz, Material.DIRT);
        }
        reader.put(SX + dx, 65, SZ + dz, Material.GRASS_BLOCK);
      }
    }
    return reader;
  }

  private static MapBlockReader forestSite() {
    MapBlockReader reader = flatGround();
    int tx = SX - 2;
    int tz = SZ - 2;
    for (int y = 66; y <= 70; y++) {
      reader.put(tx, y, tz, Material.OAK_LOG);
    }
    for (int y = 67; y <= 68; y++) {
      for (int dx = -2; dx <= 2; dx++) {
        for (int dz = -2; dz <= 2; dz++) {
          reader.put(tx + dx, y, tz + dz, Material.OAK_LEAVES);
        }
      }
    }
    for (int dx = -1; dx <= 1; dx++) {
      for (int dz = -1; dz <= 1; dz++) {
        reader.put(tx + dx, 69, tz + dz, Material.OAK_LEAVES);
      }
    }
    reader.put(tx + 1, 69, tz, Material.BEE_NEST);
    reader.put(tx + 1, 67, tz, Material.VINE);
    reader.put(SX - 3, 66, SZ - 1, Material.LEAF_LITTER);
    reader.put(SX - 1, 66, SZ - 2, Material.LEAF_LITTER);
    reader.put(SX - 3, 66, SZ + 1, Material.BUSH);
    reader.put(SX - 2, 66, SZ - 2, Material.TALL_GRASS);
    reader.put(SX - 1, 66, SZ + 1, Material.SHORT_GRASS);
    reader.put(SX + 1, 66, SZ - 1, Material.POPPY);
    reader.put(SX + 3, 66, SZ + 3, Material.OAK_SAPLING);
    return reader;
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
      if (edit.material() != Material.AIR) blocks.put(key(edit.x(), edit.y(), edit.z()), edit.material());
    }
    return blocks;
  }

  private static String key(int x, int y, int z) {
    return x + "," + y + "," + z;
  }

  @Test
  void forestFootprintIsAcceptedAndEveryVegetationCellIsCleared() {
    SpawnZoneBuilder.Prepared prepared = builder().prepare(forestSite(), SX, SZ, layout(),
        true, true, List.of(), null, false, false);

    assertTrue(prepared.accepted(), prepared.defect());
    assertEquals(65, prepared.floorY());
    assertEquals(66, prepared.spawnY());

    Set<String> cleared = clearedCells(prepared);
    // The newer ground cover that broke 1.3.0 (leaf litter and a bush) and the ordinary plants.
    assertTrue(cleared.contains(key(SX - 3, 66, SZ - 1)), "leaf litter");
    assertTrue(cleared.contains(key(SX - 1, 66, SZ - 2)), "leaf litter");
    assertTrue(cleared.contains(key(SX - 3, 66, SZ + 1)), "bush");
    assertTrue(cleared.contains(key(SX - 2, 66, SZ - 2)), "tall grass");
    assertTrue(cleared.contains(key(SX - 1, 66, SZ + 1)), "short grass");
    assertTrue(cleared.contains(key(SX + 1, 66, SZ - 1)), "poppy");
    assertTrue(cleared.contains(key(SX + 3, 66, SZ + 3)), "sapling");

    // The whole tree: trunk, its canopy over the arrival column, and the bee nest on the trunk.
    for (int y = 66; y <= 70; y++) {
      assertTrue(cleared.contains(key(SX - 2, y, SZ - 2)), "trunk at y=" + y);
    }
    assertTrue(cleared.contains(key(SX, 68, SZ)), "canopy over the arrival column");
    assertTrue(cleared.contains(key(SX - 2, 69, SZ - 2)), "canopy above the roof");
    assertTrue(cleared.contains(key(SX - 1, 69, SZ - 2)), "generated bee nest goes with the tree");
    assertTrue(cleared.contains(key(SX - 1, 67, SZ - 2)), "vine goes with the tree");

    // The canopy also reaches outside the footprint (x = SX-4) within the bounded radius, so no
    // floating leaves are left beside the house.
    assertTrue(cleared.contains(key(SX - 4, 67, SZ - 2)), "canopy outside the footprint");
    assertTrue(cleared.contains(key(SX - 4, 68, SZ - 2)), "canopy outside the footprint");
    assertFalse(cleared.contains(key(SX - 4, 66, SZ - 2)), "the ground outside is not cleared");
  }

  @Test
  void everyClearableCellInTheBuildVolumeIsScheduledForClearing() {
    MapBlockReader reader = forestSite();
    SpawnZoneBuilder.Prepared prepared = builder().prepare(reader, SX, SZ, layout(),
        true, true, List.of(), null, false, false);
    assertTrue(prepared.accepted(), prepared.defect());
    Set<String> cleared = clearedCells(prepared);

    // Every vegetation/tree cell inside the footprint and door columns, up to the top of its
    // column, must be gone.
    for (int dx = -3; dx <= 3; dx++) {
      for (int dz = -3; dz <= 3; dz++) {
        assertColumnCleared(reader, cleared, SX + dx, SZ + dz);
      }
    }
    assertColumnCleared(reader, cleared, SX, SZ + 4);
  }

  private static void assertColumnCleared(MapBlockReader reader, Set<String> cleared, int x, int z) {
    int top = reader.topY(x, z);
    for (int y = -64; y <= top; y++) {
      if (SpawnZonePolicy.isClearableVegetation(reader.blockAt(x, y, z))) {
        assertTrue(cleared.contains(key(x, y, z)),
            "vegetation at " + key(x, y, z) + " must be scheduled for clearing");
      }
    }
  }

  @Test
  void theZonePlanAlsoBuildsOnTheForestedSiteAndOnlyClearsTheCoreColumns() {
    MapBlockReader reader = forestSite();
    SpawnZoneBuilder.Prepared prepared = builder().prepare(reader, SX, SZ, layout(),
        false, false, List.of(), null, false, false);

    assertTrue(prepared.accepted(), prepared.defect());
    Set<String> cleared = clearedCells(prepared);
    // The core columns are cleared; a distant leaf-litter cell outside them is not part of the zone.
    assertTrue(cleared.contains(key(SX, 68, SZ)), "canopy over the arrival column");
    assertFalse(cleared.contains(key(SX - 3, 66, SZ - 1)), "vegetation off the zone columns");

    Map<String, Material> placed = placedBlocks(prepared);
    assertEquals(Material.STONE_BRICKS, placed.get(key(SX, 65, SZ)));
    assertFalse(placed.containsValue(Material.OAK_PLANKS), "the zone plan places no house blocks");
  }

  @Test
  void theHousePlanPlacesAFloorWallsRoofCoreAndFurnishings() {
    SpawnZoneBuilder.Prepared prepared = builder().prepare(flatGround(), SX, SZ, layout(),
        true, true, List.of(), null, false, false);
    assertTrue(prepared.accepted(), prepared.defect());
    Map<String, Material> placed = placedBlocks(prepared);
    assertEquals(Material.STONE_BRICKS, placed.get(key(SX, 65, SZ)), "core top layer");
    assertEquals(Material.STONE_BRICKS, placed.get(key(SX, 63, SZ)), "core bottom layer");
    assertEquals(Material.OAK_PLANKS, placed.get(key(SX - 3, 65, SZ)), "house floor");
    assertEquals(Material.COBBLESTONE, placed.get(key(SX - 3, 66, SZ)), "wall");
    assertEquals(Material.OAK_PLANKS, placed.get(key(SX - 3, 69, SZ)), "roof");
    assertEquals(Material.CRAFTING_TABLE, placed.get(key(SX, 66, SZ - 2)), "crafting table");
    assertEquals(Material.CHEST, placed.get(key(SX + 1, 66, SZ - 2)), "supply chest");
    // Nothing is ever placed inside the protected arrival column.
    for (int y = 66; y <= 68; y++) {
      for (int dx = -1; dx <= 1; dx++) {
        for (int dz = -1; dz <= 1; dz++) {
          Material material = placed.get(key(SX + dx, y, SZ + dz));
          assertNull(material, "nothing may be placed in the arrival column at " + dx + "," + y + "," + dz);
        }
      }
    }
  }

  @Test
  void nonNaturalBlocksEachRefuseTheSiteAndNameTheBlockAndPosition() {
    assertRefusedNames(Material.CHEST, SX, 66, SZ, "CHEST at (" + SX + ", 66, " + SZ + ")");
    assertRefusedNames(Material.FURNACE, SX - 1, 66, SZ, "FURNACE");
    assertRefusedNames(Material.OAK_PLANKS, SX + 1, 66, SZ, "OAK_PLANKS");
    assertRefusedNames(Material.OAK_DOOR, SX, 66, SZ - 1, "OAK_DOOR");
    assertRefusedNames(Material.OAK_SIGN, SX, 66, SZ + 1, "OAK_SIGN");
    assertRefusedNames(Material.SPAWNER, SX + 2, 66, SZ, "SPAWNER");
    assertRefusedNames(Material.STRIPPED_OAK_LOG, SX - 2, 66, SZ, "STRIPPED_OAK_LOG");
  }

  @Test
  void farmlandWithCropsRefusesTheSite() {
    MapBlockReader reader = flatGround()
        .put(SX, 65, SZ, Material.FARMLAND)
        .put(SX, 66, SZ, Material.WHEAT);
    SpawnZoneBuilder.Prepared prepared = builder().prepare(reader, SX, SZ, layout(),
        true, true, List.of(), null, false, false);
    assertFalse(prepared.accepted());
    assertTrue(prepared.defect().contains("FARMLAND"), prepared.defect());
    assertTrue(prepared.defect().contains("(" + SX + ", 65, " + SZ + ")"), prepared.defect());
  }

  private static void assertRefusedNames(Material material, int x, int y, int z, String expected) {
    MapBlockReader reader = flatGround().put(x, y, z, material);
    SpawnZoneBuilder.Prepared prepared = builder().prepare(reader, SX, SZ, layout(),
        true, true, List.of(), null, false, false);
    assertFalse(prepared.accepted(), material + " should refuse the site");
    assertTrue(prepared.defect().contains(expected),
        "expected the message to name " + expected + ", was: " + prepared.defect());
    assertTrue(prepared.defect().contains("not natural terrain"), prepared.defect());
  }

  @Test
  void aForcedBuildReplacesNonNaturalBlocks() {
    MapBlockReader reader = flatGround().put(SX + 1, 66, SZ, Material.OAK_PLANKS);
    SpawnZoneBuilder.Prepared prepared = builder().prepare(reader, SX, SZ, layout(),
        true, true, List.of(), null, true, false);
    assertTrue(prepared.accepted(), prepared.defect());
    assertTrue(clearedCells(prepared).contains(key(SX + 1, 66, SZ)), "planks cleared by force");
  }

  @Test
  void aForcedBuildStillRefusesAContainerThatHoldsContents() {
    MapBlockReader reader = flatGround().put(SX + 1, 66, SZ, Material.CHEST).protect(SX + 1, 66, SZ);
    SpawnZoneBuilder.Prepared refused = builder().prepare(reader, SX, SZ, layout(),
        true, true, List.of(), null, true, false);
    assertFalse(refused.accepted());
    assertTrue(refused.defect().contains("holds contents"), refused.defect());

    // The explicit second argument lets the operator replace it anyway.
    SpawnZoneBuilder.Prepared forced = builder().prepare(reader, SX, SZ, layout(),
        true, true, List.of(), null, true, true);
    assertTrue(forced.accepted(), forced.defect());
    assertTrue(clearedCells(forced).contains(key(SX + 1, 66, SZ)), "chest cleared by force items");
  }

  @Test
  void aDistantTreeEntirelyOutsideTheFootprintIsNeverTouched() {
    MapBlockReader reader = flatGround();
    int tx = SX + 12;
    int tz = SZ + 12;
    for (int y = 66; y <= 72; y++) {
      reader.put(tx, y, tz, Material.OAK_LOG);
    }
    reader.put(tx + 1, 70, tz, Material.OAK_LEAVES);
    SpawnZoneBuilder.Prepared prepared = builder().prepare(reader, SX, SZ, layout(),
        true, true, List.of(), null, false, false);
    assertTrue(prepared.accepted(), prepared.defect());
    Set<String> cleared = clearedCells(prepared);
    assertFalse(cleared.contains(key(tx, 66, tz)), "a tree outside the footprint is left alone");
    assertFalse(cleared.contains(key(tx + 1, 70, tz)), "its canopy is left alone");
  }
}
