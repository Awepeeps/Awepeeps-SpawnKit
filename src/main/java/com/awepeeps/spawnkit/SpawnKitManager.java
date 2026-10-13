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
import java.util.UUID;
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
 * terrain is unsuitable. A site that is refused for deterministic terrain is logged once in full and
 * is not retried on a timer; the operator clears it or runs the build command. Terrain is loaded
 * asynchronously and ticketed only for the build.
 */
final class SpawnKitManager {
  private static final int LAYOUT_VERSION = 3;
  private static final int PREVIOUS_LAYOUT_VERSION = 2;
  /** Vanilla's default, used only if the game rule cannot be read at all. */
  private static final int DEFAULT_RESPAWN_RADIUS = 10;
  private static final String BLUEMAP_PLUGIN = "BlueMap";

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
  private final Map<String, Integer> lastRepairCounts = new HashMap<>();
  private final Map<UUID, Set<Long>> outstandingTickets = new HashMap<>();
  private final Set<String> pendingSetups = new HashSet<>();
  private final Set<String> deterministicDeferrals = new HashSet<>();
  private final Set<String> upgradeLogged = new HashSet<>();
  private final Set<String> previousLayoutLogged = new HashSet<>();
  private final Set<String> interruptedLogged = new HashSet<>();
  private final Set<String> spawnIndependenceLogged = new HashSet<>();
  private boolean invalidConfigLogged;
  private boolean missingBlueMapLogged;

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
      protection.clearRegion(world);
      spawnControl.clear(world);
      restoreSpawnRadiusForShutdown();
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
        applyStoredZone(world, worldPath);
        if (!pendingSetups.contains(world.getUID().toString())) {
          repairZone(world, worldPath);
        }
      }
      case UPGRADE_AVAILABLE -> {
        applyStoredZone(world, worldPath);
        if (!pendingSetups.contains(world.getUID().toString())) {
          repairZone(world, worldPath);
        }
        logPreviousLayout(world);
      }
      case IN_PROGRESS -> {
        // A reservation this instance created is still building; only a reservation left behind by
        // an earlier run (or a crash) is an interruption worth warning about.
        if (!pendingSetups.contains(world.getUID().toString())
            && interruptedLogged.add(world.getUID().toString())) {
          plugin.getLogger().warning(
              "Spawn zone setup was interrupted for world " + world.getName()
                  + "; it will not be built again automatically. Run '/spawnkit build confirm' in"
                  + " that world to rebuild the layout, or clear"
                  + " plugins/AwepeepsSpawnKit/issued-kits.yml after a fresh world reset.");
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

  /**
   * Re-seats the core and clears the arrival column, at most once every tick cycle (five seconds).
   * Skipped while the spawn chunks are not loaded, so this periodic check can never pull chunks back
   * in on the main thread; nothing is repaired unless the record already says the zone is complete.
   * The protected core is meant to be a fixed shape, so a creative operator's edit is deliberately
   * reverted on the next pass (see the README).
   */
  private void repairZone(World world, String worldPath) {
    int sx = store.getInt(worldPath + ".spawn-x");
    int sy = store.getInt(worldPath + ".spawn-y");
    int sz = store.getInt(worldPath + ".spawn-z");
    if (!zoneChunksLoaded(world, sx, sz)) return;
    int repaired = builder.repairZone(world, layout, sx, sy, sz);
    String worldId = world.getUID().toString();
    if (repaired > 0 && lastRepairCounts.getOrDefault(worldId, -1) != repaired) {
      plugin.getLogger().info("Repaired " + repaired
          + " protected spawn block(s) in world " + world.getName() + ".");
    }
    lastRepairCounts.put(worldId, repaired);
  }

  private boolean zoneChunksLoaded(World world, int spawnX, int spawnZ) {
    SpawnKitPolicy.ChunkBounds bounds =
        SpawnKitPolicy.chunkBounds(spawnX, spawnZ, layout.wallRadius());
    for (int chunkX = bounds.minX(); chunkX <= bounds.maxX(); chunkX++) {
      for (int chunkZ = bounds.minZ(); chunkZ <= bounds.maxZ(); chunkZ++) {
        if (!world.isChunkLoaded(chunkX, chunkZ)) return false;
      }
    }
    return true;
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
    if (state == SpawnZonePolicy.SetupState.UPGRADE_AVAILABLE) {
      logPreviousLayout(world);
      return;
    }
    if (state != SpawnZonePolicy.SetupState.CURRENT) return; // wait for the protected zone
    String worldId = world.getUID().toString();
    if (pendingSetups.contains(worldId)) return;
    SpawnRegion core = recordedZone(world, worldPath);
    beginBuild(world, worldPath, "house", true, chestConfigured(), true, core);
  }

  // ---- Build --------------------------------------------------------------------------------

  private void beginBuild(World world, String worldPath, String kind, boolean houseEnabled,
                          boolean chestEnabled, boolean supplies, SpawnRegion existingCore) {
    beginBuild(world, worldPath, kind, houseEnabled, chestEnabled, supplies, existingCore,
        false, false, false, false);
  }

  private void beginBuild(World world, String worldPath, String kind, boolean houseEnabled,
                          boolean chestEnabled, boolean supplies, SpawnRegion existingCore,
                          boolean upgrade, boolean force, boolean forceItems, boolean manual) {
    String worldId = world.getUID().toString();
    if (!manual && System.nanoTime() < retryAfterNanos.getOrDefault(worldId, 0L)) return;

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
            existingCore, upgrade, force, forceItems, loaded, error)));
  }

  private CompletableFuture<List<Chunk>> loadBuildChunks(World world) {
    Location spawn = world.getSpawnLocation();
    int extent = layout.wallRadius() + SpawnZoneLayout.MAX_ENTRY_STEPS + 2;
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
                                 boolean upgrade, boolean force, boolean forceItems,
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
        retainTicket(world, chunk, ticketed);
      }
      BlockReader reader = new WorldBlockReader(world);
      if (upgrade) {
        int sx = store.getInt(worldPath + ".spawn-x");
        int sy = store.getInt(worldPath + ".spawn-y");
        int sz = store.getInt(worldPath + ".spawn-z");
        if (!store.contains(worldPath + ".spawn-x")) {
          releaseTickets(world, ticketed);
          abandonBuild(worldPath, worldId, "no recorded zone");
          logDeferredOnce(worldId, "Spawn house upgrade deferred: no recorded zone location.");
          return;
        }
        boolean chestExists = store.getBoolean(worldPath + ".chest-placed", false);
        SpawnZoneBuilder.Prepared prepared = builder.prepareUpgrade(reader, layout,
            sx, sy, sz, chestExists, chestEnabled, supplies ? stacks : List.of());
        logUpgradeResult(kind, prepared);
        builder.execute(world, prepared, () -> {
          releaseTickets(world, ticketed);
          completeUpgrade(world, worldPath, supplies);
        });
        return;
      }
      Location spawn = world.getSpawnLocation();
      SpawnZoneBuilder.Prepared prepared = builder.prepare(reader, spawn.getBlockX(),
          spawn.getBlockZ(), layout, houseEnabled, chestEnabled, supplies ? stacks : List.of(),
          existingCore, force, forceItems);
      if (!prepared.accepted()) {
        releaseTickets(world, ticketed);
        released = true;
        refuseSite(world, worldPath, worldId, kind, prepared.defect());
        return;
      }
      loggedNotes(prepared);

      store.set(worldPath + ".spawn-x", spawn.getBlockX());
      store.set(worldPath + ".spawn-y", prepared.spawnY());
      store.set(worldPath + ".spawn-z", spawn.getBlockZ());
      store.set(worldPath + ".layout-version", LAYOUT_VERSION);
      if (!saveStore("Could not persist the spawn zone location; nothing was built.")) {
        releaseTickets(world, ticketed);
        abandonBuild(worldPath, worldId, "persistence failed");
        return;
      }

      builder.execute(world, prepared, () -> {
        releaseTickets(world, ticketed);
        completeBuild(world, worldPath, kind, houseEnabled, chestEnabled);
      });
    } catch (RuntimeException failure) {
      if (!released) releaseTickets(world, ticketed);
      abandonBuild(worldPath, worldId, "placement failed");
      plugin.getLogger().log(Level.WARNING, "Spawn " + kind + " setup failed.", failure);
    }
  }

  private void retainTicket(World world, Chunk chunk, List<Chunk> ticketed) {
    if (!world.addPluginChunkTicket(chunk.getX(), chunk.getZ(), plugin)) {
      throw new IllegalStateException("Could not retain a loaded build chunk.");
    }
    ticketed.add(chunk);
    outstandingTickets.computeIfAbsent(world.getUID(), key -> new HashSet<>())
        .add(ticketKey(chunk.getX(), chunk.getZ()));
  }

  private void releaseTickets(World world, List<Chunk> ticketed) {
    Set<Long> keys = outstandingTickets.get(world.getUID());
    for (Chunk chunk : ticketed) {
      world.removePluginChunkTicket(chunk.getX(), chunk.getZ(), plugin);
      if (keys != null) keys.remove(ticketKey(chunk.getX(), chunk.getZ()));
    }
    if (keys != null && keys.isEmpty()) {
      outstandingTickets.remove(world.getUID());
    }
  }

  private static long ticketKey(int chunkX, int chunkZ) {
    return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
  }

  /**
   * Releases every chunk ticket that is still held (a build interrupted by a plugin disable never
   * reaches its completion callback) and restores the respawn radius. Runs on the main thread.
   */
  void shutdown() {
    for (Map.Entry<UUID, Set<Long>> entry : outstandingTickets.entrySet()) {
      World world = Bukkit.getWorld(entry.getKey());
      if (world == null) continue;
      for (long key : entry.getValue()) {
        world.removePluginChunkTicket((int) (key >> 32), (int) key, plugin);
      }
    }
    outstandingTickets.clear();
    restoreSpawnRadiusForShutdown();
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
    clearRetryState(worldId);
    teleportPlayersOutOfFootprint(world, worldPath);
    plugin.getLogger().info("Built the " + kind + " spawn layout in world " + world.getName() + ".");
  }

  /**
   * Records a finished additive upgrade. The version is only written once the world changes have
   * run, so a failed upgrade leaves the world at version 2 and is retried on the next command.
   */
  private void completeUpgrade(World world, String worldPath, boolean supplies) {
    String worldId = world.getUID().toString();
    pendingSetups.remove(worldId);
    store.set(worldPath + ".spawn-zone-complete", true);
    store.set(worldPath + ".layout-version", LAYOUT_VERSION);
    store.set(worldPath + ".setup-complete", true);
    store.set(worldPath + ".house-built", true);
    if (supplies) {
      store.set(worldPath + ".chest-placed", true);
    }
    store.set(worldPath + ".setup-in-progress", false);
    saveStore("Spawn house upgrade changed the world, but the record could not be saved.");
    applyStoredZone(world, worldPath);
    clearRetryState(worldId);
    teleportPlayersOutOfFootprint(world, worldPath);
    plugin.getLogger().info("Upgraded the spawn house to layout version " + LAYOUT_VERSION
        + " in world " + world.getName() + ".");
  }

  private void clearRetryState(String worldId) {
    retryAfterNanos.remove(worldId);
    retryAttempts.remove(worldId);
    lastDeferral.remove(worldId);
    deterministicDeferrals.remove(worldId);
    interruptedLogged.remove(worldId);
    upgradeLogged.remove(worldId);
    previousLayoutLogged.remove(worldId);
  }

  private void abandonBuild(String worldPath, String worldId, String reason) {
    pendingSetups.remove(worldId);
    store.set(worldPath + ".setup-in-progress", false);
    saveStore("Could not clear the spawn setup reservation after " + reason + ".");
    retryLater(worldId);
  }

  /**
   * Handles a refused site. A deterministic refusal (the terrain is what world generation put
   * there) is reported once in full and the silent timer retry is stopped; a transient one keeps
   * retrying. Either way the operator can always retry with the build command.
   */
  private void refuseSite(World world, String worldPath, String worldId, String kind, String defect) {
    pendingSetups.remove(worldId);
    store.set(worldPath + ".setup-in-progress", false);
    saveStore("Could not clear the spawn setup reservation after a site refusal.");
    boolean deterministic = isDeterministic(defect);
    if (deterministic) {
      deterministicDeferrals.add(worldId);
      retryAfterNanos.put(worldId, Long.MAX_VALUE / 2);
      if (spawnIndependenceLogged.add(worldId)) {
        plugin.getLogger().warning("No spawn zone is placed in world " + world.getName()
            + " yet, so the world spawn and the vanilla respawn_radius remain in use and new"
            + " players are not being placed on a spawn pad until the site is built.");
      }
      logDeferredOnce(worldId, "Spawn " + kind + " build deferred: " + defect
          + " This is the terrain as generated, so it will not change by waiting. Clear the site"
          + " and run '/spawnkit build confirm' while the kit window is active; 'force' is only"
          + " accepted on a fresh zone, not on an additive upgrade.");
    } else {
      retryLater(worldId);
      logDeferredOnce(worldId, "Spawn " + kind + " build deferred: " + defect);
    }
  }

  private static boolean isDeterministic(String defect) {
    return SpawnKitPolicy.isDeterministicDefect(defect);
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

  /** Builds (or upgrades) the layout for a world on operator request. */
  String buildNow(World world, boolean withSupplies) {
    return buildNow(world, withSupplies, false, false);
  }

  /**
   * Builds (or upgrades) the layout for a world on operator request. {@code force} replaces
   * non-natural blocks in the footprint; {@code forceItems} additionally allows replacing a block
   * entity that holds contents.
   */
  String buildNow(World world, boolean withSupplies, boolean force, boolean forceItems) {
    if (world.getEnvironment() != World.Environment.NORMAL) {
      return "The spawn layout is only built in the Overworld.";
    }
    if (!zoneEnabled()) {
      return "spawn-zone.enabled is false; nothing was built.";
    }
    String worldId = world.getUID().toString();
    if (pendingSetups.contains(worldId)) {
      return "A spawn build is already in progress for this world.";
    }
    if (withSupplies && !chestConfigured()) {
      return "Supplies were requested but spawn-kit.house.chest is not configured.";
    }
    String worldPath = "worlds." + world.getUID();
    // An explicit command always overrides a stopped or backed-off timer retry.
    deterministicDeferrals.remove(worldId);
    retryAfterNanos.remove(worldId);
    upgradeLogged.remove(worldId);
    previousLayoutLogged.remove(worldId);

    SpawnZonePolicy.SetupState state = classify(worldPath);
    // An additive upgrade never overwrites an existing house or chest, so 'force'/'items' have no
    // meaning there: reject them clearly before any reservation, world change or supply is made.
    String forceRefusal = SpawnZonePolicy.additiveUpgradeForceRefusal(state, force, forceItems);
    if (forceRefusal != null) {
      return forceRefusal;
    }
    if (state == SpawnZonePolicy.SetupState.CURRENT) {
      return buildCurrent(world, worldPath, withSupplies);
    }
    if (state == SpawnZonePolicy.SetupState.UPGRADE_AVAILABLE) {
      if (!store.contains(worldPath + ".spawn-x")) {
        return "This world has no recorded spawn zone; run the command after a fresh world reset.";
      }
      beginBuild(world, worldPath, "upgrade", true, chestConfigured(), withSupplies, null,
          true, force, forceItems, true);
      return "Spawn house upgrade started for world " + world.getName()
          + (withSupplies ? " (with supplies)." : " (without supplies).");
    }
    SpawnRegion existingCore = recordedZone(world, worldPath);
    beginBuild(world, worldPath, "operator", true, chestConfigured(), withSupplies, existingCore,
        false, force, forceItems, true);
    return "Spawn layout build started for world " + world.getName()
        + (withSupplies ? " (with supplies)." : " (without supplies).");
  }

  /**
   * A world already at the current layout version: report it clearly and, as designed, retry any
   * additive piece that was skipped earlier. Nothing structural is ever replaced, so on a complete
   * house this adds nothing. While the house pass has not recorded completion the additive pass is
   * not run at all: the automatic house build owns the world, and running here would place
   * furnishings on the bare zone and mark it complete before the shell exists.
   */
  private String buildCurrent(World world, String worldPath, boolean withSupplies) {
    SpawnZonePolicy.CurrentBuild action = SpawnZonePolicy.currentBuild(
        houseConfigured(), store.contains(worldPath + ".spawn-x"),
        store.getBoolean(worldPath + ".setup-complete", false));
    if (action == SpawnZonePolicy.CurrentBuild.ALREADY_CURRENT) {
      return SpawnKitPolicy.alreadyCurrent(LAYOUT_VERSION);
    }
    if (action == SpawnZonePolicy.CurrentBuild.AWAIT_HOUSE) {
      // The house pass has not recorded completion, so the automatic build still owns this world.
      // Running the additive pass now would place furnishings on the bare zone and then mark the
      // world complete, leaving the wall/floor/roof shell unbuilt. The command has already cleared
      // any deferral, so the house pass resumes on its next cycle and keeps the durable reservation.
      return "This world has its protected spawn zone but not its house yet; the automatic house"
          + " build will run on its next cycle while the kit window is active. Nothing was changed.";
    }
    int sx = store.getInt(worldPath + ".spawn-x");
    int sy = store.getInt(worldPath + ".spawn-y");
    int sz = store.getInt(worldPath + ".spawn-z");
    if (!zoneChunksLoaded(world, sx, sz)) {
      return "This world is already at the current spawn layout (version " + LAYOUT_VERSION
          + "); the spawn chunks are not loaded so nothing was checked or changed.";
    }
    boolean chestExists = store.getBoolean(worldPath + ".chest-placed", false);
    SpawnZoneBuilder.Prepared prepared = builder.prepareUpgrade(new WorldBlockReader(world), layout,
        sx, sy, sz, chestExists, chestConfigured(), withSupplies ? stacks : List.of());
    if (prepared.edits().isEmpty() && prepared.postOps().isEmpty()) {
      return SpawnKitPolicy.alreadyCurrent(LAYOUT_VERSION);
    }
    logUpgradeResult("additive", prepared);
    builder.execute(world, prepared, () -> completeUpgrade(world, worldPath, withSupplies));
    return "This world is already at the current spawn layout (version " + LAYOUT_VERSION
        + "); added " + prepared.placed().size() + " missing piece(s): " + prepared.placed() + ".";
  }

  private void logUpgradeResult(String kind, SpawnZoneBuilder.Prepared prepared) {
    if (!prepared.placed().isEmpty()) {
      plugin.getLogger().info("Spawn " + kind + " upgrade: adding " + prepared.placed().size()
          + " piece(s): " + prepared.placed() + ".");
    }
    if (!prepared.skipped().isEmpty()) {
      plugin.getLogger().info("Spawn " + kind + " upgrade: skipped " + prepared.skipped().size()
          + " piece(s): " + prepared.skipped() + ".");
    }
  }

  private void loggedNotes(SpawnZoneBuilder.Prepared prepared) {
    for (String note : prepared.placed()) {
      plugin.getLogger().warning("Spawn zone build: " + note + ".");
    }
  }

  /**
   * The protected zone this world's record already knows about, or null. The coordinates are written
   * before the first block change, so even a build interrupted half way through is recognised and a
   * later rebuild does not refuse the site for the plugin's own blocks.
   */
  private SpawnRegion recordedZone(World world, String worldPath) {
    if (store.getInt(worldPath + ".layout-version", 0) < LAYOUT_VERSION) return null;
    if (!store.contains(worldPath + ".spawn-x")) return null;
    return layout.protectedRegion().translated(
        store.getInt(worldPath + ".spawn-x"),
        store.getInt(worldPath + ".spawn-y"),
        store.getInt(worldPath + ".spawn-z"));
  }

  // ---- Spawn radius handling ----------------------------------------------------------------

  private void applySpawnRadius(World world, String worldPath) {
    if (!manageSpawnRadius()) {
      restoreSpawnRadius(world);
      return;
    }
    String key = worldPath + ".original-respawn-radius";
    if (!store.contains(key)) {
      Integer current = world.getGameRuleValue(GameRules.RESPAWN_RADIUS);
      store.set(key, current == null ? DEFAULT_RESPAWN_RADIUS : current);
      if (!saveStore("Could not record the original respawn radius; the rule is left alone.")) {
        // Never pin the rule without a durable record of the original value: if the write failed
        // and the server crashed now, the original would be lost and 0 would become permanent.
        return;
      }
    }
    world.setGameRule(GameRules.RESPAWN_RADIUS, SpawnZonePolicy.MANAGED_RESPAWN_RADIUS);
  }

  private void restoreSpawnRadius(World world) {
    String worldPath = "worlds." + world.getUID();
    if (!store.contains(worldPath + ".original-respawn-radius")) return;
    world.setGameRule(GameRules.RESPAWN_RADIUS,
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
    if (!Bukkit.getPluginManager().isPluginEnabled(BLUEMAP_PLUGIN)) {
      // Checked before the marker class is touched: loading it without BlueMap on the classpath
      // would throw NoClassDefFoundError from the scheduled task.
      if (!missingBlueMapLogged) {
        missingBlueMapLogged = true;
        plugin.getLogger().warning(
            "Spawn marker is enabled, but BlueMap is not installed/enabled; the marker will be"
                + " retried every minute.");
      }
      nextMarkerUpdateNanos.put(worldId, now + TimeUnit.MINUTES.toNanos(1));
      return;
    }
    missingBlueMapLogged = false;
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
    int version = store.getInt(worldPath + ".layout-version", 0);
    boolean hasCurrentLayout = version >= LAYOUT_VERSION;
    boolean hasPreviousLayout = version == PREVIOUS_LAYOUT_VERSION;
    boolean hasLegacy = store.contains(worldPath + ".setup-complete")
        || store.contains(worldPath + ".issued")
        || store.contains(worldPath + ".x");
    boolean inProgress = store.getBoolean(worldPath + ".setup-in-progress", false);
    boolean zoneComplete = store.getBoolean(worldPath + ".spawn-zone-complete", false);
    return SpawnZonePolicy.classifySetup(
        hasCurrentLayout, hasPreviousLayout, hasLegacy, inProgress, zoneComplete);
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

  /**
   * Logs, once, that a 1.2.x world can be upgraded. It is deliberately not applied automatically:
   * players may have changed the house, and the upgrade is additive, so it waits for an operator.
   */
  private void logPreviousLayout(World world) {
    if (!previousLayoutLogged.add(world.getUID().toString())) return;
    plugin.getLogger().warning(
        "World " + world.getName() + " has a 1.2.x spawn house (layout version 2). A new furnished"
            + " layout is available and is NOT applied automatically, because the house may have"
            + " changed. Run '/spawnkit build confirm' in that world to add the new furnishings"
            + " additively (existing blocks and chest contents are kept); add 'supplies' only to hand"
            + " out another set of items.");
  }

  private void retryLater(String worldId) {
    retryAttempts.merge(worldId, 1, Integer::sum);
    int attempt = retryAttempts.get(worldId) - 1;
    retryAfterNanos.put(worldId,
        System.nanoTime() + TimeUnit.SECONDS.toNanos(SpawnKitPolicy.retryDelaySeconds(attempt)));
  }

  private boolean logDeferredOnce(String worldId, String summary) {
    String reason = SpawnKitPolicy.deferralKey(summary);
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
