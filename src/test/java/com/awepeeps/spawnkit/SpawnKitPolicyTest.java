package com.awepeeps.spawnkit;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

class SpawnKitPolicyTest {
  @Test
  void featureGateUsesInclusiveLocalDateWindow() {
    assertTrue(SpawnKitPolicy.isActive(
        true, "2026-05-01", "2026-05-02", "America/New_York",
        Instant.parse("2026-05-01T04:00:00Z")));
    assertFalse(SpawnKitPolicy.isActive(
        true, "2026-05-01", "2026-05-02", "America/New_York",
        Instant.parse("2026-05-01T03:59:59Z")));
    assertFalse(SpawnKitPolicy.isActive(
        true, "2026-05-01", "2026-05-02", "America/New_York",
        Instant.parse("2026-05-03T04:00:00Z")));
    assertFalse(SpawnKitPolicy.isActive(
        false, "2026-05-01", "2026-05-02", "America/New_York",
        Instant.parse("2026-05-01T12:00:00Z")));
    assertFalse(SpawnKitPolicy.isActive(
        true, "", "2026-05-02", "UTC", Instant.parse("2026-05-01T12:00:00Z")));
    assertFalse(SpawnKitPolicy.isActive(
        true, "not-a-date", "2026-05-02", "UTC", Instant.parse("2026-05-01T12:00:00Z")));
    assertFalse(SpawnKitPolicy.isActive(
        true, "2026-05-01", "2026-05-02", "invalid-zone",
        Instant.parse("2026-05-01T12:00:00Z")));
    assertFalse(SpawnKitPolicy.isActive(
        true, "2026-05-03", "2026-05-02", "UTC", Instant.parse("2026-05-02T12:00:00Z")));
  }

  @Test
  void chestSearchRespectsSpawnProtectionAndBoundedWorldHeight() {
    assertFalse(SpawnKitPolicy.outsideProtection(16, 0, 16));
    assertFalse(SpawnKitPolicy.outsideProtection(16, 16, 16));
    assertTrue(SpawnKitPolicy.outsideProtection(17, 0, 16));
    assertEquals(18, SpawnKitPolicy.searchRadius(16, 8));
    assertEquals(64, SpawnKitPolicy.searchRadius(62, 8));
    assertEquals(-1, SpawnKitPolicy.searchRadius(64, 8));
    assertEquals(65, SpawnKitPolicy.surfacePlacementY(64, 64, -64, 320));
    assertNull(SpawnKitPolicy.surfacePlacementY(80, 64, -64, 320));
    assertNull(SpawnKitPolicy.surfacePlacementY(254, 240, 0, 256));
  }

  @Test
  void placementRequiresSafeUnoccupiedGroundAndChestClearance() {
    assertTrue(SpawnKitPolicy.safeFloor(true, false, false, false));
    assertFalse(SpawnKitPolicy.safeFloor(false, false, false, false));
    assertFalse(SpawnKitPolicy.safeFloor(true, true, false, false));
    assertFalse(SpawnKitPolicy.safeFloor(true, false, true, false));
    assertFalse(SpawnKitPolicy.safeFloor(true, false, false, true));
    assertTrue(SpawnKitPolicy.safePlacement(true, true, true, false));
    assertFalse(SpawnKitPolicy.safePlacement(false, true, true, false));
    assertFalse(SpawnKitPolicy.safePlacement(true, false, true, false));
    assertFalse(SpawnKitPolicy.safePlacement(true, true, false, false));
    assertFalse(SpawnKitPolicy.safePlacement(true, true, true, true));
  }

  @Test
  void shelterCanLevelSmallTerrainVariationsButRejectsSteepSites() {
    assertTrue(SpawnKitPolicy.canLevelShelterTerrain(64, 68));
    assertFalse(SpawnKitPolicy.canLevelShelterTerrain(63, 68));
    assertFalse(SpawnKitPolicy.canLevelShelterTerrain(68, 64));
  }

  @Test
  void shelterSearchRadiusIsBoundedAndConfigurable() {
    assertFalse(SpawnKitPolicy.validShelterSearchRadius(7));
    assertTrue(SpawnKitPolicy.validShelterSearchRadius(8));
    assertTrue(SpawnKitPolicy.validShelterSearchRadius(16));
    assertTrue(SpawnKitPolicy.validShelterSearchRadius(32));
    assertFalse(SpawnKitPolicy.validShelterSearchRadius(33));
  }

  @Test
  void configuredKitItemsAreParsedAndStackAmountsPreserved() {
    SpawnKitContents kit = new SpawnKitContents(List.of(
        Map.of("material", "PAPER", "amount", 99),
        Map.of("material", "EMERALD", "amount", 1)), SpawnKitPolicyTest::resolve);

    assertEquals(List.of(
        new SpawnKitContents.Stack(Material.PAPER, 99),
        new SpawnKitContents.Stack(Material.EMERALD, 1)), kit.stacks());
    assertEquals(2, kit.stacks().size());
  }

  @Test
  void configuredKitMustFitSingleChestAndUseValidItems() {
    assertThrows(IllegalArgumentException.class,
        () -> new SpawnKitContents(List.of(Map.of("material", "NOT_A_MATERIAL", "amount", 1)),
            SpawnKitPolicyTest::resolve));
    assertThrows(IllegalArgumentException.class,
        () -> new SpawnKitContents(List.of(Map.of("material", "AIR", "amount", 1)),
            SpawnKitPolicyTest::resolve));
    assertThrows(IllegalArgumentException.class,
        () -> new SpawnKitContents(List.of(Map.of("material", "PAPER", "amount", 1729)),
            SpawnKitPolicyTest::resolve));
    assertThrows(IllegalArgumentException.class,
        () -> new SpawnKitContents(List.of(Map.of("material", "STONE", "amount", 0)),
            SpawnKitPolicyTest::resolve));
    assertTrue(new SpawnKitContents(List.of(), SpawnKitPolicyTest::resolve).isEmpty());
  }

  @Test
  void serverCanSpecifyItsOwnKitItems() {
    SpawnKitContents kit = new SpawnKitContents(List.of(
        Map.of("material", "PAPER", "amount", 3),
        Map.of("material", "EMERALD", "amount", 9),
        Map.of("material", "STONE", "amount", 65)), SpawnKitPolicyTest::resolve);
    Set<Material> materials = kit.stacks().stream()
        .map(SpawnKitContents.Stack::material).collect(Collectors.toSet());
    assertTrue(materials.containsAll(Set.of(
        Material.PAPER, Material.EMERALD, Material.STONE)));
    assertThrows(IllegalArgumentException.class, () -> new SpawnKitContents(List.of(
        Map.of("material", "PAPER", "amount", 1728),
        Map.of("material", "EMERALD", "amount", 1)), SpawnKitPolicyTest::resolve));
  }

  private static SpawnKitContents.ItemDefinition resolve(String name) {
    return switch (name) {
      case "PAPER" -> new SpawnKitContents.ItemDefinition(Material.PAPER, 64, true);
      case "EMERALD" -> new SpawnKitContents.ItemDefinition(Material.EMERALD, 64, true);
      case "STONE" -> new SpawnKitContents.ItemDefinition(Material.STONE, 64, true);
      case "AIR" -> new SpawnKitContents.ItemDefinition(Material.AIR, 64, false);
      default -> null;
    };
  }
}
