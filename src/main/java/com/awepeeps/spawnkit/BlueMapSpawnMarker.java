package com.awepeeps.spawnkit;

import com.flowpowered.math.vector.Vector3d;
import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.markers.POIMarker;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

final class BlueMapSpawnMarker {
  private static final String MARKER_SET_ID = "awepeeps-spawn";
  private static boolean missingApiLogged;

  private BlueMapSpawnMarker() {}

  static boolean update(JavaPlugin plugin, World world, int x, int y, int z, String label) {
    var api = BlueMapAPI.getInstance();
    if (api.isEmpty()) {
      if (!missingApiLogged) {
        plugin.getLogger().warning(
            "Spawn marker is enabled, but BlueMap is not available; the marker will be retried.");
        missingApiLogged = true;
      }
      return false;
    }
    missingApiLogged = false;
    var blueMapWorld = api.get().getWorld(world.getUID());
    if (blueMapWorld.isEmpty()) return false;
    if (blueMapWorld.get().getMaps().isEmpty()) return false;
    blueMapWorld.get().getMaps().forEach(map -> {
      MarkerSet markerSet = map.getMarkerSets().computeIfAbsent(
          MARKER_SET_ID,
          key -> MarkerSet.builder().label("World Spawn").build());
      String id = "spawn-" + world.getUID();
      String safeLabel = escapeHtml(label);
      String detail = "<b>" + safeLabel + "</b><br><b>World:</b> "
          + escapeHtml(world.getName()) + "<br><b>Position:</b> "
          + x + ", " + y + ", " + z;
      POIMarker marker = POIMarker.builder()
          .label(label)
          .position(new Vector3d(x + 0.5, y, z + 0.5))
          .detail(detail)
          .build();
      markerSet.getMarkers().put(id, marker);
    });
    return true;
  }

  private static String escapeHtml(String value) {
    return value.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
  }
}
