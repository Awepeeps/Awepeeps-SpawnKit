package com.awepeeps.spawnkit;

import org.bukkit.plugin.java.JavaPlugin;

public final class AwepeepsSpawnKit extends JavaPlugin {
  @Override
  public void onEnable() {
    saveDefaultConfig();
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
    SpawnKitManager manager = new SpawnKitManager(this, contents.stacks());
    getServer().getScheduler().runTaskTimer(this, manager::tick, 1L, 100L);
  }
}
