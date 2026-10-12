package com.awepeeps.spawnkit;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

class SpawnZonePolicyTest {
  @Test
  void terrainIsClassifiedIntoAirWaterLavaVegetationNaturalOrOccupied() {
    assertEquals(SpawnZonePolicy.BlockKind.AIR, SpawnZonePolicy.classify(Material.AIR));
    assertEquals(SpawnZonePolicy.BlockKind.AIR, SpawnZonePolicy.classify(Material.CAVE_AIR));
    assertEquals(SpawnZonePolicy.BlockKind.WATER, SpawnZonePolicy.classify(Material.WATER));
    assertEquals(SpawnZonePolicy.BlockKind.LAVA, SpawnZonePolicy.classify(Material.LAVA));
    assertEquals(SpawnZonePolicy.BlockKind.VEGETATION,
        SpawnZonePolicy.classify(Material.SHORT_GRASS));
    assertEquals(SpawnZonePolicy.BlockKind.VEGETATION, SpawnZonePolicy.classify(Material.OAK_LOG));
    assertEquals(SpawnZonePolicy.BlockKind.VEGETATION, SpawnZonePolicy.classify(Material.OAK_LEAVES));
    assertEquals(SpawnZonePolicy.BlockKind.NATURAL, SpawnZonePolicy.classify(Material.STONE));
    assertEquals(SpawnZonePolicy.BlockKind.NATURAL, SpawnZonePolicy.classify(Material.GRASS_BLOCK));
    assertEquals(SpawnZonePolicy.BlockKind.OCCUPIED, SpawnZonePolicy.classify(Material.CHEST));
    assertEquals(SpawnZonePolicy.BlockKind.OCCUPIED, SpawnZonePolicy.classify(Material.OAK_PLANKS));
  }

  @Test
  void nonNaturalBlocksArePreservedAndNeverMarkedReplaceable() {
    assertFalse(SpawnZonePolicy.isReplaceableByBuild(Material.CHEST));
    assertFalse(SpawnZonePolicy.isReplaceableByBuild(Material.OAK_PLANKS));
    assertFalse(SpawnZonePolicy.isReplaceableByBuild(Material.CRAFTING_TABLE));
    assertFalse(SpawnZonePolicy.isReplaceableByBuild(Material.DIAMOND_BLOCK));
    assertTrue(SpawnZonePolicy.isReplaceableByBuild(Material.AIR));
    assertTrue(SpawnZonePolicy.isReplaceableByBuild(Material.STONE));
    assertTrue(SpawnZonePolicy.isReplaceableByBuild(Material.WATER));
    assertTrue(SpawnZonePolicy.isReplaceableByBuild(Material.SHORT_GRASS));
  }

  @Test
  void floorLevelIsChosenFromTerrainUnderTheFootprint() {
    // Flat ground: the floor sits on the highest surface.
    assertEquals(64, SpawnZonePolicy.chooseFloorLevel(64, 64, false, -64, 320, 3, 3));
    // Modest variation is levelled onto the highest surface.
    assertEquals(68, SpawnZonePolicy.chooseFloorLevel(64, 68, false, -64, 320, 3, 3));
    // Too steep: refuse.
    assertNull(SpawnZonePolicy.chooseFloorLevel(63, 68, false, -64, 320, 3, 3));
    // A liquid surface raises the floor one block above the water.
    assertEquals(65, SpawnZonePolicy.chooseFloorLevel(64, 64, true, -64, 320, 3, 3));
    // Not enough room for the core under the floor.
    assertNull(SpawnZonePolicy.chooseFloorLevel(-62, -62, false, -64, 320, 3, 3));
    // Not enough headroom above the floor.
    assertNull(SpawnZonePolicy.chooseFloorLevel(318, 318, false, -64, 320, 3, 3));
  }

  @Test
  void placementDefectsAreOrderedFromCatastrophicToCosmetic() {
    assertEquals("footprint outside the world border",
        SpawnZonePolicy.placementDefect(false, false, false, false, false, true));
    assertEquals("void beneath the footprint",
        SpawnZonePolicy.placementDefect(true, false, false, false, false, false));
    assertEquals("lava beneath the footprint",
        SpawnZonePolicy.placementDefect(false, true, false, false, false, false));
    assertEquals("footprint intersects non-natural blocks, containers or a structure",
        SpawnZonePolicy.placementDefect(false, false, true, false, false, false));
    assertEquals("terrain is too uneven to level",
        SpawnZonePolicy.placementDefect(false, false, false, true, false, false));
    assertEquals("no headroom or too close to the world floor",
        SpawnZonePolicy.placementDefect(false, false, false, false, true, false));
    assertNull(SpawnZonePolicy.placementDefect(false, false, false, false, false, false));
  }

  @Test
  void everyCoveredEventHasAProtectionDecision() {
    for (SpawnZonePolicy.GuardEvent event : SpawnZonePolicy.GuardEvent.values()) {
      // Outside the region nothing is ever denied.
      assertFalse(SpawnZonePolicy.denied(event, false, false), event + " outside region");
      assertFalse(SpawnZonePolicy.denied(event, false, true), event + " outside region (op)");
      if (event.playerExempt()) {
        assertTrue(SpawnZonePolicy.denied(event, true, false), event + " by a survival player");
        assertFalse(SpawnZonePolicy.denied(event, true, true), event + " by a creative op");
      } else {
        assertTrue(SpawnZonePolicy.denied(event, true, false), event + " by the environment");
        assertTrue(SpawnZonePolicy.denied(event, true, true),
            event + " must not be exempted for a creative op");
      }
    }
  }

  @Test
  void coveredEventsEnumerationIsTheDocumentedSet() {
    Set<String> actual = Stream.of(SpawnZonePolicy.GuardEvent.values())
        .map(Enum::name).collect(Collectors.toSet());
    assertEquals(Set.of(
        "BLOCK_BREAK", "BLOCK_PLACE", "BLOCK_MULTI_PLACE", "PLAYER_BUCKET_EMPTY",
        "ENTITY_PLACE", "HANGING_PLACE",
        "BLOCK_BURN", "BLOCK_SPREAD", "BLOCK_FORM", "BLOCK_FADE", "BLOCK_GROW",
        "BLOCK_FERTILIZE", "BLOCK_FROM_TO", "FLUID_LEVEL_CHANGE", "BLOCK_IGNITE",
        "BLOCK_DISPENSE", "BLOCK_PISTON_EXTEND", "BLOCK_PISTON_RETRACT",
        "BLOCK_EXPLODE", "ENTITY_EXPLODE", "ENTITY_CHANGE_BLOCK", "ENTITY_BLOCK_FORM",
        "STRUCTURE_GROW", "SPONGE_ABSORB"), actual);
  }

  @Test
  void regionHelpersDetectBlockAndPistonIntrusions() {
    SpawnRegion region = SpawnRegion.of(-1, -3, -1, 1, -1, 1);
    assertTrue(SpawnZonePolicy.anyInRegion(region,
        List.of(new SpawnBlockPos(9, 9, 9), new SpawnBlockPos(0, -2, 0))));
    assertFalse(SpawnZonePolicy.anyInRegion(region, List.of(new SpawnBlockPos(2, -2, 0))));
    // A piston pushes a block into the region even though the source is outside it.
    assertTrue(SpawnZonePolicy.pistonTouchesRegion(region,
        List.of(new SpawnBlockPos(0, -1, -2)), 0, 0, 1));
    assertFalse(SpawnZonePolicy.pistonTouchesRegion(region,
        List.of(new SpawnBlockPos(0, -1, -9)), 0, 0, 1));
    assertTrue(SpawnZonePolicy.pistonTouchesRegion(region,
        List.of(new SpawnBlockPos(0, -2, 0)), 0, 0, 1));
  }

  @Test
  void spawnLocationRulesKeepPersonalSpawns() {
    // Only a first ever join is redirected: a returning player keeps the player-data location.
    assertTrue(SpawnZonePolicy.overrideJoinSpawn(true));
    assertFalse(SpawnZonePolicy.overrideJoinSpawn(false));
    assertTrue(SpawnZonePolicy.overrideRespawn(false, false));
    assertFalse(SpawnZonePolicy.overrideRespawn(true, false));
    assertFalse(SpawnZonePolicy.overrideRespawn(false, true));
    assertFalse(SpawnZonePolicy.overrideRespawn(true, true));
    assertEquals(0, SpawnZonePolicy.MANAGED_RESPAWN_RADIUS);
  }

  @Test
  void multiBlockPlacementsAreDeniedWhenAnyHalfLandsInTheArrivalSpace() {
    // The arrival space above the core top, in absolute coordinates.
    SpawnRegion arrival = SpawnRegion.of(-1, 0, -1, 1, 2, 1);
    // A bed with its foot outside the zone and its head inside it.
    List<SpawnBlockPos> straddlingBed =
        List.of(new SpawnBlockPos(0, 0, 2), new SpawnBlockPos(0, 0, 1));
    assertTrue(SpawnZonePolicy.deniedPlacement(SpawnZonePolicy.GuardEvent.BLOCK_MULTI_PLACE,
        arrival, straddlingBed, false));
    assertFalse(SpawnZonePolicy.deniedPlacement(SpawnZonePolicy.GuardEvent.BLOCK_MULTI_PLACE,
        arrival, straddlingBed, true), "a creative operator may still place it");
    // A bed entirely outside the zone is allowed.
    assertFalse(SpawnZonePolicy.deniedPlacement(SpawnZonePolicy.GuardEvent.BLOCK_MULTI_PLACE,
        arrival, List.of(new SpawnBlockPos(0, 0, 3), new SpawnBlockPos(0, 0, 4)), false));
  }

  @Test
  void dispensersFiringIntoTheRegionAreBlocked() {
    SpawnRegion core = SpawnRegion.of(-1, -3, -1, 1, -1, 1);
    // A dispenser next to the core, firing at the core block it faces.
    assertTrue(SpawnZonePolicy.dispenserAffectsRegion(core, new SpawnBlockPos(0, -1, -2), 0, 0, 1));
    // A dispenser inside the core.
    assertTrue(SpawnZonePolicy.dispenserAffectsRegion(core, new SpawnBlockPos(0, -1, 0), 0, 1, 0));
    // Firing away from the core.
    assertFalse(SpawnZonePolicy.dispenserAffectsRegion(core, new SpawnBlockPos(0, -1, -2), 0, 0, -1));
  }

  @Test
  void reissuedLayoutsNeverReplaceTheSupplyChest() {    assertFalse(SpawnZonePolicy.overwriteExisting(SpawnZoneLayout.Role.CHEST));
    assertTrue(SpawnZonePolicy.overwriteExisting(SpawnZoneLayout.Role.DOOR_LOWER));
    assertTrue(SpawnZonePolicy.overwriteExisting(SpawnZoneLayout.Role.BED_HEAD));
    assertTrue(SpawnZonePolicy.overwriteExisting(SpawnZoneLayout.Role.CORE));
  }

  @Test
  void aRebuildIgnoresTheCoreAndFoundationThePluginAlreadyPlaced() {
    // spawnY 65, core-depth 3, clear-height 3: the protected zone is y = 62..67 in these columns.
    SpawnRegion protectedZone = SpawnRegion.of(-1, 62, -1, 1, 67, 1);
    SpawnRegion own = SpawnZonePolicy.ownedVolume(protectedZone, 64, 3, -64);
    assertEquals(SpawnRegion.of(-1, 60, -1, 1, 67, 1), own);
    // The core itself and the foundation the previous pass could have filled beneath the floor.
    assertTrue(own.contains(0, 62, 0));
    assertTrue(own.contains(0, 61, 0), "the deepest foundation cell must be treated as ours");
    assertTrue(own.contains(0, 60, 0), "the whole scanned depth under the core is ours");
    // Nothing outside the core columns or below the scanned band is claimed.
    assertFalse(own.contains(2, 61, 0));
    assertFalse(own.contains(0, 59, 0));
    assertFalse(own.contains(0, 68, 0));
    // A world floor above the band must not produce an inverted region, and the world floor is
    // never claimed beyond what exists.
    assertEquals(-64, SpawnZonePolicy.ownedVolume(protectedZone, -60, 3, -64).minY());
    assertEquals(62, SpawnZonePolicy.ownedVolume(protectedZone, 200, 3, -64).minY());
  }

  @Test
  void playerPlantedCropsAreOccupiedAndNeverCleared() {    // Crops only ever exist because a player planted them, so they must refuse the site instead of
    // being silently cleared (and never be counted as replaceable natural vegetation).
    for (Material crop : List.of(Material.WHEAT, Material.CARROTS, Material.POTATOES,
        Material.BEETROOTS, Material.PUMPKIN_STEM, Material.MELON_STEM, Material.FARMLAND)) {
      assertEquals(SpawnZonePolicy.BlockKind.OCCUPIED, SpawnZonePolicy.classify(crop), crop.name());
      assertFalse(SpawnZonePolicy.isClearableVegetation(crop), crop.name());
      assertFalse(SpawnZonePolicy.isReplaceableByBuild(crop), crop.name());
    }
    assertTrue(SpawnZonePolicy.isClearableVegetation(Material.SHORT_GRASS));
    assertTrue(SpawnZonePolicy.isClearableVegetation(Material.SNOW));
    assertTrue(SpawnZonePolicy.isClearableVegetation(Material.DANDELION));
  }

  @Test
  void clampPullsConfiguredValuesIntoTheirSupportedRange() {
    assertEquals(1, SpawnZonePolicy.clamp(0, 1, 8));
    assertEquals(8, SpawnZonePolicy.clamp(99, 1, 8));
    assertEquals(3, SpawnZonePolicy.clamp(3, 1, 8));
  }

  @Test
  void setupStateSeparatesFreshWorldsFromLegacyOnesAndInProgressBuilds() {
    assertEquals(SpawnZonePolicy.SetupState.CURRENT,
        SpawnZonePolicy.classifySetup(true, true, false, true));
    assertEquals(SpawnZonePolicy.SetupState.IN_PROGRESS,
        SpawnZonePolicy.classifySetup(false, true, true, false));
    assertEquals(SpawnZonePolicy.SetupState.LEGACY_UNMIGRATED,
        SpawnZonePolicy.classifySetup(false, true, false, false));
    assertEquals(SpawnZonePolicy.SetupState.LEGACY_UNMIGRATED,
        SpawnZonePolicy.classifySetup(false, true, false, false));
    assertEquals(SpawnZonePolicy.SetupState.FRESH,
        SpawnZonePolicy.classifySetup(false, false, false, false));
    // A world that recorded a current layout but whose zone is missing is rebuilt as in progress.
    assertEquals(SpawnZonePolicy.SetupState.FRESH,
        SpawnZonePolicy.classifySetup(true, true, false, false));
  }
}
