package com.awepeeps.spawnkit;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Orchestrates the protected spawn zone (always available, independent of the date window) and the
 * test-only house/chest kit. Persists a per-world record before touching the world, never builds
 * twice, repairs a damaged protected core, and defers with a reason and backoff when the spawn
 * terrain is unsuitable. Terrain is loaded asynchronously and ticketed only for the build.
 */
final class SpawnKitManager {
  private static final int LAYOUT_VERSION = 2;

  private final JavaPlugin plugin;
  private final List<SpawnKitContents.Stack> stacks;
  private final SpawnZoneLayout layout;
  private final SpawnZoneBuilder builder;
  private final SpawnZoneProtection protection = new SpawnZoneProtection();
  private final SpawnZoneSpawnControl spawnControl = new SpawnZoneSpawnControl();

  private final File storeFile;
  private final YamlConfiguration store;
  private final Map<String, Long> retryAfterNanos = new HashMap<>();
  private final Map<String, Integer> retryAttempts = new HashMap<>();
  private final Map<String, String> lastDeferral = new HashMap<>();
  private final Map<String, Long> nextMarkerUpdateNanos = new HashMap<>();
  private final Set<String> pendingSetups = new HashSet<>();
  private final Set<String> upgradeLogged = new HashSet<>();
  private final Set<String> interruptedLogged = new HashSet<>();
  private boolean invalidConfigLogged;

  SpawnKitManager(JavaPlugin plugin, SpawnZoneLayout layout, List<SpawnKitContents.Stack> stacks) {
    this.plugin = plugin;
    this.layout = layout;
    this.stacks = stacks;
    NamespacedKey ownedKey = new NamespacedKey(plugin, "spawn_kit_chest");
    this.builder = new SpawnZoneBuilder(plugin, ownedKey);
    this.storeFile = new File(plugin.getDataFolder(), "issued-kits.yml");
    this.store = YamlConfiguration.loadConfiguration(storeFile);
  }

  SpawnZoneProtection protection() {
    return protection;
  }

  SpawnZoneSpawnControl spawnControl() {
    return spawnControl;
  }

  SpawnZoneLayout layout() {
    return layout;
  }

  private boolean zoneEnabled() {
    return plugin.getConfig().getBoolean("spawn-zone.enabled", true);
  }

  private boolean houseConfigured() {
    return plugin.getConfig().getBoolean("spawn-kit.house.enabled", false);
  }

  private boolean chestConfigured() {
    return plugin.getConfig().getBoolean("spawn-kit.house.chest.enabled", false);
  }

  private boolean markerConfigured() {
    return plugin.getConfig().getBoolean("spawn-kit.spawn-marker.enabled", false);
  }

  private boolean manageSpawnRadius() {
    return plugin.getConfig().getBoolean("spawn-zone.manage-spawn-radius", true);
  }

  void tick() {
    boolean kitEnabled = plugin.getConfig().getBoolean("spawn-kit.enabled", false);
    boolean kitActive = SpawnKitPolicy.isActive(
        kitEnabled,
        plugin.getConfig().getString("spawn-kit.starts-on", ""),
        plugin.getConfig().getString("spawn-kit.expires-on", ""),
        plugin.getConfig().getString("spawn-kit.timezone", ""),
        Instant.now());
    if (kitEnabled && !kitActive && !invalidConfigLogged) {
      invalidConfigLogged = true;
      plugin.getLogger().warning(
          "Spawn Kit is inactive: configure a valid date window and timezone.");
    } else if (kitActive) {
      invalidConfigLogged = false;
    }

    World world = primaryOverworld();
    if (world == null) return;

    spawnControl.setEnabled(zoneEnabled());
    if (zoneEnabled()) {
      ensureZone(world);
    } else {
      restoreSpawnRadius(world);
    }
    if (kitActive) {
      ensureKit(world);
    }
    if (markerConfigured()) {
      updateSpawnMarker(world);
    }
  }

  private World primaryOverworld() {
    return Bukkit.getWorlds().stream()
        .filter(candidate -> candidate.getEnvironment() == World.Environment.NORMAL)
        .findFirst().orElse(null);
  }

  // ---- Protected zone -----------------------------------------------------------------------

  private void ensureZone(World world) {
    String worldPath = "worlds." + world.getUID();
    switch (classify(worldPath)) {
      case CURRENT -> {
        String worldId = world.getUID().toString();
        applyStoredZone(world, worldPath);
        if (!pendingSetups.contains(worldId)) {
          int repaired = repairZone(world, worldPath);
          if (repaired > 0) {
            plugin.getLogger().info("Repaired " + repaired
                + " protected spawn block(s) in world " + world.getName() + ".");
          }
        }
      }
      case IN_PROGRESS -> {
        if (interruptedLogged.add(world.getUID().toString())) {
          plugin.getLogger().warning(
              "Spawn zone setup was interrupted for world " + world.getName()
                  + "; refusing to build again until the world/setup record is reviewed.");
        }
      }
      case LEGACY_UNMIGRATED -> logUpgrade(world);
      case FRESH -> beginBuild(world, worldPath, "zone", false, false, false, null);
    }
  }

  private void applyStoredZone(World world, String worldPath) {
    int sx = store.getInt(worldPath + ".spawn-x");
    int sy = store.getInt(worldPath + ".spawn-y");
    int sz = store.getInt(worldPath + ".spawn-z");
    protection.setRegion(world, layout.protectedRegion().translated(sx, sy, sz));
    spawnControl.setSpawn(world, new Location(world, sx + 0.5, sy, sz + 0.5));
    Location current = world.getSpawnLocation();
    if (current.getBlockX() != sx || current.getBlockY() != sy || current.getBlockZ() != sz) {
      world.setSpawnLocation(sx, sy, sz);
    }
    applySpawnRadius(world, worldPath);
  }

  private int repairZone(World world, String worldPath) {
    return builder.repairZone(world, layout,
        store.getInt(worldPath + ".spawn-x"),
        store.getInt(worldPath + ".spawn-y"),
        store.getInt(worldPath + ".spawn-z"));
  }

  // ---- Test-only house kit ------------------------------------------------------------------

  private void ensureKit(World world) {
    if (!houseConfigured()) return;
    String worldPath = "worlds." + world.getUID();
    if (store.getBoolean(worldPath + ".setup-complete", false)) return;
    SpawnZonePolicy.SetupState state = classify(worldPath);
    if (state == SpawnZonePolicy.SetupState.LEGACY_UNMIGRATED) {
      logUpgrade(world);
      return;
    }
    if (state != SpawnZonePolicy.SetupState.CURRENT) return; // wait for the protected zone
    String worldId = world.getUID().toString();
    if (pendingSetups.contains(worldId)) return;
    SpawnRegion core = layout.protectedRegion().translated(
        store.getInt(worldPath + ".spawn-x"),
        store.getInt(worldPath + ".spawn-y"),
        store.getInt(worldPath + ".spawn-z"));
    beginBuild(world, worldPath, "house", true, chestConfigured(), true, core);
  }

  // ---- Build --------------------------------------------------------------------------------

  private void beginBuild(World world, String worldPath, String kind, boolean houseEnabled,
                          boolean chestEnabled, boolean supplies, SpawnRegion existingCore) {
    String worldId = world.getUID().toString();
    if (System.nanoTime() < retryAfterNanos.getOrDefault(worldId, 0L)) return;

    store.set(worldPath + ".setup-in-progress", true);
    if (!saveStore("Could not reserve the spawn setup record; nothing was built.")) {
      store.set(worldPath + ".setup-in-progress", false);
      retryLater(worldId);
      return;
    }
    pendingSetups.add(worldId);

    CompletableFuture<List<Chunk>> chunks;
    try {
      chunks = loadBuildChunks(world);
    } catch (RuntimeException error) {
      abandonBuild(worldPath, worldId, "terrain loading could not start");
      if (logDeferredOnce(worldId, "Spawn " + kind + " build deferred: terrain loading failed.")) {
        plugin.getLogger().log(Level.WARNING, "Spawn build could not start chunk loading.", error);
      }
      return;
    }
    chunks.whenComplete((loaded, error) -> Bukkit.getScheduler().runTask(plugin,
        () -> afterChunksLoaded(world, worldPath, kind, houseEnabled, chestEnabled, supplies,
            existingCore, loaded, error)));
  }

  private CompletableFuture<List<Chunk>> loadBuildChunks(World world) {
    Location spawn = world.getSpawnLocation();
    int extent = layout.wallRadius() + 2;
    SpawnKitPolicy.ChunkBounds bounds =
        SpawnKitPolicy.chunkBounds(spawn.getBlockX(), spawn.getBlockZ(), extent);
    List<CompletableFuture<Chunk>> futures = new ArrayList<>();
    for (int chunkX = bounds.minX(); chunkX <= bounds.maxX(); chunkX++) {
      for (int chunkZ = bounds.minZ(); chunkZ <= bounds.maxZ(); chunkZ++) {
        futures.add(world.getChunkAtAsync(chunkX, chunkZ, true, true));
      }
    }
    return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
        .thenApply(ignored -> futures.stream().map(CompletableFuture::join).toList());
  }

  private void afterChunksLoaded(World world, String worldPath, String kind, boolean houseEnabled,
                                 boolean chestEnabled, boolean supplies, SpawnRegion existingCore,
                                 List<Chunk> chunks, Throwable error) {
    String worldId = world.getUID().toString();
    if (error != null) {
      abandonBuild(worldPath, worldId, "terrain loading failed");
      if (logDeferredOnce(worldId,
          "Spawn " + kind + " build deferred: terrain loading failed: "
              + rootCause(error).getMessage() + ".")) {
        plugin.getLogger().log(Level.WARNING, "Spawn build could not load terrain.", rootCause(error));
      }
      return;
    }

    List<Chunk> ticketed = new ArrayList<>();
    boolean released = false;
    try {
      for (Chunk chunk : chunks) {
        if (!world.addPluginChunkTicket(chunk.getX(), chunk.getZ(), plugin)) {
          throw new IllegalStateException("Could not retain a loaded build chunk.");
        }
        ticketed.add(chunk);
      }
      SpawnZoneBuilder.Prepared prepared = builder.prepare(world, layout, houseEnabled, chestEnabled,
          supplies ? stacks : List.of(), existingCore);
      if (!prepared.accepted()) {
        releaseTickets(world, ticketed);
        released = true;
        abandonBuild(worldPath, worldId, "site refused");
        logDeferredOnce(worldId, "Spawn " + kind + " build deferred: " + prepared.defect() + ".");
        return;
      }

      store.set(worldPath + ".spawn-x", world.getSpawnLocation().getBlockX());
      store.set(worldPath + ".spawn-y", prepared.spawnY());
      store.set(worldPath + ".spawn-z", world.getSpawnLocation().getBlockZ());
      store.set(worldPath + ".layout-version", LAYOUT_VERSION);
      if (!saveStore("Could not persist the spawn zone location; nothing was built.")) {
        releaseTickets(world, ticketed);
        abandonBuild(worldPath, worldId, "persistence failed");
        return;
      }

      builder.execute(prepared, () -> {
        releaseTickets(world, ticketed);
        completeBuild(world, worldPath, kind, houseEnabled, chestEnabled);
      });
    } catch (RuntimeException failure) {
      if (!released) releaseTickets(world, ticketed);
      abandonBuild(worldPath, worldId, "placement failed");
      plugin.getLogger().log(Level.WARNING, "Spawn " + kind + " setup failed.", failure);
    }
  }

  private void releaseTickets(World world, List<Chunk> ticketed) {
    for (Chunk chunk : ticketed) {
      world.removePluginChunkTicket(chunk.getX(), chunk.getZ(), plugin);
    }
  }

  private void completeBuild(World world, String worldPath, String kind,
                             boolean houseEnabled, boolean chestEnabled) {
    String worldId = world.getUID().toString();
    pendingSetups.remove(worldId);
    store.set(worldPath + ".spawn-zone-complete", true);
    store.set(worldPath + ".layout-version", LAYOUT_VERSION);
    if (houseEnabled) {
      store.set(worldPath + ".setup-complete", true);
      store.set(worldPath + ".house-built", true);
    }
    if (chestEnabled) {
      store.set(worldPath + ".chest-placed", true);
    }
    store.set(worldPath + ".setup-in-progress", false);
    saveStore("Spawn " + kind + " was built, but the record could not be saved; verify before restarting.");

    applyStoredZone(world, worldPath);
    retryAfterNanos.remove(worldId);
    retryAttempts.remove(worldId);
    lastDeferral.remove(worldId);
    interruptedLogged.remove(worldId);
    upgradeLogged.remove(worldId);
    teleportPlayersOutOfFootprint(world, worldPath);
    plugin.getLogger().info("Built the " + kind + " spawn layout in world " + world.getName() + ".");
  }

  private void abandonBuild(String worldPath, String worldId, String reason) {
    pendingSetups.remove(worldId);
    store.set(worldPath + ".setup-in-progress", false);
    saveStore("Could not clear the spawn setup reservation after " + reason + ".");
    retryLater(worldId);
  }

  private void teleportPlayersOutOfFootprint(World world, String worldPath) {
    int sx = store.getInt(worldPath + ".spawn-x");
    int sy = store.getInt(worldPath + ".spawn-y");
    int sz = store.getInt(worldPath + ".spawn-z");
    SpawnRegion footprint = layout.footprint().translated(sx, sy, sz);
    Location spawn = new Location(world, sx + 0.5, sy, sz + 0.5);
    for (var player : world.getPlayers()) {
      Location location = player.getLocation();
      if (footprint.contains(location.getBlockX(), location.getBlockY(), location.getBlockZ())) {
        player.teleport(spawn);
      }
    }
  }

  // ---- Explicit command ---------------------------------------------------------------------

  /** Builds (or migrates) the layout for a world on operator request. */
  String buildNow(World world, boolean withSupplies) {
    if (world.getEnvironment() != World.Environment.NORMAL) {
      return "The spawn layout is only built in the Overworld.";
    }
    if (pendingSetups.contains(world.getUID().toString())) {
      return "A spawn build is already in progress for this world.";
    }
    if (withSupplies && !chestConfigured()) {
      return "Supplies were requested but spawn-kit.house.chest is not configured.";
    }
    String worldPath = "worlds." + world.getUID();
    SpawnRegion existingCore = null;
    if (store.getBoolean(worldPath + ".spawn-zone-complete", false)) {
      existingCore = layout.protectedRegion().translated(
          store.getInt(worldPath + ".spawn-x"),
          store.getInt(worldPath + ".spawn-y"),
          store.getInt(worldPath + ".spawn-z"));
    }
    upgradeLogged.remove(world.getUID().toString());
    beginBuild(world, worldPath, "operator", true, chestConfigured(), withSupplies, existingCore);
    return "Spawn layout build started for world " + world.getName()
        + (withSupplies ? " (with supplies)." : " (without supplies).");
  }

  // ---- Spawn radius handling ----------------------------------------------------------------

  private void applySpawnRadius(World world, String worldPath) {
    if (!manageSpawnRadius()) {
      restoreSpawnRadius(world);
      return;
    }
    if (!store.contains(worldPath + ".original-respawn-radius")) {
      Integer current = world.getGameRuleValue(GameRules.RESPAWN_RADIUS);
      store.set(worldPath + ".original-respawn-radius", current == null ? 10 : current);
      saveStore("Could not record the original respawn radius; it will not be restored later.");
    }
    world.setGameRule(GameRules.RESPAWN_RADIUS, SpawnZonePolicy.MANAGED_RESPAWN_RADIUS);
  }

  private void restoreSpawnRadius(World world) {
    String worldPath = "worlds." + world.getUID();
    if (!store.contains(worldPath + ".original-respawn-radius")) return;    world.setGameRule(GameRules.RESPAWN_RADIUS,
        store.getInt(worldPath + ".original-respawn-radius"));
    store.set(worldPath + ".original-respawn-radius", null);
    saveStore("Could not clear the recorded respawn radius after restoring it.");
  }

  /** Restores the respawn radius for every loaded Overworld when the plugin is disabled. */
  void restoreSpawnRadiusForShutdown() {
    for (World world : Bukkit.getWorlds()) {
      if (world.getEnvironment() == World.Environment.NORMAL) {
        restoreSpawnRadius(world);
      }
    }
  }

  // ---- Marker -------------------------------------------------------------------------------

  private void updateSpawnMarker(World world) {
    String worldId = world.getUID().toString();
    long now = System.nanoTime();
    if (now < nextMarkerUpdateNanos.getOrDefault(worldId, 0L)) return;
    String worldPath = "worlds." + world.getUID();
    Location spawn = store.getBoolean(worldPath + ".spawn-zone-complete", false)
        ? new Location(world, store.getInt(worldPath + ".spawn-x"),
            store.getInt(worldPath + ".spawn-y"), store.getInt(worldPath + ".spawn-z"))
        : world.getSpawnLocation();
    String label = plugin.getConfig().getString("spawn-kit.spawn-marker.name", "World Spawn");
    if (BlueMapSpawnMarker.update(plugin, world,
        spawn.getBlockX(), spawn.getBlockY(), spawn.getBlockZ(), label)) {
      nextMarkerUpdateNanos.put(worldId, now + TimeUnit.MINUTES.toNanos(1));
    }
  }

  // ---- Records and logging ------------------------------------------------------------------

  private SpawnZonePolicy.SetupState classify(String worldPath) {
    boolean hasLayoutVersion = store.getInt(worldPath + ".layout-version", 0) >= LAYOUT_VERSION;
    boolean hasLegacy = store.contains(worldPath + ".setup-complete")
        || store.contains(worldPath + ".issued")
        || store.contains(worldPath + ".x");
    boolean inProgress = store.getBoolean(worldPath + ".setup-in-progress", false);
    boolean zoneComplete = store.getBoolean(worldPath + ".spawn-zone-complete", false);
    return SpawnZonePolicy.classifySetup(hasLayoutVersion, hasLegacy, inProgress, zoneComplete);
  }

  private void logUpgrade(World world) {
    if (!upgradeLogged.add(world.getUID().toString())) return;
    plugin.getLogger().warning(
        "World " + world.getName() + " has a 1.1.x spawn kit record. This build does not migrate"
            + " it automatically. Review the old shelter, then run '/spawnkit build confirm' in"
            + " that world to place the new protected spawn layout (add 'supplies' only to hand out"
            + " a new chest). Clear plugins/AwepeepsSpawnKit/issued-kits.yml after a fresh world"
            + " reset.");
  }

  private void retryLater(String worldId) {
    retryAttempts.merge(worldId, 1, Integer::sum);
    int attempt = retryAttempts.get(worldId) - 1;
    retryAfterNanos.put(worldId,
        System.nanoTime() + TimeUnit.SECONDS.toNanos(SpawnKitPolicy.retryDelaySeconds(attempt)));
  }

  private boolean logDeferredOnce(String worldId, String summary) {
    String reason = summary.replaceAll("=\\d+", "");
    if (reason.equals(lastDeferral.put(worldId, reason))) return false;
    plugin.getLogger().info(summary);
    return true;
  }

  private Throwable rootCause(Throwable error) {
    Throwable cause = error;
    while (cause instanceof CompletionException && cause.getCause() != null) {
      cause = cause.getCause();
    }
    return cause;
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
}
