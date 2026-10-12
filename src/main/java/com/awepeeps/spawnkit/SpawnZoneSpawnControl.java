package com.awepeeps.spawnkit;

import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerRespawnEvent;

/**
 * Puts first-time joining players and bed-less respawning players on top of the protected core.
 * Returning players keep the position stored in their player data, and bed and respawn-anchor
 * spawns are never overridden.
 *
 * <p>Joins are handled through Paper's {@link AsyncPlayerSpawnLocationEvent} rather than the
 * deprecated {@code org.spigotmc.event.player.PlayerSpawnLocationEvent}: that event is
 * {@code @Deprecated(since = "1.21.9", forRemoval = true)} and is documented as firing "during the
 * configuration phase, before a player entity is normally created", so reading
 * {@code Player#hasPlayedBefore()} or {@code Player#getBedSpawnLocation()} from it is unreliable.
 * The replacement event reports {@code isNewPlayer()} itself, which is exactly the first-ever-join
 * flag this class needs, and defaults the location to the player data location for returning
 * players.
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
    if (!enabled || world == null) return null;
    return spawn(world);
  }

  /**
   * Fires on every login (and while reconfiguring), so only a player's first ever join may be
   * redirected. This handler runs asynchronously: it only reads the precomputed spawn location.
   */
  @EventHandler(priority = EventPriority.HIGH)
  public void onJoinSpawnLocation(AsyncPlayerSpawnLocationEvent event) {
    Location location = target(event.getSpawnLocation().getWorld());
    if (location == null) return;
    if (!SpawnZonePolicy.overrideJoinSpawn(event.isNewPlayer())) return;
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
