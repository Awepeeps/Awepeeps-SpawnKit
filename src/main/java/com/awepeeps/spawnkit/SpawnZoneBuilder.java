package com.awepeeps.spawnkit;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.Furnace;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Reads terrain, refuses unsuitable spawn sites, and places the {@link SpawnZoneLayout} in bounded
 * batches on the main thread. Terrain is read through a {@link BlockReader} and the plan is a list of
 * {@link SpawnEdit}s, so the whole scan and clearing decision is testable without a server; only
 * {@link #execute} touches the world. Every decision comes from the pure {@link SpawnZonePolicy} and
 * {@link SpawnZoneLayout}.
 */
final class SpawnZoneBuilder {
  static final int BATCH_SIZE = 256;

  static final Material CORE_MATERIAL = Material.STONE_BRICKS;
  static final Material FLOOR_MATERIAL = Material.OAK_PLANKS;
  static final Material WALL_MATERIAL = Material.COBBLESTONE;
  static final Material ROOF_MATERIAL = Material.OAK_PLANKS;
  static final Material FOUNDATION_MATERIAL = Material.COBBLESTONE;
  static final Material DOOR_MATERIAL = Material.OAK_DOOR;
  static final Material BED_MATERIAL = Material.WHITE_BED;
  static final Material CRAFTING_MATERIAL = Material.CRAFTING_TABLE;
  static final Material ANVIL_MATERIAL = Material.ANVIL;
  static final Material FURNACE_MATERIAL = Material.FURNACE;
  static final Material LODESTONE_MATERIAL = Material.LODESTONE;

  private static final int[][] NEIGHBOURS = {
      {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
  };

  private final JavaPlugin plugin;
  private final NamespacedKey ownedKey;
  private final int batchSize;

  SpawnZoneBuilder(JavaPlugin plugin, NamespacedKey ownedKey) {
    this(plugin, ownedKey, BATCH_SIZE);
  }

  SpawnZoneBuilder(JavaPlugin plugin, NamespacedKey ownedKey, int batchSize) {
    this.plugin = plugin;
    this.ownedKey = ownedKey;
    this.batchSize = batchSize;
  }

  /**
   * A prepared build: either a refusal with a diagnostic reason, or the ordered block edits that
   * make up the layout, plus any deferred world operations and operator-facing notes.
   */
  record Prepared(boolean accepted, int floorY, int spawnY, String defect,
                  List<SpawnEdit> edits, List<Consumer<World>> postOps,
                  SpawnRegion protectedRegion, List<String> placed, List<String> skipped) {
    static Prepared refused(String defect) {
      return new Prepared(false, 0, 0, defect, List.of(), List.of(), null, List.of(), List.of());
    }
  }

  private record TreeClear(List<int[]> cells, boolean truncated) {}

  Prepared prepare(BlockReader reader, int sx, int sz, SpawnZoneLayout layout,
                   boolean houseEnabled, boolean chestEnabled, List<SpawnKitContents.Stack> contents,
                   SpawnRegion existingCore, boolean force, boolean forceItems) {
    int wallRadius = layout.wallRadius();
    int minHeight = reader.minHeight();
    int maxHeight = reader.maxHeight();

    List<int[]> columns = buildColumns(wallRadius);
    List<Column> sampled = new ArrayList<>(columns.size());
    int minSurface = Integer.MAX_VALUE;
    int maxSurface = Integer.MIN_VALUE;
    boolean anyLiquid = false;
    boolean anyVoid = false;
    boolean anyLava = false;
    for (int[] column : columns) {
      int bx = sx + column[0];
      int bz = sz + column[1];
      if (!reader.isLoaded(bx, bz)) {
        return Prepared.refused("spawn chunks were not loaded");
      }
      int topY = reader.topY(bx, bz);
      int surfaceY = reader.surfaceY(bx, bz);
      while (surfaceY > minHeight
          && SpawnZonePolicy.isClearableVegetation(reader.blockAt(bx, surfaceY, bz))) {
        surfaceY--;
      }
      Material surface = reader.blockAt(bx, surfaceY, bz);
      boolean liquid = surface == Material.WATER || surface == Material.LAVA;
      boolean lava = surface == Material.LAVA;
      boolean isVoid = SpawnZonePolicy.isAir(surface);
      if (lava) anyLava = true;
      if (isVoid) anyVoid = true;
      if (liquid) anyLiquid = true;
      minSurface = Math.min(minSurface, surfaceY);
      maxSurface = Math.max(maxSurface, surfaceY);
      sampled.add(new Column(bx, bz, surfaceY, topY));
    }

    Integer floorY = SpawnZonePolicy.chooseFloorLevel(
        minSurface, maxSurface, anyLiquid, minHeight, maxHeight,
        layout.coreDepth(), layout.wallHeight());
    boolean unevenTerrain = floorY == null
        && maxSurface - minSurface > SpawnZonePolicy.MAX_TERRAIN_VARIATION;
    boolean outOfHeight = floorY == null && !unevenTerrain;

    boolean occupied = false;
    boolean lavaBeneath = anyLava;
    boolean outsideBorder = false;
    int scanFloor = floorY == null ? maxSurface : floorY;
    // Everything the previous pass already placed in these columns counts as ours, including the
    // foundation it filled beneath the floor; otherwise the second pass (the house) would refuse the
    // site on any terrain that dips more than a couple of blocks below the chosen floor.
    SpawnRegion ownBuild = existingCore == null ? null
        : SpawnZonePolicy.ownedVolume(existingCore, scanFloor, layout.coreDepth(), minHeight);
    Map<Material, int[]> firstOffenders = new LinkedHashMap<>();
    int offendingCount = 0;
    List<int[]> offenderPositions = force ? new ArrayList<>() : null;
    int[] protectedOffender = null;
    Material protectedOffenderMaterial = null;
    for (Column column : sampled) {
      if (!reader.insideWorldBorder(column.x(), column.z())) {
        outsideBorder = true;
      }
      for (int y = scanFloor - layout.coreDepth() - 1;
           y <= scanFloor + layout.wallHeight() + 1; y++) {
        if (y < minHeight || y >= maxHeight) continue;
        if (ownBuild != null && ownBuild.contains(column.x(), y, column.z())) {
          continue; // Our own protected core or foundation from a previous pass.
        }
        Material material = reader.blockAt(column.x(), y, column.z());
        SpawnZonePolicy.BlockKind kind = SpawnZonePolicy.classify(material);
        if (kind == SpawnZonePolicy.BlockKind.LAVA) {
          lavaBeneath = true;
        } else if (kind == SpawnZonePolicy.BlockKind.OCCUPIED) {
          occupied = true;
          offendingCount++;
          firstOffenders.putIfAbsent(material, new int[] {column.x(), y, column.z()});
          if (offenderPositions != null) {
            offenderPositions.add(new int[] {column.x(), y, column.z()});
          }
          if (force && !forceItems && protectedOffender == null
              && !reader.forceReplaceable(column.x(), y, column.z())) {
            protectedOffender = new int[] {column.x(), y, column.z()};
            protectedOffenderMaterial = material;
          }
        }
      }
    }

    SpawnZonePolicy.Defect defect = SpawnZonePolicy.firstDefect(
        anyVoid, lavaBeneath, occupied, unevenTerrain, outOfHeight, outsideBorder);
    if (force && defect == SpawnZonePolicy.Defect.OCCUPIED) {
      // Force bypasses occupancy only. The terrain shape is never overridden, so a site that is
      // both occupied and uneven (or without headroom) is refused with that explicit shape/height
      // defect: the occupancy check is ordered first and would otherwise mask it, leaving no floor
      // level to build on. Recomputing with occupancy false surfaces the shape defect, if any.
      SpawnZonePolicy.Defect shapeDefect = SpawnZonePolicy.firstDefect(
          anyVoid, lavaBeneath, false, unevenTerrain, outOfHeight, outsideBorder);
      if (shapeDefect != null) {
        return Prepared.refused(SpawnZonePolicy.message(shapeDefect));
      }
      if (protectedOffender != null) {
        return Prepared.refused("a block entity at (" + protectedOffender[0] + ", "
            + protectedOffender[1] + ", " + protectedOffender[2] + ") holds contents ("
            + describe(protectedOffenderMaterial)
            + "); add 'items' to replace it as well, or clear it by hand first");
      }
      defect = null; // forced build: the offending blocks are cleared below.
    } else if (defect != null) {
      String message = defect == SpawnZonePolicy.Defect.OCCUPIED
          ? offendingCount + " block(s) in the footprint are not natural terrain: "
              + describeOffenders(firstOffenders)
          : SpawnZonePolicy.message(defect);
      return Prepared.refused(message);
    }

    // The exterior decorations sit outside the footprint, which the occupancy scan above does not
    // cover. Refuse the site rather than overwrite a non-natural block there.
    List<int[]> exteriorClears = new ArrayList<>();
    if (houseEnabled) {
      int spawnY = floorY + 1;
      for (SpawnBlockPos offset : layout.exteriorOffsets()) {
        int ex = sx + offset.x();
        int ey = spawnY + offset.y();
        int ez = sz + offset.z();
        if (!SpawnZonePolicy.isReplaceableByBuild(reader.blockAt(ex, ey, ez))) {
          if (!force) {
            return Prepared.refused("a house decoration would overwrite a non-natural block: "
                + describe(reader.blockAt(ex, ey, ez)) + " at (" + ex + ", " + ey + ", " + ez + ")");
          }
          if (!forceItems && !reader.forceReplaceable(ex, ey, ez)) {
            return Prepared.refused("a block entity at (" + ex + ", " + ey + ", " + ez
                + ") holds contents (" + describe(reader.blockAt(ex, ey, ez))
                + "); add 'items' to replace it as well, or clear it by hand first");
          }
          exteriorClears.add(new int[] {ex, ey, ez});
        }
      }
    }

    int spawnY = floorY + 1;
    List<SpawnEdit> edits = new ArrayList<>();

    // 1. Clear natural vegetation and snow across the columns being built on.
    List<int[]> clearColumns = houseEnabled ? columns : coreColumns();
    Set<Long> clearedCells = new HashSet<>();
    for (int[] clearColumn : clearColumns) {
      int bx = sx + clearColumn[0];
      int bz = sz + clearColumn[1];
      int limit = Math.min(topAt(sampled, bx, bz), maxHeight - 1);
      for (int y = minHeight; y <= limit; y++) {
        if (SpawnZonePolicy.isClearableVegetation(reader.blockAt(bx, y, bz))) {
          addClear(edits, clearedCells, bx, y, bz);
        }
      }
    }

    // 2. A forced build clears whatever non-natural blocks the occupancy scan found.
    if (offenderPositions != null) {
      for (int[] pos : offenderPositions) {
        addClear(edits, clearedCells, pos[0], pos[1], pos[2]);
      }
      for (int[] pos : exteriorClears) {
        addClear(edits, clearedCells, pos[0], pos[1], pos[2]);
      }
    }

    // 3. Remove whole trees whose trunk stands inside the build so no canopy floats over the house.
    List<String> notes = new ArrayList<>();
    if (houseEnabled) {
      TreeClear treeClear = planTreeClearing(reader, sx, sz, layout, floorY, minHeight);
      for (int[] pos : treeClear.cells()) {
        addClear(edits, clearedCells, pos[0], pos[1], pos[2]);
      }
      if (treeClear.truncated()) {
        notes.add("tree clearing stopped at the cap of "
            + SpawnZonePolicy.TREE_CLEAR_MAX_BLOCKS + " block(s); some leaves or logs may remain");
      }
    }

    List<int[]> foundationColumns = houseEnabled ? columns : coreColumns();
    for (int[] column : foundationColumns) {
      int bx = sx + column[0];
      int bz = sz + column[1];
      int surfaceY = surfaceAt(sampled, bx, bz);
      for (int y = surfaceY + 1; y < floorY; y++) {
        edits.add(SpawnEdit.place(bx, y, bz, FOUNDATION_MATERIAL, null));
      }
    }

    for (Map.Entry<SpawnBlockPos, SpawnZoneLayout.Role> entry : layout.blockPlan().entrySet()) {
      SpawnBlockPos offset = entry.getKey();
      SpawnZoneLayout.Role role = entry.getValue();
      if (!roleEnabled(role, houseEnabled, chestEnabled)) continue;
      int x = sx + offset.x();
      int y = spawnY + offset.y();
      int z = sz + offset.z();
      edits.add(switch (role) {
        case CORE -> SpawnEdit.place(x, y, z, CORE_MATERIAL, null);
        case FLOOR -> SpawnEdit.place(x, y, z, FLOOR_MATERIAL, null);
        case WALL -> SpawnEdit.place(x, y, z, WALL_MATERIAL, null);
        case ROOF -> SpawnEdit.place(x, y, z, ROOF_MATERIAL, null);
        case CRAFTING_TABLE -> SpawnEdit.place(x, y, z, CRAFTING_MATERIAL, null);
        case ANVIL -> SpawnEdit.place(x, y, z, ANVIL_MATERIAL,
            () -> directional(ANVIL_MATERIAL, roomFacing(offset)));
        case FURNACE -> SpawnEdit.place(x, y, z, FURNACE_MATERIAL, () -> furnaceData(offset));
        case LODESTONE -> SpawnEdit.place(x, y, z, LODESTONE_MATERIAL, null);
        case EXTRA -> SpawnEdit.place(x, y, z, layout.extraMaterial(offset), null);
        case DOORSTEP -> SpawnEdit.place(x, y, z, FLOOR_MATERIAL, null);
        case DOOR_LOWER -> SpawnEdit.place(x, y, z, DOOR_MATERIAL,
            () -> doorData(Bisected.Half.BOTTOM));
        case DOOR_UPPER -> SpawnEdit.place(x, y, z, DOOR_MATERIAL,
            () -> doorData(Bisected.Half.TOP));
        case BED_FOOT -> SpawnEdit.place(x, y, z, BED_MATERIAL,
            () -> bedData(offset, Bed.Part.FOOT, layout));
        case BED_HEAD -> SpawnEdit.place(x, y, z, BED_MATERIAL,
            () -> bedData(offset, Bed.Part.HEAD, layout));
        case CHEST -> new SpawnEdit(x, y, z, Material.CHEST,
            () -> directional(Material.CHEST, roomFacing(offset)),
            SpawnZonePolicy.overwriteExisting(role));
        case WALL_TORCH -> SpawnEdit.place(x, y, z, Material.WALL_TORCH,
            () -> directional(Material.WALL_TORCH, layout.wallTorchFacing(offset)));
        case EXTERIOR_TORCH -> SpawnEdit.place(x, y, z, Material.WALL_TORCH,
            () -> directional(Material.WALL_TORCH, layout.exteriorFacing(offset)));
        case BANNER_LEFT -> SpawnEdit.place(x, y, z,
            SpawnKitFurnishings.bannerMaterial(layout.furnishings().leftBanner()),
            () -> directional(SpawnKitFurnishings.bannerMaterial(layout.furnishings().leftBanner()),
                layout.exteriorFacing(offset)));
        case BANNER_RIGHT -> SpawnEdit.place(x, y, z,
            SpawnKitFurnishings.bannerMaterial(layout.furnishings().rightBanner()),
            () -> directional(SpawnKitFurnishings.bannerMaterial(layout.furnishings().rightBanner()),
                layout.exteriorFacing(offset)));
      });
    }

    if (houseEnabled) {
      edits.addAll(planEntrySteps(reader, layout, sx, sz, floorY, minHeight, maxHeight));
    }

    List<Consumer<World>> postOps = new ArrayList<>();
    if (houseEnabled && chestEnabled && !contents.isEmpty()) {
      postOps.add(world -> fillChests(world, layout, sx, spawnY, sz, contents));
    }

    SpawnRegion protectedRegion = layout.protectedRegion().translated(sx, spawnY, sz);
    return new Prepared(true, floorY, spawnY, null, edits, postOps, protectedRegion,
        notes, List.of());
  }

  private static void addClear(List<SpawnEdit> edits, Set<Long> seen, int x, int y, int z) {
    if (seen.add(cellKey(x, y, z))) {
      edits.add(SpawnEdit.clear(x, y, z));
    }
  }

  private static long cellKey(int x, int y, int z) {
    return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
  }

  private static String describe(Material material) {
    return material == null ? "unknown" : material.getKey().getKey().toUpperCase(java.util.Locale.ROOT);
  }

  private static String describeOffenders(Map<Material, int[]> offenders) {
    StringBuilder builder = new StringBuilder();
    int shown = 0;
    for (Map.Entry<Material, int[]> entry : offenders.entrySet()) {
      if (shown == SpawnZonePolicy.MAX_REPORTED_OFFENDER_TYPES) {
        builder.append(", ...");
        break;
      }
      if (shown > 0) builder.append(", ");
      int[] pos = entry.getValue();
      builder.append(describe(entry.getKey()))
          .append(" at (").append(pos[0]).append(", ").append(pos[1]).append(", ").append(pos[2])
          .append(')');
      shown++;
    }
    return builder.toString();
  }

  /**
   * A bounded breadth-first walk of every tree whose trunk stands inside the build columns: the
   * connected logs, leaves and tree attachments are cleared within a radius around the footprint and
   * a height above the roof, so removing the trunk cannot leave a floating canopy. Trees entirely
   * outside the footprint are never touched.
   */
  private TreeClear planTreeClearing(BlockReader reader, int sx, int sz, SpawnZoneLayout layout,
                                     int floorY, int minHeight) {
    int wallRadius = layout.wallRadius();
    int horizon = wallRadius + SpawnZonePolicy.TREE_CLEAR_RADIUS;
    int top = Math.min(reader.maxHeight() - 1,
        floorY + layout.wallHeight() + SpawnZonePolicy.TREE_CLEAR_ABOVE_ROOF);
    Set<Long> seen = new HashSet<>();
    Deque<int[]> queue = new ArrayDeque<>();
    for (int[] column : buildColumns(wallRadius)) {
      int bx = sx + column[0];
      int bz = sz + column[1];
      for (int y = minHeight; y <= top; y++) {
        if (SpawnZonePolicy.isTreeTrunk(reader.blockAt(bx, y, bz)) && seen.add(cellKey(bx, y, bz))) {
          queue.add(new int[] {bx, y, bz});
        }
      }
    }
    List<int[]> cleared = new ArrayList<>();
    boolean truncated = false;
    while (!queue.isEmpty()) {
      int[] pos = queue.poll();
      if (!SpawnZonePolicy.isTreePart(reader.blockAt(pos[0], pos[1], pos[2]))) continue;
      if (cleared.size() >= SpawnZonePolicy.TREE_CLEAR_MAX_BLOCKS) {
        truncated = true;
        break;
      }
      cleared.add(pos);
      for (int[] delta : NEIGHBOURS) {
        int nx = pos[0] + delta[0];
        int ny = pos[1] + delta[1];
        int nz = pos[2] + delta[2];
        if (ny < minHeight || ny > top) continue;
        if (Math.abs(nx - sx) > horizon || Math.abs(nz - sz) > horizon) continue;
        if (!seen.add(cellKey(nx, ny, nz))) continue;
        if (SpawnZonePolicy.isTreePart(reader.blockAt(nx, ny, nz))) {
          queue.add(new int[] {nx, ny, nz});
        }
      }
    }
    return new TreeClear(cleared, truncated);
  }

  /**
   * The additive upgrade for a world already built with the previous layout version: only the
   * furnishing and decoration cells are touched, and only where the target is replaceable and its
   * support exists. No structural block is ever replaced or removed, an existing chest is never
   * touched (unless {@code supplies} was requested), and every skipped piece is reported with a
   * reason.
   */
  Prepared prepareUpgrade(BlockReader reader, SpawnZoneLayout layout, int sx, int sy, int sz,
                          boolean chestExists, boolean chestEnabled,
                          List<SpawnKitContents.Stack> contents) {
    List<SpawnEdit> edits = new ArrayList<>();
    List<String> placed = new ArrayList<>();
    List<String> skipped = new ArrayList<>();
    boolean chestPresent = chestExists || anyChestInRing(reader, layout, sx, sy, sz);
    Set<SpawnBlockPos> bedCells = new HashSet<>();
    for (SpawnZoneLayout.BedPlacement bed : layout.bedPlacements()) {
      bedCells.add(bed.foot());
      bedCells.add(bed.head());
    }

    for (Map.Entry<SpawnBlockPos, SpawnZoneLayout.Role> entry : layout.furnishPlan()) {
      SpawnBlockPos offset = entry.getKey();
      SpawnZoneLayout.Role role = entry.getValue();
      if (bedCells.contains(offset)) continue; // a bed is handled as one unit below
      if (role == SpawnZoneLayout.Role.CHEST && (chestPresent || !chestEnabled)) {
        skipped.add("chest at " + offset + " ("
            + (chestPresent ? "a chest already exists" : "the chest is disabled") + ")");
        continue;
      }
      int x = sx + offset.x();
      int y = sy + offset.y();
      int z = sz + offset.z();
      Material current = reader.blockAt(x, y, z);
      boolean replaceable = SpawnZonePolicy.isReplaceableByBuild(current);
      boolean support = hasSupport(reader, layout, sx, sy, sz, offset, role);
      if (!SpawnZonePolicy.upgradeAllowed(role, replaceable, support, chestPresent)) {
        skipped.add(describe(role) + " at " + offset + " ("
            + (replaceable ? "support missing" : "occupied by " + current) + ")");
        continue;
      }
      edits.add(upgradeEdit(layout, x, y, z, offset, role));
      placed.add(describe(role) + " at " + offset);
    }

    for (SpawnZoneLayout.BedPlacement bed : layout.bedPlacements()) {
      int fx = sx + bed.foot().x();
      int fy = sy + bed.foot().y();
      int fz = sz + bed.foot().z();
      int hx = sx + bed.head().x();
      int hy = sy + bed.head().y();
      int hz = sz + bed.head().z();
      boolean replaceable = SpawnZonePolicy.isReplaceableByBuild(reader.blockAt(fx, fy, fz))
          && SpawnZonePolicy.isReplaceableByBuild(reader.blockAt(hx, hy, hz));
      boolean support = !isAir(reader.blockAt(fx, fy - 1, fz))
          && !isAir(reader.blockAt(hx, hy - 1, hz));
      if (!SpawnZonePolicy.upgradeAllowed(
          SpawnZoneLayout.Role.BED_FOOT, replaceable, support, chestPresent)) {
        skipped.add("bed at " + bed.foot() + " (" + (replaceable ? "support missing" : "occupied") + ")");
        continue;
      }
      edits.add(SpawnEdit.place(fx, fy, fz, BED_MATERIAL,
          () -> bedData(bed.foot(), Bed.Part.FOOT, layout)));
      edits.add(SpawnEdit.place(hx, hy, hz, BED_MATERIAL,
          () -> bedData(bed.head(), Bed.Part.HEAD, layout)));
      placed.add("bed at " + bed.foot());
    }

    List<Consumer<World>> postOps = new ArrayList<>();
    if (chestPresent && !contents.isEmpty()) {
      postOps.add(world -> fillExistingChests(world, layout, sx, sy, sz, contents));
    }

    SpawnRegion protectedRegion = layout.protectedRegion().translated(sx, sy, sz);
    return new Prepared(true, sy - 1, sy, null, edits, postOps, protectedRegion, placed, skipped);
  }

  private SpawnEdit upgradeEdit(SpawnZoneLayout layout, int x, int y, int z, SpawnBlockPos offset,
                                SpawnZoneLayout.Role role) {
    return switch (role) {
      case CRAFTING_TABLE -> SpawnEdit.place(x, y, z, CRAFTING_MATERIAL, null);
      case ANVIL -> SpawnEdit.place(x, y, z, ANVIL_MATERIAL,
          () -> directional(ANVIL_MATERIAL, roomFacing(offset)));
      case FURNACE -> SpawnEdit.place(x, y, z, FURNACE_MATERIAL, () -> furnaceData(offset));
      case LODESTONE -> SpawnEdit.place(x, y, z, LODESTONE_MATERIAL, null);
      case EXTRA -> SpawnEdit.place(x, y, z, layout.extraMaterial(offset), null);
      case DOORSTEP -> SpawnEdit.place(x, y, z, FLOOR_MATERIAL, null);
      case CHEST -> SpawnEdit.place(x, y, z, Material.CHEST,
          () -> directional(Material.CHEST, roomFacing(offset)));
      case WALL_TORCH -> SpawnEdit.place(x, y, z, Material.WALL_TORCH,
          () -> directional(Material.WALL_TORCH, layout.wallTorchFacing(offset)));
      case EXTERIOR_TORCH -> SpawnEdit.place(x, y, z, Material.WALL_TORCH,
          () -> directional(Material.WALL_TORCH, layout.exteriorFacing(offset)));
      case BANNER_LEFT -> SpawnEdit.place(x, y, z,
          SpawnKitFurnishings.bannerMaterial(layout.furnishings().leftBanner()),
          () -> directional(SpawnKitFurnishings.bannerMaterial(layout.furnishings().leftBanner()),
              layout.exteriorFacing(offset)));
      case BANNER_RIGHT -> SpawnEdit.place(x, y, z,
          SpawnKitFurnishings.bannerMaterial(layout.furnishings().rightBanner()),
          () -> directional(SpawnKitFurnishings.bannerMaterial(layout.furnishings().rightBanner()),
              layout.exteriorFacing(offset)));
      default -> throw new IllegalArgumentException("Not an upgradeable role: " + role);
    };
  }

  private boolean hasSupport(BlockReader reader, SpawnZoneLayout layout, int sx, int sy, int sz,
                             SpawnBlockPos offset, SpawnZoneLayout.Role role) {
    if (role == SpawnZoneLayout.Role.WALL_TORCH) {
      BlockFace facing = layout.wallTorchFacing(offset);
      return !isAir(reader.blockAt(sx + offset.x() - facing.getModX(),
          sy + offset.y() - facing.getModY(), sz + offset.z() - facing.getModZ()));
    }
    if (role == SpawnZoneLayout.Role.EXTERIOR_TORCH
        || role == SpawnZoneLayout.Role.BANNER_LEFT
        || role == SpawnZoneLayout.Role.BANNER_RIGHT) {
      SpawnBlockPos mounting = layout.exteriorMounting(offset);
      return !isAir(reader.blockAt(sx + mounting.x(), sy + mounting.y(), sz + mounting.z()));
    }
    return !isAir(reader.blockAt(sx + offset.x(), sy + offset.y() - 1, sz + offset.z()));
  }

  private boolean anyChestInRing(BlockReader reader, SpawnZoneLayout layout,
                                 int sx, int sy, int sz) {
    for (SpawnBlockPos slot : layout.ringSlots()) {
      if (reader.blockAt(sx + slot.x(), sy + slot.y(), sz + slot.z()) == Material.CHEST) {
        return true;
      }
    }
    return false;
  }

  private static boolean isAir(Material material) {
    return SpawnZonePolicy.isAir(material);
  }

  private static String describe(SpawnZoneLayout.Role role) {
    return role.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
  }

  /**
   * Builds the entry steps below the doorstep: one plank tread per block of drop, up to the
   * configured maximum, each supported down to the terrain. When the ground past the door is
   * higher, a walkable approach is cleared instead. Non-natural blocks are never destroyed; a
   * column that is not replaceable is simply skipped.
   */
  private List<SpawnEdit> planEntrySteps(BlockReader reader, SpawnZoneLayout layout, int sx, int sz,
                                         int floorY, int minHeight, int maxHeight) {
    List<SpawnEdit> edits = new ArrayList<>();
    int firstColumnZ = layout.exteriorZ() + 1;
    Integer firstGround = groundTop(reader, sx, sz + firstColumnZ, minHeight);
    if (firstGround == null) return edits;
    SpawnZonePolicy.EntrySteps steps =
        SpawnZonePolicy.entrySteps(floorY, firstGround, SpawnZoneLayout.MAX_ENTRY_STEPS);
    int columnCount = steps.groundHigher()
        ? SpawnZoneLayout.MAX_ENTRY_STEPS : steps.count();
    for (int i = 0; i < columnCount; i++) {
      int bz = sz + firstColumnZ + i;
      if (!reader.isLoaded(sx, bz)) break;
      Integer ground = groundTop(reader, sx, bz, minHeight);
      if (ground == null) continue;
      if (steps.groundHigher()) {
        // Clear a walkable approach: nothing but replaceable blocks above the doorstep level.
        for (int y = Math.max(minHeight, floorY + 1); y <= Math.min(ground, maxHeight - 1); y++) {
          if (SpawnZonePolicy.isReplaceableByBuild(reader.blockAt(sx, y, bz))
              && !isAir(reader.blockAt(sx, y, bz))) {
            edits.add(SpawnEdit.clear(sx, y, bz));
          }
        }
        continue;
      }
      int treadY = steps.treads().get(i);
      if (ground >= treadY) continue; // the terrain already reaches this tread
      if (!replaceableColumn(reader, sx, bz, ground + 1, treadY, minHeight, maxHeight)) {
        continue; // never destroy a non-natural block for a step
      }
      for (int y = ground + 1; y < treadY; y++) {
        edits.add(SpawnEdit.place(sx, y, bz, FOUNDATION_MATERIAL, null));
      }
      edits.add(SpawnEdit.place(sx, treadY, bz, FLOOR_MATERIAL, null));
    }
    return edits;
  }

  private boolean replaceableColumn(BlockReader reader, int bx, int bz, int fromY, int toY,
                                    int minHeight, int maxHeight) {
    int start = Math.max(minHeight, fromY);
    int end = Math.min(toY, maxHeight - 1);
    for (int y = start; y <= end; y++) {
      if (!SpawnZonePolicy.isReplaceableByBuild(reader.blockAt(bx, y, bz))) {
        return false;
      }
    }
    return true;
  }

  private Integer groundTop(BlockReader reader, int bx, int bz, int minHeight) {
    if (!reader.isLoaded(bx, bz)) return null;
    int y = reader.surfaceY(bx, bz);
    while (y > minHeight && SpawnZonePolicy.isClearableVegetation(reader.blockAt(bx, y, bz))) {
      y--;
    }
    if (SpawnZonePolicy.isAir(reader.blockAt(bx, y, bz))) return null;
    return y;
  }

  /** Runs the prepared edits in bounded batches, then the deferred operations and {@code onComplete}. */
  void execute(World world, Prepared prepared, Runnable onComplete) {
    List<SpawnEdit> edits = prepared.edits();
    List<Consumer<World>> postOps = prepared.postOps();
    if (edits.isEmpty() && postOps.isEmpty()) {
      onComplete.run();
      return;
    }
    int[] index = {0};
    plugin.getServer().getScheduler().runTaskTimer(plugin, task -> {
      int processed = 0;
      while (index[0] < edits.size() && processed < batchSize) {
        try {
          apply(world, edits.get(index[0]));
        } catch (RuntimeException error) {
          plugin.getLogger().log(Level.WARNING, "Spawn zone block operation failed.", error);
        }
        index[0]++;
        processed++;
      }
      if (index[0] >= edits.size()) {
        task.cancel();
        for (Consumer<World> operation : postOps) {
          try {
            operation.accept(world);
          } catch (RuntimeException error) {
            plugin.getLogger().log(Level.WARNING, "Spawn zone deferred operation failed.", error);
          }
        }
        onComplete.run();
      }
    }, 1L, 1L);
  }

  private void apply(World world, SpawnEdit edit) {
    Block block = world.getBlockAt(edit.x(), edit.y(), edit.z());
    if (!edit.overwrite() && block.getType() == edit.material()) return;
    BlockData data = edit.data() == null ? null : edit.data().get();
    if (data != null) {
      block.setBlockData(data, false);
    } else if (edit.material() == Material.AIR) {
      block.setType(Material.AIR, false);
    } else {
      block.setType(edit.material(), false);
    }
  }

  private List<int[]> buildColumns(int wallRadius) {
    List<int[]> columns = new ArrayList<>();
    for (int x = -wallRadius; x <= wallRadius; x++) {
      for (int z = -wallRadius; z <= wallRadius; z++) {
        columns.add(new int[] {x, z});
      }
    }
    // Walkable door step just outside the footprint.
    columns.add(new int[] {0, wallRadius + 1});
    return columns;
  }

  private List<int[]> coreColumns() {
    List<int[]> columns = new ArrayList<>();
    for (int x = -SpawnZoneLayout.CORE_RADIUS; x <= SpawnZoneLayout.CORE_RADIUS; x++) {
      for (int z = -SpawnZoneLayout.CORE_RADIUS; z <= SpawnZoneLayout.CORE_RADIUS; z++) {
        columns.add(new int[] {x, z});
      }
    }
    return columns;
  }

  private int surfaceAt(List<Column> sampled, int bx, int bz) {
    for (Column column : sampled) {
      if (column.x() == bx && column.z() == bz) return column.surfaceY();
    }
    return Integer.MIN_VALUE;
  }

  private int topAt(List<Column> sampled, int bx, int bz) {
    for (Column column : sampled) {
      if (column.x() == bx && column.z() == bz) return column.topY();
    }
    return Integer.MIN_VALUE;
  }

  private boolean roleEnabled(SpawnZoneLayout.Role role, boolean houseEnabled, boolean chestEnabled) {
    return switch (role) {
      case CORE -> true;
      case CHEST -> houseEnabled && chestEnabled;
      default -> houseEnabled;
    };
  }

  private Door doorData(Bisected.Half half) {
    Door door = (Door) Bukkit.createBlockData(DOOR_MATERIAL);
    door.setFacing(BlockFace.SOUTH);
    door.setHinge(Door.Hinge.LEFT);
    door.setHalf(half);
    return door;
  }

  private BlockData directional(Material material, BlockFace facing) {
    Directional data = (Directional) Bukkit.createBlockData(material);
    data.setFacing(facing);
    return data;
  }

  private Furnace furnaceData(SpawnBlockPos offset) {
    Furnace furnace = (Furnace) Bukkit.createBlockData(FURNACE_MATERIAL);
    furnace.setFacing(roomFacing(offset));
    return furnace;
  }

  /** The direction a wall-mounted piece faces to look into the room, i.e. at the house centre. */
  private BlockFace roomFacing(SpawnBlockPos offset) {
    if (Math.abs(offset.z()) >= Math.abs(offset.x())) {
      return offset.z() <= 0 ? BlockFace.SOUTH : BlockFace.NORTH;
    }
    return offset.x() <= 0 ? BlockFace.EAST : BlockFace.WEST;
  }

  private Bed bedData(SpawnBlockPos offset, Bed.Part part, SpawnZoneLayout layout) {
    BlockFace facing = BlockFace.EAST;
    for (SpawnZoneLayout.BedPlacement placement : layout.bedPlacements()) {
      if (placement.foot().equals(offset) || placement.head().equals(offset)) {
        facing = placement.facing();
        break;
      }
    }
    Bed bed = (Bed) Bukkit.createBlockData(BED_MATERIAL);
    bed.setFacing(facing);
    bed.setPart(part);
    return bed;
  }

  private void fillChests(World world, SpawnZoneLayout layout, int sx, int spawnY, int sz,
                          List<SpawnKitContents.Stack> contents) {
    for (SpawnBlockPos offset : layout.chestOffsets()) {
      fillChest(world.getBlockAt(sx + offset.x(), spawnY + offset.y(), sz + offset.z()), contents);
    }
  }

  /** Fills any chest already present in the ring (used by an upgrade with {@code supplies}). */
  private void fillExistingChests(World world, SpawnZoneLayout layout, int sx, int sy, int sz,
                                  List<SpawnKitContents.Stack> contents) {
    List<SpawnBlockPos> cells = new ArrayList<>(layout.ringSlots());
    cells.addAll(layout.chestOffsets());
    for (SpawnBlockPos offset : cells) {
      Block block = world.getBlockAt(sx + offset.x(), sy + offset.y(), sz + offset.z());
      if (block.getType() == Material.CHEST) {
        fillChest(block, contents);
      }
    }
  }

  private void fillChest(Block block, List<SpawnKitContents.Stack> contents) {
    if (block.getType() != Material.CHEST) return;
    BlockState state = block.getState();
    if (!(state instanceof Chest chest)) return;
    chest.getPersistentDataContainer().set(ownedKey, PersistentDataType.BYTE, (byte) 1);
    if (!chest.update(true, false)) {
      plugin.getLogger().warning("Could not mark a spawn zone chest as owned.");
      return;
    }
    SpawnKitContents.fill(chest.getBlockInventory(), contents);
  }

  /** Re-seats the protected core and clears the arrival column; returns the number of repairs. */
  int repairZone(World world, SpawnZoneLayout layout, int spawnX, int spawnY, int spawnZ) {
    int repaired = 0;
    for (SpawnBlockPos offset : layout.offsetsWithRole(SpawnZoneLayout.Role.CORE)) {
      Block block = world.getBlockAt(spawnX + offset.x(), spawnY + offset.y(), spawnZ + offset.z());
      if (block.getType() != CORE_MATERIAL) {
        block.setType(CORE_MATERIAL, false);
        repaired++;
      }
    }
    SpawnRegion arrival = layout.arrival();
    for (int x = arrival.minX(); x <= arrival.maxX(); x++) {
      for (int y = arrival.minY(); y <= arrival.maxY(); y++) {
        for (int z = arrival.minZ(); z <= arrival.maxZ(); z++) {
          Block block = world.getBlockAt(spawnX + x, spawnY + y, spawnZ + z);
          if (!SpawnZonePolicy.isAir(block.getType())) {
            block.setType(Material.AIR, false);
            repaired++;
          }
        }
      }
    }
    return repaired;
  }

  private record Column(int x, int z, int surfaceY, int topY) {}
}
