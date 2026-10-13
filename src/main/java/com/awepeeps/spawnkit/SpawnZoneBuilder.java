package com.awepeeps.spawnkit;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.Furnace;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Reads terrain, refuses unsuitable spawn sites, and places the {@link SpawnZoneLayout} in bounded
 * batches on the main thread. All world access lives here; every decision it takes comes from the
 * pure {@link SpawnZonePolicy} and {@link SpawnZoneLayout}.
 */
final class SpawnZoneBuilder {
  static final int BATCH_SIZE = 256;

  static final Material CORE_MATERIAL = Material.STONE_BRICKS;
  static final Material FLOOR_MATERIAL = Material.OAK_PLANKS;
  static final Material WALL_MATERIAL = Material.COBBLESTONE;
  static final Material ROOF_MATERIAL = Material.OAK_PLANKS;
  static final Material FOUNDATION_MATERIAL = Material.COBBLESTONE;
  static final Material DOOR_MATERIAL = Material.OAK_DOOR;
  static final Material BED_MATERIAL = Material.WHITE_BED;
  static final Material CRAFTING_MATERIAL = Material.CRAFTING_TABLE;
  static final Material ANVIL_MATERIAL = Material.ANVIL;
  static final Material FURNACE_MATERIAL = Material.FURNACE;
  static final Material LODESTONE_MATERIAL = Material.LODESTONE;

  private final JavaPlugin plugin;
  private final NamespacedKey ownedKey;
  private final int batchSize;

  SpawnZoneBuilder(JavaPlugin plugin, NamespacedKey ownedKey) {
    this(plugin, ownedKey, BATCH_SIZE);
  }

  SpawnZoneBuilder(JavaPlugin plugin, NamespacedKey ownedKey, int batchSize) {
    this.plugin = plugin;
    this.ownedKey = ownedKey;
    this.batchSize = batchSize;
  }

  /**
   * A prepared build: either a refusal with a diagnostic reason, or the ordered block operations
   * that make up the layout.
   */
  record Prepared(boolean accepted, int floorY, int spawnY, String defect,
                  List<Runnable> operations, SpawnRegion protectedRegion) {}

  Prepared prepare(World world, SpawnZoneLayout layout, boolean houseEnabled,
                   boolean chestEnabled, List<SpawnKitContents.Stack> contents,
                   SpawnRegion existingCore) {
    Location spawn = world.getSpawnLocation();
    int sx = spawn.getBlockX();
    int sz = spawn.getBlockZ();
    int wallRadius = layout.wallRadius();
    int minHeight = world.getMinHeight();
    int maxHeight = world.getMaxHeight();

    List<int[]> columns = buildColumns(wallRadius);
    List<Column> sampled = new ArrayList<>(columns.size());
    int minSurface = Integer.MAX_VALUE;
    int maxSurface = Integer.MIN_VALUE;
    boolean anyLiquid = false;
    boolean anyVoid = false;
    boolean anyLava = false;
    for (int[] column : columns) {
      int bx = sx + column[0];
      int bz = sz + column[1];
      if (!world.isChunkLoaded(bx >> 4, bz >> 4)) {
        return new Prepared(false, 0, 0, "spawn chunks were not loaded", List.of(), null);
      }
      int topY = world.getHighestBlockYAt(bx, bz, org.bukkit.HeightMap.MOTION_BLOCKING);
      int surfaceY = world.getHighestBlockYAt(bx, bz, org.bukkit.HeightMap.MOTION_BLOCKING_NO_LEAVES);
      while (surfaceY > minHeight
          && SpawnZonePolicy.isClearableVegetation(world.getBlockAt(bx, surfaceY, bz).getType())) {
        surfaceY--;
      }
      Material surface = world.getBlockAt(bx, surfaceY, bz).getType();
      boolean liquid = surface == Material.WATER || surface == Material.LAVA;
      boolean lava = surface == Material.LAVA;
      boolean isVoid = SpawnZonePolicy.isAir(surface);
      if (lava) anyLava = true;
      if (isVoid) anyVoid = true;
      if (liquid) anyLiquid = true;
      minSurface = Math.min(minSurface, surfaceY);
      maxSurface = Math.max(maxSurface, surfaceY);
      sampled.add(new Column(bx, bz, surfaceY, topY));
    }

    Integer floorY = SpawnZonePolicy.chooseFloorLevel(
        minSurface, maxSurface, anyLiquid, minHeight, maxHeight,
        layout.coreDepth(), layout.wallHeight());
    boolean unevenTerrain = floorY == null
        && maxSurface - minSurface > SpawnZonePolicy.MAX_TERRAIN_VARIATION;
    boolean outOfHeight = floorY == null && !unevenTerrain;

    boolean occupied = false;
    boolean lavaBeneath = anyLava;
    boolean outsideBorder = false;
    int scanFloor = floorY == null ? maxSurface : floorY;
    // Everything the previous pass already placed in these columns counts as ours, including the
    // foundation it filled beneath the floor; otherwise the second pass (the house) would refuse the
    // site on any terrain that dips more than a couple of blocks below the chosen floor.
    SpawnRegion ownBuild = existingCore == null ? null
        : SpawnZonePolicy.ownedVolume(existingCore, scanFloor, layout.coreDepth(), minHeight);
    for (Column column : sampled) {
      if (!world.getWorldBorder().isInside(
          new Location(world, column.x() + 0.5, scanFloor, column.z() + 0.5))) {
        outsideBorder = true;
      }
      for (int y = scanFloor - layout.coreDepth() - 1;
           y <= scanFloor + layout.wallHeight() + 1; y++) {
        if (y < minHeight || y >= maxHeight) continue;
        if (ownBuild != null && ownBuild.contains(column.x(), y, column.z())) {
          continue; // Our own protected core or foundation from a previous pass.
        }
        Material material = world.getBlockAt(column.x(), y, column.z()).getType();
        SpawnZonePolicy.BlockKind kind = SpawnZonePolicy.classify(material);
        if (kind == SpawnZonePolicy.BlockKind.LAVA) {
          lavaBeneath = true;
        } else if (kind == SpawnZonePolicy.BlockKind.OCCUPIED) {
          occupied = true;
        }
      }
    }

    String defect = SpawnZonePolicy.placementDefect(
        anyVoid, lavaBeneath, occupied, unevenTerrain, outOfHeight, outsideBorder);
    if (defect != null) {
      return new Prepared(false, 0, 0, defect, List.of(), null);
    }

    int spawnY = floorY + 1;

    // The exterior decorations sit outside the footprint, which the occupancy scan above does not
    // cover. Refuse the site rather than overwrite a non-natural block there.
    if (houseEnabled) {
      for (SpawnBlockPos offset : layout.exteriorOffsets()) {
        Block block = world.getBlockAt(sx + offset.x(), spawnY + offset.y(), sz + offset.z());
        if (!SpawnZonePolicy.isReplaceableByBuild(block.getType())) {
          return new Prepared(false, 0, 0,
              "a house decoration would overwrite a non-natural block", List.of(), null);
        }
      }
    }

    List<Runnable> operations = new ArrayList<>();

    // 1. Clear natural vegetation and snow across the columns being built on.
    List<int[]> clearColumns = houseEnabled ? columns : coreColumns();
    for (int[] clearColumn : clearColumns) {
      int bx = sx + clearColumn[0];
      int bz = sz + clearColumn[1];
      int limit = Math.min(topAt(sampled, bx, bz), maxHeight - 1);
      for (int y = minHeight; y <= limit; y++) {
        Block block = world.getBlockAt(bx, y, bz);
        if (SpawnZonePolicy.isClearableVegetation(block.getType())) {
          operations.add(place(block, Material.AIR, null));
        }
      }
    }

    List<int[]> foundationColumns = houseEnabled ? columns : coreColumns();
    for (int[] column : foundationColumns) {
      int bx = sx + column[0];
      int bz = sz + column[1];
      int surfaceY = surfaceAt(sampled, bx, bz);
      for (int y = surfaceY + 1; y < floorY; y++) {
        operations.add(place(world.getBlockAt(bx, y, bz), FOUNDATION_MATERIAL, null));
      }
    }

    for (var entry : layout.blockPlan().entrySet()) {
      SpawnBlockPos offset = entry.getKey();
      SpawnZoneLayout.Role role = entry.getValue();
      if (!roleEnabled(role, houseEnabled, chestEnabled)) continue;
      Block block = world.getBlockAt(sx + offset.x(), spawnY + offset.y(), sz + offset.z());
      operations.add(switch (role) {
        case CORE -> place(block, CORE_MATERIAL, null);
        case FLOOR -> place(block, FLOOR_MATERIAL, null);
        case WALL -> place(block, WALL_MATERIAL, null);
        case ROOF -> place(block, ROOF_MATERIAL, null);
        case CRAFTING_TABLE -> place(block, CRAFTING_MATERIAL, null);
        case ANVIL -> place(block, ANVIL_MATERIAL, directional(ANVIL_MATERIAL, roomFacing(offset)));
        case FURNACE -> place(block, FURNACE_MATERIAL, furnaceData(offset));
        case LODESTONE -> place(block, LODESTONE_MATERIAL, null);
        case EXTRA -> place(block, layout.extraMaterial(offset), null);
        case DOORSTEP -> place(block, FLOOR_MATERIAL, null);
        case DOOR_LOWER -> place(block, DOOR_MATERIAL, doorData(Bisected.Half.BOTTOM));
        case DOOR_UPPER -> place(block, DOOR_MATERIAL, doorData(Bisected.Half.TOP));
        case BED_FOOT -> place(block, BED_MATERIAL, bedData(offset, Bed.Part.FOOT, layout));
        case BED_HEAD -> place(block, BED_MATERIAL, bedData(offset, Bed.Part.HEAD, layout));
        case CHEST -> place(block, Material.CHEST, directional(Material.CHEST, roomFacing(offset)),
            SpawnZonePolicy.overwriteExisting(role));
        case WALL_TORCH -> place(block, Material.WALL_TORCH,
            directional(Material.WALL_TORCH, layout.wallTorchFacing(offset)));
        case EXTERIOR_TORCH -> place(block, Material.WALL_TORCH,
            directional(Material.WALL_TORCH, layout.exteriorFacing(offset)));
        case BANNER_LEFT -> place(block,
            SpawnKitFurnishings.bannerMaterial(layout.furnishings().leftBanner()),
            directional(SpawnKitFurnishings.bannerMaterial(layout.furnishings().leftBanner()),
                layout.exteriorFacing(offset)));
        case BANNER_RIGHT -> place(block,
            SpawnKitFurnishings.bannerMaterial(layout.furnishings().rightBanner()),
            directional(SpawnKitFurnishings.bannerMaterial(layout.furnishings().rightBanner()),
                layout.exteriorFacing(offset)));
      });
    }

    if (houseEnabled) {
      operations.addAll(planEntrySteps(world, layout, sx, sz, floorY, minHeight, maxHeight));
    }

    if (houseEnabled && chestEnabled && !contents.isEmpty()) {
      operations.add(() -> fillChests(world, layout, sx, spawnY, sz, contents));
    }

    SpawnRegion protectedRegion = layout.protectedRegion().translated(sx, spawnY, sz);
    return new Prepared(true, floorY, spawnY, null, operations, protectedRegion);
  }

  /**
   * The additive upgrade for a world already built with the previous layout version: only the
   * furnishing and decoration cells are touched, and only where the target is replaceable and its
   * support exists. No structural block is ever replaced or removed, an existing chest is never
   * touched (unless {@code supplies} was requested), and every skipped piece is logged with a
   * reason.
   */
  Prepared prepareUpgrade(World world, SpawnZoneLayout layout, int sx, int sy, int sz,
                          boolean chestExists, boolean chestEnabled,
                          List<SpawnKitContents.Stack> contents) {
    List<Runnable> operations = new ArrayList<>();
    List<String> placed = new ArrayList<>();
    List<String> skipped = new ArrayList<>();
    boolean chestPresent = chestExists || anyChestInRing(world, layout, sx, sy, sz);
    Set<SpawnBlockPos> bedCells = new HashSet<>();
    for (SpawnZoneLayout.BedPlacement bed : layout.bedPlacements()) {
      bedCells.add(bed.foot());
      bedCells.add(bed.head());
    }

    for (var entry : layout.furnishPlan()) {
      SpawnBlockPos offset = entry.getKey();
      SpawnZoneLayout.Role role = entry.getValue();
      if (bedCells.contains(offset)) continue; // a bed is handled as one unit below
      if (role == SpawnZoneLayout.Role.CHEST && (chestPresent || !chestEnabled)) {
        skipped.add("chest at " + offset + " ("
            + (chestPresent ? "a chest already exists" : "the chest is disabled") + ")");
        continue;
      }
      Block block = blockAt(world, sx, sy, sz, offset);
      boolean replaceable = SpawnZonePolicy.isReplaceableByBuild(block.getType());
      boolean support = hasSupport(world, layout, sx, sy, sz, offset, role);
      if (!SpawnZonePolicy.upgradeAllowed(role, replaceable, support, chestPresent)) {
        skipped.add(describe(role) + " at " + offset + " ("
            + (replaceable ? "support missing" : "occupied by " + block.getType()) + ")");
        continue;
      }
      operations.add(upgradeOperation(layout, block, offset, role));
      placed.add(describe(role) + " at " + offset);
    }

    for (SpawnZoneLayout.BedPlacement bed : layout.bedPlacements()) {
      Block foot = blockAt(world, sx, sy, sz, bed.foot());
      Block head = blockAt(world, sx, sy, sz, bed.head());
      boolean replaceable = SpawnZonePolicy.isReplaceableByBuild(foot.getType())
          && SpawnZonePolicy.isReplaceableByBuild(head.getType());
      boolean support = !isAir(foot.getRelative(BlockFace.DOWN).getType())
          && !isAir(head.getRelative(BlockFace.DOWN).getType());
      if (!SpawnZonePolicy.upgradeAllowed(SpawnZoneLayout.Role.BED_FOOT, replaceable, support, chestPresent)) {
        skipped.add("bed at " + bed.foot() + " (" + (replaceable ? "support missing" : "occupied") + ")");
        continue;
      }
      operations.add(place(foot, BED_MATERIAL, bedData(bed.foot(), Bed.Part.FOOT, layout)));
      operations.add(place(head, BED_MATERIAL, bedData(bed.head(), Bed.Part.HEAD, layout)));
      placed.add("bed at " + bed.foot());
    }

    if (!operations.isEmpty()) {
      plugin.getLogger().info("Spawn house upgrade: adding " + placed.size()
          + " piece(s): " + placed + ".");
    }
    if (!skipped.isEmpty()) {
      plugin.getLogger().info("Spawn house upgrade: skipped " + skipped.size()
          + " piece(s): " + skipped + ".");
    }
    if (chestPresent && !contents.isEmpty()) {
      operations.add(() -> fillExistingChests(world, layout, sx, sy, sz, contents));
    }

    SpawnRegion protectedRegion = layout.protectedRegion().translated(sx, sy, sz);
    return new Prepared(true, sy - 1, sy, null, operations, protectedRegion);
  }

  private Runnable upgradeOperation(SpawnZoneLayout layout, Block block, SpawnBlockPos offset,
                                    SpawnZoneLayout.Role role) {
    return switch (role) {
      case CRAFTING_TABLE -> place(block, CRAFTING_MATERIAL, null);
      case ANVIL -> place(block, ANVIL_MATERIAL, directional(ANVIL_MATERIAL, roomFacing(offset)));
      case FURNACE -> place(block, FURNACE_MATERIAL, furnaceData(offset));
      case LODESTONE -> place(block, LODESTONE_MATERIAL, null);
      case EXTRA -> place(block, layout.extraMaterial(offset), null);
      case DOORSTEP -> place(block, FLOOR_MATERIAL, null);
      case CHEST -> place(block, Material.CHEST, directional(Material.CHEST, roomFacing(offset)));
      case WALL_TORCH -> place(block, Material.WALL_TORCH,
          directional(Material.WALL_TORCH, layout.wallTorchFacing(offset)));
      case EXTERIOR_TORCH -> place(block, Material.WALL_TORCH,
          directional(Material.WALL_TORCH, layout.exteriorFacing(offset)));
      case BANNER_LEFT -> place(block,
          SpawnKitFurnishings.bannerMaterial(layout.furnishings().leftBanner()),
          directional(SpawnKitFurnishings.bannerMaterial(layout.furnishings().leftBanner()),
              layout.exteriorFacing(offset)));
      case BANNER_RIGHT -> place(block,
          SpawnKitFurnishings.bannerMaterial(layout.furnishings().rightBanner()),
          directional(SpawnKitFurnishings.bannerMaterial(layout.furnishings().rightBanner()),
              layout.exteriorFacing(offset)));
      default -> throw new IllegalArgumentException("Not an upgradeable role: " + role);
    };
  }

  private boolean hasSupport(World world, SpawnZoneLayout layout, int sx, int sy, int sz,
                             SpawnBlockPos offset, SpawnZoneLayout.Role role) {
    if (role == SpawnZoneLayout.Role.WALL_TORCH) {
      BlockFace facing = layout.wallTorchFacing(offset);
      return !isAir(blockAt(world, sx, sy, sz,
          offset.offset(-facing.getModX(), -facing.getModY(), -facing.getModZ())).getType());
    }
    if (role == SpawnZoneLayout.Role.EXTERIOR_TORCH
        || role == SpawnZoneLayout.Role.BANNER_LEFT
        || role == SpawnZoneLayout.Role.BANNER_RIGHT) {
      return !isAir(blockAt(world, sx, sy, sz, layout.exteriorMounting(offset)).getType());
    }
    return !isAir(blockAt(world, sx, sy, sz, offset.offset(0, -1, 0)).getType());
  }

  private boolean anyChestInRing(World world, SpawnZoneLayout layout, int sx, int sy, int sz) {
    for (SpawnBlockPos slot : layout.ringSlots()) {
      if (blockAt(world, sx, sy, sz, slot).getType() == Material.CHEST) return true;
    }
    return false;
  }

  private static boolean isAir(Material material) {
    return SpawnZonePolicy.isAir(material);
  }

  private Block blockAt(World world, int sx, int sy, int sz, SpawnBlockPos offset) {
    return world.getBlockAt(sx + offset.x(), sy + offset.y(), sz + offset.z());
  }

  private static String describe(SpawnZoneLayout.Role role) {
    return role.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
  }

  /**
   * Builds the entry steps below the doorstep: one plank tread per block of drop, up to the
   * configured maximum, each supported down to the terrain. When the ground past the door is
   * higher, a walkable approach is cleared instead. Non-natural blocks are never destroyed; a
   * column that is not replaceable is simply skipped.
   */
  private List<Runnable> planEntrySteps(World world, SpawnZoneLayout layout, int sx, int sz,
                                        int floorY, int minHeight, int maxHeight) {
    List<Runnable> operations = new ArrayList<>();
    int firstColumnZ = layout.exteriorZ() + 1;
    Integer firstGround = groundTop(world, sx, sz + firstColumnZ, minHeight);
    if (firstGround == null) return operations;
    SpawnZonePolicy.EntrySteps steps =
        SpawnZonePolicy.entrySteps(floorY, firstGround, SpawnZoneLayout.MAX_ENTRY_STEPS);
    int columnCount = steps.groundHigher()
        ? SpawnZoneLayout.MAX_ENTRY_STEPS : steps.count();
    for (int i = 0; i < columnCount; i++) {
      int bz = sz + firstColumnZ + i;
      if (!world.isChunkLoaded(sx >> 4, bz >> 4)) break;
      Integer ground = groundTop(world, sx, bz, minHeight);
      if (ground == null) continue;
      if (steps.groundHigher()) {
        // Clear a walkable approach: nothing but replaceable blocks above the doorstep level.
        for (int y = Math.max(minHeight, floorY + 1); y <= Math.min(ground, maxHeight - 1); y++) {
          Block block = world.getBlockAt(sx, y, bz);
          if (SpawnZonePolicy.isReplaceableByBuild(block.getType()) && !isAir(block.getType())) {
            operations.add(place(block, Material.AIR, null));
          }
        }
        continue;
      }
      int treadY = steps.treads().get(i);
      if (ground >= treadY) continue; // the terrain already reaches this tread
      if (!replaceableColumn(world, sx, bz, ground + 1, treadY, minHeight, maxHeight)) {
        continue; // never destroy a non-natural block for a step
      }
      for (int y = ground + 1; y < treadY; y++) {
        operations.add(place(world.getBlockAt(sx, y, bz), FOUNDATION_MATERIAL, null));
      }
      operations.add(place(world.getBlockAt(sx, treadY, bz), FLOOR_MATERIAL, null));
    }
    return operations;
  }

  private boolean replaceableColumn(World world, int bx, int bz, int fromY, int toY,
                                    int minHeight, int maxHeight) {
    int start = Math.max(minHeight, fromY);
    int end = Math.min(toY, maxHeight - 1);
    for (int y = start; y <= end; y++) {
      if (!SpawnZonePolicy.isReplaceableByBuild(world.getBlockAt(bx, y, bz).getType())) {
        return false;
      }
    }
    return true;
  }

  private Integer groundTop(World world, int bx, int bz, int minHeight) {
    if (!world.isChunkLoaded(bx >> 4, bz >> 4)) return null;
    int y = world.getHighestBlockYAt(bx, bz, org.bukkit.HeightMap.MOTION_BLOCKING_NO_LEAVES);
    while (y > minHeight && SpawnZonePolicy.isClearableVegetation(world.getBlockAt(bx, y, bz).getType())) {
      y--;
    }
    if (SpawnZonePolicy.isAir(world.getBlockAt(bx, y, bz).getType())) return null;
    return y;
  }

  /** Runs the prepared operations in bounded batches, then calls {@code onComplete}. */
  void execute(Prepared prepared, Runnable onComplete) {
    List<Runnable> operations = prepared.operations();
    if (operations.isEmpty()) {
      onComplete.run();
      return;
    }
    int[] index = {0};
    plugin.getServer().getScheduler().runTaskTimer(plugin, task -> {
      int processed = 0;
      while (index[0] < operations.size() && processed < batchSize) {
        try {
          operations.get(index[0]).run();
        } catch (RuntimeException error) {
          plugin.getLogger().log(Level.WARNING, "Spawn zone block operation failed.", error);
        }
        index[0]++;
        processed++;
      }
      if (index[0] >= operations.size()) {
        task.cancel();
        onComplete.run();
      }
    }, 1L, 1L);
  }

  private List<int[]> buildColumns(int wallRadius) {
    List<int[]> columns = new ArrayList<>();
    for (int x = -wallRadius; x <= wallRadius; x++) {
      for (int z = -wallRadius; z <= wallRadius; z++) {
        columns.add(new int[] {x, z});
      }
    }
    // Walkable door step just outside the footprint.
    columns.add(new int[] {0, wallRadius + 1});
    return columns;
  }

  private List<int[]> coreColumns() {
    List<int[]> columns = new ArrayList<>();
    for (int x = -SpawnZoneLayout.CORE_RADIUS; x <= SpawnZoneLayout.CORE_RADIUS; x++) {
      for (int z = -SpawnZoneLayout.CORE_RADIUS; z <= SpawnZoneLayout.CORE_RADIUS; z++) {
        columns.add(new int[] {x, z});
      }
    }
    return columns;
  }

  private int surfaceAt(List<Column> sampled, int bx, int bz) {
    for (Column column : sampled) {
      if (column.x() == bx && column.z() == bz) return column.surfaceY();
    }
    return Integer.MIN_VALUE;
  }

  private int topAt(List<Column> sampled, int bx, int bz) {
    for (Column column : sampled) {
      if (column.x() == bx && column.z() == bz) return column.topY();
    }
    return Integer.MIN_VALUE;
  }

  private boolean roleEnabled(SpawnZoneLayout.Role role, boolean houseEnabled, boolean chestEnabled) {
    return switch (role) {
      case CORE -> true;
      case CHEST -> houseEnabled && chestEnabled;
      default -> houseEnabled;
    };
  }

  private Runnable place(Block block, Material material, org.bukkit.block.data.BlockData data) {
    return place(block, material, data, true);
  }

  /**
   * With {@code overwrite} false the operation is skipped when the block already is the wanted
   * block. That is used for the supply chest so a re-issued layout can never replace the chest
   * block (which would wipe whatever the players have stored in it).
   */
  private Runnable place(Block block, Material material, org.bukkit.block.data.BlockData data,
                         boolean overwrite) {
    return () -> {
      if (!overwrite && block.getType() == material) return;
      if (data != null) {
        block.setBlockData(data, false);
      } else if (material == Material.AIR) {
        block.setType(Material.AIR, false);
      } else {
        block.setType(material, false);
      }
    };
  }

  private Door doorData(Bisected.Half half) {
    Door door = (Door) Bukkit.createBlockData(DOOR_MATERIAL);
    door.setFacing(BlockFace.SOUTH);
    door.setHinge(Door.Hinge.LEFT);
    door.setHalf(half);
    return door;
  }

  private org.bukkit.block.data.BlockData directional(Material material, BlockFace facing) {
    Directional data = (Directional) Bukkit.createBlockData(material);
    data.setFacing(facing);
    return data;
  }

  private Furnace furnaceData(SpawnBlockPos offset) {
    Furnace furnace = (Furnace) Bukkit.createBlockData(FURNACE_MATERIAL);
    furnace.setFacing(roomFacing(offset));
    return furnace;
  }

  /** The direction a wall-mounted piece faces to look into the room, i.e. at the house centre. */
  private BlockFace roomFacing(SpawnBlockPos offset) {
    if (Math.abs(offset.z()) >= Math.abs(offset.x())) {
      return offset.z() <= 0 ? BlockFace.SOUTH : BlockFace.NORTH;
    }
    return offset.x() <= 0 ? BlockFace.EAST : BlockFace.WEST;
  }

  private Bed bedData(SpawnBlockPos offset, Bed.Part part, SpawnZoneLayout layout) {
    BlockFace facing = BlockFace.EAST;
    for (SpawnZoneLayout.BedPlacement placement : layout.bedPlacements()) {
      if (placement.foot().equals(offset) || placement.head().equals(offset)) {
        facing = placement.facing();
        break;
      }
    }
    Bed bed = (Bed) Bukkit.createBlockData(BED_MATERIAL);
    bed.setFacing(facing);
    bed.setPart(part);
    return bed;
  }

  private void fillChests(World world, SpawnZoneLayout layout, int sx, int spawnY, int sz,
                          List<SpawnKitContents.Stack> contents) {
    for (SpawnBlockPos offset : layout.chestOffsets()) {
      fillChest(world.getBlockAt(sx + offset.x(), spawnY + offset.y(), sz + offset.z()), contents);
    }
  }

  /** Fills any chest already present in the ring (used by an upgrade with {@code supplies}). */
  private void fillExistingChests(World world, SpawnZoneLayout layout, int sx, int sy, int sz,
                                  List<SpawnKitContents.Stack> contents) {
    List<SpawnBlockPos> cells = new ArrayList<>(layout.ringSlots());
    cells.addAll(layout.chestOffsets());
    for (SpawnBlockPos offset : cells) {
      Block block = blockAt(world, sx, sy, sz, offset);
      if (block.getType() == Material.CHEST) {
        fillChest(block, contents);
      }
    }
  }

  private void fillChest(Block block, List<SpawnKitContents.Stack> contents) {
    if (block.getType() != Material.CHEST) return;
    BlockState state = block.getState();
    if (!(state instanceof Chest chest)) return;
    chest.getPersistentDataContainer().set(ownedKey, PersistentDataType.BYTE, (byte) 1);
    if (!chest.update(true, false)) {
      plugin.getLogger().warning("Could not mark a spawn zone chest as owned.");
      return;
    }
    SpawnKitContents.fill(chest.getBlockInventory(), contents);
  }

  /** Re-seats the protected core and clears the arrival column; returns the number of repairs. */
  int repairZone(World world, SpawnZoneLayout layout, int spawnX, int spawnY, int spawnZ) {
    int repaired = 0;
    for (SpawnBlockPos offset : layout.offsetsWithRole(SpawnZoneLayout.Role.CORE)) {
      Block block = world.getBlockAt(spawnX + offset.x(), spawnY + offset.y(), spawnZ + offset.z());
      if (block.getType() != CORE_MATERIAL) {
        block.setType(CORE_MATERIAL, false);
        repaired++;
      }
    }
    SpawnRegion arrival = layout.arrival();
    for (int x = arrival.minX(); x <= arrival.maxX(); x++) {
      for (int y = arrival.minY(); y <= arrival.maxY(); y++) {
        for (int z = arrival.minZ(); z <= arrival.maxZ(); z++) {
          Block block = world.getBlockAt(spawnX + x, spawnY + y, spawnZ + z);
          if (!SpawnZonePolicy.isAir(block.getType())) {
            block.setType(Material.AIR, false);
            repaired++;
          }
        }
      }
    }
    return repaired;
  }

  private record Column(int x, int z, int surfaceY, int topY) {}
}
