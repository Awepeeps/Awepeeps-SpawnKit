package com.awepeeps.spawnkit;

import java.util.ArrayList;
import java.util.List;
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
    for (Column column : sampled) {
      if (!world.getWorldBorder().isInside(
          new Location(world, column.x() + 0.5, scanFloor, column.z() + 0.5))) {
        outsideBorder = true;
      }
      for (int y = scanFloor - layout.coreDepth() - 1;
           y <= scanFloor + layout.wallHeight() + 1; y++) {
        if (y < minHeight || y >= maxHeight) continue;
        if (existingCore != null
            && existingCore.contains(column.x(), y, column.z())) {
          continue; // Our own protected core from a previous pass.
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
        case DOOR_LOWER -> place(block, DOOR_MATERIAL, doorData(Bisected.Half.BOTTOM));
        case DOOR_UPPER -> place(block, DOOR_MATERIAL, doorData(Bisected.Half.TOP));
        case BED_FOOT -> place(block, BED_MATERIAL, bedData(offset, Bed.Part.FOOT, layout));
        case BED_HEAD -> place(block, BED_MATERIAL, bedData(offset, Bed.Part.HEAD, layout));
        case CHEST -> place(block, Material.CHEST, null);
        case WALL_TORCH -> place(block, Material.WALL_TORCH, torchData(offset, layout));
      });
    }

    if (houseEnabled && chestEnabled && !contents.isEmpty()) {
      operations.add(() -> fillChests(world, layout, sx, spawnY, sz, contents));
    }

    SpawnRegion protectedRegion = layout.protectedRegion().translated(sx, spawnY, sz);
    return new Prepared(true, floorY, spawnY, null, operations, protectedRegion);
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
    return () -> {
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

  private Bed bedData(SpawnBlockPos offset, Bed.Part part, SpawnZoneLayout layout) {
    int headDirection = 1;
    for (SpawnZoneLayout.BedPlacement placement : layout.bedPlacements()) {
      if (placement.foot().equals(offset) || placement.head().equals(offset)) {
        headDirection = placement.headDirection();
        break;
      }
    }
    Bed bed = (Bed) Bukkit.createBlockData(BED_MATERIAL);
    bed.setFacing(headDirection >= 0 ? BlockFace.EAST : BlockFace.WEST);
    bed.setPart(part);
    return bed;
  }

  private org.bukkit.block.data.BlockData torchData(SpawnBlockPos offset, SpawnZoneLayout layout) {
    Directional torch = (Directional) Bukkit.createBlockData(Material.WALL_TORCH);
    int r = layout.innerRadius();
    BlockFace facing;
    if (offset.z() == r) {
      facing = BlockFace.NORTH; // mounted on the south wall, pointing into the room
    } else if (offset.z() == -r) {
      facing = BlockFace.SOUTH;
    } else if (offset.x() == r) {
      facing = BlockFace.WEST;
    } else {
      facing = BlockFace.EAST;
    }
    torch.setFacing(facing);
    return torch;
  }

  private void fillChests(World world, SpawnZoneLayout layout, int sx, int spawnY, int sz,
                          List<SpawnKitContents.Stack> contents) {
    for (SpawnBlockPos offset : layout.chestOffsets()) {
      Block block = world.getBlockAt(sx + offset.x(), spawnY + offset.y(), sz + offset.z());
      if (block.getType() != Material.CHEST) continue;
      BlockState state = block.getState();
      if (!(state instanceof Chest chest)) continue;
      chest.getPersistentDataContainer().set(ownedKey, PersistentDataType.BYTE, (byte) 1);
      if (!chest.update(true, false)) {
        plugin.getLogger().warning("Could not mark a spawn zone chest as owned.");
        continue;
      }
      SpawnKitContents.fill(chest.getBlockInventory(), contents);
    }
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
