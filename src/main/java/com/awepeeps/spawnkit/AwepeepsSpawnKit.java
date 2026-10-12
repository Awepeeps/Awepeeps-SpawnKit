package com.awepeeps.spawnkit;

import org.bukkit.plugin.java.JavaPlugin;

public final class AwepeepsSpawnKit extends JavaPlugin {
  @Override
  public void onEnable() {
    saveDefaultConfig();
    int searchRadius = getConfig().getInt(
        "spawn-kit.search-radius", SpawnKitPolicy.DEFAULT_SHELTER_SEARCH_RADIUS);
    if (!SpawnKitPolicy.validShelterSearchRadius(searchRadius)) {
      getLogger().severe("Spawn Kit search-radius must be between "
          + SpawnKitPolicy.MIN_SHELTER_SEARCH_RADIUS + " and "
          + SpawnKitPolicy.MAX_SHELTER_SEARCH_RADIUS + ".");
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
    SpawnKitManager manager = new SpawnKitManager(this, contents.stacks(), searchRadius);
    getServer().getScheduler().runTaskTimer(this, manager::tick, 1L, 100L);
  }
}
