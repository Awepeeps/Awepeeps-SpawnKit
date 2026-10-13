package com.awepeeps.spawnkit;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;

class SpawnZoneLayoutTest {
  private static SpawnZoneLayout defaultLayout() {
    return new SpawnZoneLayout(3, 3, 1, 1, 1);
  }

  private static SpawnKitFurnishings allOn() {
    return SpawnKitFurnishings.defaults();
  }

  private static SpawnKitFurnishings toggles(boolean anvil, boolean furnace, boolean lodestone,
                                             boolean craftingTable, boolean banners,
                                             boolean torches) {
    return new SpawnKitFurnishings(anvil, furnace, lodestone, craftingTable, banners, torches,
        List.of(), SpawnKitFurnishings.DEFAULT_LEFT_BANNER, SpawnKitFurnishings.DEFAULT_RIGHT_BANNER);
  }

  private static List<Material> extras(int count) {
    List<Material> result = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      result.add(SpawnKitFurnishings.EXTRA_ALLOW_LIST.get(i % SpawnKitFurnishings.EXTRA_ALLOW_LIST.size()));
    }
    return result;
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
        SpawnZoneLayout.Role.ANVIL, SpawnZoneLayout.Role.FURNACE,
        SpawnZoneLayout.Role.LODESTONE, SpawnZoneLayout.Role.WALL_TORCH,
        SpawnZoneLayout.Role.EXTERIOR_TORCH, SpawnZoneLayout.Role.BANNER_LEFT,
        SpawnZoneLayout.Role.BANNER_RIGHT, SpawnZoneLayout.Role.DOORSTEP)));
  }

  @Test
  void doorIsReachableFromTheCoreTopThroughWalkableAir() {
    SpawnZoneLayout layout = defaultLayout();
    int wallRadius = layout.wallRadius();
    // The path from the spawn block to the door is two-high walkable air.
    for (int z = 1; z < wallRadius; z++) {
      assertFalse(layout.isSolid(new SpawnBlockPos(0, 0, z)),
          "door path cell 0,0," + z + " must be air");
      assertFalse(layout.isSolid(new SpawnBlockPos(0, 1, z)),
          "door path cell 0,1," + z + " must be two-high air");
    }
    assertEquals(SpawnZoneLayout.Role.DOOR_LOWER,
        layout.blockPlan().get(layout.doorLower()));
    assertEquals(SpawnZoneLayout.Role.DOOR_UPPER,
        layout.blockPlan().get(layout.doorUpper()));
    assertEquals(layout.doorLower(), layout.doorPath().get(layout.doorPath().size() - 1));
    // The exit outside the door is open and the doorstep sits one below it.
    assertFalse(layout.isSolid(layout.doorExit()));
    assertEquals(SpawnZoneLayout.Role.DOORSTEP, layout.blockPlan().get(layout.doorstep()));
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
    // Margin 2 has room for all four; margin 1 fits the pairs that are not blocked by the fixed
    // furnishings, and never overlaps them.
    for (int margin = SpawnZoneLayout.MIN_MARGIN; margin <= SpawnZoneLayout.MAX_MARGIN; margin++) {
      for (int beds = 1; beds <= SpawnZoneLayout.MAX_BEDS; beds++) {
        SpawnZoneLayout layout = new SpawnZoneLayout(3, 3, margin, beds, 1, allOn());
        assertTrue(layout.bedPlacements().size() <= beds);
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
    SpawnZoneLayout roomy = new SpawnZoneLayout(3, 3, 2, 4, 4, allOn());
    assertEquals(4, roomy.bedPlacements().size());
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
  void defaultFurnishingsMatchTheDocumentedPlan() {
    SpawnZoneLayout layout = defaultLayout();
    Map<SpawnBlockPos, SpawnZoneLayout.Role> plan = layout.blockPlan();
    // Diagram row z=-2: anvil, crafting table, chest along the back wall, lodestone in the corner.
    assertEquals(SpawnZoneLayout.Role.ANVIL, plan.get(new SpawnBlockPos(-1, 0, -2)));
    assertEquals(SpawnZoneLayout.Role.CRAFTING_TABLE, plan.get(new SpawnBlockPos(0, 0, -2)));
    assertEquals(SpawnZoneLayout.Role.CHEST, plan.get(new SpawnBlockPos(1, 0, -2)));
    assertEquals(SpawnZoneLayout.Role.LODESTONE, plan.get(new SpawnBlockPos(2, 0, -2)));
    // Diagram row z=0: the bed foot against the west wall and the furnace against the east wall.
    assertEquals(SpawnZoneLayout.Role.BED_FOOT, plan.get(new SpawnBlockPos(-2, 0, 0)));
    assertEquals(SpawnZoneLayout.Role.FURNACE, plan.get(new SpawnBlockPos(2, 0, 0)));
    // Diagram row z=1: the bed head.
    assertEquals(SpawnZoneLayout.Role.BED_HEAD, plan.get(new SpawnBlockPos(-2, 0, 1)));
    // Diagram row z=3: the two-block door.
    assertEquals(SpawnZoneLayout.Role.DOOR_LOWER, plan.get(new SpawnBlockPos(0, 0, 3)));
    assertEquals(SpawnZoneLayout.Role.DOOR_UPPER, plan.get(new SpawnBlockPos(0, 1, 3)));
    // Front elevation: torches by the door, banners further out, all on the south face at head
    // height, and the doorstep one block below the doorway.
    assertEquals(Set.of(new SpawnBlockPos(-1, 1, 4), new SpawnBlockPos(1, 1, 4)),
        Set.copyOf(layout.offsetsWithRole(SpawnZoneLayout.Role.EXTERIOR_TORCH)));
    assertEquals(Set.of(new SpawnBlockPos(-2, 1, 4)),
        Set.copyOf(layout.offsetsWithRole(SpawnZoneLayout.Role.BANNER_LEFT)));
    assertEquals(Set.of(new SpawnBlockPos(2, 1, 4)),
        Set.copyOf(layout.offsetsWithRole(SpawnZoneLayout.Role.BANNER_RIGHT)));
    assertEquals(new SpawnBlockPos(0, -1, 4), layout.doorstep());
    // Interior torches sit high on the back and side walls.
    assertEquals(Set.of(
        new SpawnBlockPos(-1, 2, -2), new SpawnBlockPos(1, 2, -2),
        new SpawnBlockPos(-2, 2, 0), new SpawnBlockPos(2, 2, 0)),
        Set.copyOf(layout.offsetsWithRole(SpawnZoneLayout.Role.WALL_TORCH)));
    // Lower door half is planned before the upper half so it is placed first.
    List<SpawnBlockPos> order = new ArrayList<>(plan.keySet());
    assertTrue(order.indexOf(new SpawnBlockPos(0, 0, 3))
        < order.indexOf(new SpawnBlockPos(0, 1, 3)));
  }

  @Test
  void everyFurnishingIsOutsideProtectedAndNeverOverlapsForEveryShape() {
    for (int margin = SpawnZoneLayout.MIN_MARGIN; margin <= SpawnZoneLayout.MAX_MARGIN; margin++) {
      for (int beds = SpawnZoneLayout.MIN_BEDS; beds <= SpawnZoneLayout.MAX_BEDS; beds++) {
        for (int chests = SpawnZoneLayout.MIN_CHESTS; chests <= SpawnZoneLayout.MAX_CHESTS; chests++) {
          SpawnZoneLayout layout = new SpawnZoneLayout(3, 3, margin, beds, chests, allOn());
          Set<SpawnBlockPos> seen = new java.util.HashSet<>();
          for (var entry : layout.furnishPlan()) {
            SpawnBlockPos pos = entry.getKey();
            assertFalse(layout.protectedRegion().contains(pos),
                entry.getValue() + " at " + pos + " must be outside the protected zone");
            assertTrue(seen.add(pos), "two pieces share the cell " + pos);
            SpawnZoneLayout.Role role = entry.getValue();
            boolean exterior = role == SpawnZoneLayout.Role.EXTERIOR_TORCH
                || role == SpawnZoneLayout.Role.BANNER_LEFT
                || role == SpawnZoneLayout.Role.BANNER_RIGHT
                || role == SpawnZoneLayout.Role.DOORSTEP;
            if (!exterior) {
              assertTrue(layout.footprint().containsColumn(pos.x(), pos.z()),
                  role + " at " + pos + " must be inside the house footprint");
            } else {
              assertEquals(layout.exteriorZ(), pos.z(),
                  role + " must sit on the outer face of the south wall");
            }
          }
        }
      }
    }
  }

  @Test
  void doorRouteStaysTwoHighAirWithEveryFurnishingAndMaxExtras() {
    for (int margin = SpawnZoneLayout.MIN_MARGIN; margin <= SpawnZoneLayout.MAX_MARGIN; margin++) {
      SpawnZoneLayout base = new SpawnZoneLayout(3, 3, margin, 1, 1, allOn());
      int max = base.maxExtras();
      assertTrue(max >= 0);
      SpawnKitFurnishings extras = new SpawnKitFurnishings(true, true, true, true, true, true,
          extras(max), SpawnKitFurnishings.DEFAULT_LEFT_BANNER,
          SpawnKitFurnishings.DEFAULT_RIGHT_BANNER);
      SpawnZoneLayout full = new SpawnZoneLayout(3, 3, margin, 1, 1, extras);
      assertEquals(max, full.extraOffsets().size());
      assertEquals(0, full.maxExtras());
      for (int z = 1; z <= full.innerRadius(); z++) {
        assertFalse(full.isSolid(new SpawnBlockPos(0, 0, z)), "door route 0,0," + z + " must be air");
        assertFalse(full.isSolid(new SpawnBlockPos(0, 1, z)), "door route 0,1," + z + " must be air");
      }
    }
  }

  @Test
  void exteriorBannersAndTorchesAreMountedFacingOutward() {
    for (int margin = SpawnZoneLayout.MIN_MARGIN; margin <= SpawnZoneLayout.MAX_MARGIN; margin++) {
      SpawnZoneLayout layout = new SpawnZoneLayout(3, 3, margin, 1, 1, allOn());
      List<SpawnBlockPos> banners = new ArrayList<>();
      banners.addAll(layout.offsetsWithRole(SpawnZoneLayout.Role.BANNER_LEFT));
      banners.addAll(layout.offsetsWithRole(SpawnZoneLayout.Role.BANNER_RIGHT));
      banners.addAll(layout.offsetsWithRole(SpawnZoneLayout.Role.EXTERIOR_TORCH));
      for (SpawnBlockPos piece : banners) {
        assertEquals(BlockFace.SOUTH, layout.exteriorFacing(piece));
        assertNotEquals(0, piece.x(), "an exterior piece must not sit in the doorway column");
        assertNotEquals(layout.doorstep(), piece, "an exterior piece must not sit on the doorstep");
        SpawnBlockPos mounting = layout.exteriorMounting(piece);
        assertEquals(SpawnZoneLayout.Role.WALL, layout.blockPlan().get(mounting),
            piece + " must be mounted on a solid wall at " + mounting);
      }
      SpawnBlockPos left = layout.offsetsWithRole(SpawnZoneLayout.Role.BANNER_LEFT).get(0);
      SpawnBlockPos right = layout.offsetsWithRole(SpawnZoneLayout.Role.BANNER_RIGHT).get(0);
      assertTrue(left.x() < 0, "the left banner is west of the door");
      assertTrue(right.x() > 0, "the right banner is east of the door");
    }
  }

  @Test
  void anvilRestsOnTheFloorChestHasAirAboveAndFurnishingsAreReachable() {
    for (int margin = SpawnZoneLayout.MIN_MARGIN; margin <= SpawnZoneLayout.MAX_MARGIN; margin++) {
      SpawnZoneLayout layout = new SpawnZoneLayout(3, 3, margin, 1, 1, allOn());
      SpawnBlockPos anvil = layout.anvilOffset();
      assertEquals(SpawnZoneLayout.Role.FLOOR, layout.blockPlan().get(anvil.offset(0, -1, 0)),
          "an anvil is a gravity block and must rest on the floor");
      for (SpawnBlockPos chest : layout.chestOffsets()) {
        assertFalse(layout.isSolid(chest.offset(0, 1, 0)),
            "the chest at " + chest + " needs air above so its lid can open");
      }
      for (SpawnZoneLayout.Role role : List.of(SpawnZoneLayout.Role.ANVIL,
          SpawnZoneLayout.Role.FURNACE, SpawnZoneLayout.Role.CRAFTING_TABLE,
          SpawnZoneLayout.Role.LODESTONE)) {
        for (SpawnBlockPos pos : layout.offsetsWithRole(role)) {
          assertTrue(hasAdjacentWalkable(layout, pos),
              role + " at " + pos + " must be reachable from a walkable cell");
        }
      }
    }
  }

  private static boolean hasAdjacentWalkable(SpawnZoneLayout layout, SpawnBlockPos pos) {
    for (BlockFace face : List.of(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)) {
      SpawnBlockPos neighbour =
          pos.offset(face.getModX(), face.getModY(), face.getModZ());
      if (!layout.isSolid(neighbour)) return true;
    }
    return false;
  }

  @Test
  void turningOffAToggleRemovesExactlyThatPiece() {
    SpawnZoneLayout off = new SpawnZoneLayout(3, 3, 1, 1, 1, toggles(
        false, false, false, false, false, false));
    assertTrue(off.offsetsWithRole(SpawnZoneLayout.Role.ANVIL).isEmpty());
    assertTrue(off.offsetsWithRole(SpawnZoneLayout.Role.FURNACE).isEmpty());
    assertTrue(off.offsetsWithRole(SpawnZoneLayout.Role.LODESTONE).isEmpty());
    assertTrue(off.offsetsWithRole(SpawnZoneLayout.Role.CRAFTING_TABLE).isEmpty());
    assertTrue(off.offsetsWithRole(SpawnZoneLayout.Role.BANNER_LEFT).isEmpty());
    assertTrue(off.offsetsWithRole(SpawnZoneLayout.Role.BANNER_RIGHT).isEmpty());
    assertTrue(off.offsetsWithRole(SpawnZoneLayout.Role.EXTERIOR_TORCH).isEmpty());
    // The shell, bed, chest, doorstep and interior torches remain.
    assertFalse(off.offsetsWithRole(SpawnZoneLayout.Role.BED_FOOT).isEmpty());
    assertFalse(off.offsetsWithRole(SpawnZoneLayout.Role.CHEST).isEmpty());
    assertFalse(off.offsetsWithRole(SpawnZoneLayout.Role.WALL_TORCH).isEmpty());
    assertEquals(SpawnZoneLayout.Role.DOORSTEP, off.blockPlan().get(off.doorstep()));

    SpawnZoneLayout onlyAnvilOff = new SpawnZoneLayout(3, 3, 1, 1, 1,
        toggles(false, true, true, true, true, true));
    assertEquals(1, onlyAnvilOff.offsetsWithRole(SpawnZoneLayout.Role.FURNACE).size());
    assertEquals(1, onlyAnvilOff.offsetsWithRole(SpawnZoneLayout.Role.LODESTONE).size());
    assertEquals(1, onlyAnvilOff.offsetsWithRole(SpawnZoneLayout.Role.CRAFTING_TABLE).size());
    assertTrue(onlyAnvilOff.offsetsWithRole(SpawnZoneLayout.Role.ANVIL).isEmpty());
  }

  @Test
  void extrasFillFreeRingCellsAndTooManyAreTruncated() {
    SpawnZoneLayout base = new SpawnZoneLayout(3, 3, 1, 1, 1, allOn());
    int max = base.maxExtras();
    SpawnKitFurnishings furnishing = new SpawnKitFurnishings(true, true, true, true, true, true,
        extras(max), SpawnKitFurnishings.DEFAULT_LEFT_BANNER, SpawnKitFurnishings.DEFAULT_RIGHT_BANNER);
    SpawnZoneLayout filled = new SpawnZoneLayout(3, 3, 1, 1, 1, furnishing);
    assertEquals(max, filled.extraOffsets().size());
    for (SpawnBlockPos extra : filled.extraOffsets()) {
      assertNotNull(filled.extraMaterial(extra));
      assertFalse(filled.protectedRegion().contains(extra));
    }

    SpawnKitFurnishings tooMany = new SpawnKitFurnishings(true, true, true, true, true, true,
        extras(max + 3), SpawnKitFurnishings.DEFAULT_LEFT_BANNER,
        SpawnKitFurnishings.DEFAULT_RIGHT_BANNER);
    SpawnZoneLayout truncated = new SpawnZoneLayout(3, 3, 1, 1, 1, tooMany);
    assertEquals(max, truncated.extraOffsets().size());
  }

  @Test
  void everyInteriorWallTorchIsMountedOnASolidWall() {
    for (int margin = SpawnZoneLayout.MIN_MARGIN; margin <= SpawnZoneLayout.MAX_MARGIN; margin++) {
      SpawnZoneLayout layout = new SpawnZoneLayout(3, 3, margin, 1, 1, allOn());
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
