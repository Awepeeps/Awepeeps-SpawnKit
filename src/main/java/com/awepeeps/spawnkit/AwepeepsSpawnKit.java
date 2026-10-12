package com.awepeeps.spawnkit;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class AwepeepsSpawnKit extends JavaPlugin {
  private SpawnKitManager manager;

  @Override
  public void onEnable() {
    saveDefaultConfig();

    SpawnZoneLayout layout;
    try {
      layout = new SpawnZoneLayout(
          getConfig().getInt("spawn-zone.core-depth", 3),
          getConfig().getInt("spawn-zone.clear-height", 3),
          getConfig().getInt("spawn-zone.margin", 1),
          getConfig().getInt("spawn-zone.beds", 1),
          getConfig().getInt("spawn-zone.chests", 1));
    } catch (IllegalArgumentException error) {
      getLogger().severe("Spawn Kit spawn-zone dimensions are invalid: " + error.getMessage());
      getServer().getPluginManager().disablePlugin(this);
      return;
    }

    final SpawnKitContents contents;
    try {
      contents = new SpawnKitContents(
          getConfig().getMapList("spawn-kit.house.chest.items"), SpawnKitContents::resolveMaterial);
    } catch (IllegalArgumentException error) {
      getLogger().severe("Spawn Kit configuration is invalid: " + error.getMessage());
      getServer().getPluginManager().disablePlugin(this);
      return;
    }
    if (getConfig().getBoolean("spawn-kit.enabled", false)
        && getConfig().getBoolean("spawn-kit.house.chest.enabled", false)
        && contents.isEmpty()) {
      getLogger().severe("Spawn Kit chest is enabled but spawn-kit.house.chest.items is empty.");
      getServer().getPluginManager().disablePlugin(this);
      return;
    }

    manager = new SpawnKitManager(this, layout, contents.stacks());
    getServer().getPluginManager().registerEvents(manager.protection(), this);
    getServer().getPluginManager().registerEvents(manager.spawnControl(), this);
    var command = getCommand("spawnkit");
    if (command != null) {
      command.setExecutor(new SpawnKitCommand(manager));
    }
    getServer().getScheduler().runTaskTimer(this, manager::tick, 1L, 100L);
    warnAboutVanillaSpawnProtection();
    getLogger().info("Spawn Kit " + getDescription().getVersion()
        + " enabled: protected core " + (2 * SpawnZoneLayout.CORE_RADIUS + 1) + "x"
        + (2 * SpawnZoneLayout.CORE_RADIUS + 1) + "x" + layout.coreDepth()
        + ", clear column " + (2 * SpawnZoneLayout.CORE_RADIUS + 1) + "x"
        + (2 * SpawnZoneLayout.CORE_RADIUS + 1) + "x" + layout.clearHeight() + ".");
  }

  @Override
  public void onDisable() {
    if (manager != null) {
      manager.restoreSpawnRadiusForShutdown();
    }
  }

  private void warnAboutVanillaSpawnProtection() {
    boolean active = getConfig().getBoolean("spawn-zone.enabled", true)
        || getConfig().getBoolean("spawn-kit.enabled", false);
    int spawnProtection = Bukkit.getSpawnRadius();
    if (active && spawnProtection > 0) {
      getLogger().warning("server.properties spawn-protection=" + spawnProtection
          + " is greater than 0. Vanilla spawn protection makes the whole square around the world"
          + " spawn indestructible for non-operators (and silently does nothing when the server has"
          + " no operators), which contradicts this plugin's destructible house. Set"
          + " spawn-protection=0 and let the plugin protect the core and arrival space.");
    }
  }
}
