package com.awepeeps.spawnkit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure plan of the spawn layout: a 3x3 core of protected blocks, a protected clear column above
 * it, and a destructible house built strictly around both. All coordinates are relative offsets
 * from the world spawn block (the block the arriving player's feet occupy), so the same plan can be
 * placed in any world. No Bukkit types are used here so the whole geometry is unit testable.
 *
 * <pre>
 *   relative Y          what lives there
 *   wallHeight          roof (closed, destructible)
 *   0 .. wallHeight-1   walls on the perimeter; interior air; arrival space at the centre
 *  -1                   house floor (destructible) except the 3x3 core top layer (protected)
 *  -coreDepth .. -1     3x3 core (protected, solid)
 * </pre>
 */
final class SpawnZoneLayout {
  /** Blocks the builder places, and what each one is. */
  enum Role {
    CORE,
    FLOOR,
    WALL,
    ROOF,
    DOOR_LOWER,
    DOOR_UPPER,
    BED_FOOT,
    BED_HEAD,
    CHEST,
    CRAFTING_TABLE,
    WALL_TORCH
  }

  static final int CORE_RADIUS = 1;
  static final int MIN_CLEAR_HEIGHT = 2;
  static final int MAX_CLEAR_HEIGHT = 8;
  static final int MIN_CORE_DEPTH = 1;
  static final int MAX_CORE_DEPTH = 8;
  static final int MIN_MARGIN = 1;
  static final int MAX_MARGIN = 2;
  static final int MIN_BEDS = 1;
  static final int MAX_BEDS = 4;
  static final int MIN_CHESTS = 1;
  static final int MAX_CHESTS = 4;
  static final int MIN_WALL_HEIGHT = 3;

  private final int coreDepth;
  private final int clearHeight;
  private final int margin;
  private final int beds;
  private final int chests;

  private final SpawnRegion core;
  private final SpawnRegion arrival;
  private final SpawnRegion protectedRegion;
  private final SpawnRegion footprint;
  private final SpawnRegion clearance;
  private final List<SpawnBlockPos> doorPath;
  private final Map<SpawnBlockPos, Role> blockPlan;

  SpawnZoneLayout(int coreDepth, int clearHeight, int margin, int beds, int chests) {
    if (!validCoreDepth(coreDepth)) throw new IllegalArgumentException("Invalid core-depth.");
    if (!validClearHeight(clearHeight)) throw new IllegalArgumentException("Invalid clear-height.");
    if (!validMargin(margin)) throw new IllegalArgumentException("Invalid interior margin.");
    if (!validBeds(beds)) throw new IllegalArgumentException("Invalid bed count.");
    if (!validChests(chests)) throw new IllegalArgumentException("Invalid chest count.");
    this.coreDepth = coreDepth;
    this.clearHeight = clearHeight;
    this.margin = margin;
    this.beds = beds;
    this.chests = chests;

    core = SpawnRegion.of(-CORE_RADIUS, -coreDepth, -CORE_RADIUS, CORE_RADIUS, -1, CORE_RADIUS);
    arrival = SpawnRegion.of(-CORE_RADIUS, 0, -CORE_RADIUS, CORE_RADIUS, clearHeight - 1, CORE_RADIUS);
    protectedRegion = SpawnRegion.of(
        -CORE_RADIUS, -coreDepth, -CORE_RADIUS, CORE_RADIUS, clearHeight - 1, CORE_RADIUS);

    int wallRadius = wallRadius();
    footprint = SpawnRegion.of(
        -wallRadius, -1, -wallRadius, wallRadius, wallHeight(), wallRadius);
    clearance = SpawnRegion.of(
        -innerRadius(), 0, -innerRadius(), innerRadius(), wallHeight() - 1, innerRadius());

    doorPath = new ArrayList<>();
    for (int z = 0; z <= wallRadius; z++) {
      doorPath.add(new SpawnBlockPos(0, 0, z));
    }
    blockPlan = buildPlan();
  }

  static boolean validCoreDepth(int value) {
    return value >= MIN_CORE_DEPTH && value <= MAX_CORE_DEPTH;
  }

  static boolean validClearHeight(int value) {
    return value >= MIN_CLEAR_HEIGHT && value <= MAX_CLEAR_HEIGHT;
  }

  static boolean validMargin(int value) {
    return value >= MIN_MARGIN && value <= MAX_MARGIN;
  }

  static boolean validBeds(int value) {
    return value >= MIN_BEDS && value <= MAX_BEDS;
  }

  static boolean validChests(int value) {
    return value >= MIN_CHESTS && value <= MAX_CHESTS;
  }

  int wallRadius() {
    return CORE_RADIUS + 1 + margin;
  }

  int innerRadius() {
    return wallRadius() - 1;
  }

  int wallHeight() {
    return Math.max(MIN_WALL_HEIGHT, clearHeight);
  }

  int coreDepth() {
    return coreDepth;
  }

  int clearHeight() {
    return clearHeight;
  }

  int margin() {
    return margin;
  }

  int beds() {
    return beds;
  }

  int chests() {
    return chests;
  }

  SpawnRegion core() {
    return core;
  }

  SpawnRegion arrival() {
    return arrival;
  }

  SpawnRegion protectedRegion() {
    return protectedRegion;
  }

  SpawnRegion footprint() {
    return footprint;
  }

  SpawnRegion clearance() {
    return clearance;
  }

  List<SpawnBlockPos> doorPath() {
    return doorPath;
  }

  SpawnBlockPos doorLower() {
    return new SpawnBlockPos(0, 0, wallRadius());
  }

  SpawnBlockPos doorUpper() {
    return new SpawnBlockPos(0, 1, wallRadius());
  }

  SpawnBlockPos doorExit() {
    return new SpawnBlockPos(0, 0, wallRadius() + 1);
  }

  Map<SpawnBlockPos, Role> blockPlan() {
    return blockPlan;
  }

  List<SpawnBlockPos> offsetsWithRole(Role role) {
    List<SpawnBlockPos> result = new ArrayList<>();
    for (Map.Entry<SpawnBlockPos, Role> entry : blockPlan.entrySet()) {
      if (entry.getValue() == role) result.add(entry.getKey());
    }
    return result;
  }

  boolean isSolid(SpawnBlockPos pos) {
    return blockPlan.containsKey(pos);
  }

  private Map<SpawnBlockPos, Role> buildPlan() {
    Map<SpawnBlockPos, Role> plan = new LinkedHashMap<>();
    int wallRadius = wallRadius();
    int wallHeight = wallHeight();

    // Core: 3x3 by core-depth (protected, solid).
    for (int x = -CORE_RADIUS; x <= CORE_RADIUS; x++) {
      for (int z = -CORE_RADIUS; z <= CORE_RADIUS; z++) {
        for (int y = -coreDepth; y <= -1; y++) {
          plan.put(new SpawnBlockPos(x, y, z), Role.CORE);
        }
      }
    }

    // Floor: full footprint at Y=-1, except the protected core top layer.
    for (int x = -wallRadius; x <= wallRadius; x++) {
      for (int z = -wallRadius; z <= wallRadius; z++) {
        SpawnBlockPos pos = new SpawnBlockPos(x, -1, z);
        if (!plan.containsKey(pos)) plan.put(pos, Role.FLOOR);
      }
    }

    // Walls: the perimeter, apart from the two-block door opening.
    for (int x = -wallRadius; x <= wallRadius; x++) {
      for (int z = -wallRadius; z <= wallRadius; z++) {
        boolean perimeter = Math.abs(x) == wallRadius || Math.abs(z) == wallRadius;
        if (!perimeter) continue;
        for (int y = 0; y <= wallHeight - 1; y++) {
          SpawnBlockPos pos = new SpawnBlockPos(x, y, z);
          if (isDoorCell(pos)) {
            plan.put(pos, y == 0 ? Role.DOOR_LOWER : Role.DOOR_UPPER);
          } else {
            plan.put(pos, Role.WALL);
          }
        }
      }
    }

    // Roof: closed everywhere above the interior.
    for (int x = -wallRadius; x <= wallRadius; x++) {
      for (int z = -wallRadius; z <= wallRadius; z++) {
        plan.put(new SpawnBlockPos(x, wallHeight, z), Role.ROOF);
      }
    }

    placeBeds(plan);
    placeChests(plan);
    plan.put(new SpawnBlockPos(0, 0, -innerRadius()), Role.CRAFTING_TABLE);
    for (SpawnBlockPos torch : torchOffsets()) {
      plan.putIfAbsent(torch, Role.WALL_TORCH);
    }
    return plan;
  }

  private boolean isDoorCell(SpawnBlockPos pos) {
    return pos.x() == 0 && pos.z() == wallRadius() && (pos.y() == 0 || pos.y() == 1);
  }

  /**
   * Beds, in placement order: south-east, south-west, north-east, north-west. Each bed is a
   * two-block head/foot pair occupying ring cells only (never the protected 3x3 centre).
   */
  List<BedPlacement> bedPlacements() {
    int r = innerRadius();
    List<BedPlacement> slots = List.of(
        new BedPlacement(new SpawnBlockPos(1, 0, r), 1),
        new BedPlacement(new SpawnBlockPos(-1, 0, r), -1),
        new BedPlacement(new SpawnBlockPos(1, 0, -r), 1),
        new BedPlacement(new SpawnBlockPos(-1, 0, -r), -1));
    return slots.subList(0, Math.min(beds, slots.size()));
  }

  private void placeBeds(Map<SpawnBlockPos, Role> plan) {
    for (BedPlacement bed : bedPlacements()) {
      SpawnBlockPos foot = bed.foot();
      SpawnBlockPos head = foot.offset(bed.headDirection(), 0, 0);
      if (plan.containsKey(foot) || plan.containsKey(head)) continue;
      plan.put(foot, Role.BED_FOOT);
      plan.put(head, Role.BED_HEAD);
    }
  }

  private void placeChests(Map<SpawnBlockPos, Role> plan) {
    int r = innerRadius();
    List<SpawnBlockPos> candidates = List.of(
        new SpawnBlockPos(r, 0, 0),
        new SpawnBlockPos(-r, 0, 0),
        new SpawnBlockPos(r, 0, 1),
        new SpawnBlockPos(-r, 0, 1));
    int placed = 0;
    for (SpawnBlockPos candidate : candidates) {
      if (placed >= chests) break;
      if (plan.containsKey(candidate)) continue;
      plan.put(candidate, Role.CHEST);
      placed++;
    }
  }

  List<SpawnBlockPos> chestOffsets() {
    return offsetsWithRole(Role.CHEST);
  }

  private List<SpawnBlockPos> torchOffsets() {
    int r = innerRadius();
    return List.of(
        new SpawnBlockPos(1, 1, r),
        new SpawnBlockPos(-1, 1, r),
        new SpawnBlockPos(1, 1, -r),
        new SpawnBlockPos(-1, 1, -r),
        new SpawnBlockPos(r, 1, 0),
        new SpawnBlockPos(-r, 1, 0));
  }

  /** A bed's foot offset and the X direction (+1/-1) its head lies in. */
  record BedPlacement(SpawnBlockPos foot, int headDirection) {
    SpawnBlockPos head() {
      return foot.offset(headDirection, 0, 0);
    }
  }
}
