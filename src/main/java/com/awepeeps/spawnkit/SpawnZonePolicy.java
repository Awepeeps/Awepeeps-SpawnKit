package com.awepeeps.spawnkit;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.Tag;

/**
 * Pure decision logic for the spawn zone: terrain classification, floor-level selection, the
 * protection decision for each covered event, spawn-location handling and setup/migration state.
 * No world access, so every rule here is unit testable.
 *
 * <p>Terrain classification prefers the vanilla block tags (via {@link TerrainTags}) and falls back
 * to material names and explicit sets for everything the tags do not cover, so it keeps working for
 * blocks added by a future Minecraft version. The tag memberships themselves come from the running
 * server's registry; without a server (plain unit tests) the tag questions answer {@code false} and
 * the explicit rules carry the classification.
 */
final class SpawnZonePolicy {
  static final int MAX_TERRAIN_VARIATION = 4;
  /** The value the {@code respawn_radius} game rule is pinned to while spawn control is managed. */
  static final int MANAGED_RESPAWN_RADIUS = 0;

  /** Bounds for removing a tree whose trunk stands inside the footprint (floating-canopy guard). */
  static final int TREE_CLEAR_RADIUS = 4;
  static final int TREE_CLEAR_ABOVE_ROOF = 12;
  static final int TREE_CLEAR_MAX_BLOCKS = 512;

  /** How many distinct offending block types a deferral message names. */
  static final int MAX_REPORTED_OFFENDER_TYPES = 4;

  /** Every event the plugin covers. {@code playerExempt} events may be performed by a creative op. */
  enum GuardEvent {
    BLOCK_BREAK(true),
    BLOCK_PLACE(true),
    BLOCK_MULTI_PLACE(true),
    PLAYER_BUCKET_EMPTY(true),
    ENTITY_PLACE(true),
    HANGING_PLACE(true),
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

  /** The cause of a refused site, in the order the checks are applied. */
  enum Defect {
    OUTSIDE_BORDER,
    VOID,
    LAVA,
    OCCUPIED,
    UNEVEN,
    OUT_OF_HEIGHT
  }

  /**
   * A vanilla block tag the classifier may consult. Kept behind {@link TerrainTags} because the
   * real tag contents need the running server's registry.
   */
  enum TerrainTag {
    LEAVES,
    LOGS,
    SAPLINGS,
    FLOWERS,
    DIRT,
    BASE_STONE,
    SAND,
    SNOW,
    ICE,
    MOSS,
    REPLACEABLE_BY_TREES,
    CAVE_VINES
  }

  /** Answers block-tag membership questions. */
  interface TerrainTags {
    boolean isTagged(TerrainTag tag, Material material);

    /** No tag is known: every explicit rule still applies. Used without a server. */
    TerrainTags NONE = (tag, material) -> false;

    /** The tags of the running server. */
    static TerrainTags server() {
      return new ServerTerrainTags();
    }
  }

  private static final class ServerTerrainTags implements TerrainTags {
    @Override
    public boolean isTagged(TerrainTag tag, Material material) {
      try {
        return tagOf(tag).isTagged(material);
      } catch (LinkageError | RuntimeException error) {
        // Reached when there is no server registry to read (plain unit tests). The explicit rules
        // still classify every material, so the classifier stays correct without tags.
        return false;
      }
    }

    private static Tag<Material> tagOf(TerrainTag tag) {
      return switch (tag) {
        case LEAVES -> Tag.LEAVES;
        case LOGS -> Tag.LOGS;
        case SAPLINGS -> Tag.SAPLINGS;
        case FLOWERS -> Tag.FLOWERS;
        case DIRT -> Tag.DIRT;
        case BASE_STONE -> Tag.BASE_STONE_OVERWORLD;
        case SAND -> Tag.SAND;
        case SNOW -> Tag.SNOW;
        case ICE -> Tag.ICE;
        case MOSS -> Tag.MOSS_BLOCKS;
        case REPLACEABLE_BY_TREES -> Tag.REPLACEABLE_BY_TREES;
        case CAVE_VINES -> Tag.CAVE_VINES;
      };
    }
  }

  private static final TerrainTags SERVER_TAGS = TerrainTags.server();

  /** Setup lifecycle derived from the durable per-world record. */
  enum SetupState {
    FRESH,
    IN_PROGRESS,
    CURRENT,
    /** Built by an older layout version; can be upgraded additively on request. */
    UPGRADE_AVAILABLE,
    LEGACY_UNMIGRATED
  }

  /**
   * A planned entry staircase. {@code treads} holds the absolute Y of each step's top surface,
   * ordered outwards from the doorstep; it is empty when the ground is level with or higher than
   * the doorstep (in which case a walkable approach is cleared instead of stepped).
   */
  record EntrySteps(List<Integer> treads, boolean groundHigher, boolean truncated) {
    int count() {
      return treads.size();
    }
  }

  private static final Set<Material> NATURAL_GROUND = Set.of(
      Material.GRASS_BLOCK, Material.DIRT, Material.COARSE_DIRT, Material.ROOTED_DIRT,
      Material.PODZOL, Material.MYCELIUM, Material.MUD, Material.MUDDY_MANGROVE_ROOTS,
      Material.CLAY, Material.SAND, Material.RED_SAND, Material.SUSPICIOUS_SAND,
      Material.GRAVEL, Material.SNOW_BLOCK, Material.STONE, Material.ANDESITE, Material.DIORITE,
      Material.GRANITE, Material.TUFF, Material.DEEPSLATE, Material.CALCITE,
      Material.DRIPSTONE_BLOCK, Material.BEDROCK, Material.SANDSTONE, Material.RED_SANDSTONE,
      Material.MOSS_BLOCK, Material.ICE, Material.PACKED_ICE, Material.BLUE_ICE,
      Material.MAGMA_BLOCK, Material.OBSIDIAN, Material.BASALT, Material.SMOOTH_BASALT,
      Material.BLACKSTONE, Material.TERRACOTTA, Material.WATER, Material.LAVA,
      Material.POINTED_DRIPSTONE, Material.AMETHYST_BLOCK, Material.FROSTED_ICE);

  /**
   * Blocks the builder may clear without asking the operator. Covers everything world generation
   * places on or near the surface: ground cover, flowers, crops of the wild, vines and whole trees.
   * Deliberately excludes anything a player plants on farmland (wheat, carrots, potatoes, beetroots
   * and the pumpkin/melon stems) and every crafted block: those are never generated by the world,
   * so clearing them would silently destroy a player's work. Excluding them also makes them
   * {@link BlockKind#OCCUPIED}, which refuses the site instead.
   */
  private static final Set<Material> CLEARABLE = Set.of(
      Material.SNOW, Material.POWDER_SNOW, Material.SHORT_GRASS, Material.TALL_GRASS,
      Material.FERN, Material.LARGE_FERN, Material.SHORT_DRY_GRASS, Material.TALL_DRY_GRASS,
      Material.DEAD_BUSH, Material.DANDELION, Material.POPPY, Material.BLUE_ORCHID,
      Material.ALLIUM, Material.AZURE_BLUET, Material.RED_TULIP, Material.ORANGE_TULIP,
      Material.WHITE_TULIP, Material.PINK_TULIP, Material.OXEYE_DAISY, Material.CORNFLOWER,
      Material.LILY_OF_THE_VALLEY, Material.TORCHFLOWER, Material.PITCHER_PLANT,
      Material.LILAC, Material.SUNFLOWER, Material.ROSE_BUSH, Material.PEONY,
      Material.PINK_PETALS, Material.WILDFLOWERS, Material.FIREFLY_BUSH,
      Material.OPEN_EYEBLOSSOM, Material.CLOSED_EYEBLOSSOM, Material.SUGAR_CANE, Material.BAMBOO,
      Material.CACTUS, Material.CACTUS_FLOWER, Material.MELON, Material.PUMPKIN, Material.VINE,
      Material.LILY_PAD, Material.MOSS_CARPET, Material.PALE_MOSS_CARPET,
      Material.PALE_HANGING_MOSS, Material.RESIN_CLUMP, Material.SWEET_BERRY_BUSH,
      Material.BROWN_MUSHROOM, Material.RED_MUSHROOM, Material.LEAF_LITTER, Material.BUSH,
      Material.AZALEA, Material.FLOWERING_AZALEA, Material.BIG_DRIPLEAF, Material.SMALL_DRIPLEAF,
      Material.SPORE_BLOSSOM, Material.HANGING_ROOTS, Material.GLOW_LICHEN, Material.CAVE_VINES,
      Material.CAVE_VINES_PLANT, Material.WEEPING_VINES, Material.WEEPING_VINES_PLANT,
      Material.TWISTING_VINES, Material.TWISTING_VINES_PLANT, Material.CRIMSON_ROOTS,
      Material.WARPED_ROOTS, Material.NETHER_SPROUTS, Material.KELP, Material.KELP_PLANT,
      Material.SEAGRASS, Material.TALL_SEAGRASS, Material.COCOA, Material.BEE_NEST,
      Material.MANGROVE_ROOTS);

  private SpawnZonePolicy() {}

  static BlockKind classify(Material material) {
    return classify(material, SERVER_TAGS);
  }

  static BlockKind classify(Material material, TerrainTags tags) {
    if (isAir(material)) return BlockKind.AIR;
    if (material == Material.LAVA) return BlockKind.LAVA;
    if (material == Material.WATER || material == Material.BUBBLE_COLUMN) return BlockKind.WATER;
    if (isClearableVegetation(material, tags)) return BlockKind.VEGETATION;
    if (isNaturalGround(material, tags)) return BlockKind.NATURAL;
    return BlockKind.OCCUPIED;
  }

  static boolean isAir(Material material) {
    return material == Material.AIR
        || material == Material.CAVE_AIR
        || material == Material.VOID_AIR;
  }

  static boolean isNaturalGround(Material material) {
    return isNaturalGround(material, SERVER_TAGS);
  }

  static boolean isNaturalGround(Material material, TerrainTags tags) {
    if (NATURAL_GROUND.contains(material)) return true;
    if (tags.isTagged(TerrainTag.DIRT, material)
        || tags.isTagged(TerrainTag.BASE_STONE, material)
        || tags.isTagged(TerrainTag.SAND, material)
        || tags.isTagged(TerrainTag.SNOW, material)
        || tags.isTagged(TerrainTag.ICE, material)
        || tags.isTagged(TerrainTag.MOSS, material)) {
      return true;
    }
    String name = material.name();
    return name.endsWith("_TERRACOTTA")
        || name.endsWith("_ORE")
        || name.endsWith("SANDSTONE");
  }

  static boolean isClearableVegetation(Material material) {
    return isClearableVegetation(material, SERVER_TAGS);
  }

  static boolean isClearableVegetation(Material material, TerrainTags tags) {
    if (CLEARABLE.contains(material)) return true;
    if (tags.isTagged(TerrainTag.LEAVES, material)
        || tags.isTagged(TerrainTag.LOGS, material)
        || tags.isTagged(TerrainTag.SAPLINGS, material)
        || tags.isTagged(TerrainTag.FLOWERS, material)
        || tags.isTagged(TerrainTag.CAVE_VINES, material)
        || tags.isTagged(TerrainTag.REPLACEABLE_BY_TREES, material)) {
      return true;
    }
    String name = material.name();
    // A stripped log/wood is a processed block a player made: refuse it even though its name ends
    // with _LOG/_WOOD.
    if (name.startsWith("STRIPPED_")) return false;
    return name.endsWith("_LOG")
        || name.endsWith("_WOOD")
        || name.endsWith("_LEAVES")
        || name.endsWith("_SAPLING")
        || name.endsWith("_FLOWER")
        || name.endsWith("_TULIP");
  }

  /** A tree trunk block (log or bark), used to seed connected-tree clearing. */
  static boolean isTreeTrunk(Material material) {
    return isTreeTrunk(material, SERVER_TAGS);
  }

  static boolean isTreeTrunk(Material material, TerrainTags tags) {
    if (tags.isTagged(TerrainTag.LOGS, material)) return true;
    String name = material.name();
    if (name.startsWith("STRIPPED_")) return false;
    return name.endsWith("_LOG") || name.endsWith("_WOOD");
  }

  /**
   * A block that belongs to a generated tree and should go with it: the trunk, its leaves and the
   * attachments world generation puts on trees (vines, cocoa, a generated bee nest).
   */
  static boolean isTreePart(Material material) {
    return isTreePart(material, SERVER_TAGS);
  }

  static boolean isTreePart(Material material, TerrainTags tags) {
    if (isTreeTrunk(material, tags)) return true;
    if (tags.isTagged(TerrainTag.LEAVES, material)) return true;
    String name = material.name();
    return name.endsWith("_LEAVES")
        || material == Material.VINE
        || material == Material.COCOA
        || material == Material.BEE_NEST;
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

  /** The first blocking defect for a candidate site, or null when it can be built. */
  static Defect firstDefect(boolean voidBeneath, boolean lavaBeneath, boolean occupied,
                            boolean unevenTerrain, boolean outOfHeightRange,
                            boolean outsideWorldBorder) {
    if (outsideWorldBorder) return Defect.OUTSIDE_BORDER;
    if (voidBeneath) return Defect.VOID;
    if (lavaBeneath) return Defect.LAVA;
    if (occupied) return Defect.OCCUPIED;
    if (unevenTerrain) return Defect.UNEVEN;
    if (outOfHeightRange) return Defect.OUT_OF_HEIGHT;
    return null;
  }

  static String message(Defect defect) {
    return switch (defect) {
      case OUTSIDE_BORDER -> "footprint outside the world border";
      case VOID -> "void beneath the footprint";
      case LAVA -> "lava beneath the footprint";
      case OCCUPIED -> "footprint intersects non-natural blocks, containers or a structure";
      case UNEVEN -> "terrain is too uneven to level";
      case OUT_OF_HEIGHT -> "no headroom or too close to the world floor";
    };
  }

  /**
   * Returns the first blocking defect for a candidate site, or null when it can be built. Order is
   * deliberate: catastrophic cases first, then occupancy, then shape.
   */
  static String placementDefect(boolean voidBeneath, boolean lavaBeneath, boolean occupied,
                                boolean unevenTerrain, boolean outOfHeightRange,
                                boolean outsideWorldBorder) {
    Defect defect = firstDefect(voidBeneath, lavaBeneath, occupied, unevenTerrain,
        outOfHeightRange, outsideWorldBorder);
    return defect == null ? null : message(defect);
  }

  /** Whether a covered event must be denied for a block inside the protected region. */
  static boolean denied(GuardEvent event, boolean blockInRegion, boolean creativeOperator) {
    if (!blockInRegion) return false;
    return !(event.playerExempt() && creativeOperator);
  }

  /**
   * Whether a multi-block placement (bed, door, tall plant) must be denied. Checking only the
   * primary block is not enough: placing a bed so that its head lands in the arrival space would
   * otherwise succeed and leave an un-removable half inside the protected zone.
   */
  static boolean deniedPlacement(GuardEvent event, SpawnRegion region,
                                 List<SpawnBlockPos> placedBlocks, boolean creativeOperator) {
    return denied(event, anyInRegion(region, placedBlocks), creativeOperator);
  }

  /**
   * Whether a dispenser's action can reach the protected region: it does when the dispenser itself
   * sits inside it or when the block it fires at (the block it faces) is inside it.
   */
  static boolean dispenserAffectsRegion(SpawnRegion region, SpawnBlockPos dispenser,
                                        int dirX, int dirY, int dirZ) {
    return region.contains(dispenser) || region.contains(dispenser.offset(dirX, dirY, dirZ));
  }

  /**
   * Whether the builder may overwrite a block that is already the layout block. The supply chest is
   * the exception: re-issuing the layout must never replace the chest block, because replacing it
   * would wipe whatever the players have stored in it.
   */
  static boolean overwriteExisting(SpawnZoneLayout.Role role) {
    return role != SpawnZoneLayout.Role.CHEST;
  }

  /**
   * The part of a build's scan volume that the plugin's own previous pass may already have filled:
   * the recorded protected zone plus, in the same columns, the foundation it filled beneath the
   * floor. A later pass (the house, or an operator rebuild) must ignore those blocks instead of
   * refusing the site for "non-natural blocks" that the plugin placed itself.
   *
   * @param builtZone the protected region recorded for the world
   * @param scanFloor the floor level the new pass chose
   * @param coreDepth the configured core depth
   * @param minHeight the world's minimum height
   */
  static SpawnRegion ownedVolume(SpawnRegion builtZone, int scanFloor, int coreDepth, int minHeight) {
    int minY = Math.max(minHeight, scanFloor - coreDepth - 1);
    return SpawnRegion.of(builtZone.minX(), Math.min(minY, builtZone.minY()), builtZone.minZ(),
        builtZone.maxX(), builtZone.maxY(), builtZone.maxZ());
  }

  /** Clamps a configured dimension into its supported range. */
  static int clamp(int value, int min, int max) {
    return Math.max(min, Math.min(max, value));
  }

  /**
   * Whether an additive upgrade may place one piece. A piece is only placed where the target cell
   * is replaceable and its support exists, and the supply chest is never (re)placed while any chest
   * already exists. This is the whole upgrade policy, so the "never overwrite" rule is testable
   * without a world.
   */
  static boolean upgradeAllowed(SpawnZoneLayout.Role role, boolean targetReplaceable,
                                boolean supportPresent, boolean chestExists) {
    if (role == SpawnZoneLayout.Role.CHEST && chestExists) return false;
    return targetReplaceable && supportPresent;
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

  /**
   * Joining players are placed on the core only on their first ever join. A returning player keeps
   * the position stored in their player data (their logout position), which is also what the join
   * event already defaults to; overriding it would drag them back to spawn on every login.
   */
  static boolean overrideJoinSpawn(boolean newPlayer) {
    return newPlayer;
  }

  /** Only world-spawn respawns are redirected; beds and respawn anchors are never overridden. */
  static boolean overrideRespawn(boolean bedSpawn, boolean anchorSpawn) {
    return !bedSpawn && !anchorSpawn;
  }

  /**
   * Classifies a world from its durable record.
   *
   * @param hasCurrentLayout the record's {@code layout-version} is at or above the current one
   * @param hasPreviousLayout the record's {@code layout-version} is the immediately previous one
   * @param hasLegacyRecord the record contains any 1.1.x key (issued, setup-complete, x/y/z)
   * @param inProgress a build reservation is present
   * @param zoneComplete the protected core is recorded as built for this world
   */
  static SetupState classifySetup(boolean hasCurrentLayout, boolean hasPreviousLayout,
                                  boolean hasLegacyRecord, boolean inProgress,
                                  boolean zoneComplete) {
    if (hasCurrentLayout && zoneComplete) return SetupState.CURRENT;
    if (inProgress) return SetupState.IN_PROGRESS;
    if (hasPreviousLayout && zoneComplete) return SetupState.UPGRADE_AVAILABLE;
    if (!hasCurrentLayout && !hasPreviousLayout && hasLegacyRecord) {
      return SetupState.LEGACY_UNMIGRATED;
    }
    return SetupState.FRESH;
  }

  /**
   * The clear refusal to show when an operator asks to {@code force} the build on a world that is
   * only ever upgraded additively, or null when the flags are allowed. An additive upgrade only
   * fills replaceable cells and never overwrites an existing house or chest, so the destructive
   * flags have no meaning there and must be rejected rather than silently ignored.
   */
  static String additiveUpgradeForceRefusal(SetupState state, boolean force, boolean forceItems) {
    if (!force && !forceItems) return null;
    if (state != SetupState.UPGRADE_AVAILABLE && state != SetupState.CURRENT) return null;
    return "This world is upgraded additively: every existing block and chest keeps its contents,"
        + " so 'force' and 'items' are not accepted here. Run '/spawnkit build confirm' without"
        + " them.";
  }

  /** What an explicit build request should do for a world already at the current layout version. */
  enum CurrentBuild {
    /** Not a current-layout world (or no house kit): report that nothing needs doing. */
    ALREADY_CURRENT,
    /**
     * The protected zone is built but the house pass has not recorded completion: the automatic
     * house build still owns the world, so the additive pass must not run.
     */
    AWAIT_HOUSE,
    /** The house is recorded built: retry any additive piece that was skipped earlier. */
    ADDITIVE_UPGRADE
  }

  /**
   * Decides what an operator build request does for a world whose setup state is {@code CURRENT}.
   * The additive upgrade is only safe once the house pass has recorded completion: before that the
   * automatic house build still owns the world, and an additive pass run in the window after the
   * zone completes (and before the next house cycle, or after a deferred house pass) would place
   * furnishings on the bare zone, mark the world complete, and leave the house shell unbuilt. The
   * explicit command un-defers the house pass, so leaving the work to it preserves operator
   * recovery without letting the two writers overlap.
   */
  static CurrentBuild currentBuild(boolean houseConfigured, boolean zoneRecorded,
                                   boolean houseRecorded) {
    if (!houseConfigured || !zoneRecorded) return CurrentBuild.ALREADY_CURRENT;
    return houseRecorded ? CurrentBuild.ADDITIVE_UPGRADE : CurrentBuild.AWAIT_HOUSE;
  }

  /**
   * Plans the entry steps below the doorstep.
   *
   * <p>{@code doorstepTopY} and {@code groundTopY} are the Y of the top surface of the doorstep
   * block and of the terrain just past it. When the ground is higher, the plan asks for a cleared
   * approach ({@code groundHigher}); when it is lower, one tread is added per block of drop, up to
   * {@code maxSteps} (further drop is left as the final step down to the ground, marked
   * {@code truncated}).
   */
  static EntrySteps entrySteps(int doorstepTopY, int groundTopY, int maxSteps) {
    if (groundTopY >= doorstepTopY) {
      return new EntrySteps(List.of(), true, false);
    }
    int gap = doorstepTopY - groundTopY;
    int wanted = Math.max(0, gap - 1);
    int count = Math.min(wanted, Math.max(0, maxSteps));
    List<Integer> treads = new ArrayList<>(count);
    for (int step = 1; step <= count; step++) {
      treads.add(doorstepTopY - step);
    }
    return new EntrySteps(List.copyOf(treads), false, wanted > count);
  }
}
