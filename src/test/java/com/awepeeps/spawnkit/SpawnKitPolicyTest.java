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
  void asyncChunkBoundsCoverTheWholeFootprintAndBackOff() {
    SpawnKitPolicy.ChunkBounds bounds = SpawnKitPolicy.chunkBounds(31, -9, 36);
    assertEquals(-1, bounds.minX());
    assertEquals(4, bounds.maxX());
    assertEquals(-3, bounds.minZ());
    assertEquals(1, bounds.maxZ());
    assertEquals(30, SpawnKitPolicy.retryDelaySeconds(0));
    assertEquals(60, SpawnKitPolicy.retryDelaySeconds(1));
    assertEquals(120, SpawnKitPolicy.retryDelaySeconds(2));
    assertEquals(1800, SpawnKitPolicy.retryDelaySeconds(10));
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
