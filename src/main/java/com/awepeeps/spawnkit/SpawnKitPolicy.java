package com.awepeeps.spawnkit;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Set;
import org.bukkit.Material;

final class SpawnKitPolicy {
  static final int MAX_SEARCH_RADIUS = 64;
  static final int MAX_VERTICAL_SEARCH = 16;
  static final int MAX_SHELTER_TERRAIN_VARIATION = 4;
  static final int MIN_SHELTER_SEARCH_RADIUS = 8;
  static final int DEFAULT_SHELTER_SEARCH_RADIUS = 16;
  static final int MAX_SHELTER_SEARCH_RADIUS = 32;
  private static final Set<Material> NATURAL_FLOORS = Set.of(
      Material.GRASS_BLOCK, Material.DIRT, Material.COARSE_DIRT, Material.ROOTED_DIRT,
      Material.PODZOL, Material.MYCELIUM, Material.MUD, Material.CLAY, Material.SAND,
      Material.RED_SAND, Material.GRAVEL, Material.SNOW_BLOCK, Material.STONE,
      Material.ANDESITE, Material.DIORITE, Material.GRANITE, Material.TUFF, Material.DEEPSLATE);
  private static final Set<Material> CLEARABLE_VEGETATION = Set.of(
      Material.SNOW, Material.SHORT_GRASS, Material.TALL_GRASS, Material.FERN,
      Material.LARGE_FERN, Material.DEAD_BUSH, Material.DANDELION, Material.POPPY,
      Material.BLUE_ORCHID, Material.ALLIUM, Material.AZURE_BLUET, Material.RED_TULIP,
      Material.ORANGE_TULIP, Material.WHITE_TULIP, Material.PINK_TULIP,
      Material.OXEYE_DAISY, Material.CORNFLOWER, Material.LILY_OF_THE_VALLEY,
      Material.TORCHFLOWER, Material.PITCHER_PLANT);

  private SpawnKitPolicy() {}

  static boolean isActive(
      boolean enabled, String startDate, String expiryDate, String timezone, Instant now) {
    if (!enabled || startDate == null || expiryDate == null || timezone == null) return false;
    try {
      LocalDate start = LocalDate.parse(startDate.trim());
      LocalDate expiry = LocalDate.parse(expiryDate.trim());
      ZoneId zone = ZoneId.of(timezone.trim());
      return !now.isBefore(start.atStartOfDay(zone).toInstant())
          && now.isBefore(expiry.plusDays(1).atStartOfDay(zone).toInstant());
    } catch (java.time.DateTimeException | ArithmeticException error) {
      return false;
    }
  }

  static boolean outsideProtection(int deltaX, int deltaZ, int protectionRadius) {
    return protectionRadius <= 0
        || Math.max(Math.abs(deltaX), Math.abs(deltaZ)) > protectionRadius;
  }

  static int searchRadius(int protectionRadius, int preferredRadius) {
    if (protectionRadius >= MAX_SEARCH_RADIUS) return -1;
    return Math.min(MAX_SEARCH_RADIUS,
        Math.max(preferredRadius, Math.max(0, protectionRadius) + 2));
  }

  static Integer surfacePlacementY(int surfaceBlockY, int spawnBlockY, int minHeight, int maxHeight) {
    int placementY = surfaceBlockY + 1;
    if (Math.abs((long) placementY - spawnBlockY) > MAX_VERTICAL_SEARCH
        || placementY <= minHeight || placementY + 1 >= maxHeight) return null;
    return placementY;
  }

  static boolean canLevelShelterTerrain(int lowestSurfaceY, int highestSurfaceY) {
    return highestSurfaceY >= lowestSurfaceY
        && highestSurfaceY - lowestSurfaceY <= MAX_SHELTER_TERRAIN_VARIATION;
  }

  static boolean validShelterSearchRadius(int radius) {
    return radius >= MIN_SHELTER_SEARCH_RADIUS && radius <= MAX_SHELTER_SEARCH_RADIUS;
  }

  static ChunkBounds searchChunkBounds(int centerX, int centerZ, int extent) {
    return new ChunkBounds(
        (centerX - extent) >> 4, (centerX + extent) >> 4,
        (centerZ - extent) >> 4, (centerZ + extent) >> 4);
  }

  static int retryDelaySeconds(int failedAttempts) {
    return (int) Math.min(30 * 60L, 30L << Math.min(Math.max(0, failedAttempts), 6));
  }

  static boolean safeFloor(boolean solid, boolean liquid, boolean leaves, boolean hazardous) {
    return solid && !liquid && !leaves && !hazardous;
  }

  static boolean isNaturalFloor(Material material) {
    return NATURAL_FLOORS.contains(material);
  }

  static boolean isClearableVegetation(Material material) {
    return CLEARABLE_VEGETATION.contains(material);
  }

  static boolean safePlacement(
      boolean airAtChest, boolean airAbove, boolean insideWorldBorder, boolean nextToChest) {
    return airAtChest && airAbove && insideWorldBorder && !nextToChest;
  }

  record ChunkBounds(int minX, int maxX, int minZ, int maxZ) {}
}
