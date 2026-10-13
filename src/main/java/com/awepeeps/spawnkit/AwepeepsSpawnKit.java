package com.awepeeps.spawnkit;

import java.util.List;
import java.util.function.IntUnaryOperator;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class AwepeepsSpawnKit extends JavaPlugin {
  private SpawnKitManager manager;

  @Override
  public void onEnable() {
    saveDefaultConfig();

    SpawnZoneLayout layout = loadLayout();
    SpawnKitContents contents = loadContents();
    if (getConfig().getBoolean("spawn-kit.enabled", false)
        && getConfig().getBoolean("spawn-kit.house.chest.enabled", false)
        && contents.isEmpty()) {
      getLogger().warning("spawn-kit.house.chest is enabled but spawn-kit.house.chest.items is"
          + " empty; the chest will be placed empty.");
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
    getLogger().info("Spawn Kit " + getPluginMeta().getVersion()
        + " enabled: protected core " + (2 * SpawnZoneLayout.CORE_RADIUS + 1) + "x"
        + (2 * SpawnZoneLayout.CORE_RADIUS + 1) + "x" + layout.coreDepth()
        + ", clear column " + (2 * SpawnZoneLayout.CORE_RADIUS + 1) + "x"
        + (2 * SpawnZoneLayout.CORE_RADIUS + 1) + "x" + layout.clearHeight() + ".");
  }

  @Override
  public void onDisable() {
    if (manager != null) {
      manager.shutdown();
    }
  }

  /**
   * Reads the spawn-zone dimensions, clamping anything out of range with a warning. A bad dimension
   * must never disable the plugin: disabling it would silently drop spawn protection and spawn
   * control as well.
   */
  private SpawnZoneLayout loadLayout() {
    SpawnKitFurnishings furnishings = loadFurnishings();
    try {
      return new SpawnZoneLayout(
          bounded("spawn-zone.core-depth", 3, SpawnZoneLayout::clampCoreDepth),
          bounded("spawn-zone.clear-height", 3, SpawnZoneLayout::clampClearHeight),
          bounded("spawn-zone.margin", 1, SpawnZoneLayout::clampMargin),
          bounded("spawn-zone.beds", 1, SpawnZoneLayout::clampBeds),
          bounded("spawn-zone.chests", 1, SpawnZoneLayout::clampChests),
          furnishings);
    } catch (RuntimeException error) {
      getLogger().severe("Spawn Kit spawn-zone dimensions could not be read ("
          + error.getMessage() + "); using the documented defaults.");
      return SpawnZoneLayout.defaults();
    }
  }

  private SpawnKitFurnishings loadFurnishings() {
    try {
      return SpawnKitFurnishings.parse(
          getConfig().getConfigurationSection("spawn-kit.house.furnishings"),
          message -> getLogger().warning(message));
    } catch (RuntimeException error) {
      getLogger().warning("Spawn Kit house furnishings could not be read ("
          + error.getMessage() + "); using the defaults.");
      return SpawnKitFurnishings.defaults();
    }
  }

  private int bounded(String key, int fallback, IntUnaryOperator clamp) {
    Object raw = getConfig().get(key);
    int value = fallback;
    if (raw instanceof Number number) {
      value = number.intValue();
    } else if (raw != null) {
      getLogger().warning("Spawn Kit " + key + " is not a number; using " + fallback + ".");
    }
    int clamped = clamp.applyAsInt(value);
    if (clamped != value) {
      getLogger().warning(
          "Spawn Kit " + key + "=" + value + " is out of range; using " + clamped + ".");
    }
    return clamped;
  }

  /**
   * Reads the chest contents. Invalid entries are reported and the chest is left empty rather than
   * disabling the plugin, which would also switch off the protected spawn zone.
   */
  private SpawnKitContents loadContents() {
    try {
      return new SpawnKitContents(
          getConfig().getMapList("spawn-kit.house.chest.items"), SpawnKitContents::resolveMaterial);
    } catch (IllegalArgumentException error) {
      getLogger().severe("Spawn Kit chest contents are invalid and will not be issued: "
          + error.getMessage());
      return new SpawnKitContents(List.of(), SpawnKitContents::resolveMaterial);
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
