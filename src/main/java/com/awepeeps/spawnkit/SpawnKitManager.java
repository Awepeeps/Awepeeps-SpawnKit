package com.awepeeps.spawnkit;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

final class SpawnKitManager {
  private static final int SEARCH_RADIUS = 8;
  private static final int DISCOVERY_Y = 8;
  private static final Set<Material> UNSAFE_FLOORS = Set.of(
      Material.CACTUS, Material.MAGMA_BLOCK, Material.CAMPFIRE, Material.SOUL_CAMPFIRE,
      Material.FIRE, Material.SOUL_FIRE, Material.POWDER_SNOW, Material.SWEET_BERRY_BUSH,
      Material.POINTED_DRIPSTONE, Material.SLIME_BLOCK, Material.HONEY_BLOCK);

  private final JavaPlugin plugin;
  private final List<SpawnKitContents.Stack> stacks;
  private final int shelterSearchRadius;
  private final NamespacedKey ownedKey;
  private final SpawnKitShelter shelter;
  private final File storeFile;
  private final YamlConfiguration store;
  private final Map<String, Long> retryAfterNanos = new HashMap<>();
  private final Map<String, Long> nextNoCandidateLogNanos = new HashMap<>();
  private final Map<String, Long> nextMarkerUpdateNanos = new HashMap<>();
  private final Set<String> incompleteSetupReported = new HashSet<>();
  private boolean invalidConfigLogged;

  SpawnKitManager(JavaPlugin plugin, List<SpawnKitContents.Stack> stacks, int shelterSearchRadius) {
    this.plugin = plugin;
    this.stacks = stacks;
    this.shelterSearchRadius = shelterSearchRadius;
    this.ownedKey = new NamespacedKey(plugin, "spawn_kit_chest");
    this.shelter = new SpawnKitShelter(plugin, ownedKey, shelterSearchRadius);
    this.storeFile = new File(plugin.getDataFolder(), "issued-kits.yml");
    this.store = YamlConfiguration.loadConfiguration(storeFile);
  }

  void tick() {
    boolean enabled = plugin.getConfig().getBoolean("spawn-kit.enabled", false);
    if (!SpawnKitPolicy.isActive(
        enabled,
        plugin.getConfig().getString("spawn-kit.starts-on", ""),
        plugin.getConfig().getString("spawn-kit.expires-on", ""),
        plugin.getConfig().getString("spawn-kit.timezone", ""),
        Instant.now())) {
      if (enabled && !invalidConfigLogged) {
        invalidConfigLogged = true;
        plugin.getLogger().warning(
            "Spawn Kit is disabled: configure a valid date window and timezone.");
      }
      return;
    }
    invalidConfigLogged = false;

    World world = Bukkit.getWorlds().stream()
        .filter(candidate -> candidate.getEnvironment() == World.Environment.NORMAL)
        .findFirst().orElse(null);
    if (world != null) ensureWorldSetup(world);
  }

  private void ensureWorldSetup(World world) {
    String worldPath = "worlds." + world.getUID();
    boolean houseEnabled = plugin.getConfig().getBoolean("spawn-kit.house.enabled", false);
    boolean markerEnabled = plugin.getConfig().getBoolean("spawn-kit.spawn-marker.enabled", false);
    boolean chestEnabled = plugin.getConfig().getBoolean("spawn-kit.house.chest.enabled", false);
    if (!houseEnabled && !markerEnabled && chestEnabled) {
      ensureChest(world);
      return;
    }
    if (!houseEnabled && !markerEnabled) return;

    boolean setupComplete = store.getBoolean(worldPath + ".setup-complete", false);
    if (!setupComplete && store.getBoolean(worldPath + ".setup-in-progress", false)) {
      String worldId = world.getUID().toString();
      if (incompleteSetupReported.add(worldId)) {
        plugin.getLogger().severe(
            "Spawn Kit setup was interrupted for world " + world.getName()
                + "; refusing to build another shelter until the world/setup record is reviewed.");
      }
      return;
    }
    if (!setupComplete) {
      long now = System.nanoTime();
      String worldId = world.getUID().toString();
      if (now < retryAfterNanos.getOrDefault(worldId, 0L)) return;
      store.set(worldPath + ".setup-in-progress", true);
      if (!saveStore("Could not reserve the spawn setup record; no shelter was built.")) {
        store.set(worldPath + ".setup-in-progress", false);
        retryAfterNanos.put(worldId, now + TimeUnit.SECONDS.toNanos(30));
        return;
      }
      Location spawn = shelter.build(world, houseEnabled, markerEnabled, chestEnabled, stacks);
      if (spawn == null) {
        store.set(worldPath + ".setup-in-progress", false);
        saveStore("Could not clear the spawn setup reservation after a safe site was not found.");
        retryAfterNanos.put(worldId, now + TimeUnit.SECONDS.toNanos(30));
        if (now >= nextNoCandidateLogNanos.getOrDefault(worldId, 0L)) {
          plugin.getLogger().info("Spawn Kit setup deferred: no safe, natural, loaded site was found within "
              + shelterSearchRadius + " blocks of world spawn.");
          nextNoCandidateLogNanos.put(worldId, now + TimeUnit.MINUTES.toNanos(5));
        }
        return;
      }
      store.set(worldPath + ".setup-complete", true);
      store.set(worldPath + ".setup-in-progress", false);
      store.set(worldPath + ".house-built", houseEnabled);
      store.set(worldPath + ".chest-placed", chestEnabled);
      store.set(worldPath + ".spawn-x", spawn.getBlockX());
      store.set(worldPath + ".spawn-y", spawn.getBlockY());
      store.set(worldPath + ".spawn-z", spawn.getBlockZ());
      if (!saveStore("Spawn shelter was built, but its location could not be recorded.")) {
        plugin.getLogger().severe(
            "Spawn shelter placement completed without a durable record; verify this world before restarting.");
      }
      retryAfterNanos.remove(worldId);
      nextNoCandidateLogNanos.remove(worldId);
      incompleteSetupReported.remove(worldId);
    }

    if (markerEnabled) {
      int x = store.getInt(worldPath + ".spawn-x", world.getSpawnLocation().getBlockX());
      int y = store.getInt(worldPath + ".spawn-y", world.getSpawnLocation().getBlockY());
      int z = store.getInt(worldPath + ".spawn-z", world.getSpawnLocation().getBlockZ());
      Block spawnFloor = world.getBlockAt(x, y - 1, z);
      if (store.getBoolean(worldPath + ".house-built", false)
          && spawnFloor.getType() == Material.OAK_PLANKS) {
        spawnFloor.setType(Material.LODESTONE, false);
      }
      long now = System.nanoTime();
      String worldId = world.getUID().toString();
      if (now >= nextMarkerUpdateNanos.getOrDefault(worldId, 0L)) {
        String label = plugin.getConfig().getString("spawn-kit.spawn-marker.name", "World Spawn");
        BlueMapSpawnMarker.update(plugin, world, x, y, z, label);
        nextMarkerUpdateNanos.put(worldId, now + TimeUnit.MINUTES.toNanos(1));
      }
    }
  }

  private void ensureChest(World world) {
    String worldId = world.getUID().toString();
    long now = System.nanoTime();
    if (now < retryAfterNanos.getOrDefault(worldId, 0L)) return;
    String worldPath = "worlds." + world.getUID();
    boolean persistedTombstone = store.getBoolean(worldPath + ".issued", false);
    boolean hasSavedPosition = store.contains(worldPath + ".x")
        && store.contains(worldPath + ".y") && store.contains(worldPath + ".z");
    if (persistedTombstone || hasSavedPosition) {
      if (!persistedTombstone) reserveIssuance(worldPath);
      return;
    }

    int spawnRadius = Bukkit.getSpawnRadius();
    Block discovered = findOwnedNearSpawn(world, SpawnKitPolicy.MAX_SEARCH_RADIUS);
    if (discovered != null) {
      reserveIssuance(worldPath);
      remember(world, discovered);
      return;
    }

    int permittedSearchRadius = SpawnKitPolicy.searchRadius(spawnRadius, SEARCH_RADIUS);
    if (permittedSearchRadius < 0) {
      retryLater(worldId, now);
      return;
    }
    SearchResult search = findSafeCandidate(world, permittedSearchRadius, spawnRadius);
    if (search.block() == null) {
      retryLater(worldId, now);
      if (now >= nextNoCandidateLogNanos.getOrDefault(worldId, 0L)) {
        plugin.getLogger().info("Spawn Kit placement deferred: no safe loaded surface in the bounded spawn search "
            + "(columns=" + search.columns() + ", loaded=" + search.loadedColumns()
            + ", surfaces=" + search.surfacesInRange() + ", safeFloors=" + search.safeFloors()
            + ", clearSpots=" + search.clearSpots() + ").");
        nextNoCandidateLogNanos.put(worldId, now + TimeUnit.MINUTES.toNanos(5));
      }
      return;
    }

    // Reserve before editing the world: a persistence failure must leave terrain untouched.
    if (!reserveIssuance(worldPath)) return;
    Block candidate = search.block();
    candidate.setType(Material.CHEST, false);
    BlockState state = candidate.getState();
    if (!(state instanceof Chest chest)) {
      candidate.setType(Material.AIR, false);
      return;
    }
    chest.getPersistentDataContainer().set(ownedKey, PersistentDataType.BYTE, (byte) 1);
    if (!chest.update(true, false)) {
      if (candidate.getType() == Material.CHEST) candidate.setType(Material.AIR, false);
      return;
    }

    remember(world, candidate);
    BlockState placedState = candidate.getState();
    if (placedState instanceof Chest placedChest) {
      SpawnKitContents.fill(placedChest.getBlockInventory(), stacks);
    }
    retryAfterNanos.remove(worldId);
    nextNoCandidateLogNanos.remove(worldId);
  }

  private void retryLater(String worldId, long now) {
    retryAfterNanos.put(worldId, now + TimeUnit.SECONDS.toNanos(30));
  }

  private Block findOwnedNearSpawn(World world, int radius) {
    var spawn = world.getSpawnLocation();
    int centerX = spawn.getBlockX();
    int centerY = spawn.getBlockY();
    int centerZ = spawn.getBlockZ();
    for (int chunkX = (centerX - radius) >> 4; chunkX <= (centerX + radius) >> 4; chunkX++) {
      for (int chunkZ = (centerZ - radius) >> 4; chunkZ <= (centerZ + radius) >> 4; chunkZ++) {
        if (!world.isChunkLoaded(chunkX, chunkZ)) continue;
        for (var state : world.getChunkAt(chunkX, chunkZ).getTileEntities()) {
          if (!(state instanceof Chest chest)) continue;
          Block block = chest.getBlock();
          if (Math.abs(block.getX() - centerX) <= radius
              && Math.abs(block.getZ() - centerZ) <= radius
              && Math.abs(block.getY() - centerY) <= DISCOVERY_Y
              && isOwned(block)) return block;
        }
      }
    }
    return null;
  }

  private SearchResult findSafeCandidate(World world, int radius, int protectionRadius) {
    var spawn = world.getSpawnLocation();
    int centerX = spawn.getBlockX();
    int centerY = spawn.getBlockY();
    int centerZ = spawn.getBlockZ();
    List<int[]> offsets = new ArrayList<>();
    for (int dx = -radius; dx <= radius; dx++) {
      for (int dz = -radius; dz <= radius; dz++) {
        if (dx * dx + dz * dz <= radius * radius
            && SpawnKitPolicy.outsideProtection(dx, dz, protectionRadius)) {
          offsets.add(new int[] {dx, dz});
        }
      }
    }
    offsets.sort(Comparator.comparingInt(offset -> offset[0] * offset[0] + offset[1] * offset[1]));
    int loadedColumns = 0;
    int surfacesInRange = 0;
    int safeFloors = 0;
    int clearSpots = 0;
    for (int[] offset : offsets) {
      int x = centerX + offset[0];
      int z = centerZ + offset[1];
      if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
      loadedColumns++;
      int surfaceBlockY = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
      Integer y = SpawnKitPolicy.surfacePlacementY(
          surfaceBlockY, centerY, world.getMinHeight(), world.getMaxHeight());
      if (y == null) continue;
      surfacesInRange++;
      Block floor = world.getBlockAt(x, y - 1, z);
      Material floorType = floor.getType();
      boolean leaves = floorType.name().equals("LEAVES") || floorType.name().endsWith("_LEAVES");
      boolean hazardous = UNSAFE_FLOORS.contains(floorType);
      if (!SpawnKitPolicy.safeFloor(floorType.isSolid(), floor.isLiquid(), leaves, hazardous)) continue;
      safeFloors++;
      Block spot = world.getBlockAt(x, y, z);
      Block headroom = world.getBlockAt(x, y + 1, z);
      boolean nextToChest = nextToChest(spot);
      if (!SpawnKitPolicy.safePlacement(
          spot.getType().isAir(), headroom.getType().isAir(),
          world.getWorldBorder().isInside(spot.getLocation()), nextToChest)) continue;
      clearSpots++;
      return new SearchResult(spot, offsets.size(), loadedColumns, surfacesInRange, safeFloors, clearSpots);
    }
    return new SearchResult(null, offsets.size(), loadedColumns, surfacesInRange, safeFloors, clearSpots);
  }

  private boolean nextToChest(Block block) {
    for (Block adjacent : List.of(
        block.getRelative(1, 0, 0), block.getRelative(-1, 0, 0),
        block.getRelative(0, 0, 1), block.getRelative(0, 0, -1))) {
      if (!adjacent.getWorld().isChunkLoaded(adjacent.getX() >> 4, adjacent.getZ() >> 4)) return true;
      Material type = adjacent.getType();
      if (type == Material.CHEST || type == Material.TRAPPED_CHEST) return true;
    }
    return false;
  }

  private boolean isOwned(Block block) {
    if (block.getType() != Material.CHEST) return false;
    BlockState state = block.getState();
    return state instanceof Chest chest
        && chest.getPersistentDataContainer().has(ownedKey, PersistentDataType.BYTE);
  }

  private void remember(World world, Block chest) {
    String worldPath = "worlds." + world.getUID();
    store.set(worldPath + ".name", world.getName());
    store.set(worldPath + ".x", chest.getX());
    store.set(worldPath + ".y", chest.getY());
    store.set(worldPath + ".z", chest.getZ());
    saveStore("Could not persist test supply chest location; ownership marker remains on the chest.");
  }

  private boolean reserveIssuance(String worldPath) {
    store.set(worldPath + ".issued", true);
    return saveStore("Could not persist test supply issuance; no chest was placed.");
  }

  private boolean saveStore(String failureMessage) {
    try {
      store.save(storeFile);
      return true;
    } catch (IOException error) {
      plugin.getLogger().log(Level.WARNING, failureMessage, error);
      return false;
    }
  }

  private record SearchResult(
      Block block, int columns, int loadedColumns, int surfacesInRange, int safeFloors, int clearSpots) {}
}
