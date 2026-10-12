package com.awepeeps.spawnkit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
  private static final List<String> REJECTION_ORDER = List.of(
      "unloaded chunks", "spawn protection", "unsafe ground", "uneven terrain",
      "height limits", "occupied blocks or world border");

  private final JavaPlugin plugin;
  private final NamespacedKey ownedKey;
  private final int searchRadius;

  SpawnKitShelter(JavaPlugin plugin, NamespacedKey ownedKey, int searchRadius) {
    this.plugin = plugin;
    this.ownedKey = ownedKey;
    this.searchRadius = searchRadius;
  }

  BuildResult build(World world, boolean houseEnabled, boolean markerEnabled,
                    boolean chestEnabled, int protectionRadius,
                    List<SpawnKitContents.Stack> contents) {
    SearchResult search = findSite(world, houseEnabled, chestEnabled, protectionRadius);
    Site site = search.site();
    if (site == null) return new BuildResult(null, search.summary());

    Location originalSpawn = world.getSpawnLocation();
    Map<Block, BlockState> originals = new LinkedHashMap<>();
    try {
      if (houseEnabled) {
        clearVegetation(world, site, originals);
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
      return new BuildResult(originalSpawn, search.summary());
    } catch (RuntimeException error) {
      rollback(originals);
      plugin.getLogger().log(Level.SEVERE, "Could not safely build the configured spawn shelter.", error);
      return new BuildResult(null, search.summary());
    }
  }

  private SearchResult findSite(
      World world, boolean houseEnabled, boolean chestEnabled, int protectionRadius) {
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
    SearchDiagnostics diagnostics = new SearchDiagnostics();
    for (int[] offset : offsets) {
      diagnostics.checked++;
      int x = centerX + offset[0];
      int z = centerZ + offset[1];
      if (houseEnabled && chestEnabled
          && !SpawnKitPolicy.outsideProtection(offset[0] - 1, offset[1], protectionRadius)) {
        diagnostics.reject("spawn protection");
        continue;
      }
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
      if (!loaded) {
        diagnostics.reject("unloaded chunks");
        continue;
      }
      for (SurfaceColumn column : columns) {
        lowestSurfaceY = Math.min(lowestSurfaceY, column.surfaceY());
        highestSurfaceY = Math.max(highestSurfaceY, column.surfaceY());
      }
      if (!SpawnKitPolicy.canLevelShelterTerrain(lowestSurfaceY, highestSurfaceY)) {
        diagnostics.reject("uneven terrain");
        continue;
      }
      int floorY = highestSurfaceY + 1;
      if (floorY <= world.getMinHeight() || floorY + 4 >= world.getMaxHeight()
          || Math.abs((long) floorY - origin.getBlockY()) > SpawnKitPolicy.MAX_VERTICAL_SEARCH) {
        diagnostics.reject("height limits");
        continue;
      }
      boolean valid = true;
      int clearThrough = floorY + (houseEnabled ? 4 : 1);
      for (SurfaceColumn column : columns) {
        String rejection = safeSurfaceColumn(world, column, floorY, clearThrough);
        if (rejection != null) {
          diagnostics.reject(rejection);
          valid = false;
          break;
        }
      }
      if (!valid) continue;
      return new SearchResult(new Site(x, floorY, z, columns), diagnostics.summary());
    }
    return new SearchResult(null, diagnostics.summary());
  }

  private boolean addSurfaceColumn(World world, int x, int z, List<SurfaceColumn> columns) {
    if (!world.isChunkLoaded(x >> 4, z >> 4)) return false;
    int surfaceY = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
    while (surfaceY > world.getMinHeight()
        && SpawnKitPolicy.isClearableVegetation(world.getBlockAt(x, surfaceY, z).getType())) {
      surfaceY--;
    }
    columns.add(new SurfaceColumn(x, z, surfaceY));
    return true;
  }

  private String safeSurfaceColumn(
      World world, SurfaceColumn column, int floorY, int clearThrough) {
    int x = column.x();
    int z = column.z();
    if (!world.getWorldBorder().isInside(new Location(world, x + 0.5, floorY, z + 0.5))) {
      return "occupied blocks or world border";
    }
    if (floorY - 1 - column.surfaceY() > SpawnKitPolicy.MAX_SHELTER_TERRAIN_VARIATION) {
      return "uneven terrain";
    }
    Block floor = world.getBlockAt(x, column.surfaceY(), z);
    Material floorType = floor.getType();
    if (floor.getState() instanceof TileState) return "unsafe ground";
    boolean leaves = floorType.name().equals("LEAVES") || floorType.name().endsWith("_LEAVES");
    if (!SpawnKitPolicy.isNaturalFloor(floorType)
        || !SpawnKitPolicy.safeFloor(
            floorType.isSolid(), floor.isLiquid(), leaves, false)) return "unsafe ground";
    for (int y = column.surfaceY() + 1; y <= clearThrough; y++) {
      Block block = world.getBlockAt(x, y, z);
      if (block.getType().isAir()) continue;
      if (block.getState() instanceof TileState || block.getState() instanceof org.bukkit.block.Container
          || !SpawnKitPolicy.isClearableVegetation(block.getType())) {
        return "occupied blocks or world border";
      }
    }
    return null;
  }

  private void clearVegetation(World world, Site site, Map<Block, BlockState> originals) {
    int clearThrough = site.y() + 4;
    for (SurfaceColumn column : site.columns()) {
      for (int y = column.surfaceY() + 1; y <= clearThrough; y++) {
        Block block = world.getBlockAt(column.x(), y, column.z());
        if (SpawnKitPolicy.isClearableVegetation(block.getType())) {
          set(block, Material.AIR, originals);
        }
      }
    }
  }

  private void buildFoundations(World world, Site site, Map<Block, BlockState> originals) {
    for (SurfaceColumn column : site.columns()) {
      for (int y = column.surfaceY() + 1; y < site.y(); y++) {
        set(world.getBlockAt(column.x(), y, column.z()), Material.COBBLESTONE, originals);
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

  record BuildResult(Location spawn, String diagnostics) {}

  private record SearchResult(Site site, String summary) {}

  private record Site(int x, int y, int z, List<SurfaceColumn> columns) {}

  private record SurfaceColumn(int x, int z, int surfaceY) {}

  private static final class SearchDiagnostics {
    private int checked;
    private final Map<String, Integer> rejected = new HashMap<>();

    private void reject(String reason) {
      rejected.merge(reason, 1, Integer::sum);
    }

    private String summary() {
      StringBuilder summary = new StringBuilder("candidates checked=").append(checked);
      List<String> reasons = new ArrayList<>(REJECTION_ORDER);
      reasons.sort((first, second) -> Integer.compare(
          rejected.getOrDefault(second, 0), rejected.getOrDefault(first, 0)));
      int shown = 0;
      for (String reason : reasons) {
        int count = rejected.getOrDefault(reason, 0);
        if (count == 0) break;
        summary.append(shown++ == 0 ? "; rejected: " : ", ")
            .append(reason).append('=').append(count);
        if (shown == 2) break;
      }
      return summary.toString();
    }
  }
}
