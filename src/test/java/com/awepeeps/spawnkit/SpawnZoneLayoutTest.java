package com.awepeeps.spawnkit;

import static org.junit.jupiter.api.Assertions.*;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SpawnZoneLayoutTest {
  private static SpawnZoneLayout defaultLayout() {
    return new SpawnZoneLayout(3, 3, 1, 1, 1);
  }

  @Test
  void coreIsThreeByThreeByDepthAndCentredOnSpawn() {
    SpawnZoneLayout layout = defaultLayout();
    SpawnRegion core = layout.core();
    assertEquals(-1, core.minX());
    assertEquals(1, core.maxX());
    assertEquals(-1, core.minZ());
    assertEquals(1, core.maxZ());
    assertEquals(-3, core.minY());
    assertEquals(-1, core.maxY());
    assertEquals(27, core.volume());

    var coreOffsets = layout.offsetsWithRole(SpawnZoneLayout.Role.CORE);
    assertEquals(27, coreOffsets.size());
    for (SpawnBlockPos pos : coreOffsets) {
      assertTrue(core.contains(pos), pos + " must lie in the core");
    }
    // Exactly the 3x3 at each of the three depths.
    for (int y = -3; y <= -1; y++) {
      for (int x = -1; x <= 1; x++) {
        for (int z = -1; z <= 1; z++) {
          assertEquals(SpawnZoneLayout.Role.CORE, layout.blockPlan().get(new SpawnBlockPos(x, y, z)));
        }
      }
    }
  }

  @Test
  void arrivalSpaceAboveCoreIsEntirelyAir() {
    SpawnZoneLayout layout = defaultLayout();
    for (int y = 0; y < layout.clearHeight(); y++) {
      for (int x = -1; x <= 1; x++) {
        for (int z = -1; z <= 1; z++) {
          assertFalse(layout.isSolid(new SpawnBlockPos(x, y, z)),
              "arrival cell " + x + "," + y + "," + z + " must be air");
        }
      }
    }
    assertTrue(layout.protectedRegion().contains(0, 0, 0));
    assertTrue(layout.protectedRegion().contains(0, layout.clearHeight() - 1, 0));
    assertFalse(layout.protectedRegion().contains(0, layout.clearHeight(), 0));
  }

  @Test
  void everyHouseBlockAndFurnishingLiesOutsideTheProtectedZone() {
    SpawnZoneLayout layout = defaultLayout();
    SpawnRegion protectedRegion = layout.protectedRegion();
    for (Map.Entry<SpawnBlockPos, SpawnZoneLayout.Role> entry : layout.blockPlan().entrySet()) {
      if (entry.getValue() == SpawnZoneLayout.Role.CORE) continue;
      assertFalse(protectedRegion.contains(entry.getKey()),
          entry.getValue() + " at " + entry.getKey() + " must be outside the protected zone");
    }
    Set<SpawnZoneLayout.Role> roles = EnumSet.noneOf(SpawnZoneLayout.Role.class);
    roles.addAll(layout.blockPlan().values());
    assertTrue(roles.containsAll(EnumSet.of(
        SpawnZoneLayout.Role.FLOOR, SpawnZoneLayout.Role.WALL, SpawnZoneLayout.Role.ROOF,
        SpawnZoneLayout.Role.DOOR_LOWER, SpawnZoneLayout.Role.DOOR_UPPER,
        SpawnZoneLayout.Role.BED_FOOT, SpawnZoneLayout.Role.BED_HEAD,
        SpawnZoneLayout.Role.CHEST, SpawnZoneLayout.Role.CRAFTING_TABLE,
        SpawnZoneLayout.Role.WALL_TORCH)));
  }

  @Test
  void doorIsReachableFromTheCoreTopThroughWalkableAir() {
    SpawnZoneLayout layout = defaultLayout();
    int wallRadius = layout.wallRadius();
    // The path from the spawn block to the door is walkable air.
    for (int z = 1; z < wallRadius; z++) {
      assertFalse(layout.isSolid(new SpawnBlockPos(0, 0, z)),
          "door path cell 0,0," + z + " must be air");
    }
    assertEquals(SpawnZoneLayout.Role.DOOR_LOWER,
        layout.blockPlan().get(layout.doorLower()));
    assertEquals(SpawnZoneLayout.Role.DOOR_UPPER,
        layout.blockPlan().get(layout.doorUpper()));
    assertEquals(layout.doorLower(), layout.doorPath().get(layout.doorPath().size() - 1));
    // The exit outside the door is open and inside the footprint.
    assertFalse(layout.isSolid(layout.doorExit()));
  }

  @Test
  void wallsAndRoofAreClosedApartFromTheDoor() {
    SpawnZoneLayout layout = defaultLayout();
    int wallRadius = layout.wallRadius();
    for (int y = 0; y <= layout.wallHeight() - 1; y++) {
      for (int x = -wallRadius; x <= wallRadius; x++) {
        for (int z = -wallRadius; z <= wallRadius; z++) {
          boolean perimeter = Math.abs(x) == wallRadius || Math.abs(z) == wallRadius;
          if (!perimeter) continue;
          SpawnZoneLayout.Role role = layout.blockPlan().get(new SpawnBlockPos(x, y, z));
          assertNotNull(role, "wall cell " + x + "," + y + "," + z + " must be closed");
          boolean doorCell = x == 0 && z == wallRadius && y <= 1;
          if (doorCell) {
            assertEquals(y == 0
                ? SpawnZoneLayout.Role.DOOR_LOWER : SpawnZoneLayout.Role.DOOR_UPPER, role);
          } else {
            assertEquals(SpawnZoneLayout.Role.WALL, role);
          }
        }
      }
    }
    for (int x = -wallRadius; x <= wallRadius; x++) {
      for (int z = -wallRadius; z <= wallRadius; z++) {
        assertEquals(SpawnZoneLayout.Role.ROOF,
            layout.blockPlan().get(new SpawnBlockPos(x, layout.wallHeight(), z)));
      }
    }
  }

  @Test
  void regionContainmentIsCorrectAtEveryBoundary() {
    SpawnRegion region = SpawnRegion.of(-1, -3, -1, 1, -1, 1);
    assertTrue(region.contains(-1, -3, -1));
    assertTrue(region.contains(1, -1, 1));
    assertTrue(region.contains(0, -2, 0));
    assertFalse(region.contains(-2, -2, 0));
    assertFalse(region.contains(2, -2, 0));
    assertFalse(region.contains(0, -4, 0));
    assertFalse(region.contains(0, 0, 0));
    assertFalse(region.contains(0, -2, -2));
    assertFalse(region.contains(0, -2, 2));
    assertEquals(27, region.volume());
    assertEquals(SpawnRegion.of(0, -2, 0, 2, 0, 2), region.translated(1, 1, 1));
  }

  @Test
  void bedCountIsConfigurableAndEachBedStaysOutsideTheProtectedZone() {
    for (int beds = 1; beds <= SpawnZoneLayout.MAX_BEDS; beds++) {
      SpawnZoneLayout layout = new SpawnZoneLayout(3, 3, 1, beds, 1);
      assertEquals(beds, layout.bedPlacements().size());
      for (SpawnZoneLayout.BedPlacement bed : layout.bedPlacements()) {
        SpawnBlockPos head = bed.head();
        assertEquals(SpawnZoneLayout.Role.BED_FOOT, layout.blockPlan().get(bed.foot()));
        assertEquals(SpawnZoneLayout.Role.BED_HEAD, layout.blockPlan().get(head));
        assertEquals(bed.foot().y(), head.y());
        assertEquals(1, Math.abs(head.x() - bed.foot().x()) + Math.abs(head.z() - bed.foot().z()));
        assertFalse(layout.protectedRegion().contains(bed.foot()));
        assertFalse(layout.protectedRegion().contains(head));
      }
    }
  }

  @Test
  void dimensionsAreConfigurableAndMoveTheGeometry() {
    SpawnZoneLayout layout = new SpawnZoneLayout(5, 4, 2, 2, 2);
    assertEquals(4, layout.wallRadius());
    assertEquals(4, layout.wallHeight());
    assertEquals(3, layout.innerRadius());
    assertEquals(-5, layout.core().minY());
    assertEquals(-1, layout.core().maxY());
    assertEquals(45, layout.core().volume());
    assertEquals(0, layout.arrival().minY());
    assertEquals(3, layout.arrival().maxY());
    assertEquals(-5, layout.protectedRegion().minY());
    assertEquals(3, layout.protectedRegion().maxY());
    // A taller clear height also raises the walls so the interior stays enclosed.
    assertEquals(4, layout.wallHeight());
  }

  @Test
  void layoutRejectsOutOfRangeDimensions() {
    assertThrows(IllegalArgumentException.class, () -> new SpawnZoneLayout(0, 3, 1, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> new SpawnZoneLayout(3, 1, 1, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> new SpawnZoneLayout(3, 3, 0, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> new SpawnZoneLayout(3, 3, 1, 0, 1));
    assertThrows(IllegalArgumentException.class, () -> new SpawnZoneLayout(3, 3, 1, 1, 5));
    assertTrue(SpawnZoneLayout.validCoreDepth(3));
    assertTrue(SpawnZoneLayout.validClearHeight(2));
    assertTrue(SpawnZoneLayout.validMargin(2));
  }
}
