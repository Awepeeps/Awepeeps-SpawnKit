package com.awepeeps.spawnkit;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.spigotmc.event.player.PlayerSpawnLocationEvent;

/**
 * Puts joining and bed-less respawning players on top of the protected core. Bed and respawn-anchor
 * spawns are never overridden.
 */
final class SpawnZoneSpawnControl implements Listener {
  private final Map<UUID, Location> spawnLocations = new ConcurrentHashMap<>();
  private volatile boolean enabled;

  void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  boolean enabled() {
    return enabled;
  }

  void setSpawn(World world, Location location) {
    spawnLocations.put(world.getUID(), location.clone());
  }

  void clear(World world) {
    spawnLocations.remove(world.getUID());
  }

  Location spawn(World world) {
    Location location = spawnLocations.get(world.getUID());
    return location == null ? null : location.clone();
  }

  private Location target(World world) {
    if (!enabled) return null;
    return spawn(world);
  }

  @EventHandler(priority = EventPriority.HIGH)
  public void onSpawnLocation(PlayerSpawnLocationEvent event) {
    Player player = event.getPlayer();
    Location location = target(event.getSpawnLocation().getWorld());
    if (location == null) return;
    if (!SpawnZonePolicy.overrideJoinSpawn(player.getBedSpawnLocation() != null)) return;
    event.setSpawnLocation(location);
  }

  @EventHandler(priority = EventPriority.HIGH)
  public void onRespawn(PlayerRespawnEvent event) {
    Location location = target(event.getRespawnLocation().getWorld());
    if (location == null) return;
    if (!SpawnZonePolicy.overrideRespawn(event.isBedSpawn(), event.isAnchorSpawn())) return;
    event.setRespawnLocation(location);
  }
}
