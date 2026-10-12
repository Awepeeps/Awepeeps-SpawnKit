package com.awepeeps.spawnkit;

import java.util.List;
import java.util.Set;
import org.bukkit.Material;

/**
 * Pure decision logic for the spawn zone: terrain classification, floor-level selection, the
 * protection decision for each covered event, spawn-location handling and setup/migration state.
 * No world access, so every rule here is unit testable.
 */
final class SpawnZonePolicy {
  static final int MAX_TERRAIN_VARIATION = 4;
  /** The value the {@code respawn_radius} game rule is pinned to while spawn control is managed. */
  static final int MANAGED_RESPAWN_RADIUS = 0;

  /** Every event the plugin covers. {@code playerExempt} events may be performed by a creative op. */
  enum GuardEvent {
    BLOCK_BREAK(true),
    BLOCK_PLACE(true),
    BLOCK_MULTI_PLACE(true),
    PLAYER_BUCKET_EMPTY(true),
    BLOCK_BURN(false),
    BLOCK_SPREAD(false),
    BLOCK_FORM(false),
    BLOCK_FADE(false),
    BLOCK_GROW(false),
    BLOCK_FERTILIZE(false),
    BLOCK_FROM_TO(false),
    FLUID_LEVEL_CHANGE(false),
    BLOCK_IGNITE(false),
    BLOCK_DISPENSE(false),
    BLOCK_PHYSICS(false),
    BLOCK_PISTON_EXTEND(false),
    BLOCK_PISTON_RETRACT(false),
    BLOCK_EXPLODE(false),
    ENTITY_EXPLODE(false),
    ENTITY_CHANGE_BLOCK(false),
    ENTITY_BLOCK_FORM(false),
    STRUCTURE_GROW(false),
    SPONGE_ABSORB(false);

    private final boolean playerExempt;

    GuardEvent(boolean playerExempt) {
      this.playerExempt = playerExempt;
    }

    /** True when a creative operator is allowed to perform this modification inside the zone. */
    boolean playerExempt() {
      return playerExempt;
    }
  }

  /** How a single block is treated by the placement rule. */
  enum BlockKind {
    AIR,
    WATER,
    LAVA,
    VEGETATION,
    NATURAL,
    OCCUPIED
  }

  /** Setup lifecycle derived from the durable per-world record. */
  enum SetupState {
    FRESH,
    IN_PROGRESS,
    CURRENT,
    LEGACY_UNMIGRATED
  }

  private static final Set<Material> NATURAL_GROUND = Set.of(
      Material.GRASS_BLOCK, Material.DIRT, Material.COARSE_DIRT, Material.ROOTED_DIRT,
      Material.PODZOL, Material.MYCELIUM, Material.MUD, Material.CLAY, Material.SAND,
      Material.RED_SAND, Material.GRAVEL, Material.SNOW_BLOCK, Material.STONE,
      Material.ANDESITE, Material.DIORITE, Material.GRANITE, Material.TUFF, Material.DEEPSLATE,
      Material.CALCITE, Material.DRIPSTONE_BLOCK, Material.BEDROCK, Material.SANDSTONE,
      Material.RED_SANDSTONE, Material.MOSS_BLOCK, Material.ICE, Material.PACKED_ICE,
      Material.BLUE_ICE, Material.MAGMA_BLOCK, Material.OBSIDIAN, Material.BASALT,
      Material.SMOOTH_BASALT, Material.BLACKSTONE, Material.TERRACOTTA, Material.WATER,
      Material.LAVA, Material.POINTED_DRIPSTONE, Material.AMETHYST_BLOCK);

  private static final Set<Material> CLEARABLE = Set.of(
      Material.SNOW, Material.SHORT_GRASS, Material.TALL_GRASS, Material.FERN,
      Material.LARGE_FERN, Material.DEAD_BUSH, Material.DANDELION, Material.POPPY,
      Material.BLUE_ORCHID, Material.ALLIUM, Material.AZURE_BLUET, Material.RED_TULIP,
      Material.ORANGE_TULIP, Material.WHITE_TULIP, Material.PINK_TULIP,
      Material.OXEYE_DAISY, Material.CORNFLOWER, Material.LILY_OF_THE_VALLEY,
      Material.TORCHFLOWER, Material.PITCHER_PLANT, Material.SUGAR_CANE, Material.BAMBOO,
      Material.CACTUS, Material.MELON, Material.PUMPKIN, Material.VINE, Material.LILY_PAD,
      Material.MOSS_CARPET, Material.SWEET_BERRY_BUSH, Material.BROWN_MUSHROOM,
      Material.RED_MUSHROOM, Material.PUMPKIN_STEM, Material.MELON_STEM, Material.WHEAT,
      Material.CARROTS, Material.POTATOES, Material.BEETROOTS);

  private SpawnZonePolicy() {}

  static BlockKind classify(Material material) {
    if (isAir(material)) return BlockKind.AIR;
    if (material == Material.LAVA) return BlockKind.LAVA;
    if (material == Material.WATER || material == Material.BUBBLE_COLUMN) return BlockKind.WATER;
    if (isClearableVegetation(material)) return BlockKind.VEGETATION;
    if (isNaturalGround(material)) return BlockKind.NATURAL;
    return BlockKind.OCCUPIED;
  }

  static boolean isAir(Material material) {
    return material == Material.AIR
        || material == Material.CAVE_AIR
        || material == Material.VOID_AIR;
  }

  static boolean isNaturalGround(Material material) {
    if (NATURAL_GROUND.contains(material)) return true;
    String name = material.name();
    return name.endsWith("_TERRACOTTA")
        || name.endsWith("DEEPSLATE")
        || name.endsWith("_ORE");
  }

  static boolean isClearableVegetation(Material material) {
    if (CLEARABLE.contains(material)) return true;
    String name = material.name();
    return name.endsWith("_LOG")
        || name.endsWith("_WOOD")
        || name.endsWith("_LEAVES")
        || name.endsWith("_SAPLING")
        || name.endsWith("_FLOWER")
        || name.endsWith("_TULIP")
        || name.equals("MANGROVE_ROOTS")
        || name.equals("MUDDY_MANGROVE_ROOTS");
  }

  /** True when the block may be replaced or cleared during construction; false means preserve. */
  static boolean isReplaceableByBuild(Material material) {
    BlockKind kind = classify(material);
    return kind == BlockKind.AIR
        || kind == BlockKind.VEGETATION
        || kind == BlockKind.WATER
        || kind == BlockKind.NATURAL;
  }

  /**
   * Chooses the absolute Y of the house floor layer (the block under the player's feet) from the
   * terrain under the footprint, or null when the site must be refused.
   */
  static Integer chooseFloorLevel(int minSurfaceY, int maxSurfaceY, boolean liquidSurface,
                                  int minHeight, int maxHeight, int coreDepth, int wallHeight) {
    if (maxSurfaceY < minSurfaceY) return null;
    if (maxSurfaceY - minSurfaceY > MAX_TERRAIN_VARIATION) return null;
    int floorY = liquidSurface ? maxSurfaceY + 1 : maxSurfaceY;
    if (floorY - coreDepth < minHeight) return null;
    if (floorY + wallHeight + 1 > maxHeight) return null;
    return floorY;
  }

  /**
   * Returns the first blocking defect for a candidate site, or null when it can be built. Order is
   * deliberate: catastrophic cases first, then occupancy, then shape.
   */
  static String placementDefect(boolean voidBeneath, boolean lavaBeneath, boolean occupied,
                                boolean unevenTerrain, boolean outOfHeightRange,
                                boolean outsideWorldBorder) {
    if (outsideWorldBorder) return "footprint outside the world border";
    if (voidBeneath) return "void beneath the footprint";
    if (lavaBeneath) return "lava beneath the footprint";
    if (occupied) return "footprint intersects non-natural blocks, containers or a structure";
    if (unevenTerrain) return "terrain is too uneven to level";
    if (outOfHeightRange) return "no headroom or too close to the world floor";
    return null;
  }

  /** Whether a covered event must be denied for a block inside the protected region. */
  static boolean denied(GuardEvent event, boolean blockInRegion, boolean creativeOperator) {
    if (!blockInRegion) return false;
    return !(event.playerExempt() && creativeOperator);
  }

  static boolean anyInRegion(SpawnRegion region, List<SpawnBlockPos> positions) {
    for (SpawnBlockPos pos : positions) {
      if (region.contains(pos)) return true;
    }
    return false;
  }

  /** A piston affects the region when a moved block or its destination lands inside it. */
  static boolean pistonTouchesRegion(SpawnRegion region, List<SpawnBlockPos> moved,
                                     int dirX, int dirY, int dirZ) {
    for (SpawnBlockPos pos : moved) {
      if (region.contains(pos) || region.contains(pos.offset(dirX, dirY, dirZ))) return true;
    }
    return false;
  }

  /** Joining players keep a personal bed spawn; everyone else is placed on the core top. */
  static boolean overrideJoinSpawn(boolean hasBedSpawn) {
    return !hasBedSpawn;
  }

  /** Only world-spawn respawns are redirected; beds and respawn anchors are never overridden. */
  static boolean overrideRespawn(boolean bedSpawn, boolean anchorSpawn) {
    return !bedSpawn && !anchorSpawn;
  }

  /**
   * Classifies a world from its durable record.
   *
   * @param hasLayoutVersion the record contains a {@code layout-version} at or above the current one
   * @param hasLegacyRecord the record contains any 1.1.x key (issued, setup-complete, x/y/z)
   * @param inProgress a build reservation is present
   * @param zoneComplete the protected core is recorded as built for this world
   */
  static SetupState classifySetup(boolean hasLayoutVersion, boolean hasLegacyRecord,
                                  boolean inProgress, boolean zoneComplete) {
    if (hasLayoutVersion && zoneComplete) return SetupState.CURRENT;
    if (inProgress) return SetupState.IN_PROGRESS;
    if (!hasLayoutVersion && hasLegacyRecord) return SetupState.LEGACY_UNMIGRATED;
    return SetupState.FRESH;
  }
}
