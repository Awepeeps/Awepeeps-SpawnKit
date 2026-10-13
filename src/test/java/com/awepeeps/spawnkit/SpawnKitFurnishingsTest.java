package com.awepeeps.spawnkit;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class SpawnKitFurnishingsTest {
  private final List<String> warnings = new ArrayList<>();

  private SpawnKitFurnishings parse(Map<String, Object> values) {
    YamlConfiguration config = new YamlConfiguration();
    for (Map.Entry<String, Object> entry : values.entrySet()) {
      config.set("furnishings." + entry.getKey(), entry.getValue());
    }
    ConfigurationSection section = config.getConfigurationSection("furnishings");
    return SpawnKitFurnishings.parse(section, warnings::add);
  }

  @Test
  void aMissingSectionUsesEveryDefault() {
    SpawnKitFurnishings furnishings = SpawnKitFurnishings.parse(null, warnings::add);
    assertTrue(furnishings.anvil());
    assertTrue(furnishings.furnace());
    assertTrue(furnishings.lodestone());
    assertTrue(furnishings.craftingTable());
    assertTrue(furnishings.exteriorBanners());
    assertTrue(furnishings.exteriorTorches());
    assertTrue(furnishings.extras().isEmpty());
    assertEquals(SpawnKitFurnishings.DEFAULT_LEFT_BANNER, furnishings.leftBanner());
    assertEquals(SpawnKitFurnishings.DEFAULT_RIGHT_BANNER, furnishings.rightBanner());
    assertTrue(warnings.isEmpty());
  }

  @Test
  void anEmptySectionStillUsesEveryDefault() {
    SpawnKitFurnishings furnishings = parse(new LinkedHashMap<>());
    assertTrue(furnishings.anvil());
    assertTrue(furnishings.extras().isEmpty());
    assertEquals("WHITE", furnishings.leftBanner());
    assertEquals("RED", furnishings.rightBanner());
  }

  @Test
  void eachToggleCanBeTurnedOffIndependently() {
    SpawnKitFurnishings furnishings = parse(Map.of(
        "anvil", false,
        "furnace", false,
        "lodestone", false,
        "crafting-table", false,
        "exterior-banners", false,
        "exterior-torches", false));
    assertFalse(furnishings.anvil());
    assertFalse(furnishings.furnace());
    assertFalse(furnishings.lodestone());
    assertFalse(furnishings.craftingTable());
    assertFalse(furnishings.exteriorBanners());
    assertFalse(furnishings.exteriorTorches());
    assertTrue(warnings.isEmpty());
  }

  @Test
  void unknownOrDisallowedExtrasAreSkippedWithASingleWarning() {
    SpawnKitFurnishings furnishings = parse(Map.of("extras",
        List.of("BARREL", "DIAMOND_BLOCK", "CHEST", "NOT_A_MATERIAL", "smithing_table")));
    assertEquals(List.of(Material.BARREL, Material.SMITHING_TABLE), furnishings.extras());
    assertEquals(1, warnings.size(), "exactly one summary warning");
  }

  @Test
  void bannedExtrasAreNeverAccepted() {
    for (Material banned : List.of(Material.CHEST, Material.TRAPPED_CHEST, Material.SHULKER_BOX,
        Material.REDSTONE_BLOCK, Material.WATER, Material.SAND, Material.TNT, Material.SPAWNER,
        Material.HOPPER, Material.DISPENSER)) {
      assertFalse(SpawnKitFurnishings.EXTRA_ALLOW_LIST.contains(banned), banned.name());
    }
    assertTrue(SpawnKitFurnishings.EXTRA_ALLOW_LIST.contains(Material.BARREL));
    assertTrue(SpawnKitFurnishings.EXTRA_ALLOW_LIST.contains(Material.ENDER_CHEST));
  }

  @Test
  void bannerColoursAreValidatedAndFallBackToTheDefaults() {
    assertTrue(SpawnKitFurnishings.isValidBannerColour("WHITE"));
    assertTrue(SpawnKitFurnishings.isValidBannerColour("red"));
    assertFalse(SpawnKitFurnishings.isValidBannerColour("RAINBOW"));
    assertFalse(SpawnKitFurnishings.isValidBannerColour(""));
    assertFalse(SpawnKitFurnishings.isValidBannerColour(null));
    assertEquals(Material.WHITE_WALL_BANNER, SpawnKitFurnishings.bannerMaterial("WHITE"));
    assertEquals(Material.RED_WALL_BANNER, SpawnKitFurnishings.bannerMaterial("RED"));

    SpawnKitFurnishings fallback = parse(Map.of("banners.left", "RAINBOW"));
    assertEquals("WHITE", fallback.leftBanner());
    assertEquals("RED", fallback.rightBanner());
    assertEquals(1, warnings.size());

    warnings.clear();
    SpawnKitFurnishings custom = parse(Map.of("banners.left", "pink", "banners.right", "LIME"));
    assertEquals("PINK", custom.leftBanner());
    assertEquals("LIME", custom.rightBanner());
    assertTrue(warnings.isEmpty());
  }
}
