package com.awepeeps.spawnkit;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.block.BlockFace;
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

  @Test
  void clampsAnyConfiguredDimensionIntoAUsableLayout() {
    assertEquals(1, SpawnZoneLayout.clampCoreDepth(0));
    assertEquals(1, SpawnZoneLayout.clampCoreDepth(-5));
    assertEquals(8, SpawnZoneLayout.clampCoreDepth(99));
    assertEquals(3, SpawnZoneLayout.clampCoreDepth(3));
    assertEquals(2, SpawnZoneLayout.clampClearHeight(1));
    assertEquals(8, SpawnZoneLayout.clampClearHeight(100));
    assertEquals(1, SpawnZoneLayout.clampMargin(0));
    assertEquals(2, SpawnZoneLayout.clampMargin(3));
    assertEquals(1, SpawnZoneLayout.clampBeds(0));
    assertEquals(4, SpawnZoneLayout.clampBeds(9));
    assertEquals(1, SpawnZoneLayout.clampChests(-1));
    assertEquals(4, SpawnZoneLayout.clampChests(9));

    // Values that would previously refuse to start must build a usable layout instead.
    SpawnZoneLayout clamped = new SpawnZoneLayout(
        SpawnZoneLayout.clampCoreDepth(Integer.MIN_VALUE),
        SpawnZoneLayout.clampClearHeight(Integer.MAX_VALUE),
        SpawnZoneLayout.clampMargin(0),
        SpawnZoneLayout.clampBeds(99),
        SpawnZoneLayout.clampChests(99));
    assertEquals(1, clamped.coreDepth());
    assertEquals(8, clamped.clearHeight());
    assertEquals(1, clamped.margin());
    assertEquals(4, clamped.beds());
    assertEquals(4, clamped.chests());
    assertNotNull(SpawnZoneLayout.defaults());
  }

  @Test
  void furnishingsMatchTheDocumentedFloorPlan() {
    SpawnZoneLayout layout = defaultLayout();
    Map<SpawnBlockPos, SpawnZoneLayout.Role> plan = layout.blockPlan();
    // Diagram row z=-2: the crafting table against the north wall.
    assertEquals(SpawnZoneLayout.Role.CRAFTING_TABLE, plan.get(new SpawnBlockPos(0, 0, -2)));
    // Diagram row z=0: the chest against the east wall.
    assertEquals(SpawnZoneLayout.Role.CHEST, plan.get(new SpawnBlockPos(2, 0, 0)));
    // Diagram row z=2: the bed foot and head.
    assertEquals(SpawnZoneLayout.Role.BED_FOOT, plan.get(new SpawnBlockPos(1, 0, 2)));
    assertEquals(SpawnZoneLayout.Role.BED_HEAD, plan.get(new SpawnBlockPos(2, 0, 2)));
    // Diagram row z=3: the two-block door.
    assertEquals(SpawnZoneLayout.Role.DOOR_LOWER, plan.get(new SpawnBlockPos(0, 0, 3)));
    assertEquals(SpawnZoneLayout.Role.DOOR_UPPER, plan.get(new SpawnBlockPos(0, 1, 3)));
    // Exactly the documented torches, all at head height on the walls.
    assertEquals(Set.of(
        new SpawnBlockPos(1, 1, 2), new SpawnBlockPos(-1, 1, 2),
        new SpawnBlockPos(1, 1, -2), new SpawnBlockPos(-1, 1, -2),
        new SpawnBlockPos(2, 1, 0), new SpawnBlockPos(-2, 1, 0)),
        Set.copyOf(layout.offsetsWithRole(SpawnZoneLayout.Role.WALL_TORCH)));
    // Lower door half is planned before the upper half so it is placed first.
    List<SpawnBlockPos> order = new ArrayList<>(plan.keySet());
    assertTrue(order.indexOf(new SpawnBlockPos(0, 0, 3))
        < order.indexOf(new SpawnBlockPos(0, 1, 3)));
    assertTrue(order.indexOf(new SpawnBlockPos(1, 0, 2))
        < order.indexOf(new SpawnBlockPos(2, 0, 2)));
  }

  @Test
  void everyWallTorchIsMountedOnASolidWall() {
    for (int margin = SpawnZoneLayout.MIN_MARGIN; margin <= SpawnZoneLayout.MAX_MARGIN; margin++) {
      SpawnZoneLayout layout = new SpawnZoneLayout(3, 3, margin, 1, 1);
      for (SpawnBlockPos torch : layout.offsetsWithRole(SpawnZoneLayout.Role.WALL_TORCH)) {
        // A torch faces away from the wall it is mounted on, so the mounting block is behind it.
        BlockFace facing = layout.wallTorchFacing(torch);
        SpawnBlockPos mounting = torch.offset(
            -facing.getModX(), -facing.getModY(), -facing.getModZ());
        assertEquals(SpawnZoneLayout.Role.WALL, layout.blockPlan().get(mounting),
            "torch at " + torch + " must be mounted on a wall at " + mounting);
      }
    }
  }
}
