package com.awepeeps.spawnkit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.TileState;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.type.Door;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

final class SpawnKitShelter {
  private static final int HOUSE_RADIUS = 3;
  private static final Set<Material> NATURAL_FLOORS = Set.of(
      Material.GRASS_BLOCK, Material.DIRT, Material.COARSE_DIRT, Material.ROOTED_DIRT,
      Material.PODZOL, Material.MYCELIUM, Material.MUD, Material.CLAY, Material.SAND,
      Material.RED_SAND, Material.GRAVEL, Material.SNOW_BLOCK, Material.STONE,
      Material.ANDESITE, Material.DIORITE, Material.GRANITE, Material.TUFF, Material.DEEPSLATE);
  private static final Set<Material> UNSAFE_FLOORS = Set.of(
      Material.CACTUS, Material.MAGMA_BLOCK, Material.CAMPFIRE, Material.SOUL_CAMPFIRE,
      Material.FIRE, Material.SOUL_FIRE, Material.POWDER_SNOW, Material.SWEET_BERRY_BUSH,
      Material.POINTED_DRIPSTONE, Material.SLIME_BLOCK, Material.HONEY_BLOCK);

  private final JavaPlugin plugin;
  private final NamespacedKey ownedKey;
  private final int searchRadius;

  SpawnKitShelter(JavaPlugin plugin, NamespacedKey ownedKey, int searchRadius) {
    this.plugin = plugin;
    this.ownedKey = ownedKey;
    this.searchRadius = searchRadius;
  }

  Location build(World world, boolean houseEnabled, boolean markerEnabled,
                 boolean chestEnabled, List<SpawnKitContents.Stack> contents) {
    Site site = findSite(world, houseEnabled);
    if (site == null) return null;

    Location originalSpawn = world.getSpawnLocation();
    Map<Block, BlockState> originals = new LinkedHashMap<>();
    try {
      if (houseEnabled) {
        buildFoundations(world, site, originals);
        for (int dx = -HOUSE_RADIUS; dx <= HOUSE_RADIUS; dx++) {
          for (int dz = -HOUSE_RADIUS; dz <= HOUSE_RADIUS; dz++) {
            set(world.getBlockAt(site.x() + dx, site.y(), site.z() + dz),
                dx == 0 && dz == 0 && markerEnabled ? Material.LODESTONE : Material.OAK_PLANKS,
                originals);
          }
        }
        buildWallsAndRoof(world, site, originals);
        buildDoor(world, site, originals);
        placeBed(world, site, originals);
        if (chestEnabled) placeChest(world, site, contents, originals);
        placeHouseTorches(world, site, originals);
      } else if (markerEnabled) {
        set(world.getBlockAt(site.x(), site.y() - 1, site.z()), Material.LODESTONE, originals);
      }

      if (houseEnabled) placeOutsideTorches(world, site, originals);
      Location spawn = new Location(world, site.x() + 0.5,
          site.y() + (houseEnabled ? 1 : 0), site.z() + 0.5);
      if (!world.setSpawnLocation(spawn)) {
        throw new IllegalStateException("Could not move world spawn to the shelter.");
      }
      return spawn;
    } catch (RuntimeException error) {
      rollback(originals);
      if (!world.setSpawnLocation(originalSpawn)) {
        plugin.getLogger().severe("Could not restore the previous world spawn after shelter placement failed.");
      }
      plugin.getLogger().log(Level.SEVERE, "Could not safely build the configured spawn shelter.", error);
      return null;
    }
  }

  private Site findSite(World world, boolean houseEnabled) {
    Location origin = world.getSpawnLocation();
    List<int[]> offsets = new ArrayList<>();
    for (int dx = -searchRadius; dx <= searchRadius; dx++) {
      for (int dz = -searchRadius; dz <= searchRadius; dz++) {
        offsets.add(new int[] {dx, dz});
      }
    }
    offsets.sort(Comparator.comparingInt(offset -> offset[0] * offset[0] + offset[1] * offset[1]));

    int centerX = origin.getBlockX();
    int centerZ = origin.getBlockZ();
    for (int[] offset : offsets) {
      int x = centerX + offset[0];
      int z = centerZ + offset[1];
      List<SurfaceColumn> columns = new ArrayList<>();
      int lowestSurfaceY = Integer.MAX_VALUE;
      int highestSurfaceY = Integer.MIN_VALUE;
      boolean loaded = true;
      int radius = houseEnabled ? HOUSE_RADIUS : 0;
      for (int dx = -radius; dx <= radius && loaded; dx++) {
        for (int dz = -radius; dz <= radius && loaded; dz++) {
          loaded = addSurfaceColumn(world, x + dx, z + dz, columns);
        }
      }
      if (houseEnabled && loaded) {
        for (int[] torchOffset : outsideTorchOffsets()) {
          if (!addSurfaceColumn(
              world, x + torchOffset[0], z + torchOffset[1], columns)) {
            loaded = false;
            break;
          }
        }
      }
      if (!loaded) continue;
      for (SurfaceColumn column : columns) {
        lowestSurfaceY = Math.min(lowestSurfaceY, column.surfaceY());
        highestSurfaceY = Math.max(highestSurfaceY, column.surfaceY());
      }
      if (!SpawnKitPolicy.canLevelShelterTerrain(lowestSurfaceY, highestSurfaceY)) continue;
      int floorY = highestSurfaceY + 1;
      if (floorY <= world.getMinHeight() || floorY + 4 >= world.getMaxHeight()) continue;
      if (Math.abs((long) floorY - origin.getBlockY()) > SpawnKitPolicy.MAX_VERTICAL_SEARCH) continue;
      boolean valid = true;
      int clearThrough = floorY + (houseEnabled ? 4 : 1);
      for (SurfaceColumn column : columns) {
        if (!safeSurfaceColumn(world, column, floorY, clearThrough)) {
          valid = false;
          break;
        }
      }
      if (!valid) continue;
      return new Site(x, floorY, z);
    }
    return null;
  }

  private boolean addSurfaceColumn(World world, int x, int z, List<SurfaceColumn> columns) {
    if (!world.isChunkLoaded(x >> 4, z >> 4)) return false;
    int surfaceY = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
    columns.add(new SurfaceColumn(x, z, surfaceY));
    return true;
  }

  private boolean safeSurfaceColumn(
      World world, SurfaceColumn column, int floorY, int clearThrough) {
    int x = column.x();
    int z = column.z();
    if (!world.getWorldBorder().isInside(new Location(world, x + 0.5, floorY, z + 0.5))) return false;
    if (floorY - 1 - column.surfaceY() > SpawnKitPolicy.MAX_SHELTER_TERRAIN_VARIATION) return false;
    Block floor = world.getBlockAt(x, column.surfaceY(), z);
    Material floorType = floor.getType();
    if (floor.getState() instanceof TileState) return false;
    boolean leaves = floorType.name().equals("LEAVES") || floorType.name().endsWith("_LEAVES");
    if (!NATURAL_FLOORS.contains(floorType)
        || !SpawnKitPolicy.safeFloor(
            floorType.isSolid(), floor.isLiquid(), leaves, UNSAFE_FLOORS.contains(floorType))) return false;
    for (int y = column.surfaceY() + 1; y <= clearThrough; y++) {
      Block block = world.getBlockAt(x, y, z);
      if (!block.getType().isAir() || block.getState() instanceof org.bukkit.block.Container) return false;
    }
    return true;
  }

  private void buildFoundations(World world, Site site, Map<Block, BlockState> originals) {
    List<int[]> supports = new ArrayList<>();
    for (int dx = -HOUSE_RADIUS; dx <= HOUSE_RADIUS; dx++) {
      for (int dz = -HOUSE_RADIUS; dz <= HOUSE_RADIUS; dz++) {
        supports.add(new int[] {dx, dz});
      }
    }
    supports.addAll(outsideTorchOffsets());
    for (int[] offset : supports) {
      int x = site.x() + offset[0];
      int z = site.z() + offset[1];
      int surfaceY = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
      for (int y = surfaceY + 1; y < site.y(); y++) {
        set(world.getBlockAt(x, y, z), Material.COBBLESTONE, originals);
      }
    }
  }

  private List<int[]> outsideTorchOffsets() {
    return List.of(
        new int[] {-4, 0}, new int[] {4, 0}, new int[] {0, -4}, new int[] {0, 4});
  }

  private void buildWallsAndRoof(World world, Site site, Map<Block, BlockState> originals) {
    for (int dx = -HOUSE_RADIUS; dx <= HOUSE_RADIUS; dx++) {
      for (int dz = -HOUSE_RADIUS; dz <= HOUSE_RADIUS; dz++) {
        boolean wall = Math.abs(dx) == HOUSE_RADIUS || Math.abs(dz) == HOUSE_RADIUS;
        for (int dy = 1; dy <= 3; dy++) {
          if (wall) set(world.getBlockAt(site.x() + dx, site.y() + dy, site.z() + dz),
              Material.COBBLESTONE, originals);
        }
        set(world.getBlockAt(site.x() + dx, site.y() + 4, site.z() + dz),
            Material.OAK_PLANKS, originals);
      }
    }
  }

  private void buildDoor(World world, Site site, Map<Block, BlockState> originals) {
    Block lower = world.getBlockAt(site.x(), site.y() + 1, site.z() + HOUSE_RADIUS);
    Block upper = lower.getRelative(0, 1, 0);
    Door bottomData = (Door) Bukkit.createBlockData(Material.OAK_DOOR);
    bottomData.setFacing(BlockFace.SOUTH);
    bottomData.setHalf(Bisected.Half.BOTTOM);
    Door topData = (Door) bottomData.clone();
    topData.setHalf(Bisected.Half.TOP);
    set(lower, Material.OAK_DOOR, originals);
    lower.setBlockData(bottomData, false);
    set(upper, Material.OAK_DOOR, originals);
    upper.setBlockData(topData, false);
  }

  private void placeBed(World world, Site site, Map<Block, BlockState> originals) {
    Block foot = world.getBlockAt(site.x() + 1, site.y() + 1, site.z());
    Block head = foot.getRelative(0, 0, -1);
    Bed footData = (Bed) Bukkit.createBlockData(Material.WHITE_BED);
    footData.setFacing(BlockFace.NORTH);
    footData.setPart(Bed.Part.FOOT);
    Bed headData = (Bed) footData.clone();
    headData.setPart(Bed.Part.HEAD);
    set(foot, Material.WHITE_BED, originals);
    foot.setBlockData(footData, false);
    set(head, Material.WHITE_BED, originals);
    head.setBlockData(headData, false);
  }

  private void placeChest(World world, Site site, List<SpawnKitContents.Stack> contents,
                          Map<Block, BlockState> originals) {
    Block block = world.getBlockAt(site.x() - 1, site.y() + 1, site.z());
    set(block, Material.CHEST, originals);
    BlockState state = block.getState();
    if (!(state instanceof Chest chest)) throw new IllegalStateException("Configured chest is not a chest block.");
    chest.getPersistentDataContainer().set(ownedKey, PersistentDataType.BYTE, (byte) 1);
    if (!chest.update(true, false)) throw new IllegalStateException("Could not mark the starter chest as owned.");
    SpawnKitContents.fill(chest.getBlockInventory(), contents);
  }

  private void placeHouseTorches(World world, Site site, Map<Block, BlockState> originals) {
    for (int[] offset : List.of(
        new int[] {-2, -2}, new int[] {-2, 2}, new int[] {2, -2}, new int[] {2, 2})) {
      set(world.getBlockAt(site.x() + offset[0], site.y() + 1, site.z() + offset[1]),
          Material.TORCH, originals);
    }
  }

  private void placeOutsideTorches(World world, Site site, Map<Block, BlockState> originals) {
    for (int[] offset : outsideTorchOffsets()) {
      set(world.getBlockAt(site.x() + offset[0], site.y(), site.z() + offset[1]),
          Material.TORCH, originals);
    }
  }

  private void set(Block block, Material material, Map<Block, BlockState> originals) {
    originals.putIfAbsent(block, block.getState());
    block.setType(material, false);
  }

  private void rollback(Map<Block, BlockState> originals) {
    List<BlockState> states = new ArrayList<>(originals.values());
    boolean failed = false;
    for (int i = states.size() - 1; i >= 0; i--) {
      if (!states.get(i).update(true, false)) {
        failed = true;
      }
    }
    if (failed) plugin.getLogger().severe(
        "Could not roll back every block after spawn shelter placement failed.");
  }

  record Site(int x, int y, int z) {}

  private record SurfaceColumn(int x, int z, int surfaceY) {}
}
